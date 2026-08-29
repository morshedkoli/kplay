// GET /api/media/play-url/[id] — tells a client where to fetch a title's bytes.
//
// The stream route proxies every byte through this server: Drive -> VPS ->
// player. That is the only thing a browser can use (see below), but it makes
// the VPS the bandwidth bottleneck for a film it never needs to look at.
//
// This route offers the alternative. It answers with either
//
//   { mode: 'direct', url, contentType, size, expiresAt }
//   { mode: 'proxy',  url, contentType, size, reason }
//
// A 'direct' url points at Google, carries a short-lived access token, and is
// fetched by the player itself — the VPS serves the metadata and then drops
// out of the path. A 'proxy' url is the existing stream route, returned
// whenever direct play is known not to work for this client or this file.
//
// The client always honours `mode` rather than assuming: which files can go
// direct is a server-side decision that changes with configuration, and a
// client that hardcoded it would break silently.
//
// WHY BROWSERS NEVER GET 'direct': Drive serves these files as
// application/octet-stream with X-Content-Type-Options: nosniff and no
// Access-Control-Allow-Origin. A <video> element refuses them both with and
// without `crossorigin`. Only a native player (ExoPlayer on the Android TV
// client), which is not bound by CORS and reads the bytes to pick an
// extractor, can use a direct URL at all.

import { ObjectId } from 'mongodb';

import { hasDeviceKey, requireDeviceOrSession } from '@/lib/auth.js';
import { getAccessToken, getFileMetadata } from '@/lib/gdrive.js';
import { videoContentType } from '@/lib/library/video-types.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

// Containers a direct URL is allowed to serve.
//
// Going direct means losing this server's Content-Type override: Google sends
// application/octet-stream for most of this library (see
// lib/library/video-types.js) and the player has only the bytes to go on.
// ExoPlayer sniffs successfully for MP4 and friends. Matroska is the case
// that misdetects — the same family of files that already needs
// /api/media/seek-index to be seekable at all — so .mkv keeps the proxy,
// where the type is stated correctly, unless deliberately opted in.
//
// Override with PLAY_URL_DIRECT_CONTAINERS as a comma-separated extension
// list, e.g. "mp4,m4v,mkv". Empty disables direct play entirely, which is the
// switch to reach for if Drive starts rate-limiting.
const DEFAULT_DIRECT_CONTAINERS = 'mp4,m4v,mov,webm';

function directContainers() {
  const raw = process.env.PLAY_URL_DIRECT_CONTAINERS ?? DEFAULT_DIRECT_CONTAINERS;
  return new Set(
    raw
      .split(',')
      .map((s) => s.trim().toLowerCase())
      .filter(Boolean)
  );
}

function extensionOf(name = '') {
  const match = /\.([a-z0-9]+)$/i.exec(name);
  return match ? match[1].toLowerCase() : '';
}

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
 * Why this file cannot go direct, or null when it can.
 *
 * Split out from GET so the reason travels to the client: a title silently
 * falling back to the proxy is indistinguishable from direct play being
 * broken, and this is the field that tells them apart in the logs.
 */
function proxyReason(request, meta) {
  // A browser session, not a device key. See the CORS note at the top.
  if (!hasDeviceKey(request)) return 'browser-client';

  const allowed = directContainers();
  if (allowed.size === 0) return 'direct-play-disabled';

  const ext = extensionOf(meta.name);
  if (!allowed.has(ext)) return `container-not-direct-playable:${ext || 'unknown'}`;

  // Drive omits size for a shortcut or a file still being written. The stream
  // route has a whole-file path for that; a direct URL has no equivalent, and
  // a player handed a length-less source seeks badly.
  if (!meta.size) return 'unknown-size';

  return null;
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
    console.error('[api/media/play-url] Drive metadata failed', err);
    return Response.json({ error: 'Storage read failed' }, { status: 502 });
  }

  const contentType = videoContentType(meta.name, meta.mimeType);
  const proxyUrl = `/api/media/stream/${id}`;

  const reason = proxyReason(request, meta);
  if (reason) {
    return Response.json(
      { mode: 'proxy', url: proxyUrl, contentType, size: meta.size, reason },
      { headers: { 'Cache-Control': 'private, no-store' } }
    );
  }

  let token;
  let expiresAt;
  try {
    ({ token, expiresAt } = await getAccessToken());
  } catch (err) {
    // A token failure is not fatal: the proxy path uses the same credentials
    // through googleapis and may well still work. Fall back rather than
    // failing playback outright.
    console.error('[api/media/play-url] access token failed, falling back to proxy', err);
    return Response.json(
      { mode: 'proxy', url: proxyUrl, contentType, size: meta.size, reason: 'token-unavailable' },
      { headers: { 'Cache-Control': 'private, no-store' } }
    );
  }

  // alt=media is the bytes endpoint, and it accepts the token as a query
  // parameter — which is the only way to authenticate here, since the player
  // fetching this URL cannot be told to send an Authorization header.
  const url =
    `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(driveFileId)}` +
    `?alt=media&access_token=${encodeURIComponent(token)}`;

  console.log(`[api/media/play-url] direct ${id} -> ${driveFileId} (${meta.name})`);

  return Response.json(
    { mode: 'direct', url, contentType, size: meta.size, expiresAt },
    {
      // This body contains a live credential. It must never be written to a
      // disk cache, a shared proxy, or a browser's back-forward store.
      headers: { 'Cache-Control': 'private, no-store', Pragma: 'no-cache' },
    }
  );
}
