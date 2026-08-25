// DhakaFlix HTTP index — a second, read-only media source alongside Google
// Drive.
//
// Despite being asked for as "the FTP server", 172.16.50.4 is nginx serving
// h5ai v0.29 directory indexes over plain HTTP. The landing page holds no
// files itself; the libraries live on sibling hosts (172.16.50.7/.8/.9/.12/
// .14), which is why roots are full URLs rather than paths under one origin.
//
// Nothing here ever downloads a file. The crawler reads listings to build the
// catalog, and playback happens straight from the origin — see
// app/api/media/stream/[id]/route.js for why the bytes are not proxied.

const LOG = '[dhakaflix]';

// The categories the library imports. Overridable with DHAKAFLIX_ROOTS, a
// comma-separated list of directory URLs, so a deploy can widen or narrow the
// crawl without a code change.
const DEFAULT_ROOTS = [
  'http://172.16.50.14/DHAKA-FLIX-14/English Movies (1080p)/',
  'http://172.16.50.14/DHAKA-FLIX-14/Hindi Movies/',
  'http://172.16.50.14/DHAKA-FLIX-14/SOUTH INDIAN MOVIES/Hindi Dubbed/',
  'http://172.16.50.14/DHAKA-FLIX-14/SOUTH INDIAN MOVIES/South Movies/',
  'http://172.16.50.14/DHAKA-FLIX-14/KOREAN TV & WEB Series/',
  'http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/',
];

// A movie sits at root/(year)/Title/file.mkv and a series at
// root/Show/Season 01/file.mkv, so five levels covers both with room to spare
// while still stopping a mislinked tree from being walked forever.
const MAX_DEPTH = Number(process.env.DHAKAFLIX_MAX_DEPTH || 5);

// Sample clips and trailers share a folder with the film and parse to the same
// title, so they would land in the library as duplicates. Nothing this small is
// a feature-length file.
const MIN_FILE_BYTES = Number(process.env.DHAKAFLIX_MIN_FILE_BYTES || 50 * 1024 * 1024);

// The index is on the local ISP network, so a request is cheap, but h5ai
// renders each listing through PHP — a wide fan-out makes it the bottleneck.
const DIRECTORY_CONCURRENCY = 4;
const REQUEST_TIMEOUT_MS = Number(process.env.DHAKAFLIX_TIMEOUT_MS || 20000);

export function dhakaFlixRoots() {
  const configured = (process.env.DHAKAFLIX_ROOTS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  return configured.length ? configured : DEFAULT_ROOTS;
}

export function isDhakaFlixEnabled() {
  // Opt-out rather than opt-in: the roots have working defaults, and a deploy
  // that cannot reach the ISP network sets DHAKAFLIX_ENABLED=0.
  return process.env.DHAKAFLIX_ENABLED !== '0';
}

async function fetchText(url) {
  const res = await fetch(url, {
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    headers: { Accept: 'text/html' },
  });
  if (!res.ok) throw new Error(`${url} -> HTTP ${res.status}`);
  return res.text();
}

// h5ai's JavaScript UI is backed by a PHP JSON endpoint, but every listing also
// ships a plain <table> fallback for clients without JS. The fallback is the
// stable half: one row per entry, name and size in fixed classes.
const ROW_RE = /<td class="fb-n"><a href="([^"]*)">([\s\S]*?)<\/a><\/td>[\s\S]*?<td class="fb-s">([^<]*)<\/td>/g;

const SIZE_UNITS = { B: 1, KB: 1024, MB: 1024 ** 2, GB: 1024 ** 3, TB: 1024 ** 4 };

/**
 * The listing's size column, in bytes. h5ai rounds to a whole unit, so this is
 * an estimate — good enough to reject a 10 MB sample, not to answer a Range
 * request. fileMetadata() gets the exact number when playback needs it.
 */
function parseSize(text) {
  const match = /^\s*([\d.]+)\s*(B|KB|MB|GB|TB)\s*$/i.exec(text || '');
  if (!match) return 0;
  return Math.round(Number(match[1]) * SIZE_UNITS[match[2].toUpperCase()]);
}

function decodeEntities(text) {
  return text
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'");
}

/**
 * Reads one directory. Returns { directories: [url], files: [{ name, url,
 * size }] }, both absolute URLs on the listing's own origin.
 */
export async function listDirectory(url) {
  const html = await fetchText(url);
  const directories = [];
  const files = [];

  ROW_RE.lastIndex = 0;
  for (let row; (row = ROW_RE.exec(html)); ) {
    const [, href, rawName, rawSize] = row;
    // The parent link is relative and would walk the crawl back up the tree.
    if (href === '..' || href === '.' || href.startsWith('?')) continue;

    const absolute = new URL(href, url).href;
    if (absolute.endsWith('/')) {
      directories.push(absolute);
    } else {
      files.push({
        name: decodeEntities(rawName).trim(),
        url: absolute,
        size: parseSize(rawSize),
      });
    }
  }

  return { directories, files };
}

/**
 * Walks every configured root breadth-first and calls onFile for each entry
 * found. Errors on one directory are reported and skipped: a single unreadable
 * folder must not abandon a crawl of thousands.
 */
export async function crawl({ roots = dhakaFlixRoots(), maxDepth = MAX_DEPTH, onFile, onError } = {}) {
  const seen = new Set();
  let frontier = roots.filter((r) => !seen.has(r) && seen.add(r));

  for (let depth = 0; depth <= maxDepth && frontier.length; depth += 1) {
    const next = [];
    const queue = [...frontier];

    const workers = Array.from({ length: Math.min(DIRECTORY_CONCURRENCY, queue.length) }, async () => {
      for (let dir = queue.shift(); dir; dir = queue.shift()) {
        let listing;
        try {
          listing = await listDirectory(dir);
        } catch (err) {
          console.error(`${LOG} listing failed for ${dir}`, err.message);
          onError?.({ url: dir, error: err.message });
          continue;
        }
        for (const child of listing.directories) {
          if (seen.has(child)) continue;
          seen.add(child);
          next.push(child);
        }
        for (const file of listing.files) await onFile?.(file);
      }
    });

    await Promise.all(workers);
    frontier = next;
  }

  return { directoriesVisited: seen.size };
}

/** True when a listing entry is big enough to be a real title, not a sample. */
export function isFeatureSized(file) {
  return !file.size || file.size >= MIN_FILE_BYTES;
}

/**
 * Exact size and type for one file, straight from the origin's HEAD response.
 * Only needed by callers that must answer a Range request themselves; ordinary
 * playback goes to the origin directly and never calls this.
 */
export async function fileMetadata(url) {
  const res = await fetch(url, {
    method: 'HEAD',
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(`${url} -> HTTP ${res.status}`);
  return {
    size: Number(res.headers.get('content-length') || 0),
    mimeType: res.headers.get('content-type') || '',
    name: decodeURIComponent(new URL(url).pathname.split('/').pop() || ''),
  };
}
