# Media Server Backend + Google Drive Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace KDrive's photo-backup domain with a Jellyfin-style movie/TV media server: Google Drive as the sole storage backend, TMDb metadata matching, direct-play streaming, single-user/single-Drive.

**Architecture:** Same one-process Next.js 15 app. `lib/gdrive.js` replaces the rclone pool as the storage backend. `lib/library/` parses uploaded filenames, matches against TMDb, and writes `media`/`episode` docs to MongoDB. New `app/api/media/*` routes handle upload, list, detail, range-request streaming, and watch progress. New `app/(web)` pages replace the photo gallery with a poster-grid library browser and detail/player pages.

**Tech Stack:** Next.js 15, MongoDB (existing `lib/db.js`), Google APIs Node client (`googleapis`), TMDb REST API (plain `fetch`, no SDK needed), existing session auth (`lib/auth.js`, `lib/admin-auth.js`).

**Spec:** `docs/superpowers/specs/2026-08-20-media-server-backend-design.md`

## Global Constraints

- Single Google Drive account, single shared library — no per-user Drive, no per-household isolation (spec §Context).
- Direct-play only — no server-side transcoding (spec §Out of scope).
- MongoDB stays the metadata store; Drive holds only file bytes, addressed by `driveFileId` (spec §Architecture).
- A Drive API error on upload/stream surfaces directly to the client — no fallback backend exists (spec §Error Handling).
- A TMDb match failure never blocks upload success — the item is stored `status: 'unmatched'` (spec §Error Handling).
- Reuse existing auth/session/db plumbing (`lib/auth.js`, `lib/admin-auth.js`, `lib/db.js`, `middleware.js`) rather than rebuilding it.
- Full replacement, not additive — old photo-backup code (`lib/r2.js`, `lib/rclone-rc.js`, `lib/storage-router.js`, `lib/telegram.js`, `rclone/`, `app/api/backup/*`, `app/page.js`+`Gallery.js`, `app/upload/`, `app/storage/`) is removed as part of this plan (spec §Migration Notes).

---

## Task 1: Google Drive client (`lib/gdrive.js`)

**Files:**
- Create: `lib/gdrive.js`
- Modify: `.env.example` — add `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REFRESH_TOKEN`, `GOOGLE_DRIVE_FOLDER_ID`
- Modify: `package.json` — add `googleapis` dependency
- Test: `scripts/test-gdrive.js`

**Interfaces:**
- Produces: `uploadFile(stream, name, mimeType) → Promise<{ driveFileId: string, size: number }>`, `getFileMetadata(driveFileId) → Promise<{ size: number, mimeType: string, name: string }>`, `streamFile(driveFileId, rangeHeader) → Promise<{ stream: Readable, contentRange: string|null, contentLength: number, status: 200|206 }>`, `deleteFile(driveFileId) → Promise<void>`, `isDriveConfigured() → boolean`

- [ ] **Step 1: Install the Google APIs client**

Run: `npm install googleapis`

- [ ] **Step 2: Add Drive env vars to `.env.example`**

Add after the existing storage section:

```
# Google Drive (sole storage backend — see docs/superpowers/specs/2026-08-20-media-server-backend-design.md)
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GOOGLE_REFRESH_TOKEN=
GOOGLE_DRIVE_FOLDER_ID=
```

- [ ] **Step 3: Write `lib/gdrive.js`**

```javascript
// Google Drive storage backend — the sole place media file bytes live.
//
// Auth is a single OAuth2 refresh token tied to one Google account (this is
// a single-user, single-Drive app — see docs/superpowers/specs/
// 2026-08-20-media-server-backend-design.md). No fallback backend exists:
// a Drive API error surfaces directly to the caller.

import { google } from 'googleapis';
import { Readable } from 'node:stream';

const LOG = '[gdrive]';

export function isDriveConfigured() {
  return Boolean(
    process.env.GOOGLE_CLIENT_ID &&
      process.env.GOOGLE_CLIENT_SECRET &&
      process.env.GOOGLE_REFRESH_TOKEN
  );
}

let driveClient = null;

function getDrive() {
  if (driveClient) return driveClient;

  if (!isDriveConfigured()) {
    throw new Error(
      'Google Drive is not configured — set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REFRESH_TOKEN'
    );
  }

  const auth = new google.auth.OAuth2(
    process.env.GOOGLE_CLIENT_ID,
    process.env.GOOGLE_CLIENT_SECRET
  );
  auth.setCredentials({ refresh_token: process.env.GOOGLE_REFRESH_TOKEN });

  driveClient = google.drive({ version: 'v3', auth });
  return driveClient;
}

/** Uploads a stream as a new Drive file. Returns { driveFileId, size }. */
export async function uploadFile(stream, name, mimeType) {
  const drive = getDrive();
  const parentId = process.env.GOOGLE_DRIVE_FOLDER_ID;

  const res = await drive.files.create({
    requestBody: {
      name,
      ...(parentId ? { parents: [parentId] } : {}),
    },
    media: { mimeType, body: stream },
    fields: 'id, size',
  });

  const driveFileId = res.data.id;
  const size = Number(res.data.size || 0);
  console.log(`${LOG} uploaded ${name} -> ${driveFileId} (${size} bytes)`);
  return { driveFileId, size };
}

/** Fetches a file's size/mimeType/name without downloading its bytes. */
export async function getFileMetadata(driveFileId) {
  const drive = getDrive();
  const res = await drive.files.get({
    fileId: driveFileId,
    fields: 'size, mimeType, name',
  });
  return {
    size: Number(res.data.size || 0),
    mimeType: res.data.mimeType,
    name: res.data.name,
  };
}

/**
 * Streams a file's bytes, honoring an HTTP Range header if given.
 * Returns { stream, contentRange, contentLength, status }.
 */
export async function streamFile(driveFileId, rangeHeader) {
  const drive = getDrive();
  const meta = await getFileMetadata(driveFileId);

  const headers = {};
  let status = 200;
  let contentRange = null;
  let contentLength = meta.size;

  if (rangeHeader) {
    const match = /^bytes=(\d+)-(\d*)$/.exec(rangeHeader);
    if (!match) {
      const err = new Error('Invalid Range header');
      err.status = 416;
      throw err;
    }
    const start = Number(match[1]);
    const end = match[2] ? Number(match[2]) : meta.size - 1;
    if (start >= meta.size || end >= meta.size || start > end) {
      const err = new Error('Range out of bounds');
      err.status = 416;
      throw err;
    }
    headers.Range = `bytes=${start}-${end}`;
    status = 206;
    contentRange = `bytes ${start}-${end}/${meta.size}`;
    contentLength = end - start + 1;
  }

  const res = await drive.files.get(
    { fileId: driveFileId, alt: 'media' },
    { responseType: 'stream', headers }
  );

  return { stream: res.data, contentRange, contentLength, status };
}

/** Deletes a file. Missing is not an error. */
export async function deleteFile(driveFileId) {
  const drive = getDrive();
  try {
    await drive.files.delete({ fileId: driveFileId });
    console.log(`${LOG} deleted ${driveFileId}`);
  } catch (err) {
    if (err.code === 404) return;
    throw err;
  }
}
```

