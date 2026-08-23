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

// Bare-year release naming — the common case, and the one that used to send
// the entire filename to TMDb as the title.
{
  const r = parseFilename('Inception.2010.1080p.BluRay.x264-GROUP.mkv');
  assert.equal(r.type, 'movie');
  assert.equal(r.title, 'Inception');
  assert.equal(r.year, 2010);
  console.log('ok: bare-year movie with release tags parses');
}

{
  const r = parseFilename('The Matrix 1999 REMASTERED 2160p UHD.mkv');
  assert.equal(r.title, 'The Matrix');
  assert.equal(r.year, 1999);
  console.log('ok: space-separated bare-year movie parses');
}

{
  const r = parseFilename('Sicario.2015.mkv');
  assert.equal(r.title, 'Sicario');
  assert.equal(r.year, 2015);
  console.log('ok: bare-year movie without tags parses');
}

// Release tags trail a parenthesised year too.
{
  const r = parseFilename('Dune Part Two (2024) 1080p WEB-DL.mkv');
  assert.equal(r.title, 'Dune Part Two');
  assert.equal(r.year, 2024);
  console.log('ok: parenthesised year keeps title clean');
}

// A future four-digit number is title text, not a release year.
{
  const r = parseFilename('Blade Runner 2049.mkv');
  assert.equal(r.title, 'Blade Runner 2049');
  assert.equal(r.year, null);
  console.log('ok: future year stays part of the title');
}

// No year at all — release tags still get dropped from the TMDb query.
{
  const r = parseFilename('Arrival.1080p.WEBRip.x264.mkv');
  assert.equal(r.title, 'Arrival');
  assert.equal(r.year, null);
  console.log('ok: release tags stripped when no year present');
}

// A resolution must not be read as a year.
{
  const r = parseFilename('Movie.2160p.mkv');
  assert.equal(r.title, 'Movie');
  assert.equal(r.year, null);
  console.log('ok: resolution is not parsed as a year');
}

// A title that ends in a year of its own — the release year is the last one.
{
  const r = parseFilename('Dial.1975.2026.1080p.Hindi.DS4K.WEB-DL.2.0.x264-HDHub4u.Ms.mkv');
  assert.equal(r.title, 'Dial 1975');
  assert.equal(r.year, 2026);
  console.log('ok: last plausible year wins over a year in the title');
}

// Unknown tags between known ones still get cut, because the cut starts at the
// first known tag and runs to the end.
{
  const r = parseFilename('Welcome.to.the.Jungle.2026.1080p.JHS.WEB-DL.Hindi.DDP5.1.H.264-HDHub4u.Ms.mkv');
  assert.equal(r.title, 'Welcome to the Jungle');
  assert.equal(r.year, 2026);
  console.log('ok: unknown release tags after a known one are dropped');
}

// A title that is only a year must survive.
{
  const r = parseFilename('1917.2019.1080p.mkv');
  assert.equal(r.title, '1917');
  assert.equal(r.year, 2019);
  console.log('ok: year-only movie title survives');
}

console.log('PASS: all parse checks passed');
