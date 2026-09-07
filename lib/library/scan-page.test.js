// node lib/library/scan-page.test.js
import assert from 'node:assert/strict';
import { takePage } from './scan-page.js';

const files = [
  { driveFileId: 'a', name: 'A.mp4' },
  { driveFileId: 'b', name: 'B.mp4' },
  { driveFileId: 'c', name: 'C.mp4' },
  { driveFileId: 'd', name: 'D.mp4' },
];

// A first call with no cursor starts at the beginning and reports where to
// resume.
{
  const { page, nextCursor } = takePage(files, null, 2);
  assert.deepEqual(page.map((f) => f.driveFileId), ['a', 'b']);
  assert.equal(nextCursor, 'b');
  console.log('ok: the first page starts at the beginning');
}

// Resuming picks up after the cursor, never repeating it.
{
  const { page, nextCursor } = takePage(files, 'b', 2);
  assert.deepEqual(page.map((f) => f.driveFileId), ['c', 'd']);
  assert.equal(nextCursor, null);
  console.log('ok: resuming continues after the cursor');
}

// The last page reports no next cursor, which is how the caller stops.
{
  const { page, nextCursor } = takePage(files, 'c', 5);
  assert.deepEqual(page.map((f) => f.driveFileId), ['d']);
  assert.equal(nextCursor, null);
  console.log('ok: the final page ends the walk');
}

// A cursor naming a file that is gone restarts rather than silently importing
// nothing: files leave the pending list as they are imported, and a stale
// cursor must not strand the remainder.
{
  const { page, nextCursor } = takePage(files, 'zzz', 2);
  assert.deepEqual(page.map((f) => f.driveFileId), ['a', 'b']);
  assert.equal(nextCursor, 'b');
  console.log('ok: an unknown cursor restarts the walk');
}

// An empty pending list is finished, not paged.
{
  const { page, nextCursor } = takePage([], null, 2);
  assert.deepEqual(page, []);
  assert.equal(nextCursor, null);
  console.log('ok: nothing pending means nothing to resume');
}