- [ ] **Step 4: Write the smoke test `scripts/test-gdrive.js`**

```javascript
// Round-trips a throwaway file through the real Google Drive API.
// Run: node scripts/test-gdrive.js
// Requires GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / GOOGLE_REFRESH_TOKEN in .env.local.

import { loadEnv } from '../lib/env.js';
loadEnv();

import { Readable } from 'node:stream';
import { deleteFile, getFileMetadata, isDriveConfigured, streamFile, uploadFile } from '../lib/gdrive.js';

function assert(cond, msg) {
  if (!cond) throw new Error(`FAIL: ${msg}`);
  console.log(`  ok: ${msg}`);
}

async function main() {
  assert(isDriveConfigured(), 'Drive credentials are set');

  const content = Buffer.from('kdrive gdrive smoke test payload');
  const { driveFileId, size } = await uploadFile(
    Readable.from(content),
    `kdrive-test-${Date.now()}.txt`,
    'text/plain'
  );
  assert(driveFileId, 'upload returned a driveFileId');
  assert(size === content.length, `uploaded size matches (${size} === ${content.length})`);

  const meta = await getFileMetadata(driveFileId);
  assert(meta.size === content.length, 'metadata size matches');

  const full = await streamFile(driveFileId, null);
  assert(full.status === 200, 'full stream status is 200');
  const fullChunks = [];
  for await (const chunk of full.stream) fullChunks.push(chunk);
  assert(Buffer.concat(fullChunks).equals(content), 'full stream bytes match');

  const partial = await streamFile(driveFileId, 'bytes=0-4');
  assert(partial.status === 206, 'range stream status is 206');
  const partialChunks = [];
  for await (const chunk of partial.stream) partialChunks.push(chunk);
  assert(Buffer.concat(partialChunks).equals(content.subarray(0, 5)), 'range stream bytes match');

  await deleteFile(driveFileId);
  console.log('PASS: all gdrive checks passed');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
```

- [ ] **Step 5: Run the test**

Run: `node scripts/test-gdrive.js`
Expected: `PASS: all gdrive checks passed` (requires real Drive credentials in `.env.local` — if not yet available, note this in the task handoff rather than skipping silently).

- [ ] **Step 6: Commit**

```bash
git add lib/gdrive.js scripts/test-gdrive.js .env.example package.json package-lock.json
git commit -m "feat: add Google Drive storage backend"
```

---

## Task 2: Mongo schema + indexes for `media`/`episode`

**Files:**
- Create: `lib/models/media.js`
- Modify: `lib/migrations/001-indexes.js` — add `media`/`episode` indexes
- Test: `scripts/test-db.js` — extend with new collection checks

**Interfaces:**
- Consumes: `getCollection(name)` from `lib/db.js` (existing)
- Produces: `MediaStatus = { PROCESSING: 'processing', MATCHED: 'matched', UNMATCHED: 'unmatched' }`, `mediaCollection() → Promise<Collection>`, `episodeCollection() → Promise<Collection>`

- [ ] **Step 1: Read the existing migration file to match its pattern**

Run: `cat lib/migrations/001-indexes.js` (or open in editor) — confirm the export shape (a `runMigration()` function called from `npm run migrate`) before editing.

- [ ] **Step 2: Write `lib/models/media.js`**

```javascript
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
```

- [ ] **Step 3: Add indexes to `lib/migrations/001-indexes.js`**

Add inside the existing migration function, following its established
pattern for creating indexes on other collections (mirror the exact
`createIndex` call style already used in that file for e.g. the file
manifest collection):

