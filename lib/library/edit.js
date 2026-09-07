// lib/library/edit.js — what a person is allowed to change by hand.
//
// Filename parsing and TMDb matching get things wrong, and some files have no
// TMDb entry at all, so the panel can correct a document directly. Validation
// lives here rather than in the route because it has rules worth testing and
// the route has none.

import { MediaStatus } from '../models/media.js';

const EDITABLE = new Set(['title', 'year', 'tmdbId', 'posterPath', 'description', 'type']);
const TYPES = new Set(['movie', 'series']);

// Film has not been around longer than this, and a year beyond the near future
// is a typo rather than an upcoming release.
const EARLIEST_YEAR = 1888;

function yearIsPlausible(value) {
  return (
    Number.isInteger(value) && value >= EARLIEST_YEAR && value <= new Date().getFullYear() + 5
  );
}

/**
 * Checks a manual edit and returns the `$set` document to apply.
 *
 * `episodeCount` is how many episode documents name this title as their
 * parent; it is what makes a type change safe or unsafe.
 */
export function validateMediaPatch(patch, current, episodeCount) {
  const keys = Object.keys(patch ?? {});
  if (keys.length === 0) return { ok: false, error: 'Nothing to change' };

  const unknown = keys.filter((key) => !EDITABLE.has(key));
  if (unknown.length > 0) {
    return { ok: false, error: `Cannot edit: ${unknown.join(', ')}` };
  }

  const update = {};

  if ('title' in patch) {
    const title = typeof patch.title === 'string' ? patch.title.trim() : '';
    if (!title) return { ok: false, error: 'Title cannot be empty' };
    update.title = title;
  }

  if ('year' in patch) {
    if (patch.year !== null && !yearIsPlausible(patch.year)) {
      return { ok: false, error: 'Year must be a four-digit year' };
    }
    update.year = patch.year;
  }

  if ('tmdbId' in patch) {
    if (patch.tmdbId !== null && !Number.isInteger(patch.tmdbId)) {
      return { ok: false, error: 'tmdbId must be a whole number' };
    }
    update.tmdbId = patch.tmdbId;
    // Setting an id by hand is the act of matching the title. Leaving the
    // status alone would keep it queued for automatic rematching, which would
    // then overwrite the correction.
    if (patch.tmdbId !== null) update.status = MediaStatus.MATCHED;
  }

  if ('posterPath' in patch) update.posterPath = patch.posterPath;
  if ('description' in patch) update.description = patch.description;

  if ('type' in patch) {
    if (!TYPES.has(patch.type)) return { ok: false, error: 'Type must be movie or series' };
    if (patch.type === 'movie' && current.type === 'series' && episodeCount > 0) {
      return {
        ok: false,
        error: `Cannot become a movie while ${episodeCount} episode(s) point at it`,
      };
    }
    update.type = patch.type;
  }

  return { ok: true, update };
}
