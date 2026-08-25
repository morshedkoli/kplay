// Builds a seek index for a Matroska file that a player cannot seek by itself.
//
// The problem this solves: a .mkv carries its seek index in a Cues element,
// announced by a SeekHead at the front of the file. ExoPlayer's
// MatroskaExtractor reads that one SeekHead and no further — it does not
// follow a SeekHead that points to a second SeekHead, which is how several
// common muxers lay files out. With no Cues it builds an unseekable SeekMap,
// so fast-forward on the remote either does nothing or restarts the film.
//
// The index is almost always already in the file; only the route to it is one
// the player will not walk. So the server walks it instead, over a handful of
// Range reads, and hands the player a table of {timeMs -> byte offset} it can
// seek with directly. Nothing is re-encoded, remuxed or re-uploaded.
//
// Three strategies, cheapest first:
//
//   1. cues-seekhead — follow the SeekHead chain to the Cues element.
//   2. cues-tail     — no usable SeekHead, so scan the end of the file for a
//                      Cues element directly. Muxers overwhelmingly write Cues
//                      last, so this finds them when the pointer is missing.
//   3. clusters      — no Cues anywhere. Hop cluster to cluster reading only
//                      each one's header, since a Cluster's size field says
//                      where the next begins. One small read per cluster,
//                      never the media bytes.
//
// `read(start, end)` is injected so this is testable without a network and
// works against any storage backend.

const LOG = '[library/mkv-index]';

const ID = {
  EBML: '1a45dfa3',
  SEGMENT: '18538067',
  SEEK_HEAD: '114d9b74',
  SEEK: '4dbb',
  SEEK_ID: '53ab',
  SEEK_POSITION: '53ac',
  INFO: '1549a966',
  TIMECODE_SCALE: '2ad7b1',
  DURATION: '4489',
  CUES: '1c53bb6b',
  CUE_POINT: 'bb',
  CUE_TIME: 'b3',
  CUE_TRACK_POSITIONS: 'b7',
  CUE_CLUSTER_POSITION: 'f1',
  CLUSTER: '1f43b675',
  TIMECODE: 'e7',
};

// Enough to cover the EBML header, SeekHead, Info and Tracks of any sane file.
const HEAD_BYTES = 262144;
// Cues sit at the very end when they are not at the front. A few megabytes
// covers the index of a long film with room to spare.
const TAIL_BYTES = 8 * 1024 * 1024;
// A Cues element itself. Larger than this and something is wrong with the file.
const MAX_CUES_BYTES = 16 * 1024 * 1024;
// One read per cluster, so this is the ceiling on how much work the fallback
// may do. A two-hour film clusters roughly every few seconds.
const MAX_CLUSTER_HOPS = 5000;
const CLUSTER_HEADER_BYTES = 64;
// Below this the table is not worth storing: a handful of points cannot make
// scrubbing feel like seeking.
const MIN_USEFUL_CUES = 2;

const DEFAULT_TIMECODE_SCALE = 1000000; // nanoseconds per tick

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

/**
 * EBML data size at `pos`, with the marker bit stripped. All-ones means the
 * size is unknown, which is legal for a Segment written by a live muxer.
 */
function readSize(buf, pos) {
  const first = buf[pos];
  if (first === undefined) return null;
  let len = 0;
  for (let i = 0; i < 8; i += 1) {
    if (first & (0x80 >> i)) {
      len = i + 1;
      break;
    }
  }
  if (!len || pos + len > buf.length) return null;
  const mask = (0x80 >> (len - 1)) - 1;
  let value = BigInt(first & mask);
  let unknown = (first & mask) === mask;
  for (let i = 1; i < len; i += 1) {
    value = (value << 8n) | BigInt(buf[pos + i]);
    if (buf[pos + i] !== 0xff) unknown = false;
  }
  return { value: Number(value), len, unknown };
}

/** An EBML unsigned integer, which is simply big-endian over its own length. */
function readUint(buf, pos, length) {
  let value = 0;
  for (let i = 0; i < length; i += 1) value = value * 256 + buf[pos + i];
  return value;
}