```javascript
  const media = db.collection('media');
  await media.createIndex({ status: 1 });
  await media.createIndex({ type: 1, title: 1 });
  await media.createIndex({ driveFileId: 1 }, { unique: true, sparse: true });

  const episode = db.collection('episode');
  await episode.createIndex({ mediaId: 1, season: 1, episode: 1 });
  await episode.createIndex({ driveFileId: 1 }, { unique: true, sparse: true });
```

- [ ] **Step 4: Run the migration**

Run: `npm run migrate`
Expected: exits 0, logs the new indexes being created alongside existing ones.

- [ ] **Step 5: Extend `scripts/test-db.js`**

Open the file and add a check block following its existing pattern (insert
a doc, assert it round-trips, clean up) for the `media` collection:

```javascript
  {
    const media = await getCollection('media');
    const doc = { type: 'movie', title: 'Test Movie', status: 'processing', createdAt: new Date() };
    const { insertedId } = await media.insertOne(doc);
    const found = await media.findOne({ _id: insertedId });
    assert(found.title === 'Test Movie', 'media doc round-trips');
    await media.deleteOne({ _id: insertedId });
  }
```

(Match the exact `assert` helper and import style already present in
`scripts/test-db.js` — read the file first and slot this in consistently.)

- [ ] **Step 6: Run the test**

Run: `npm run test:db`
Expected: PASS, including the new media check.

- [ ] **Step 7: Commit**

```bash
git add lib/models/media.js lib/migrations/001-indexes.js scripts/test-db.js
git commit -m "feat: add media/episode collections and indexes"
```

---

## Task 3: Filename parser (`lib/library/parse.js`)

