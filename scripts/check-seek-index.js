// Reports which library files a player can seek in, and which it cannot.
//
//   node scripts/check-seek-index.js [https://your-server]
//
// Why this exists: a Matroska file carries its seek index in a Cues element,
// announced by the SeekHead at the front of the file. ExoPlayer's
// MatroskaExtractor reads that one SeekHead and no further — it does not
// follow a SeekHead that points to a second SeekHead, which is how several
// muxers lay files out. With no Cues it builds an unseekable SeekMap, and
// ProgressiveMediaPeriod then documents its behaviour plainly: "Treat all
// seeks into non-seekable media as being to t=0."
//
// A file listed as NO SEEK INDEX below is one MatroskaExtractor cannot seek
// on its own. That no longer means it cannot be seeked: GET /api/media/seek-
// index/[id] rebuilds the table from the file itself and the TV client hands
// it to the extractor before playback (lib/library/mkv-index.js, and
// android-tv .../data/SeekIndex.kt). This script stays useful as the quick
// answer to which files take that path.
//
// Remuxing the container is still the way to make a file seek natively
// everywhere, including in a browser — seconds of work, no quality loss:
//
//   mkvmerge -o fixed.mkv original.mkv
//
// then replace the file in the Drive folder and press Sync.
//
// Only the first 256 KB of each file is fetched, so this is cheap to run over
// a large library.

import fs from 'node:fs';
import path from 'node:path';

const MATROSKA_MAGIC = '1a45dfa3';
const HEAD_BYTES = 262144;

// The EBML ids this needs to recognise. Everything else is reported as hex.
const ELEMENT_NAMES = {
  '18538067': 'Segment',
  '114d9b74': 'SeekHead',
  '1549a966': 'Info',
  '1654ae6b': 'Tracks',
  '1c53bb6b': 'Cues',
  '1f43b675': 'Cluster',
  '1941a469': 'Attachments',
  '1043a770': 'Chapters',
  '1254c367': 'Tags',
  ec: 'Void',
  '4dbb': 'Seek',
  '53ab': 'SeekID',
  '53ac': 'SeekPosition',
};

/** Reads the .env.local value a script needs, without pulling in dotenv. */
function envValue(name) {
  const file = path.join(process.cwd(), '.env.local');
  if (!fs.existsSync(file)) return process.env[name];
  const match = new RegExp(`^${name}=(.*)$`, 'm').exec(fs.readFileSync(file, 'utf8'));
  return match ? match[1].trim() : process.env[name];
}

/** EBML element id at `pos`, kept with its marker bits as the spec defines. */
function readId(buf, pos) {
  const first = buf[pos];
  if (first === undefined) return null;
  let len;
  if (first & 0x80) len = 1;
  else if (first & 0x40) len = 2;
  else if (first & 0x20) len = 3;
  else if (first & 0x10) len = 4;
  else return null;
  if (pos + len > buf.length) return null;
  return { hex: buf.subarray(pos, pos + len).toString('hex'), len };
}

/** EBML data size at `pos`, with the marker bit stripped. All-ones means the
 * size is unknown, which is legal for a live-muxed Segment. */
function readSize(buf, pos) {
  const first = buf[pos];
  if (first === undefined) return null;
  let len = 0;
  for (let i = 0; i < 8; i++) {
    if (first & (0x80 >> i)) {
      len = i + 1;
      break;
    }
  }
  if (!len || pos + len > buf.length) return null;
  const mask = (0x80 >> (len - 1)) - 1;
  let value = BigInt(first & mask);
  let unknown = (first & mask) === mask;
  for (let i = 1; i < len; i++) {
    value = (value << 8n) | BigInt(buf[pos + i]);
    if (buf[pos + i] !== 0xff) unknown = false;
  }
  return { value, len, unknown };
}

