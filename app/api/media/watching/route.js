// GET /api/media/watching — everything part-watched, most recent first.
//
// The `progress` collection is keyed by whatever was playing: a media _id for
// a movie, an episode _id for an episode. That key alone is not enough to
// render a row, so this route resolves each one back to the thing it belongs
// to — an episode's artwork and blurb live on its parent series, never on the
// episode itself.
//
// Single-user app, so no per-user scoping (see spec).

import { requireDeviceOrSession } from '@/lib/auth.js';
import { getCollection } from '@/lib/db.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

/**
 * Below this, the viewer has not really started. Opening a title, seeing the
 * first frames and backing out should not put it in a "keep watching" list —
 * that list is only useful if everything in it is worth returning to.
 */
const MIN_POSITION_SECONDS = 30;

/**
 * At or above this fraction, treat it as finished and drop it. Credits, a
 * post-credits scene and the seconds between "done" and the player closing
 * all land in the last few percent, so anything past it is over.
 *
 * Entries with no stored duration (written before the player began sending
 * one) can never be judged this way, so they are always kept — a stale row
 * beats a silently missing one.
 */
const FINISHED_FRACTION = 0.95;

/** Enough rows to fill a TV carousel several screens deep; past that nobody
 * is scrolling, and the query stays bounded. */
const LIMIT = 40;

export async function GET(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const progress = await getCollection('progress');
  const docs = await progress
    .find({ positionSeconds: { $gte: MIN_POSITION_SECONDS } })
    .sort({ updatedAt: -1 })
    // Slack, because the finished ones are filtered out below and the rows
    // whose media has since been deleted drop out after that.
    .limit(LIMIT * 3)
    .toArray();

  const unfinished = docs.filter(
    (d) => !(d.durationSeconds > 0) || d.positionSeconds / d.durationSeconds < FINISHED_FRACTION
  );
  if (!unfinished.length) return Response.json({ items: [] });

  const ids = unfinished.map((d) => d.itemId);
  const media = await mediaCollection();
  const episodes = await episodeCollection();

  // Two lookups rather than one per row: a key is either a movie or an
  // episode, and which one is not known until it is found.
  const [movieDocs, episodeDocs] = await Promise.all([
    media.find({ _id: { $in: ids }, type: 'movie' }).toArray(),
    episodes.find({ _id: { $in: ids } }).toArray(),
  ]);

  // Episodes carry no artwork of their own, so their series is fetched too.
  const parents = episodeDocs.length
    ? await media.find({ _id: { $in: episodeDocs.map((e) => e.mediaId) } }).toArray()
    : [];

  const movieById = new Map(movieDocs.map((m) => [String(m._id), m]));
  const episodeById = new Map(episodeDocs.map((e) => [String(e._id), e]));
  const parentById = new Map(parents.map((p) => [String(p._id), p]));

  const items = [];
  for (const doc of unfinished) {
    if (items.length >= LIMIT) break;

    const key = String(doc.itemId);
    const base = {
      id: key,
      positionSeconds: doc.positionSeconds,
      durationSeconds: doc.durationSeconds ?? null,
      updatedAt: doc.updatedAt ?? null,
    };

    const movie = movieById.get(key);
    if (movie) {
      items.push({
        ...base,
        type: 'movie',
        mediaId: key,
        title: movie.title,
        description: movie.description ?? null,
        year: movie.year ?? null,
        posterPath: movie.posterPath ?? null,
        backdropPath: movie.backdropPath ?? null,
      });
      continue;
    }

    // Not a movie and not an episode means the library item was deleted while
    // its progress row outlived it. Skipping keeps a dead card off the shelf.
    const episode = episodeById.get(key);
    if (!episode) continue;
    const parent = parentById.get(String(episode.mediaId));
    if (!parent) continue;

    items.push({
      ...base,
      type: 'episode',
      mediaId: String(episode.mediaId),
      title: parent.title,
      description: episode.description ?? parent.description ?? null,
      year: parent.year ?? null,
      posterPath: parent.posterPath ?? null,
      backdropPath: parent.backdropPath ?? null,
      season: episode.season ?? 0,
      episode: episode.episode ?? 0,
      episodeTitle: episode.title ?? null,
    });
  }

  return Response.json({ items });
}