/** An EBML float, 4 or 8 bytes. Anything else is not a float this cares about. */
function readFloat(buf, pos, length) {
  if (length === 4) return buf.readFloatBE(pos);
  if (length === 8) return buf.readDoubleBE(pos);
  return null;
}

/**
 * Walks the direct children of a region, calling visit() with each. Returning
 * false from visit stops the walk.
 *
 * `base` is the file offset `buf[0]` corresponds to, so callers get absolute
 * positions back rather than having to track the window themselves.
 */
function eachChild(buf, start, end, base, visit) {
  let pos = start;
  while (pos < end) {
    const id = readId(buf, pos);
    if (!id) return;
    const size = readSize(buf, pos + id.len);
    if (!size) return;
    const body = pos + id.len + size.len;
    if (size.unknown) {
      visit({ id: id.hex, body, length: null, absolute: base + pos });
      return;
    }
    if (visit({ id: id.hex, body, length: size.value, absolute: base + pos }) === false) return;
    pos = body + size.value;
  }
}

/** The Segment element, skipping whatever header elements precede it. */
function findSegment(buf) {
  let pos = 0;
  while (pos < buf.length) {
    const id = readId(buf, pos);
    if (!id) return null;
    const size = readSize(buf, pos + id.len);
    if (!size) return null;
    const dataStart = pos + id.len + size.len;
    if (id.hex === ID.SEGMENT) return { dataStart };
    if (size.unknown) return null;
    pos = dataStart + size.value;
  }
  return null;
}

/**
 * The Segment header: where its data begins, how long a timecode tick is, and
 * every SeekHead region worth following.
 */
function parseHead(head) {
  const segment = findSegment(head);
  if (!segment) return null;

  let timecodeScale = DEFAULT_TIMECODE_SCALE;
  let durationTicks = null;
  const seekHeads = [];
  const found = {};
  let firstCluster = null;

  eachChild(head, segment.dataStart, head.length, 0, (el) => {
    if (el.id === ID.SEEK_HEAD && el.length) seekHeads.push(el);
    if (el.id === ID.CUES) found.cues = el.absolute;
    if (el.id === ID.CLUSTER && firstCluster === null) firstCluster = el.absolute;
    if (el.id === ID.INFO && el.length) {
      eachChild(head, el.body, el.body + el.length, 0, (child) => {
        if (child.id === ID.TIMECODE_SCALE && child.length) {
          timecodeScale = readUint(head, child.body, child.length);
        }
        if (child.id === ID.DURATION && child.length) {
          durationTicks = readFloat(head, child.body, child.length);
        }
        return true;
      });
    }
    // Past the first Cluster there is nothing left worth reading in the head,
    // and stepping over multi-megabyte clusters would run off the buffer.
    return el.id !== ID.CLUSTER;
  });

  return {
    segmentDataStart: segment.dataStart,
    timecodeScale,
    durationTicks,
    seekHeads,
    found,
    firstCluster,
  };
}

/**
 * Every position a SeekHead announces, as { id -> absolute offset }.
 * SeekPosition is relative to the start of the Segment's data, which is why
 * segmentDataStart has to be carried this far.
 */
function parseSeekHead(buf, start, end, base, segmentDataStart) {
  const targets = {};
  eachChild(buf, start, end, base, (entry) => {
    if (entry.id !== ID.SEEK || !entry.length) return true;
    let seekId = null;
    let seekPos = null;
    eachChild(buf, entry.body, entry.body + entry.length, base, (field) => {
      if (field.id === ID.SEEK_ID && field.length) {
        seekId = buf.subarray(field.body, field.body + field.length).toString('hex');
      }
      if (field.id === ID.SEEK_POSITION && field.length) {
        seekPos = readUint(buf, field.body, field.length);
      }
      return true;
    });
    if (seekId && seekPos !== null) targets[seekId] = segmentDataStart + seekPos;
    return true;
  });
  return targets;
}

