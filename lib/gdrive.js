// Google Drive storage backend — the sole place media file bytes live.
//
// Auth is a single OAuth2 refresh token tied to one Google account (this is
// a single-user, single-Drive app — see docs/superpowers/specs/
// 2026-08-20-media-server-backend-design.md). No fallback backend exists:
// a Drive API error surfaces directly to the caller.

import { google } from 'googleapis';

const LOG = '[gdrive]';

export function isDriveConfigured() {
  return Boolean(
    process.env.GOOGLE_CLIENT_ID &&
      process.env.GOOGLE_CLIENT_SECRET &&
      process.env.GOOGLE_REFRESH_TOKEN
  );
}

let driveClient = null;

function getDrive() {
  if (driveClient) return driveClient;

  if (!isDriveConfigured()) {
    throw new Error(
      'Google Drive is not configured — set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REFRESH_TOKEN'
    );
  }

  const auth = new google.auth.OAuth2(
    process.env.GOOGLE_CLIENT_ID,
    process.env.GOOGLE_CLIENT_SECRET
  );
  auth.setCredentials({ refresh_token: process.env.GOOGLE_REFRESH_TOKEN });

  driveClient = google.drive({ version: 'v3', auth });
  return driveClient;
}

/** Uploads a stream as a new Drive file. Returns { driveFileId, size }. */
export async function uploadFile(stream, name, mimeType) {
  const drive = getDrive();
  const parentId = process.env.GOOGLE_DRIVE_FOLDER_ID;

  const res = await drive.files.create({
    requestBody: {
      name,
      ...(parentId ? { parents: [parentId] } : {}),
    },
    media: { mimeType, body: stream },
    fields: 'id, size',
  });

  const driveFileId = res.data.id;
  const size = Number(res.data.size || 0);
  console.log(`${LOG} uploaded ${name} -> ${driveFileId} (${size} bytes)`);
  return { driveFileId, size };
}

// A video player issues one Range request per seek and several more while
// buffering, and every one of them needs the file's total size before it can
// be answered. Uploaded bytes never change, so metadata is cached in-process
// rather than costing a Drive round trip per range. Bounded so a large library
// can't grow the map without limit; the oldest entry is evicted first.
const META_CACHE_MAX = 500;
const metaCache = new Map();

/** Fetches a file's size/mimeType/name without downloading its bytes. */
export async function getFileMetadata(driveFileId) {
  const cached = metaCache.get(driveFileId);
  if (cached) return cached;

  const drive = getDrive();
  const res = await drive.files.get({
    fileId: driveFileId,
    fields: 'size, mimeType, name',
  });
  const meta = {
    size: Number(res.data.size || 0),
    mimeType: res.data.mimeType,
    name: res.data.name,
  };

  if (metaCache.size >= META_CACHE_MAX) {
    metaCache.delete(metaCache.keys().next().value);
  }
  metaCache.set(driveFileId, meta);
  return meta;
}

/**
 * Streams a file's bytes, honoring an HTTP Range header if given.
 * Returns { stream, contentRange, contentLength, status }.
 */
export async function streamFile(driveFileId, rangeHeader) {
  const drive = getDrive();
  const meta = await getFileMetadata(driveFileId);

  const headers = {};
  let status = 200;
  let contentRange = null;
  let contentLength = meta.size;

  if (rangeHeader) {
    const match = /^bytes=(\d+)-(\d*)$/.exec(rangeHeader);
    if (!match) {
      const err = new Error('Invalid Range header');
      err.status = 416;
      throw err;
    }
    const start = Number(match[1]);
    const end = match[2] ? Number(match[2]) : meta.size - 1;
    if (start >= meta.size || end >= meta.size || start > end) {
      const err = new Error('Range out of bounds');
      err.status = 416;
      throw err;
    }
    headers.Range = `bytes=${start}-${end}`;
    status = 206;
    contentRange = `bytes ${start}-${end}/${meta.size}`;
    contentLength = end - start + 1;
  }

  const res = await drive.files.get(
    { fileId: driveFileId, alt: 'media' },
    { responseType: 'stream', headers }
  );

  return { stream: res.data, contentRange, contentLength, status, mimeType: meta.mimeType };
}

/**
 * Reads one byte range of a file into memory.
 *
 * Seek-index building needs a few kilobytes from the front and the back of a
 * file, never the whole thing, so this buffers a bounded slice rather than
 * handing back a stream the caller has to drain itself.
 */
export async function readRange(driveFileId, start, end) {
  const drive = getDrive();
  const res = await drive.files.get(
    { fileId: driveFileId, alt: 'media' },
    { responseType: 'arraybuffer', headers: { Range: `bytes=${start}-${end}` } }
  );
  return Buffer.from(res.data);
}

/**
 * Lists every file in the configured folder (or the whole Drive when no
 * folder is set). Pages through results — a library outgrows one page fast.
 * Returns [{ driveFileId, name, size, mimeType }].
 */
export async function listFolderFiles() {
  const drive = getDrive();
  const parentId = process.env.GOOGLE_DRIVE_FOLDER_ID;

  const q = [
    'trashed = false',
    "mimeType != 'application/vnd.google-apps.folder'",
    ...(parentId ? [`'${parentId}' in parents`] : []),
  ].join(' and ');

  const files = [];
  let pageToken;
  do {
    const res = await drive.files.list({
      q,
      fields: 'nextPageToken, files(id, name, size, mimeType)',
      pageSize: 1000,
      pageToken,
      orderBy: 'name',
    });
    for (const f of res.data.files || []) {
      files.push({
        driveFileId: f.id,
        name: f.name,
        size: Number(f.size || 0),
        mimeType: f.mimeType,
      });
    }
    pageToken = res.data.nextPageToken;
  } while (pageToken);

  return files;
}

/** Returns Drive storage usage for the configured account. */
export async function getStorageUsage() {
  const drive = getDrive();
  const res = await drive.about.get({ fields: 'storageQuota' });
  const quota = res.data.storageQuota || {};
  return {
    usedBytes: Number(quota.usage || 0),
    limitBytes: quota.limit ? Number(quota.limit) : null,
  };
}

/** Deletes a file. Missing is not an error. */
export async function deleteFile(driveFileId) {
  const drive = getDrive();
  try {
    await drive.files.delete({ fileId: driveFileId });
    metaCache.delete(driveFileId);
    console.log(`${LOG} deleted ${driveFileId}`);
  } catch (err) {
    if (err.code === 404) return;
    throw err;
  }
}

/**
 * A short-lived OAuth access token for the library's Drive account.
 *
 * Used by /api/media/play-url to hand a player a URL it can fetch from Google
 * directly, with the VPS out of the byte path entirely. googleapis caches and
 * refreshes the token internally, so calling this per playback costs nothing
 * once the first call has warmed it.
 *
 * Returns { token, expiresAt } where expiresAt is epoch milliseconds. The
 * expiry is the OAuth client's own, not a guess: a URL handed out just before
 * a refresh would otherwise be advertised as valid for an hour and die in
 * minutes.
 */
export async function getAccessToken() {
  const drive = getDrive();
  const auth = drive.context._options.auth;

  const { token } = await auth.getAccessToken();
  if (!token) throw new Error('Google Drive returned no access token');

  // expiry_date is set by the refresh that getAccessToken() just performed (or
  // by the one that cached the token still in hand). Missing only if the
  // client has been used in a way that skips the refresh path; a conservative
  // five minutes is safer there than claiming an hour.
  const expiresAt = auth.credentials?.expiry_date || Date.now() + 5 * 60 * 1000;
  return { token, expiresAt };
}
