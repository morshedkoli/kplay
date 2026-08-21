// Round-trips a throwaway file through the real Google Drive API.
// Run: node scripts/test-gdrive.js
// Requires GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / GOOGLE_REFRESH_TOKEN in .env.local.

import { loadEnv } from '../lib/env.js';
loadEnv();

import { Readable } from 'node:stream';
import { deleteFile, getFileMetadata, isDriveConfigured, streamFile, uploadFile } from '../lib/gdrive.js';

function assert(cond, msg) {
  if (!cond) throw new Error(`FAIL: ${msg}`);
  console.log(`  ok: ${msg}`);
}

async function main() {
  assert(isDriveConfigured(), 'Drive credentials are set');

  const content = Buffer.from('kdrive gdrive smoke test payload');
  const { driveFileId, size } = await uploadFile(
    Readable.from(content),
    `kdrive-test-${Date.now()}.txt`,
    'text/plain'
  );
  assert(driveFileId, 'upload returned a driveFileId');
  assert(size === content.length, `uploaded size matches (${size} === ${content.length})`);

  const meta = await getFileMetadata(driveFileId);
  assert(meta.size === content.length, 'metadata size matches');

  const full = await streamFile(driveFileId, null);
  assert(full.status === 200, 'full stream status is 200');
  const fullChunks = [];
  for await (const chunk of full.stream) fullChunks.push(chunk);
  assert(Buffer.concat(fullChunks).equals(content), 'full stream bytes match');

  const partial = await streamFile(driveFileId, 'bytes=0-4');
  assert(partial.status === 206, 'range stream status is 206');
  const partialChunks = [];
  for await (const chunk of partial.stream) partialChunks.push(chunk);
  assert(Buffer.concat(partialChunks).equals(content.subarray(0, 5)), 'range stream bytes match');

  await deleteFile(driveFileId);
  console.log('PASS: all gdrive checks passed');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