/** Cue points as [{ timeTicks, clusterPosition }], positions still relative. */
function parseCues(buf, start, end, base) {
  const cues = [];
  eachChild(buf, start, end, base, (point) => {
    if (point.id !== ID.CUE_POINT || !point.length) return true;
    let timeTicks = null;
    let clusterPosition = null;
    eachChild(buf, point.body, point.body + point.length, base, (field) => {
      if (field.id === ID.CUE_TIME && field.length) {
        timeTicks = readUint(buf, field.body, field.length);
      }
      if (field.id === ID.CUE_TRACK_POSITIONS && field.length) {
        eachChild(buf, field.body, field.body + field.length, base, (track) => {
          if (track.id === ID.CUE_CLUSTER_POSITION && track.length && clusterPosition === null) {
            clusterPosition = readUint(buf, track.body, track.length);
          }
          return true;
        });
      }
      return true;
    });
    if (timeTicks !== null && clusterPosition !== null) cues.push({ timeTicks, clusterPosition });
    return true;
  });
  return cues;
}

/**
 * Reads a whole element given the absolute position of its id, so a caller
 * that only knows where something starts can get its body.
 */
async function readElement(read, position, size, cap) {
  const header = await read(position, Math.min(position + 15, size - 1));
  const id = readId(header, 0);
  if (!id) return null;
  const length = readSize(header, id.len);
  if (!length || length.unknown) return null;
  const bodyStart = position + id.len + length.len;
  if (length.value > cap || length.value <= 0) return null;
  const body = await read(bodyStart, Math.min(bodyStart + length.value - 1, size - 1));
  return { id: id.hex, body, bodyStart };
}

/**
 * Walks the file cluster by cluster, reading only each cluster's header.
 *
 * A Cluster declares its own size, so the next one's position is arithmetic
 * rather than a search — this reads tens of bytes per cluster, never the media
 * bytes. Used only when the file has no Cues at all.
 */
async function scanClusters(read, size, start, timecodeScale, budgetMs) {
  const cues = [];
  let position = start;
  const deadline = Date.now() + budgetMs;
  let partial = false;

  for (let hop = 0; hop < MAX_CLUSTER_HOPS && position < size; hop += 1) {
    // Each hop is a network round trip, so the ceiling that matters is time,
    // not hop count. Stopping early leaves an index covering the opening of
    // the film, which still seeks — it just runs out at the end, which is why
    // the caller is told the table is partial and can rebuild it later.
    if (Date.now() > deadline) {
      partial = true;
      break;
    }
    const header = await read(position, Math.min(position + CLUSTER_HEADER_BYTES - 1, size - 1));
    const id = readId(header, 0);
    if (!id) break;
    const length = readSize(header, id.len);
    if (!length) break;
    const bodyStart = id.len + length.len;

    if (id.hex === ID.CLUSTER) {
      // Timecode is required to be the Cluster's first child, so it is inside
      // the few bytes already read.
      let timeTicks = null;
      eachChild(header, bodyStart, header.length, 0, (child) => {
        if (child.id === ID.TIMECODE && child.length) {
          timeTicks = readUint(header, child.body, child.length);
          return false;
        }
        return true;
      });
      if (timeTicks !== null) {
        cues.push([Math.round((timeTicks * timecodeScale) / 1e6), position]);
      }
    }

    // An unknown-size element cannot be stepped over, and neither can a
    // zero-length one — either would loop here forever.
    if (length.unknown || length.value <= 0) break;
    position = position + bodyStart + length.value;
  }

  return { cues, partial: partial || (cues.length > 0 && position < size) };
}

/**
 * Builds the seek table for one file.
 *
 * Returns { seekable, method, timecodeScale, durationMs, cues: [[timeMs,
 * byteOffset]] }. `seekable: false` means this is not a Matroska file, or is
 * one nothing could be recovered from — the caller should leave the player's
 * own judgement alone in that case.
 */
