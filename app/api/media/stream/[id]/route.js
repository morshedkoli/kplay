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

import { Readable } from 'node:stream';

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { getFileMetadata, streamFile } from '@/lib/gdrive.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

// 8 MB is a few seconds of 1080p — small enough that a response always
// completes well inside the function limit, large enough that the request
// count stays sane over a two-hour film.
const MAX_CHUNK_BYTES = 8 * 1024 * 1024;

async function resolveDriveFileId(id) {
  const _id = new ObjectId(id);
  const media = await mediaCollection();
  const mediaDoc = await media.findOne({ _id });
  if (mediaDoc?.driveFileId) return mediaDoc.driveFileId;

  const episodes = await episodeCollection();
  const episodeDoc = await episodes.findOne({ _id });
  return episodeDoc?.driveFileId ?? null;
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

  // The cap is what keeps a single response short.
  if (end - start + 1 > MAX_CHUNK_BYTES) end = start + MAX_CHUNK_BYTES - 1;

  return { start, end };
}

export async function GET(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  let driveFileId;
  try {
    driveFileId = await resolveDriveFileId(id);
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }
  if (!driveFileId) return Response.json({ error: 'Not found' }, { status: 404 });

  let meta;
  try {
    meta = await getFileMetadata(driveFileId);
  } catch (err) {
    console.error('[api/media/stream] Drive metadata failed', err);
    return Response.json({ error: 'Storage read failed' }, { status: 502 });
  }

  const range = resolveRange(request.headers.get('range'), meta.size);
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
  return new Response(Readable.toWeb(result.stream), {
    status: 206,
    headers: {
      'Content-Length': String(range.end - range.start + 1),
      'Content-Range': `bytes ${range.start}-${range.end}/${meta.size}`,
      'Accept-Ranges': 'bytes',
      'Content-Type': meta.mimeType || 'application/octet-stream',
      'Cache-Control': 'private, no-store',
    },
  });
}