**Files:**
- Create: `lib/library/parse.js`
- Test: `lib/library/parse.test.js` (plain Node assertions run via `node`, matching this repo's no-test-framework convention — see `scripts/test-*.js`)

**Interfaces:**
- Produces: `parseFilename(name) → { type: 'movie'|'series', title: string, year: number|null, season: number|null, episode: number|null }`

- [ ] **Step 1: Write the failing test**

```javascript
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

console.log('PASS: all parse checks passed');
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node lib/library/parse.test.js`
Expected: FAIL — `Cannot find module './parse.js'`

- [ ] **Step 3: Write `lib/library/parse.js`**

```javascript
// Parses an uploaded filename into a library-matchable candidate.
// Targets Jellyfin-style naming: "Title (Year).ext" for movies,
// "Title SxxEyy ....ext" (with optional " - " separators) for series.
// A filename that matches neither falls back to a movie with the cleaned
// filename as title and no year — TMDb matching may still succeed or the
// item lands as unmatched (see docs/superpowers/specs/
// 2026-08-20-media-server-backend-design.md, Error Handling).

const SERIES_RE = /^(.*?)[\s.\-_]+S(\d{1,2})E(\d{1,2})\b/i;
const MOVIE_YEAR_RE = /^(.*?)[\s.\-_]*\((\d{4})\)/;

function clean(title) {
  return title.replace(/[._]+/g, ' ').replace(/\s+/g, ' ').replace(/[\s\-]+$/, '').trim();
}

function stripExt(name) {
  return name.replace(/\.[a-zA-Z0-9]+$/, '');
}

export function parseFilename(name) {
  const base = stripExt(name);

  const seriesMatch = SERIES_RE.exec(base);
  if (seriesMatch) {
    return {
      type: 'series',
      title: clean(seriesMatch[1]),
      year: null,
      season: Number(seriesMatch[2]),
      episode: Number(seriesMatch[3]),
    };
  }

  const movieMatch = MOVIE_YEAR_RE.exec(base);
  if (movieMatch) {
    return {
      type: 'movie',
      title: clean(movieMatch[1]),
      year: Number(movieMatch[2]),
      season: null,
      episode: null,
    };
  }

  return { type: 'movie', title: clean(base), year: null, season: null, episode: null };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node lib/library/parse.test.js`
Expected: `PASS: all parse checks passed`

- [ ] **Step 5: Commit**

```bash
git add lib/library/parse.js lib/library/parse.test.js
git commit -m "feat: add media filename parser"
```

---

## Task 4: TMDb client (`lib/library/tmdb.js`)

**Files:**
- Create: `lib/library/tmdb.js`
- Modify: `.env.example` — add `TMDB_API_KEY`
- Test: `scripts/test-tmdb.js`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces: `searchMovie(title, year) → Promise<{ tmdbId, title, posterPath, description }|null>`, `searchSeries(title) → Promise<{ tmdbId, title, posterPath, description }|null>`, `getSeriesEpisode(tmdbId, season, episode) → Promise<{ title, description }|null>`

- [ ] **Step 1: Add `TMDB_API_KEY` to `.env.example`**

```
# TMDb metadata (https://www.themoviedb.org/settings/api)
TMDB_API_KEY=
```

- [ ] **Step 2: Write `lib/library/tmdb.js`**

```javascript
// Thin TMDb v3 REST client — plain fetch, no SDK. Used by lib/library/match.js
// to fill poster/description/season-episode metadata after an upload. A
// missing result here never blocks upload success — see
// docs/superpowers/specs/2026-08-20-media-server-backend-design.md.

const BASE = 'https://api.themoviedb.org/3';

function apiKey() {
  const key = process.env.TMDB_API_KEY;
  if (!key) throw new Error('TMDB_API_KEY is not set');
  return key;
}

async function get(path, params = {}) {
  const url = new URL(`${BASE}${path}`);
  url.searchParams.set('api_key', apiKey());
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);

  const res = await fetch(url);
  if (!res.ok) throw new Error(`TMDb ${path} -> ${res.status}`);
  return res.json();
}

export async function searchMovie(title, year) {
  const data = await get('/search/movie', { query: title, ...(year ? { year } : {}) });
  const hit = data.results?.[0];
  if (!hit) return null;
  return {
    tmdbId: hit.id,
    title: hit.title,
    posterPath: hit.poster_path,
    description: hit.overview || '',
  };
}

export async function searchSeries(title) {
  const data = await get('/search/tv', { query: title });
  const hit = data.results?.[0];
  if (!hit) return null;
  return {
    tmdbId: hit.id,
    title: hit.name,
    posterPath: hit.poster_path,
    description: hit.overview || '',
  };
}

export async function getSeriesEpisode(tmdbId, season, episode) {
  try {
    const data = await get(`/tv/${tmdbId}/season/${season}/episode/${episode}`);
    return { title: data.name || '', description: data.overview || '' };
  } catch {
    return null;
  }
}
```

- [ ] **Step 3: Write `scripts/test-tmdb.js`**

```javascript
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
```

- [ ] **Step 4: Run the test**

Run: `node scripts/test-tmdb.js`
Expected: `PASS: all tmdb checks passed` (requires a real `TMDB_API_KEY`).

- [ ] **Step 5: Commit**

```bash
git add lib/library/tmdb.js scripts/test-tmdb.js .env.example
git commit -m "feat: add TMDb metadata client"
```

---

## Task 5: Match orchestration (`lib/library/match.js`)

**Files:**
- Create: `lib/library/match.js`
- Test: `lib/library/match.test.js`

**Interfaces:**
- Consumes: `parseFilename(name)` (Task 3), `searchMovie`/`searchSeries`/`getSeriesEpisode` (Task 4), `mediaCollection()`/`episodeCollection()`/`MediaStatus` (Task 2)
- Produces: `matchAndStore({ filename, driveFileId, size }) → Promise<{ mediaId: ObjectId, status: string }>`

- [ ] **Step 1: Write the failing test using fake collections/tmdb (no network, no DB)**

```javascript
// node lib/library/match.test.js
import assert from 'node:assert/strict';
import { matchAndStore } from './match.js';

// Fake collections capturing inserts, and a fake TMDb — matchAndStore takes
// them as injected deps so this test needs neither Mongo nor network.
function fakeDeps({ tmdbResult }) {
  const mediaDocs = [];
  const episodeDocs = [];
  return {
    mediaDocs,
    episodeDocs,
    mediaCollection: async () => ({
      insertOne: async (doc) => {
        const _id = `media-${mediaDocs.length}`;
        mediaDocs.push({ _id, ...doc });
        return { insertedId: _id };
      },
      updateOne: async (filter, update) => {
        const doc = mediaDocs.find((d) => d._id === filter._id);
        Object.assign(doc, update.$set);
      },
    }),
    episodeCollection: async () => ({
      insertOne: async (doc) => {
        episodeDocs.push(doc);
        return { insertedId: `ep-${episodeDocs.length}` };
      },
    }),
    searchMovie: async () => tmdbResult,
    searchSeries: async () => tmdbResult,
    getSeriesEpisode: async () => ({ title: 'Pilot', description: 'first ep' }),
  };
}

async function main() {
  // Matched movie.
  {
    const deps = fakeDeps({ tmdbResult: { tmdbId: 603, title: 'The Matrix', posterPath: '/x.jpg', description: 'desc' } });
    const result = await matchAndStore(
      { filename: 'The Matrix (1999).mkv', driveFileId: 'drive-1', size: 100 },
      deps
    );
    assert.equal(result.status, 'matched');
    assert.equal(deps.mediaDocs[0].posterPath, '/x.jpg');
    console.log('ok: matched movie stores poster/description');
  }

  // Unmatched (TMDb returns null) still stores the item.
  {
    const deps = fakeDeps({ tmdbResult: null });
    const result = await matchAndStore(
      { filename: 'some_random_video.mp4', driveFileId: 'drive-2', size: 50 },
      deps
    );
    assert.equal(result.status, 'unmatched');
    assert.equal(deps.mediaDocs[0].status, 'unmatched');
    console.log('ok: unmatched item still stored, upload not blocked');
  }

  // Series episode creates both a media doc and an episode doc.
  {
    const deps = fakeDeps({ tmdbResult: { tmdbId: 1396, title: 'Breaking Bad', posterPath: '/y.jpg', description: 'd' } });
    const result = await matchAndStore(
      { filename: 'Breaking Bad S01E02.mkv', driveFileId: 'drive-3', size: 200 },
      deps
    );
    assert.equal(result.status, 'matched');
    assert.equal(deps.episodeDocs.length, 1);
    assert.equal(deps.episodeDocs[0].season, 1);
    assert.equal(deps.episodeDocs[0].episode, 2);
    console.log('ok: series upload creates a media doc and an episode doc');
  }

  console.log('PASS: all match checks passed');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node lib/library/match.test.js`
Expected: FAIL — `Cannot find module './match.js'`

- [ ] **Step 3: Write `lib/library/match.js`**

```javascript
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

export async function matchAndStore({ filename, driveFileId, size }, deps = {}) {
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
      driveFileId,
      size,
      filename,
      tmdbId: hit?.tmdbId ?? null,
      posterPath: hit?.posterPath ?? null,
      description: hit?.description ?? '',
      status,
      createdAt: new Date(),
    });
    console.log(`${LOG} movie "${filename}" -> ${status}`);
    return { mediaId: insertedId, status };
  }

  // Series: find-or-create the parent media doc by title, then add an episode.
  const hit = await searchSeries(parsed.title).catch(() => null);
  const status = hit ? MediaStatus.MATCHED : MediaStatus.UNMATCHED;

  const { insertedId: mediaId } = await media.insertOne({
    type: 'series',
    title: hit?.title || parsed.title,
    year: null,
    tmdbId: hit?.tmdbId ?? null,
    posterPath: hit?.posterPath ?? null,
    description: hit?.description ?? '',
    status,
    createdAt: new Date(),
  });

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
    driveFileId,
    size,
    createdAt: new Date(),
  });

  console.log(`${LOG} series "${filename}" S${parsed.season}E${parsed.episode} -> ${status}`);
  return { mediaId, status };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node lib/library/match.test.js`
Expected: `PASS: all match checks passed`

- [ ] **Step 5: Commit**

```bash
git add lib/library/match.js lib/library/match.test.js
git commit -m "feat: add TMDb match orchestration for uploads"
```

---

## Task 6: Remove old photo-backup code

**Files:**
- Delete: `app/api/backup/` (entire directory)
- Delete: `app/page.js`, `app/Gallery.js`, `app/upload/`, `app/storage/`
- Delete: `lib/r2.js`, `lib/rclone-rc.js`, `lib/storage-router.js`, `lib/telegram.js`, `lib/download-cache.js`, `lib/multipart-stream.js`, `lib/client-upload.js`
- Delete: `rclone/` (entire directory)
- Delete: `scripts/test-router.js`
- Modify: `.env.example` — remove pool/Telegram/R2 env vars
- Modify: `package.json` — remove `test:router` script

**Interfaces:**
- Consumes: nothing (deletion task)
- Produces: nothing new — clears the way for Task 7's routes/pages to replace what's removed here

- [ ] **Step 1: Confirm nothing outside the removal set imports these files**

Run: `grep -rln "lib/r2\|lib/rclone-rc\|lib/storage-router\|lib/telegram\|lib/download-cache\|lib/multipart-stream\|lib/client-upload" app lib scripts --include=*.js | grep -v "app/api/backup\|app/upload\|app/storage\|app/page.js\|app/Gallery.js"`

Expected: no output, other than files already in the removal set. If
`lib/posters.js` or `app/movies/*` import any of these (it currently
imports `lib/r2.js` and `lib/local-store.js`), note it — `lib/posters.js`
is pre-existing in-progress work outside this plan's scope; leave it and
its R2 import in place unless it blocks the build, in which case flag it
back to the user rather than silently deleting or rewriting it.

- [ ] **Step 2: Delete the files**

```bash
git rm -r app/api/backup app/page.js app/Gallery.js app/upload app/storage \
  lib/r2.js lib/rclone-rc.js lib/storage-router.js lib/telegram.js \
  lib/download-cache.js lib/multipart-stream.js lib/client-upload.js \
  rclone scripts/test-router.js
```

(If `lib/r2.js` is still imported by `lib/posters.js`/`app/movies` per
Step 1's finding, drop it from this `git rm` list and leave it in place.)

- [ ] **Step 3: Remove obsolete env vars from `.env.example`**

Open `.env.example`, remove the R2/rclone-pool/Telegram variable block
(R2_*, RCLONE_RC_URL, and any Telegram/GramJS-related vars), keeping
`KDRIVE_LOCAL_DIR`/`MONGODB_URI`/`ADMIN_PASSWORD`/`KDRIVE_DEVICE_KEY` and
the vars added in Tasks 1 and 4.

- [ ] **Step 4: Remove the `test:router` script from `package.json`**

Delete the `"test:router": "node scripts/test-router.js"` line from the
`scripts` block.

- [ ] **Step 5: Verify the app still builds**

Run: `npm run build`
Expected: build fails only on the missing `/` route (expected — Task 7
adds it back) or succeeds if Next treats a missing root route as fine
during build; either way, no import errors referencing deleted files. If
the build fails on an import of a deleted file outside the removal set,
stop and re-check Step 1.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: remove photo-backup pool/Telegram code, replaced by Google Drive media server"
```

---

## Task 7: Media API routes (`app/api/media/*`)

**Files:**
- Create: `app/api/media/upload/route.js`
- Create: `app/api/media/list/route.js`
- Create: `app/api/media/[id]/route.js`
- Create: `app/api/media/stream/[id]/route.js`
- Create: `app/api/media/progress/route.js`
- Modify: `middleware.js` — update `matcher` to cover new page routes (see Task 8)

**Interfaces:**
- Consumes: `uploadFile`/`streamFile` (Task 1), `matchAndStore` (Task 5), `mediaCollection`/`episodeCollection` (Task 2), `requireDeviceOrSession` (existing `lib/auth.js`)
- Produces: `POST /api/media/upload`, `GET /api/media/list`, `GET /api/media/[id]`, `GET /api/media/stream/[id]` (Range-aware), `GET`/`POST /api/media/progress` — the HTTP surface `app/(web)` (Task 8) and the future Android TV client consume

- [ ] **Step 1: Write `app/api/media/upload/route.js`**

```javascript
// POST /api/media/upload — accepts a media file, stores bytes on Google
// Drive, writes a media doc, kicks off TMDb matching. Matching failure never
// fails the upload (see docs/superpowers/specs/
// 2026-08-20-media-server-backend-design.md, Error Handling).

import { requireDeviceOrSession } from '@/lib/auth.js';
import { uploadFile } from '@/lib/gdrive.js';
import { matchAndStore } from '@/lib/library/match.js';

export async function POST(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const formData = await request.formData();
  const file = formData.get('file');
  if (!file || typeof file.stream !== 'function') {
    return Response.json({ error: 'No file provided' }, { status: 400 });
  }

  const { Readable } = await import('node:stream');
  const nodeStream = Readable.fromWeb(file.stream());

  let uploaded;
  try {
    uploaded = await uploadFile(nodeStream, file.name, file.type || 'application/octet-stream');
  } catch (err) {
    console.error('[api/media/upload] Drive upload failed', err);
    return Response.json({ error: 'Upload to storage failed' }, { status: 502 });
  }

  const result = await matchAndStore({
    filename: file.name,
    driveFileId: uploaded.driveFileId,
    size: uploaded.size,
  });

  return Response.json({ mediaId: result.mediaId, status: result.status }, { status: 201 });
}
```

- [ ] **Step 2: Write `app/api/media/list/route.js`**

```javascript
// GET /api/media/list — the library, grouped by type, newest first.

import { requireDeviceOrSession } from '@/lib/auth.js';
import { mediaCollection } from '@/lib/models/media.js';

export async function GET(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const media = await mediaCollection();
  const docs = await media.find({}).sort({ createdAt: -1 }).toArray();

  return Response.json({
    movies: docs.filter((d) => d.type === 'movie'),
    series: docs.filter((d) => d.type === 'series'),
  });
}
```

- [ ] **Step 3: Write `app/api/media/[id]/route.js`**

```javascript
// GET /api/media/[id] — item detail. For a series, includes its episodes.

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export async function GET(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  const media = await mediaCollection();
  const doc = await media.findOne({ _id: new ObjectId(id) });
  if (!doc) return Response.json({ error: 'Not found' }, { status: 404 });

  if (doc.type === 'series') {
    const episodes = await episodeCollection();
    const eps = await episodes
      .find({ mediaId: doc._id })
      .sort({ season: 1, episode: 1 })
      .toArray();
    return Response.json({ ...doc, episodes: eps });
  }

  return Response.json(doc);
}
```

- [ ] **Step 4: Write `app/api/media/stream/[id]/route.js`**

```javascript
// GET /api/media/stream/[id] — proxies file bytes from Google Drive, honoring
// the client's Range header for seeking (direct-play only — no transcoding,
// see docs/superpowers/specs/2026-08-20-media-server-backend-design.md).
// `id` may be a media _id (movie) or an episode _id (series episode) — try
// media first, fall back to episode.

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { streamFile } from '@/lib/gdrive.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

async function resolveDriveFileId(id) {
  const _id = new ObjectId(id);
  const media = await mediaCollection();
  const mediaDoc = await media.findOne({ _id });
  if (mediaDoc?.driveFileId) return mediaDoc.driveFileId;

  const episodes = await episodeCollection();
  const episodeDoc = await episodes.findOne({ _id });
  return episodeDoc?.driveFileId ?? null;
}

export async function GET(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  const driveFileId = await resolveDriveFileId(id);
  if (!driveFileId) return Response.json({ error: 'Not found' }, { status: 404 });

  const range = request.headers.get('range');

  let result;
  try {
    result = await streamFile(driveFileId, range);
  } catch (err) {
    if (err.status === 416) {
      return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */*` } });
    }
    console.error('[api/media/stream] Drive stream failed', err);
    return Response.json({ error: 'Storage read failed' }, { status: 502 });
  }

  const headers = {
    'Content-Length': String(result.contentLength),
    'Accept-Ranges': 'bytes',
  };
  if (result.contentRange) headers['Content-Range'] = result.contentRange;

  return new Response(result.stream, { status: result.status, headers });
}
```

- [ ] **Step 5: Write `app/api/media/progress/route.js`**

```javascript
// GET/POST /api/media/progress — resume-watch position, keyed by media or
// episode id. Single-user app, so no per-user scoping (see spec).

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { getCollection } from '@/lib/db.js';

function progressCollection() {
  return getCollection('progress');
}

export async function GET(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const id = new URL(request.url).searchParams.get('id');
  if (!id) return Response.json({ error: 'id is required' }, { status: 400 });

  const progress = await progressCollection();
  const doc = await progress.findOne({ itemId: new ObjectId(id) });
  return Response.json({ positionSeconds: doc?.positionSeconds ?? 0 });
}

export async function POST(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id, positionSeconds } = await request.json();
  if (!id || typeof positionSeconds !== 'number') {
    return Response.json({ error: 'id and positionSeconds are required' }, { status: 400 });
  }

  const progress = await progressCollection();
  await progress.updateOne(
    { itemId: new ObjectId(id) },
    { $set: { itemId: new ObjectId(id), positionSeconds, updatedAt: new Date() } },
    { upsert: true }
  );
  return Response.json({ ok: true });
}
```

- [ ] **Step 6: Manual smoke test against a running dev server**

Run: `npm run dev`, then in another shell:

```bash
curl -s -X POST http://localhost:3000/api/media/upload \
  -H "x-kdrive-device-key: $KDRIVE_DEVICE_KEY" \
  -F "file=@/path/to/a/small/test/video.mp4"
```

Expected: `201` with a `mediaId` and `status`. Then:

```bash
curl -s http://localhost:3000/api/media/list -H "x-kdrive-device-key: $KDRIVE_DEVICE_KEY"
```

Expected: the uploaded item appears under `movies` or `series`.

- [ ] **Step 7: Commit**

```bash
git add app/api/media
git commit -m "feat: add media upload/list/detail/stream/progress API routes"
```

---

## Task 8: Web UI (`app/(web)`)

**Files:**
- Create: `app/page.js` (library browse — replaces the deleted photo gallery)
- Create: `app/Library.js` (client component: poster grid, movies/shows rows)
- Create: `app/title/[id]/page.js` (detail + play)
- Create: `app/upload/page.js` (add media)
- Modify: `app/NavBar.js` — update links (remove `/storage`/gallery-specific links, point at `/` and `/upload`)
- Modify: `middleware.js` — update `matcher` to `['/', '/upload', '/title/:path*']`

**Interfaces:**
- Consumes: `GET /api/media/list`, `GET /api/media/[id]`, `GET /api/media/stream/[id]`, `POST /api/media/upload` (Task 7)
- Produces: the pages a browser (and, informally, the reference implementation the Android TV client's API usage will follow) hits

- [ ] **Step 1: Write `app/Library.js`**

```javascript
'use client';

// Poster-grid library browser — fetches /api/media/list and renders
// Movies and Shows rows, Jellyfin-style.

import Link from 'next/link';
import { useEffect, useState } from 'react';

export default function Library() {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    fetch('/api/media/list')
      .then((res) => {
        if (!res.ok) throw new Error(`list failed: ${res.status}`);
        return res.json();
      })
      .then(setData)
      .catch((err) => setError(err.message));
  }, []);

  if (error) return <p className="error">Failed to load library: {error}</p>;
  if (!data) return <p>Loading…</p>;

  return (
    <div className="library">
      <Row title="Movies" items={data.movies} />
      <Row title="Shows" items={data.series} />
    </div>
  );
}

function Row({ title, items }) {
  if (!items.length) return null;
  return (
    <section>
      <h2>{title}</h2>
      <div className="poster-row">
        {items.map((item) => (
          <Link key={item._id} href={`/title/${item._id}`} className="poster-card">
            {item.posterPath ? (
              <img src={`https://image.tmdb.org/t/p/w300${item.posterPath}`} alt={item.title} />
            ) : (
              <div className="poster-placeholder">{item.title}</div>
            )}
            <span>{item.title}</span>
          </Link>
        ))}
      </div>
    </section>
  );
}
```

- [ ] **Step 2: Write `app/page.js`**

```javascript
// / — the media library. Movies and Shows, newest first.