export async function buildMkvIndex({ read, size, allowClusterScan = true, clusterScanBudgetMs = 20000 }) {
  const head = await read(0, Math.min(HEAD_BYTES, size) - 1);
  if (head.subarray(0, 4).toString('hex') !== ID.EBML) {
    return { seekable: false, method: 'not-matroska', cues: [] };
  }

  const parsed = parseHead(head);
  if (!parsed) return { seekable: false, method: 'no-segment', cues: [] };

  const { segmentDataStart, timecodeScale, durationTicks } = parsed;
  const durationMs = durationTicks ? Math.round((durationTicks * timecodeScale) / 1e6) : null;

  // 1. Follow the SeekHead chain. One level of indirection is exactly where
  //    MatroskaExtractor stops, so following it is the entire point.
  let cuesPosition = parsed.found.cues ?? null;
  let method = cuesPosition ? 'cues-head' : null;
  const visited = new Set();
  let pending = parsed.seekHeads.map((el) => ({
    buf: head,
    start: el.body,
    end: el.body + el.length,
    base: 0,
  }));

  while (cuesPosition === null && pending.length) {
    const next = [];
    for (const region of pending) {
      const targets = parseSeekHead(region.buf, region.start, region.end, region.base, segmentDataStart);
      if (targets[ID.CUES] !== undefined) {
        cuesPosition = targets[ID.CUES];
        method = 'cues-seekhead';
        break;
      }
      const chained = targets[ID.SEEK_HEAD];
      if (chained !== undefined && !visited.has(chained) && chained < size) {
        visited.add(chained);
        const element = await readElement(read, chained, size, MAX_CUES_BYTES).catch(() => null);
        if (element?.id === ID.SEEK_HEAD) {
          next.push({ buf: element.body, start: 0, end: element.body.length, base: element.bodyStart });
        }
      }
    }
    pending = next;
  }

  // 2. No pointer to Cues: look for the element itself at the end of the file,
  //    where every muxer that writes it last puts it.
  if (cuesPosition === null && size > 0) {
    const tailStart = Math.max(0, size - TAIL_BYTES);
    const tail = await read(tailStart, size - 1);
    const at = tail.lastIndexOf(Buffer.from(ID.CUES, 'hex'));
    if (at >= 0) {
      cuesPosition = tailStart + at;
      method = 'cues-tail';
    }
  }

  if (cuesPosition !== null && cuesPosition < size) {
    const element = await readElement(read, cuesPosition, size, MAX_CUES_BYTES).catch(() => null);
    if (element?.id === ID.CUES) {
      const cues = parseCues(element.body, 0, element.body.length, element.bodyStart)
        .map((p) => [
          Math.round((p.timeTicks * timecodeScale) / 1e6),
          segmentDataStart + p.clusterPosition,
        ])
        // A cue pointing past the end of the file would seek into nothing.
        .filter(([, offset]) => offset >= 0 && offset < size)
        .sort((a, b) => a[0] - b[0]);

      if (cues.length >= MIN_USEFUL_CUES) {
        console.log(`${LOG} ${method}: ${cues.length} cue points`);
        return { seekable: true, method, timecodeScale, durationMs, cues };
      }
    }
  }

  // 3. Nothing indexed this file. Build the index from the clusters themselves.
  if (allowClusterScan && parsed.firstCluster !== null) {
    const scanned = await scanClusters(
      read,
      size,
      parsed.firstCluster,
      timecodeScale,
      clusterScanBudgetMs
    );
    if (scanned.cues.length >= MIN_USEFUL_CUES) {
      console.log(
        `${LOG} clusters: ${scanned.cues.length} cluster points${scanned.partial ? ' (partial)' : ''}`
      );
      return {
        seekable: true,
        method: 'clusters',
        partial: scanned.partial,
        timecodeScale,
        durationMs,
        cues: scanned.cues,
      };
    }
  }

  return { seekable: false, method: 'no-index', timecodeScale, durationMs, cues: [] };
}
