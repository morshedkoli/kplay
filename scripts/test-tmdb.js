// Matches a known filename against the real TMDb API.
// Run: node scripts/test-tmdb.js
// Requires TMDB_API_KEY in .env.local.

import { loadEnv } from '../lib/env.js';
loadEnv();

import { searchMovie, searchSeries } from '../lib/library/tmdb.js';

function assert(cond, msg) {
  if (!cond) throw new Error(`FAIL: ${msg}`);
  console.log(`  ok: ${msg}`);
}

async function main() {
  const movie = await searchMovie('The Matrix', 1999);
  assert(movie, 'movie search returns a result');
  assert(movie.posterPath, 'movie result has a poster path');
  assert(movie.description.length > 0, 'movie result has a description');

  const series = await searchSeries('Breaking Bad');
  assert(series, 'series search returns a result');
  assert(series.posterPath, 'series result has a poster path');

  console.log('PASS: all tmdb checks passed');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