import { Suspense } from 'react';

import Library from './Library.js';

export const dynamic = 'force-dynamic';

export default function Home() {
  return (
    <Suspense fallback={null}>
      <Library />
    </Suspense>
  );
}
```

- [ ] **Step 3: Write `app/title/[id]/page.js`**

```javascript
'use client';

// /title/[id] — detail page: poster, description, season/episode list
// (series), play button. Direct-play only: <video> streams
// /api/media/stream/[id] with native Range support.

import { use, useEffect, useState } from 'react';

export default function TitlePage({ params }) {
  const { id } = use(params);
  const [item, setItem] = useState(null);
  const [error, setError] = useState(null);
  const [playingId, setPlayingId] = useState(null);

  useEffect(() => {
    fetch(`/api/media/${id}`)
      .then((res) => {
        if (!res.ok) throw new Error(`detail failed: ${res.status}`);
        return res.json();
      })
      .then(setItem)
      .catch((err) => setError(err.message));
  }, [id]);

  if (error) return <p className="error">Failed to load title: {error}</p>;
  if (!item) return <p>Loading…</p>;

  return (
    <div className="title-detail">
      <h1>{item.title}{item.year ? ` (${item.year})` : ''}</h1>
      <p>{item.description}</p>

      {item.type === 'movie' && (
        <button onClick={() => setPlayingId(item._id)}>Play</button>
      )}

      {item.type === 'series' && (
        <ul className="episode-list">
          {item.episodes.map((ep) => (
            <li key={ep._id}>
              S{ep.season}E{ep.episode} — {ep.title}
              <button onClick={() => setPlayingId(ep._id)}>Play</button>
            </li>
          ))}
        </ul>
      )}

      {playingId && (
        <video controls autoPlay src={`/api/media/stream/${playingId}`} style={{ width: '100%' }} />
      )}
    </div>
  );
}
```

- [ ] **Step 4: Write `app/upload/page.js`**

```javascript
'use client';

