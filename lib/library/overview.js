// Everything the admin home page reports, gathered in one place.
//
// Every lookup here is allowed to fail on its own. A home page whose whole
// point is telling you what is wrong must not itself go blank because Drive
// is unreachable or Mongo is down — so each section returns either its data
// or an `error` string, and the page renders whichever it got.

import { getStorageUsage, isDriveConfigured } from '@/lib/gdrive.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

/** Runs a lookup, returning `{ ok, value }` or `{ ok: false, error }`. */
async function attempt(fn) {
  try {
    return { ok: true, value: await fn() };
  } catch (err) {
    return { ok: false, error: err?.message || 'Failed' };
  }
}

/**
 * Library counts and total bytes.
 *
 * Sizes live on the movie docs and on the episode docs — a series doc holds
 * no bytes of its own — so the total is the sum of both, not of `media`
 * alone.
 */
async function libraryStats() {
  const [media, episodes] = [await mediaCollection(), await episodeCollection()];

  const [byType, episodeCount, mediaBytes, episodeBytes, unmatched] = await Promise.all([
    media.aggregate([{ $group: { _id: '$type', count: { $sum: 1 } } }]).toArray(),
    episodes.countDocuments(),
    media.aggregate([{ $group: { _id: null, bytes: { $sum: '$size' } } }]).toArray(),
    episodes.aggregate([{ $group: { _id: null, bytes: { $sum: '$size' } } }]).toArray(),
    media.countDocuments({ status: 'unmatched' }),
  ]);

  const counts = new Map(byType.map((row) => [row._id, row.count]));
  return {
    movies: counts.get('movie') ?? 0,
    series: counts.get('series') ?? 0,
    episodes: episodeCount,
    bytes: (mediaBytes[0]?.bytes ?? 0) + (episodeBytes[0]?.bytes ?? 0),
    unmatched,
  };
}

/** The handful of titles most recently imported, for a "what changed" list. */
async function recentTitles(limit = 6) {
  const media = await mediaCollection();
  return media
    .find(
      {},
      {
        projection: { _id: 1, type: 1, title: 1, year: 1, status: 1, createdAt: 1 },
      }
    )
    .sort({ createdAt: -1 })
    .limit(limit)
    .toArray()
    .then((docs) =>
      docs.map((doc) => ({
        id: String(doc._id),
        type: doc.type || 'movie',
        title: doc.title,
        year: doc.year ?? null,
        status: doc.status,
        createdAt: doc.createdAt ? new Date(doc.createdAt).toISOString() : null,
      }))
    );
}

/**
 * Which pieces of configuration are present.
 *
 * Presence only — never the values. This page is reachable from the internet
 * with one password in front of it, and a screen that prints secrets is one
 * shoulder away from giving them away.
 */
function configuration() {
  return [
    {
      key: 'drive',
      label: 'Google Drive',
      ok: isDriveConfigured(),
      detail: isDriveConfigured()
        ? process.env.GOOGLE_DRIVE_FOLDER_ID
          ? 'Connected, scoped to one folder'
          : 'Connected, scanning the whole Drive'
        : 'Set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET and GOOGLE_REFRESH_TOKEN',
    },
    {
      key: 'mongo',
      label: 'MongoDB',
      ok: Boolean(process.env.MONGODB_URI),
      detail: process.env.MONGODB_URI
        ? `Database "${process.env.MONGODB_DB_NAME || 'kdrive'}"`
        : 'Set MONGODB_URI',
    },
    {
      key: 'tmdb',
      label: 'TMDb matching',
      ok: Boolean(process.env.TMDB_API_KEY),
      detail: process.env.TMDB_API_KEY
        ? 'Titles, posters and episode names fill in automatically'
        : 'Set TMDB_API_KEY — without it everything imports unmatched',
    },
    {
      key: 'device',
      label: 'TV device key',
      ok: Boolean(process.env.KDRIVE_DEVICE_KEY),
      detail: process.env.KDRIVE_DEVICE_KEY
        ? 'The Android TV app can sign in'
        : 'Set KDRIVE_DEVICE_KEY to let the Android TV app connect',
    },
    {
      key: 'chunk',
      label: 'Stream chunking',
      ok: true,
      detail: Number(process.env.STREAM_MAX_CHUNK_BYTES || 0)
        ? `Capped at ${process.env.STREAM_MAX_CHUNK_BYTES} bytes per response`
        : 'Uncapped — one connection per file, which is what a VPS wants',
    },
  ];
}

/** Everything the signed-in home page shows, with per-section failures kept
 * local to the section that failed. */
export async function getOverview() {
  const [stats, usage, recent] = await Promise.all([
    attempt(libraryStats),
    attempt(getStorageUsage),
    attempt(() => recentTitles()),
  ]);

  return {
    stats: stats.ok ? stats.value : null,
    statsError: stats.ok ? null : stats.error,
    usage: usage.ok ? usage.value : null,
    usageError: usage.ok ? null : usage.error,
    recent: recent.ok ? recent.value : [],
    configuration: configuration(),
  };
}
