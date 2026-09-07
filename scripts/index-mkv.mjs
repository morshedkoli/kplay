// scripts/index-mkv.mjs — builds Matroska seek indexes and uploads them.
//
// Run this on a machine with bandwidth, after adding .mkv titles to the Drive
// folder. It reads enough of each file to find the cues, which is why it does
// not live in the deployed app: those reads are the data transfer the direct
// play design exists to avoid.
//
//   npm run index:mkv                 index every mkv missing an index
//   npm run index:mkv -- --force      rebuild indexes that already exist
//
// Needs the same GOOGLE_* variables the app uses, plus KDRIVE_BASE_URL and
// KDRIVE_DEVICE_KEY so it can post results back.

import { loadEnv } from '../lib/env.js';
loadEnv();

import { getAccessToken } from '../lib/gdrive.js';
import { buildMkvIndex } from '../lib/library/mkv-index.js';

const BASE_URL = process.env.KDRIVE_BASE_URL || 'http://localhost:3000';
const DEVICE_KEY = process.env.KDRIVE_DEVICE_KEY;
const force = process.argv.includes('--force');

if (!DEVICE_KEY) {
  console.error('Set KDRIVE_DEVICE_KEY before running this.');
  process.exit(1);
}

const headers = { 'x-kdrive-device-key': DEVICE_KEY };

async function api(path, init = {}) {
  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: { ...headers, ...(init.headers ?? {}) },
  });
  if (!res.ok) throw new Error(`${init.method ?? 'GET'} ${path} -> ${res.status}`);
  return res.json();
}

/** Reads one byte range of a Drive file, refreshing the token as it goes. */
function driveReader(driveFileId) {
  return async (start, end) => {
    const { token } = await getAccessToken();
    const res = await fetch(
      `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(driveFileId)}?alt=media`,
      { headers: { Authorization: `Bearer ${token}`, Range: `bytes=${start}-${end}` } }
    );
    if (!res.ok) throw new Error(`Drive range ${start}-${end} -> ${res.status}`);
    return Buffer.from(await res.arrayBuffer());
  };
}

const { items } = await api('/api/media/list');

const pending = items.filter(
  (item) => item.filename?.toLowerCase().endsWith('.mkv') && (force || !item.seekIndex)
);

if (pending.length === 0) {
  console.log('Nothing to index.');
  process.exit(0);
}

console.log(`Indexing ${pending.length} file(s).`);

for (const item of pending) {
  try {
    const id = item.id || item._id;
    const result = await buildMkvIndex({
      read: driveReader(item.driveFileId),
      size: item.size,
    });
    if (!result.seekable || !result.cues || result.cues.length === 0) {
      console.log(`skip: ${item.filename} (unseekable or no cues)`);
      continue;
    }
    await api(`/api/media/${id}/seek-index`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ seekIndex: result.cues }),
    });
    console.log(`ok: ${item.filename} (${result.cues.length} cue points)`);
  } catch (err) {
    console.error(`failed: ${item.filename} — ${err.message}`);
  }
}
