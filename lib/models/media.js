// Media library schema helpers — `media` (movies + series) and `episode`
// (series episodes) collections. Drive holds file bytes; these collections
// hold everything else (see docs/superpowers/specs/
// 2026-08-20-media-server-backend-design.md).
//
// A leaf doc carries exactly one address for its bytes, chosen by `source`:
// 'gdrive' -> driveFileId, 'dhakaflix' -> sourceUrl (a full http:// URL on the
// ISP index). A series parent has neither — only its episodes hold files.
//
// media doc shape:
//   { _id, type: 'movie'|'series', title, year, source: MediaSource,
//     driveFileId | sourceUrl (movie only), tmdbId, posterPath, description,
//     status: MediaStatus, filename, size, createdAt }
//
// episode doc shape:
//   { _id, mediaId, season, episode, title, source: MediaSource,
//     driveFileId | sourceUrl, size, createdAt }

import { getCollection } from '../db.js';

export const MediaSource = {
  GDRIVE: 'gdrive',
  DHAKAFLIX: 'dhakaflix',
};

export const MediaStatus = {
  PROCESSING: 'processing',
  MATCHED: 'matched',
  UNMATCHED: 'unmatched',
};

export function mediaCollection() {
  return getCollection('media');
}

export function episodeCollection() {
  return getCollection('episode');
}