// /upload — add media. Uploads the picked file to /api/media/upload and
// shows the parse/match result once it lands.

import { useState } from 'react';

export default function UploadPage() {
  const [status, setStatus] = useState('idle');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  async function handleChange(e) {
    const file = e.target.files?.[0];
    if (!file) return;

    setStatus('uploading');
    setError(null);
    const formData = new FormData();
    formData.append('file', file);

    try {
      const res = await fetch('/api/media/upload', { method: 'POST', body: formData });
      if (!res.ok) throw new Error(`upload failed: ${res.status}`);
      const data = await res.json();
      setResult(data);
      setStatus('done');
    } catch (err) {
      setError(err.message);
      setStatus('error');
    }
  }

  return (
    <div className="upload-page">
      <h1>Add media</h1>
      <input type="file" accept="video/*" onChange={handleChange} disabled={status === 'uploading'} />
      {status === 'uploading' && <p>Uploading…</p>}
      {status === 'done' && (
        <p>
          Added — match status: <strong>{result.status}</strong>
        </p>
      )}
      {status === 'error' && <p className="error">{error}</p>}
    </div>
  );
}
```

- [ ] **Step 5: Update `middleware.js` matcher**

```javascript
export const config = {
  matcher: ['/', '/upload', '/title/:path*'],
};
```

(Leave the rest of `middleware.js` — the cookie check and redirect logic
— unchanged; only the `matcher` array's route list changes.)

- [ ] **Step 6: Update `app/NavBar.js` links**

Open the file and replace any links pointing at the removed `/storage`
and old gallery routes with links to `/` (Library) and `/upload` (Add
media), keeping the component's existing structure/styling conventions.

- [ ] **Step 7: Manual browser smoke test**

Run: `npm run dev`, open `http://localhost:3000` in a browser.
Expected: redirected to `/login` if not signed in; after login, `/` shows
the (possibly empty) library; `/upload` accepts a file and reports a
match status; after a successful upload, `/` shows the new item, and its
`/title/[id]` page plays it via the `<video>` tag.

