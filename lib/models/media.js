// Media library schema helpers — `media` (movies + series) and `episode`
// (series episodes) collections. Drive holds file bytes; these collections
// hold everything else (see docs/superpowers/specs/
// 2026-08-20-media-server-backend-design.md).
//
// media doc shape:
//   { _id, type: 'movie'|'series', title, year, driveFileId (movie only),
//     tmdbId, posterPath, description, status: MediaStatus, filename,
//     size, createdAt }
//
// episode doc shape:
//   { _id, mediaId, season, episode, title, driveFileId, size, createdAt }

import { getCollection } from '../db.js';

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
