// Orchestrates: parsed filename -> TMDb lookup -> media/episode doc in Mongo.
// Runs after an upload completes. A TMDb miss never fails the upload — the
// item is stored with status: 'unmatched' and the raw filename as title
// (see docs/superpowers/specs/2026-08-20-media-server-backend-design.md).
//
// Dependencies are injectable (see lib/library/match.test.js) so this can be
// unit-tested without Mongo or network; production callers omit `deps` and
// get the real collections/TMDb client.

import { parseFilename } from './parse.js';
import * as tmdbReal from './tmdb.js';
import { MediaStatus, episodeCollection as episodeCollectionReal, mediaCollection as mediaCollectionReal } from '../models/media.js';

const LOG = '[library/match]';

// `source` says which backend holds the bytes: 'gdrive' addresses them by
// driveFileId, 'dhakaflix' by sourceUrl. Whichever one is absent is left off
// the doc entirely rather than stored as null — both are unique sparse indexes,
// and a sparse index still indexes an explicit null, so a second null-keyed doc
// would collide.
export async function matchAndStore(
  { filename, driveFileId = null, sourceUrl = null, source = 'gdrive', size },
  deps = {}
) {
  const {
    mediaCollection = mediaCollectionReal,
    episodeCollection = episodeCollectionReal,
    searchMovie = tmdbReal.searchMovie,
    searchSeries = tmdbReal.searchSeries,
    getSeriesEpisode = tmdbReal.getSeriesEpisode,
  } = deps;

  const parsed = parseFilename(filename);
  const media = await mediaCollection();

  if (parsed.type === 'movie') {
    const hit = await searchMovie(parsed.title, parsed.year).catch(() => null);
    const status = hit ? MediaStatus.MATCHED : MediaStatus.UNMATCHED;
    const { insertedId } = await media.insertOne({
      type: 'movie',
      title: hit?.title || parsed.title,
      year: parsed.year,
      source,
      ...(driveFileId ? { driveFileId } : {}),
      ...(sourceUrl ? { sourceUrl } : {}),
      size,
      filename,
      tmdbId: hit?.tmdbId ?? null,
      posterPath: hit?.posterPath ?? null,
      backdropPath: hit?.backdropPath ?? null,
      description: hit?.description ?? '',
      status,
      createdAt: new Date(),
    });
    console.log(`${LOG} movie "${filename}" -> ${status}`);
    return { mediaId: insertedId, status, type: 'movie' };
  }

  // Series: find-or-create the parent media doc, then add an episode. Prefer
  // matching on tmdbId when we have a hit, so differently-parsed spellings of
  // the same show still converge on one media doc; fall back to a title
  // lookup only when unmatched.
  const hit = await searchSeries(parsed.title).catch(() => null);
  const status = hit ? MediaStatus.MATCHED : MediaStatus.UNMATCHED;
  const seriesTitle = hit?.title || parsed.title;

  // A previously-unmatched parent has no tmdbId yet, so also fall back to a
  // title match — otherwise a title-parsed-the-same episode that now gets a
  // TMDb hit would create a duplicate parent instead of upgrading it.
  const existing = hit
    ? (await media.findOne({ type: 'series', tmdbId: hit.tmdbId })) ||
      (await media.findOne({ type: 'series', title: seriesTitle }))
    : await media.findOne({ type: 'series', title: seriesTitle });
  let mediaId;
  if (existing) {
    mediaId = existing._id;
    // Upgrade a previously-unmatched parent (created before TMDb was
    // reachable, or before any episode matched) now that we have a hit.
    if (hit && existing.status === MediaStatus.UNMATCHED) {
      await media.updateOne(
        { _id: existing._id },
        {
          $set: {
            posterPath: hit.posterPath ?? null,
            backdropPath: hit.backdropPath ?? null,
            description: hit.description ?? '',
            tmdbId: hit.tmdbId,
            status: MediaStatus.MATCHED,
          },
        }
      );
    }
  } else {
    ({ insertedId: mediaId } = await media.insertOne({
      type: 'series',
      title: seriesTitle,
      year: null,
      tmdbId: hit?.tmdbId ?? null,
      posterPath: hit?.posterPath ?? null,
      backdropPath: hit?.backdropPath ?? null,
      description: hit?.description ?? '',
      status,
      createdAt: new Date(),
    }));
  }

  let episodeMeta = { title: '', description: '' };
  if (hit) {
    episodeMeta = (await getSeriesEpisode(hit.tmdbId, parsed.season, parsed.episode).catch(() => null)) || episodeMeta;
  }

  const episodes = await episodeCollection();
  await episodes.insertOne({
    mediaId,
    season: parsed.season,
    episode: parsed.episode,
    title: episodeMeta.title,
    description: episodeMeta.description,
    source,
    ...(driveFileId ? { driveFileId } : {}),
    ...(sourceUrl ? { sourceUrl } : {}),
    size,
    createdAt: new Date(),
  });

  console.log(`${LOG} series "${filename}" S${parsed.season}E${parsed.episode} -> ${status}`);
  return { mediaId, status, type: 'series' };
}

/**
 * Retries every movie still sitting at status 'unmatched' by re-running its
 * stored filename through the current parser. Scan alone can't do this: it
 * skips any driveFileId already in the library, so an item that missed once
 * would stay unmatched forever even after a parser fix.
 *
 * Series parents already self-upgrade in matchAndStore when a later episode
 * matches, so only movies need this.
 */
export async function rematchUnmatchedMovies(deps = {}) {
  const {
    mediaCollection = mediaCollectionReal,
    searchMovie = tmdbReal.searchMovie,
  } = deps;

  const media = await mediaCollection();
  const stale = await media
    .find({ type: 'movie', status: MediaStatus.UNMATCHED, filename: { $exists: true } })
    .toArray();

  const rematched = [];
  // Sequential for the same reason the scan loop is: TMDb rate-limits.
  for (const doc of stale) {
    const parsed = parseFilename(doc.filename);
    const hit = await searchMovie(parsed.title, parsed.year).catch(() => null);
    if (!hit) continue;
    await media.updateOne(
      { _id: doc._id },
      {
        $set: {
          title: hit.title,
          year: parsed.year,
          tmdbId: hit.tmdbId,
          posterPath: hit.posterPath ?? null,
          backdropPath: hit.backdropPath ?? null,
          description: hit.description ?? '',
          status: MediaStatus.MATCHED,
        },
      }
    );
    rematched.push({ filename: doc.filename, title: hit.title });
    console.log(`${LOG} rematched "${doc.filename}" -> ${hit.title}`);
  }

  return rematched;
}