- [ ] **Step 8: Commit**

```bash
git add app/page.js app/Library.js app/title app/upload/page.js app/NavBar.js middleware.js
git commit -m "feat: add library browse, detail/play, and upload pages"
```

---

## Task 9: Admin usage page — Drive storage instead of pool

**Files:**
- Modify: `app/admin/usage/page.js` (or equivalent file — confirm exact path first)
- Modify: `lib/gdrive.js` — add `getStorageUsage()`
- Test: manual (admin page is already password-gated, not worth a scripted test for a read-only display)

**Interfaces:**
- Consumes: existing admin auth gate (`lib/admin-auth.js`)
- Produces: `getStorageUsage() → Promise<{ usedBytes: number, limitBytes: number|null }>` in `lib/gdrive.js`

- [ ] **Step 1: Locate the current admin usage page**

Run: `find app/admin -name "*.js"` and read the file to see what it
currently renders (pool usage from `lib/storage-router.js`, per the
README) so the replacement keeps the same page shell/styling.

- [ ] **Step 2: Add `getStorageUsage()` to `lib/gdrive.js`**

```javascript
/** Returns Drive storage usage for the configured account. */
export async function getStorageUsage() {
  const drive = getDrive();
  const res = await drive.about.get({ fields: 'storageQuota' });
  const quota = res.data.storageQuota || {};
  return {
    usedBytes: Number(quota.usage || 0),
    limitBytes: quota.limit ? Number(quota.limit) : null,
  };
}
```

