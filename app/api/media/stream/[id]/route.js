// GET /api/media/stream/[id] — proxies file bytes from Google Drive, honoring
// the client's Range header for seeking (direct-play only — no transcoding).
// `id` may be a media _id (movie) or an episode _id (series episode) — try
// media first, fall back to episode.
//
// Every response is capped at MAX_CHUNK_BYTES regardless of how much the
// client asked for. A <video> element opens `bytes=0-` and would otherwise
// hold one connection for the entire film, which no serverless platform
// allows (Vercel kills a function at 300s). Truncating the returned range is
// legal HTTP: the client sees a 206 with a shorter Content-Range and simply
// requests the next window. Playback and seeking are unaffected; only the
// number of requests goes up.
//
// Redirecting to a public Drive URL instead would avoid proxying entirely,
// but Drive serves these files as `application/octet-stream` with
// `X-Content-Type-Options: nosniff` and no `Access-Control-Allow-Origin`, so
// a browser <video> refuses them both with and without `crossorigin`.
//
// A DhakaFlix item is the opposite case and is answered with a 302 to its
// origin instead of any proxying at all. Two reasons: the index lives on the
// ISP's private network (172.16.50.x), which a public deploy cannot reach in
// the first place, and its nginx already serves byte ranges properly, so
// relaying whole films through this route would only add a bandwidth bill and
// a function timeout. The consequence to know about: the origin is plain HTTP,
// so a browser on an HTTPS page blocks the redirected request as mixed
// content. The Android TV client (ExoPlayer, no such rule) and the app served
// over HTTP on the local network both play it fine.

import { Readable } from 'node:stream';

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { getFileMetadata, streamFile } from '@/lib/gdrive.js';
import { videoContentType } from '@/lib/library/video-types.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

// Capping is off by default, which is what a VPS wants: browsers and
// ExoPlayer both stream an open-ended range happily, and an uncapped response
// is one connection instead of hundreds.
//
// Set STREAM_MAX_CHUNK_BYTES on a host with a function time limit (Vercel
// kills a request at 300s) — 8388608 is a sensible value there. Be aware the
// Android TV client uses ExoPlayer's ProgressiveMediaSource, which expects an
// open-ended range to run to the end of the file; a truncated range may end
// its playback early. Browsers are unaffected either way.
const MAX_CHUNK_BYTES = Number(process.env.STREAM_MAX_CHUNK_BYTES || 0);

// How long a client may reuse a range it already downloaded. A day is long
// enough to make a re-watch or a scrub free and short enough that a replaced
// file is not served stale forever.
const CACHE_SECONDS = 86400;

/**
 * The doc holding this id's bytes, movie or episode. Returns the doc itself
 * rather than one address, because which field to read depends on its source.
 */
async function resolveDoc(id) {
  const _id = new ObjectId(id);
  const media = await mediaCollection();
  const mediaDoc = await media.findOne({ _id });
  if (mediaDoc?.driveFileId || mediaDoc?.sourceUrl) return mediaDoc;

  const episodes = await episodeCollection();
  return episodes.findOne({ _id });
}

/**
 * Resolves the client's Range header against the file size and the chunk cap.
 * Returns { start, end } inclusive, or null when the range is unsatisfiable.
 * A missing Range header is treated as "from the start".
 */
function resolveRange(rangeHeader, size) {
  let start = 0;
  let end = size - 1;

  if (rangeHeader) {
    const match = /^bytes=(\d+)-(\d*)$/.exec(rangeHeader);
    if (!match) return null;
    start = Number(match[1]);
    end = match[2] ? Number(match[2]) : size - 1;
    if (start >= size || start > end) return null;
    if (end >= size) end = size - 1;
  }

  // The cap is what keeps a single response short. Zero disables it.
  if (MAX_CHUNK_BYTES > 0 && end - start + 1 > MAX_CHUNK_BYTES) {
    end = start + MAX_CHUNK_BYTES - 1;
  }

  return { start, end };
}

