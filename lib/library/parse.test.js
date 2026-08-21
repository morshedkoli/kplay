// node lib/library/parse.test.js
import assert from 'node:assert/strict';
import { parseFilename } from './parse.js';

// Movie: "Title (Year).ext"
{
  const r = parseFilename('The Matrix (1999).mkv');
  assert.equal(r.type, 'movie');
  assert.equal(r.title, 'The Matrix');
  assert.equal(r.year, 1999);
  assert.equal(r.season, null);
  assert.equal(r.episode, null);
  console.log('ok: movie with year parses');
}

// Series: "Title SxxEyy.ext" or "Title - SxxEyy.ext"
{
  const r = parseFilename('Breaking Bad S01E02.mkv');
  assert.equal(r.type, 'series');
  assert.equal(r.title, 'Breaking Bad');
  assert.equal(r.season, 1);
  assert.equal(r.episode, 2);
  console.log('ok: series SxxEyy parses');
}

{
  const r = parseFilename('Breaking Bad - S01E02 - Cats Birthday Party.mkv');
  assert.equal(r.type, 'series');
  assert.equal(r.title, 'Breaking Bad');
  assert.equal(r.season, 1);
  assert.equal(r.episode, 2);
  console.log('ok: series with episode title parses');
}

// No year/season/episode found — fall back to movie with raw title.
{
  const r = parseFilename('some_random_video.mp4');
  assert.equal(r.type, 'movie');
  assert.equal(r.title, 'some random video');
  assert.equal(r.year, null);
  console.log('ok: no-match fallback parses as movie with cleaned title');
}

// Series: the "1x04" convention.
{
  const r = parseFilename('Show.Name.1x04.mkv');
  assert.equal(r.type, 'series');
  assert.equal(r.title, 'Show Name');
  assert.equal(r.season, 1);
  assert.equal(r.episode, 4);
  console.log('ok: series 1x04 parses');
}

// Series: a separator between the season and episode halves.
{
  const r = parseFilename('Foundation.S01.E05.mkv');
  assert.equal(r.type, 'series');
  assert.equal(r.title, 'Foundation');
  assert.equal(r.season, 1);
  assert.equal(r.episode, 5);
  console.log('ok: series S01.E05 parses');
}

// A release year on a series title is noise; TMDb /search/tv matches without it.
{
  const r = parseFilename('Dark (2017) S01E01.mkv');
  assert.equal(r.type, 'series');
  assert.equal(r.title, 'Dark');
  assert.equal(r.season, 1);
  assert.equal(r.episode, 1);
  console.log('ok: series trailing year stripped from title');
}

// ...but stripping must not empty out a show that is named for a year.
{
  const r = parseFilename('1984 S01E01.mkv');
  assert.equal(r.type, 'series');
  assert.equal(r.title, '1984');
  console.log('ok: year-only series title survives stripping');
}

// A resolution suffix is not an episode number.
{
  const r = parseFilename('Movie.2160p.mkv');
  assert.equal(r.type, 'movie');
  console.log('ok: resolution suffix does not parse as series');
}

console.log('PASS: all parse checks passed');
