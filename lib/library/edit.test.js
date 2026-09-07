// node lib/library/edit.test.js
import assert from 'node:assert/strict';
import { validateMediaPatch } from './edit.js';

const movie = { type: 'movie', title: 'The Matrix', year: 1999, status: 'matched' };
const series = { type: 'series', title: 'Breaking Bad', year: 2008, status: 'matched' };

// Correcting a title is the ordinary case.
{
  const result = validateMediaPatch({ title: 'The Matrix Reloaded' }, movie, 0);
  assert.equal(result.ok, true);
  assert.equal(result.update.title, 'The Matrix Reloaded');
  console.log('ok: a title correction is accepted');
}

// Setting a tmdbId by hand marks the title matched, because that is what the
// field means and leaving it unmatched would keep it in the rematch queue.
{
  const result = validateMediaPatch({ tmdbId: 603 }, { ...movie, status: 'unmatched' }, 0);
  assert.equal(result.ok, true);
  assert.equal(result.update.tmdbId, 603);
  assert.equal(result.update.status, 'matched');
  console.log('ok: a manual tmdbId marks the title matched');
}

// An unknown field is refused rather than written through: a typo must not
// silently create a field nothing reads.
{
  const result = validateMediaPatch({ nonsense: 1 }, movie, 0);
  assert.equal(result.ok, false);
  assert.match(result.error, /nonsense/);
  console.log('ok: an unknown field is refused');
}

// A year has to be a plausible year.
{
  assert.equal(validateMediaPatch({ year: 'soon' }, movie, 0).ok, false);
  assert.equal(validateMediaPatch({ year: 1287 }, movie, 0).ok, false);
  assert.equal(validateMediaPatch({ year: 2008 }, movie, 0).ok, true);
  console.log('ok: an implausible year is refused');
}

// Turning a movie into a series is allowed; it is how a mis-parsed file gets
// fixed.
{
  const result = validateMediaPatch({ type: 'series' }, movie, 0);
  assert.equal(result.ok, true);
  assert.equal(result.update.type, 'series');
  console.log('ok: a movie can become a series');
}

// Turning a series with episodes into a movie is refused: the episodes point
// at a parent that would no longer be one, and nothing in the app would ever
// show them again.
{
  const result = validateMediaPatch({ type: 'movie' }, series, 6);
  assert.equal(result.ok, false);
  assert.match(result.error, /episode/i);
  console.log('ok: a series with episodes cannot become a movie');
}

// An empty series can become a movie — there is nothing to orphan.
{
  const result = validateMediaPatch({ type: 'movie' }, series, 0);
  assert.equal(result.ok, true);
  console.log('ok: an empty series can become a movie');
}

// An empty patch changes nothing rather than clearing the document.
{
  const result = validateMediaPatch({}, movie, 0);
  assert.equal(result.ok, false);
  assert.match(result.error, /nothing/i);
  console.log('ok: an empty patch is refused');
}