- [ ] **Step 3: Update the admin usage page to call `getStorageUsage()`**

Replace the pool-usage data source with `getStorageUsage()` from
`lib/gdrive.js`, keeping the existing page's rendering/formatting
approach (reuse `lib/format.js` if it already formats byte counts, per
the existing codebase convention).

- [ ] **Step 4: Manual smoke test**

Run: `npm run dev`, sign in as admin, visit `/admin/usage`.
Expected: page renders Drive used/limit bytes without error (or a clear
"Drive not configured" message if credentials are absent).

- [ ] **Step 5: Commit**

```bash
git add lib/gdrive.js app/admin
git commit -m "feat: show Google Drive usage on the admin dashboard"
```

---

## Task 10: Update project docs

**Files:**
- Modify: `README.md`
- Modify: `PRD.md`
- Modify: `docs/01-architecture.md`

**Interfaces:**
- Consumes: nothing — documentation only

- [ ] **Step 1: Rewrite `README.md`'s description and Quick Start**

Replace the photo-backup description (multi-cloud pool, Telegram
fallback) with the media-server description: Google Drive storage, TMDb
metadata, direct-play streaming. Update the `## What's in here` tree to
match the new `app/`/`lib/` layout from Tasks 1-9. Update `## Status` to
reflect what's built.

- [ ] **Step 2: Update `PRD.md` and `docs/01-architecture.md`**

Update the product requirements and architecture diagram to describe the
media-server domain instead of photo backup, referencing
`docs/superpowers/specs/2026-08-20-media-server-backend-design.md` as the
source of truth for this iteration.

- [ ] **Step 3: Commit**

```bash
git add README.md PRD.md docs/01-architecture.md
git commit -m "docs: update README/PRD/architecture for the media server pivot"
```

---

## Notes for the executor

- Tasks 1-5 have no dependency on a running dev server and can be built
  and unit-tested in isolation before Task 6 touches the app's routes.
- Task 6 (deletion) should run only after Tasks 1-5 are committed, so the
  new storage/library code exists before the old code is removed —
  minimizing the window where `npm run dev` is broken.
- Tasks 1's and 4's integration tests (`test-gdrive.js`, `test-tmdb.js`)
  require real credentials (`GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`/
  `GOOGLE_REFRESH_TOKEN`, `TMDB_API_KEY`) in `.env.local`. If those aren't
  available yet when a task is executed, say so explicitly rather than
  marking the step passed — per this project's existing practice
  (README's "Status" section already tracks unverified legs the same
  way).
- The Android TV client (sub-project 2) is out of scope for this plan —
  it gets its own spec once `app/api/media/*`'s shape (Task 7) is stable.