export async function GET(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  let doc;
  try {
    doc = await resolveDoc(id);
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }
  if (!doc) return Response.json({ error: 'Not found' }, { status: 404 });

  // DhakaFlix bytes are never proxied — see the note at the top of this file.
  // A 302 keeps one stream URL for every client: ExoPlayer and <video> both
  // follow it, and the Range request lands on the origin's nginx, which serves
  // ranges natively.
  if (doc.sourceUrl) {
    return new Response(null, {
      status: 302,
      headers: { Location: doc.sourceUrl, 'Cache-Control': 'private, no-store' },
    });
  }

  const { driveFileId } = doc;
  if (!driveFileId) return Response.json({ error: 'Not found' }, { status: 404 });

  let meta;
  try {
    meta = await getFileMetadata(driveFileId);
  } catch (err) {
    console.error('[api/media/stream] Drive metadata failed', err);
    return Response.json({ error: 'Storage read failed' }, { status: 502 });
  }

  // Drive does not always report a size — a shortcut, or a file still being
  // written, comes back without one. `meta.size` was then 0, every range
  // resolved as unsatisfiable, and the route answered 416 forever: the title
  // sat in the library and simply never played. Serve it whole instead and
  // let the player work the length out from the container.
  if (!meta.size) {
    console.warn(`[api/media/stream] Drive reported no size for ${driveFileId}; serving whole`);
    let whole;
    try {
      whole = await streamFile(driveFileId, null);
    } catch (err) {
      console.error('[api/media/stream] Drive stream failed', err);
      return Response.json({ error: 'Storage read failed' }, { status: 502 });
    }
    request.signal?.addEventListener('abort', () => whole.stream.destroy(), { once: true });
    return new Response(Readable.toWeb(whole.stream), {
      status: 200,
      headers: {
        'Accept-Ranges': 'none',
        'Content-Type': videoContentType(meta.name, meta.mimeType),
        'Cache-Control': 'private, no-store',
      },
    });
  }

  const rangeHeader = request.headers.get('range');
  const range = resolveRange(rangeHeader, meta.size);
  if (!range) {
    return new Response(null, {
      status: 416,
      headers: { 'Content-Range': `bytes */${meta.size}` },
    });
  }

  let result;
  try {
    result = await streamFile(driveFileId, `bytes=${range.start}-${range.end}`);
  } catch (err) {
    if (err.status === 416) {
      return new Response(null, {
        status: 416,
        headers: { 'Content-Range': `bytes */${meta.size}` },
      });
    }
    console.error('[api/media/stream] Drive stream failed', err);
    return Response.json({ error: 'Storage read failed' }, { status: 502 });
  }

  // A seek or a closed tab aborts the request mid-download. Without this the
  // Drive-side socket stays open until it times out, and a night of scrubbing
  // leaks a connection per seek.
  request.signal?.addEventListener('abort', () => result.stream.destroy(), { once: true });

  // result.stream is a Node Readable (googleapis responseType: 'stream').
  // Response wants a web ReadableStream; converting explicitly keeps
  // backpressure intact instead of relying on undici's coercion.
  // 206 whenever the client asked for a range, or whenever the cap made the
  // response narrower than the whole file. A plain GET answered in full is a
  // 200 — replying 206 to a request that carried no Range header is invalid.
  const isPartial = Boolean(rangeHeader) || range.start > 0 || range.end < meta.size - 1;

  const headers = {
    'Content-Length': String(range.end - range.start + 1),
    'Accept-Ranges': 'bytes',
    // Not Drive's mimeType. Drive reports plenty of these files as
    // application/octet-stream, and a browser <video> refuses to play that —
    // see lib/library/video-types.js.
    'Content-Type': videoContentType(meta.name, meta.mimeType),
    // A file's bytes never change once it is in Drive, so re-fetching a range
    // the client already holds is pure waste. `private` keeps it out of any
    // shared proxy (these responses are behind a device key or a session
    // cookie); `immutable` stops a revalidation round trip on every seek back
    // into already-watched footage. This was `no-store`, which forced every
    // single range request to travel all the way to Drive — the main reason
    // scrubbing rebuffered even over a fast connection.
    'Cache-Control': `private, max-age=${CACHE_SECONDS}, immutable`,
    // Lets a client that kept a range revalidate it cheaply rather than
    // re-downloading. Size plus id is enough: neither changes for a given
    // file, and both change together when the file is replaced.
    ETag: `"${driveFileId}-${meta.size}"`,
  };
  if (isPartial) headers['Content-Range'] = `bytes ${range.start}-${range.end}/${meta.size}`;

  return new Response(Readable.toWeb(result.stream), {
    status: isPartial ? 206 : 200,
    headers,
  });
}