/** Top-level elements of the Segment, and what the first SeekHead announces. */
function inspect(buf) {
  const top = [];
  let pos = 0;
  while (pos < buf.length && top.length < 40) {
    const id = readId(buf, pos);
    if (!id) break;
    const size = readSize(buf, pos + id.len);
    if (!size) break;
    const body = pos + id.len + size.len;
    // Descend into the Segment rather than stepping over the whole file.
    if (id.hex === '18538067') {
      pos = body;
      continue;
    }
    top.push({ name: ELEMENT_NAMES[id.hex] || id.hex, at: pos });
    if (size.unknown) break;
    pos = body + Number(size.value);
  }

  const announced = new Set();
  const head = top.find((e) => e.name === 'SeekHead');
  if (head) {
    const id = readId(buf, head.at);
    const size = readSize(buf, head.at + id.len);
    let pos = head.at + id.len + size.len;
    const end = Math.min(buf.length, pos + Number(size.value));
    while (pos < end) {
      const childId = readId(buf, pos);
      if (!childId) break;
      const childSize = readSize(buf, pos + childId.len);
      if (!childSize) break;
      const childBody = pos + childId.len + childSize.len;
      if (childId.hex === '4dbb') {
        pos = childBody; // descend into Seek
        continue;
      }
      if (childId.hex === '53ab') {
        const target = buf.subarray(childBody, childBody + Number(childSize.value)).toString('hex');
        announced.add(ELEMENT_NAMES[target] || target);
      }
      pos = childBody + Number(childSize.value);
    }
  }

  return { top: top.map((e) => e.name), announced: [...announced] };
}

async function main() {
  const base = (process.argv[2] || process.env.KPLAY_URL || 'http://localhost:3000').replace(
    /\/$/,
    ''
  );
  const key = envValue('KDRIVE_DEVICE_KEY');
  if (!key) {
    console.error('KDRIVE_DEVICE_KEY is not set — add it to .env.local or the environment');
    process.exit(1);
  }
  const headers = { 'x-kdrive-device-key': key };

  const list = await (await fetch(`${base}/api/media/list`, { headers })).json();
  const items = [...(list.movies || []), ...(list.series || [])];
  const unseekable = [];

  for (const item of items) {
    // A series doc holds no bytes of its own; check its first episode.
    let id = item._id;
    let label = item.title;
    if (item.type === 'series') {
      const detail = await (await fetch(`${base}/api/media/${item._id}`, { headers })).json();
      const episode = detail.episodes?.[0];
      if (!episode) {
        console.log(`${label.padEnd(30)} no episodes`);
        continue;
      }
      id = episode._id;
      label = `${item.title} (${episode.season}x${episode.episode})`;
    }

    const res = await fetch(`${base}/api/media/stream/${id}`, {
      headers: { ...headers, Range: `bytes=0-${HEAD_BYTES - 1}` },
    });
    if (!res.ok) {
      console.log(`${label.slice(0, 28).padEnd(30)} HTTP ${res.status}`);
      continue;
    }
    const buf = Buffer.from(await res.arrayBuffer());

    if (!buf.subarray(0, 4).toString('hex').startsWith(MATROSKA_MAGIC)) {
      // Only Matroska is checked here. MP4 keeps its index in the moov atom,
      // which every player finds.
      console.log(`${label.slice(0, 28).padEnd(30)} not Matroska — skipped`);
      continue;
    }

    const { top, announced } = inspect(buf);
    const seekable = top.includes('Cues') || announced.includes('Cues');
    if (!seekable) unseekable.push(label);
    console.log(
      `${label.slice(0, 28).padEnd(30)} ${seekable ? 'seekable' : 'NO SEEK INDEX'}` +
        `   seekhead announces: ${announced.join(', ') || 'nothing'}`
    );
  }

  console.log(`\n${items.length - unseekable.length}/${items.length} files are seekable.`);
  if (unseekable.length) {
    console.log('\nRemux these once — a container rewrite, not a re-encode:');
    for (const title of unseekable) console.log(`  ${title}`);
    console.log('\n  mkvmerge -o fixed.mkv original.mkv\n');
  }
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
