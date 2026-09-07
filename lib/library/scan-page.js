// lib/library/scan-page.js — splits a scan into runs a function can finish.
//
// Matching is sequential by necessity: TMDb rate-limits, and two episodes of
// one series matched at once race to create duplicate parent documents. A
// folder can therefore grow past what a single invocation has time for, and
// when it does the scan should take more calls rather than fail on the last
// file. The panel drives the walk, calling again while a cursor comes back.

// Small enough that a page finishes well inside a function's time limit even
// when every file needs a TMDb round trip, large enough that a modest library
// imports in one call.
export const PAGE_SIZE = 25;

/**
 * The next run of pending files, and where to resume after it.
 *
 * `cursor` is the driveFileId of the last file of the previous page. A cursor
 * naming a file no longer pending restarts from the beginning: imported files
 * drop out of the pending list between calls, so a cursor pointing at one is
 * ordinary rather than exceptional, and stranding the remainder would be worse
 * than repeating the diff.
 */
export function takePage(pending, cursor = null, pageSize = PAGE_SIZE) {
  const at = cursor ? pending.findIndex((file) => file.driveFileId === cursor) : -1;
  const start = at === -1 ? 0 : at + 1;

  const page = pending.slice(start, start + pageSize);
  const done = start + page.length >= pending.length;

  return {
    page,
    nextCursor: done || page.length === 0 ? null : page[page.length - 1].driveFileId,
  };
}
