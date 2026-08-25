// What counts as a video, and what to call it over HTTP.
//
// Google Drive's own mimeType is not trustworthy for this library. A file
// uploaded by rclone, by a browser on a machine with no registry entry for
// the extension, or by anything that did not bother to guess arrives as
// `application/octet-stream` — and .mkv, .m4v, .ts and .avi land that way
// constantly.
//
// Two things broke because of that:
//
//   * the scan skipped those files outright, since it only imported
//     `video/*`, so the title never appeared in the library at all; and
//   * the stream route echoed the type straight back, and a browser <video>
//     refuses to play `application/octet-stream` — the element fires an
//     error and shows nothing, which is exactly the "some videos don't play"
//     report. ExoPlayer sniffs the bytes and usually survives it, but it too
//     picks the wrong extractor for some containers when the type lies.
//
// So the extension decides, and Drive's type is only used when it is already
// a specific video type.

const BY_EXTENSION = {
  mp4: 'video/mp4',
  m4v: 'video/x-m4v',
  mkv: 'video/x-matroska',
  webm: 'video/webm',
  mov: 'video/quicktime',
  avi: 'video/x-msvideo',
  wmv: 'video/x-ms-wmv',
  flv: 'video/x-flv',
  ts: 'video/mp2t',
  m2ts: 'video/mp2t',
  mts: 'video/mp2t',
  mpg: 'video/mpeg',
  mpeg: 'video/mpeg',
  ogv: 'video/ogg',
  '3gp': 'video/3gpp',
};

/** Lowercased extension without the dot, or '' when there isn't one. */
function extensionOf(name = '') {
  const match = /\.([a-z0-9]+)$/i.exec(name);
  return match ? match[1].toLowerCase() : '';
}

/** True when this filename is one of the containers the library handles. */
export function isVideoFilename(name) {
  return Boolean(BY_EXTENSION[extensionOf(name)]);
}

/**
 * A video file is one Drive already calls `video/*`, or one whose extension
 * says so — see the note at the top for why the second half is needed.
 */
export function isVideoFile({ name, mimeType } = {}) {
  return Boolean(mimeType?.startsWith('video/')) || isVideoFilename(name);
}

/**
 * The Content-Type to serve a file's bytes with.
 *
 * A specific type from Drive wins; anything generic is replaced by what the
 * extension implies. Falls back to `video/mp4` for a file the library holds
 * but this table does not know, because a plausible video type gets further
 * with every player than octet-stream does.
 */
export function videoContentType(name, driveMimeType) {
  if (driveMimeType && driveMimeType.startsWith('video/')) return driveMimeType;
  return BY_EXTENSION[extensionOf(name)] || 'video/mp4';
}
