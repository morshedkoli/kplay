// lib/drive-url.js — the two forms of a file's address, in one place.
//
// The player asks for the local path; the Service Worker turns it into the
// Google one. Keeping both here means the worker and the pages cannot drift
// into disagreeing about the shape of either.

/** Where Drive actually serves the bytes. */
export function driveBytesUrl(driveFileId) {
  return `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(driveFileId)}?alt=media`;
}

/** The same file as a same-origin path, which is all a <video> element can use. */
export function localDrivePath(driveFileId) {
  return `/drive/${encodeURIComponent(driveFileId)}`;
}
