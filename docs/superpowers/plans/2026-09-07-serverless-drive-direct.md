# Serverless kPlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retire the VPS by hosting kPlay's control plane on Vercel's free plan and streaming every video byte from Google Drive straight to the player.

**Architecture:** Vercel serves the UI and a JSON-only API; MongoDB Atlas holds the catalog; Google Drive serves video bytes directly to the browser and TV clients over authenticated Range requests. Nothing that reads file bytes runs on Vercel — mkv indexing moves to a local script.

**Tech Stack:** Next.js 15 (App Router, JavaScript, ESM), React 18, `mongodb` 6, `googleapis` 142, Tailwind 3, `node --test` for tests.

**Spec:** `docs/superpowers/specs/2026-09-07-serverless-drive-direct-design.md`

## Global Constraints

- Node `>=18.18.0`; package is `"type": "module"` — every file uses ESM `import`/`export`, never `require`.
- Tests run via `npm test`, which is `node --test "lib/**/*.test.js"`. **Only files under `lib/` are collected.** Any logic that needs a test must live in a `lib/` module; route handlers stay thin wrappers over it.
- Existing test files use bare assertion blocks with `import assert from 'node:assert/strict'` and a `console.log('ok: ...')` per block. Match that style exactly — do not introduce `node:test`'s `test()` wrapper.
- Route files declare `export const runtime = 'nodejs';` and `export const dynamic = 'force-dynamic';`.
- Path alias `@/` maps to the repo root (see `jsconfig.json`); library imports inside `lib/` use relative paths, imports inside `app/` use `@/lib/...`.
- Video bytes must never pass through a Vercel function. Any code path that reads file content from Drive on the server is a defect.
- Any response carrying a Drive access token sets `Cache-Control: private, no-store` and `Pragma: no-cache`.
- Comments explain *why*, in the voice of the surrounding files: full sentences, no bullet-list headers, no restating what the code says.
- Commit messages: imperative subject under 72 chars, a body explaining the motivation, and the trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

## File Structure

**Created**

| Path | Responsibility |
|---|---|
| `lib/drive-token.js` | Access-token cache: decides when a cached token is stale and mints a fresh one |
| `lib/drive-token.test.js` | Tests for the staleness decision |
| `app/api/drive/token/route.js` | Thin route exposing the cache to authorised clients |
| `lib/library/scan-page.js` | Pure paging helper: splits pending files into a page plus a next cursor |
| `lib/library/scan-page.test.js` | Tests for paging and cursor round-tripping |
| `lib/library/edit.js` | Validation for manual catalog edits (title, year, tmdbId, type change) |
| `lib/library/edit.test.js` | Tests for edit validation, including the orphan-episode rule |
| `app/api/media/[id]/episodes/route.js` | Assigns an existing Drive file to a series as an episode |
| `app/api/episode/[id]/route.js` | Edits or deletes one episode |
| `app/api/media/[id]/seek-index/route.js` | Stores a precomputed mkv seek index (`PUT` only) |
| `app/admin/library/page.js` | Panel screen: fix matches, structure series |
| `app/admin/library/LibraryEditor.js` | Client component for that screen |
| `public/drive-sw.js` | Service Worker translating `/drive/<fileId>` into an authenticated Drive fetch |
| `lib/drive-url.js` | Single source of truth for the Drive bytes URL and the local SW path |
| `scripts/index-mkv.mjs` | Local byte-reading job that builds and uploads a seek index |
| `scripts/probe-drive-cors.mjs` | Throwaway probe for Task 1 |

**Modified**

| Path | Change |
|---|---|
| `lib/admin-auth.js` | Session cookie lifetime 30 days -> 1 year |
| `lib/gdrive.js` | `getAccessToken()` delegates to `lib/drive-token.js` |
| `app/api/media/scan/route.js` | Accepts `?cursor=`, returns `nextCursor` |
| `app/api/media/[id]/route.js` | Adds `PATCH`; `GET` returns `seekIndex` |
| `app/api/media/play-url/[id]/route.js` | Always `mode: 'direct'`; browser branch removed |
| `lib/models/media.js` | Documents the new `seekIndex` field |
| `app/movies/[id]/page.js`, `app/series/[id]/page.js` | Play through the Service Worker path |
| `package.json` | Adds `index:mkv` script |

**Deleted (Task 12 only)** — `app/api/media/stream/`, `app/api/media/seek-index/`, `app/admin/monitor/`, `lib/monitor/`, `app/api/admin/metrics/`, `app/api/admin/playback-report/`, `Dockerfile`, `docker-compose.yml`, `deploy/`, `scripts/probe-drive-cors.mjs`.

---

### Task 1: Prove or disprove browser CORS on Drive

The spec's one open question. `app/api/media/play-url/[id]/route.js:20-26` claims a browser can never fetch Drive bytes directly. That claim came from the rejected `?access_token=` URL form. Retest with an `Authorization` header before building anything that depends on the answer.

**This task is a spike. Its output is a recorded answer, and the script it creates is throwaway — Task 12 deletes it.**

**Files:**
- Create: `scripts/probe-drive-cors.mjs`
- Modify: `docs/superpowers/specs/2026-09-07-serverless-drive-direct-design.md` (record the finding)

**Interfaces:**
- Consumes: `getAccessToken()` from `lib/gdrive.js`, `GOOGLE_DRIVE_FOLDER_ID`
- Produces: a recorded yes/no that gates Tasks 8 and 9

- [ ] **Step 1: Write the probe script**

```javascript
// scripts/probe-drive-cors.mjs — throwaway. Deleted by Task 12.
//
// Serves a page on localhost that tries, from browser JavaScript, exactly the
// request the Service Worker in Task 8 would make. If the browser reports the
// bytes, Drive sends CORS headers for a Bearer-authenticated range request and
// browser direct play is possible. If it reports a CORS failure, it is not.

import { createServer } from 'node:http';

import { getAccessToken, listFolderFiles } from '../lib/gdrive.js';
import { isVideoFile } from '../lib/library/video-types.js';

const files = await listFolderFiles();
const target = files.find(isVideoFile);
if (!target) {
  console.error('No video file in the Drive folder to probe with.');
  process.exit(1);
}

const { token } = await getAccessToken();
const url = `https://www.googleapis.com/drive/v3/files/${target.driveFileId}?alt=media`;

const page = `<!doctype html><meta charset="utf-8"><title>Drive CORS probe</title>
<pre id="out">probing ${target.name}...</pre>
<script>
const out = document.getElementById('out');
fetch(${JSON.stringify(url)}, {
  headers: { Authorization: 'Bearer ' + ${JSON.stringify(token)}, Range: 'bytes=0-1023' },
})
  .then(async (r) => {
    const body = await r.arrayBuffer();
    out.textContent = 'RESULT: reachable\\nstatus ' + r.status +
      '\\nbytes ' + body.byteLength +
      '\\ncontent-range ' + r.headers.get('content-range');
  })
  .catch((err) => {
    out.textContent = 'RESULT: blocked\\n' + err;
  });
</script>`;

createServer((_req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(page);
}).listen(4321, () => console.log('Probe at http://localhost:4321 — open it and read the page.'));
```

- [ ] **Step 2: Run the probe**

Run: `node scripts/probe-drive-cors.mjs`

Open `http://localhost:4321` in a browser and read the page. Open DevTools' console too — a CORS rejection prints a specific message there that the `catch` block cannot see.

Expected: either `RESULT: reachable` with `status 206` and `bytes 1024`, or `RESULT: blocked`.

- [ ] **Step 3: Record the finding in the spec**

In the spec's "Open question, resolved before anything else is built" paragraph, replace the final sentence with what actually happened. Write the date, the observed status code, and the exact console message if it was blocked.

- [ ] **Step 4: Commit**

```bash
git add scripts/probe-drive-cors.mjs docs/superpowers/specs/2026-09-07-serverless-drive-direct-design.md
git commit -m "$(cat <<'EOF'
docs: record whether Drive answers a browser's authenticated range request

The design turns on this one fact and the old comment in play-url was
written from the rejected ?access_token= form, so measure it directly
before building the browser transport on top of an assumption.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Gate the remaining work**

If `blocked`: Tasks 8 and 9 are out of scope. Skip them; browser playback keeps the proxy, and Task 12 must not delete `app/api/media/stream/`. Say so explicitly before continuing. Every other task proceeds unchanged.

---

### Task 2: Extend the session to a year

**Files:**
- Modify: `lib/admin-auth.js:56-64`

**Interfaces:**
- Consumes: nothing
- Produces: nothing new; `adminCookieOptions()` keeps its signature

- [ ] **Step 1: Change the lifetime**

In `adminCookieOptions()`, replace the `maxAge` line and its comment:

```javascript
    // A single-user personal library on a private device: signing in once and
    // staying signed in is the whole point. A year is long enough that the
    // login screen is a first-run detail rather than a recurring chore, and
    // the cookie holds an HMAC rather than the password, so age costs little.
    maxAge: 60 * 60 * 24 * 365,
```

- [ ] **Step 2: Verify by hand**

Run: `npm run dev`

Sign in at `/login`. In DevTools, open Application -> Cookies -> `kdrive_admin` and confirm the Expires column reads roughly one year out, not 30 days.

- [ ] **Step 3: Commit**

```bash
git add lib/admin-auth.js
git commit -m "$(cat <<'EOF'
feat(auth): keep a signed-in session for a year

Signing in monthly is friction with no security return on a single-user
library: the cookie stores an HMAC, not the password, so a longer life
costs nothing that a shorter one was buying.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Token cache module

Playback refetches a token roughly hourly per stream. Minting one on every call would refresh against Google needlessly; the decision of when a cached token is too old is the only part with logic worth testing, so it lives in `lib/` where the test runner can reach it.

**Files:**
- Create: `lib/drive-token.js`
- Test: `lib/drive-token.test.js`
- Modify: `lib/gdrive.js:225-238`

**Interfaces:**
- Consumes: `getAccessToken()` as currently exported from `lib/gdrive.js`
- Produces:
  - `isStale(expiresAt, now)` -> `boolean`
  - `cachedAccessToken(mint, now)` -> `Promise<{ token: string, expiresAt: number }>` where `mint` is `() => Promise<{ token, expiresAt }>`
  - `resetTokenCache()` -> `void`, for tests only
  - `REFRESH_MARGIN_MS` -> `number`

- [ ] **Step 1: Write the failing test**

```javascript
// node lib/drive-token.test.js
import assert from 'node:assert/strict';
import { cachedAccessToken, isStale, REFRESH_MARGIN_MS, resetTokenCache } from './drive-token.js';

// A token with plenty of life left is not stale.
{
  const now = 1_000_000;
  assert.equal(isStale(now + 30 * 60 * 1000, now), false);
  console.log('ok: a fresh token is not stale');
}

// A token inside the refresh margin is stale, so playback never carries a
// token that dies mid-request.
{
  const now = 1_000_000;
  assert.equal(isStale(now + REFRESH_MARGIN_MS - 1, now), true);
  console.log('ok: a token inside the refresh margin is stale');
}

// An already-expired token is stale.
{
  const now = 1_000_000;
  assert.equal(isStale(now - 1, now), true);
  console.log('ok: an expired token is stale');
}

// The second call inside the token's life reuses the first mint.
{
  resetTokenCache();
  const now = 1_000_000;
  let mints = 0;
  const mint = async () => {
    mints += 1;
    return { token: `t${mints}`, expiresAt: now + 60 * 60 * 1000 };
  };

  const first = await cachedAccessToken(mint, now);
  const second = await cachedAccessToken(mint, now + 60 * 1000);

  assert.equal(first.token, 't1');
  assert.equal(second.token, 't1');
  assert.equal(mints, 1);
  console.log('ok: a live cached token is reused');
}

// Once the cached token enters the refresh margin, the next call mints again.
{
  resetTokenCache();
  const now = 1_000_000;
  let mints = 0;
  const mint = async () => {
    mints += 1;
    return { token: `t${mints}`, expiresAt: now + mints * 60 * 60 * 1000 };
  };

  await cachedAccessToken(mint, now);
  const later = await cachedAccessToken(mint, now + 60 * 60 * 1000 - REFRESH_MARGIN_MS + 1);

  assert.equal(later.token, 't2');
  assert.equal(mints, 2);
  console.log('ok: a stale cached token is replaced');
}

// Concurrent callers share one refresh rather than each starting their own.
{
  resetTokenCache();
  const now = 1_000_000;
  let mints = 0;
  const mint = async () => {
    mints += 1;
    await new Promise((resolve) => setTimeout(resolve, 10));
    return { token: `t${mints}`, expiresAt: now + 60 * 60 * 1000 };
  };

  const [a, b] = await Promise.all([cachedAccessToken(mint, now), cachedAccessToken(mint, now)]);

  assert.equal(a.token, b.token);
  assert.equal(mints, 1);
  console.log('ok: concurrent callers share one refresh');
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test lib/drive-token.test.js`

Expected: FAIL with `Cannot find module ... drive-token.js`.

- [ ] **Step 3: Write the implementation**

```javascript
// lib/drive-token.js — the short-lived Drive access token handed to players.
//
// Every play fetches a token, and a long viewing session fetches another each
// hour. Minting one per request would refresh against Google far more often
// than the token's life requires, so the last one is held here and reused
// until it is close enough to expiry that a range request issued with it might
// outlive it.

// How early a token is treated as spent. A range request issued at the very
// edge of a token's life can arrive at Google after it has expired, and the
// player sees that as a failed seek rather than as a retryable auth error.
export const REFRESH_MARGIN_MS = 5 * 60 * 1000;

let cached = null;
let inFlight = null;

/** true when a token expiring at `expiresAt` should no longer be handed out. */
export function isStale(expiresAt, now = Date.now()) {
  return !expiresAt || expiresAt - now <= REFRESH_MARGIN_MS;
}

/**
 * The current access token, minting a new one only when the held one is stale.
 *
 * `mint` is injected rather than imported so this module stays free of the
 * googleapis client and can be tested without one.
 */
export async function cachedAccessToken(mint, now = Date.now()) {
  if (cached && !isStale(cached.expiresAt, now)) return cached;

  // A cold start under load calls this from several requests at once. Sharing
  // the promise keeps that to a single refresh; without it each caller starts
  // its own and the last one to resolve wins arbitrarily.
  if (!inFlight) {
    inFlight = (async () => {
      try {
        cached = await mint();
        return cached;
      } finally {
        inFlight = null;
      }
    })();
  }

  return inFlight;
}

/** Drops the held token. Tests only — nothing in the app needs this. */
export function resetTokenCache() {
  cached = null;
  inFlight = null;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test lib/drive-token.test.js`

Expected: PASS, six `ok:` lines.

- [ ] **Step 5: Route `getAccessToken()` through the cache**

In `lib/gdrive.js`, add to the imports at the top of the file:

```javascript
import { cachedAccessToken } from './drive-token.js';
```

Replace the body of `getAccessToken()` (currently `lib/gdrive.js:225-238`) with:

```javascript
export async function getAccessToken() {
  return cachedAccessToken(async () => {
    const drive = getDrive();
    const auth = drive.context._options.auth;

    const { token } = await auth.getAccessToken();
    if (!token) throw new Error('Google Drive returned no access token');

    // expiry_date is set by the refresh that getAccessToken() just performed
    // (or by the one that cached the token still in hand). Missing only if the
    // client has been used in a way that skips the refresh path; a
    // conservative five minutes is safer there than claiming an hour.
    const expiresAt = auth.credentials?.expiry_date || Date.now() + 5 * 60 * 1000;
    return { token, expiresAt };
  });
}
```

- [ ] **Step 6: Run the whole suite**

Run: `npm test`

Expected: PASS, including the pre-existing `lib/library/*.test.js` files.

- [ ] **Step 7: Commit**

```bash
git add lib/drive-token.js lib/drive-token.test.js lib/gdrive.js
git commit -m "$(cat <<'EOF'
feat(gdrive): hold the access token until it is nearly spent

Direct play asks for a token on every start and again each hour, and
minting one per request refreshes against Google far more often than the
token's own life needs. Hold the last one, and treat it as spent five
minutes early so no range request outlives its credential.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: The token endpoint

**Files:**
- Create: `app/api/drive/token/route.js`

**Interfaces:**
- Consumes: `getAccessToken()` from `lib/gdrive.js`, `requireDeviceOrSession()` from `lib/auth.js`
- Produces: `GET /api/drive/token` -> `{ token: string, expiresAt: number }`, 503 on failure

- [ ] **Step 1: Write the route**

```javascript
// GET /api/drive/token — the credential a player needs to read bytes from
// Drive itself.
//
// This is the whole of what the server contributes to playback. The client
// takes the token, fetches ranges straight from Google, and refreshes here
// before the token expires; no file bytes pass through this deployment, which
// is the property the whole architecture rests on.
//
// There is deliberately no proxy fallback behind this route. If minting fails,
// playback fails, and that is visible rather than silently expensive.

import { requireDeviceOrSession } from '@/lib/auth.js';
import { getAccessToken } from '@/lib/gdrive.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  let token;
  let expiresAt;
  try {
    ({ token, expiresAt } = await getAccessToken());
  } catch (err) {
    console.error('[api/drive/token] mint failed', err);
    return Response.json({ error: 'Storage auth failed' }, { status: 503 });
  }

  return Response.json(
    { token, expiresAt },
    {
      // A live credential. It must never reach a disk cache, a shared proxy,
      // or a browser's back-forward store.
      headers: { 'Cache-Control': 'private, no-store', Pragma: 'no-cache' },
    }
  );
}
```

- [ ] **Step 2: Verify by hand**

Run: `npm run dev`

Sign in at `/login`, then in the browser console on any app page:

```javascript
await (await fetch('/api/drive/token')).json()
```

Expected: an object with a non-empty `token` and an `expiresAt` roughly an hour ahead.

Then check it is gated:

```bash
curl -i http://localhost:3000/api/drive/token
```

Expected: `HTTP/1.1 401` and `{"error":"Unauthorized"}`.

- [ ] **Step 3: Commit**

```bash
git add app/api/drive/token/route.js
git commit -m "$(cat <<'EOF'
feat(api): hand players a Drive token instead of the bytes

A player that holds a token reads ranges from Google directly, which
takes this deployment out of the byte path entirely — the reason the
proxy exists at all disappears with this route.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Store a precomputed seek index

Matroska files need a cue index to seek. Building one reads file bytes, which cannot happen on Vercel, so the index is computed locally (Task 6) and stored here once.

**Files:**
- Create: `app/api/media/[id]/seek-index/route.js`
- Modify: `lib/models/media.js:1-12` (document the field)
- Modify: `app/api/media/[id]/route.js` (return the field from `GET`)

**Interfaces:**
- Consumes: `requireDeviceOrSession()`, `mediaCollection()`, `episodeCollection()`
- Produces:
  - `PUT /api/media/[id]/seek-index` with body `{ seekIndex }` -> `{ ok: true }`
  - `GET /api/media/[id]` response gains `seekIndex` (or `null`)

- [ ] **Step 1: Document the field**

In `lib/models/media.js`, extend the two shape comments at the top of the file. Add to the `media` doc shape, after `size`:

```
//     seekIndex (optional, mkv only — see scripts/index-mkv.mjs),
```

and the same line to the `episode` doc shape after its `size`.

- [ ] **Step 2: Write the route**

```javascript
// PUT /api/media/[id]/seek-index — stores a cue index built somewhere else.
//
// Matroska carries its cues in a place a player cannot reach with the range
// requests it uses for playback, so seeking needs an index built by reading
// the file. Reading the file is exactly what this deployment must never do:
// the bytes would cross a serverless function and the data transfer that the
// direct-play design removes would come straight back.
//
// So the index is built by scripts/index-mkv.mjs on a machine with bandwidth
// to spare, and posted here once. Playback reads it from the catalog.

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function PUT(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;

  let _id;
  try {
    _id = new ObjectId(id);
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }

  let body;
  try {
    body = await request.json();
  } catch (err) {
    return Response.json({ error: 'Expected a JSON body' }, { status: 400 });
  }

  const { seekIndex } = body ?? {};
  if (!Array.isArray(seekIndex) || seekIndex.length === 0) {
    return Response.json({ error: 'seekIndex must be a non-empty array' }, { status: 400 });
  }

  // A title is a movie or an episode and the caller does not know which, so
  // try both rather than making the local script resolve it first.
  const media = await mediaCollection();
  const updated = await media.updateOne({ _id }, { $set: { seekIndex } });
  if (updated.matchedCount === 1) return Response.json({ ok: true });

  const episodes = await episodeCollection();
  const updatedEpisode = await episodes.updateOne({ _id }, { $set: { seekIndex } });
  if (updatedEpisode.matchedCount === 1) return Response.json({ ok: true });

  return Response.json({ error: 'Not found' }, { status: 404 });
}
```

- [ ] **Step 3: Return the field on read**

In `app/api/media/[id]/route.js`, find where the `GET` handler builds its response body and add `seekIndex` to it, defaulting to `null`:

```javascript
    seekIndex: doc.seekIndex ?? null,
```

Place it alongside the other fields copied off the document. If the handler currently returns the Mongo document directly, leave it alone — the field already travels.

- [ ] **Step 4: Verify by hand**

Run: `npm run dev`

With a signed-in browser session, in the console (substitute a real id from `/api/media/list`):

```javascript
await (await fetch('/api/media/PUT_A_REAL_ID_HERE/seek-index', {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ seekIndex: [{ time: 0, offset: 0 }] }),
})).json()
```

Expected: `{ ok: true }`. Then `GET /api/media/<same id>` and confirm `seekIndex` comes back.

Check rejection too: the same call with `body: JSON.stringify({ seekIndex: [] })` must answer 400.

- [ ] **Step 5: Commit**

```bash
git add app/api/media/'[id]'/seek-index/route.js app/api/media/'[id]'/route.js lib/models/media.js
git commit -m "$(cat <<'EOF'
feat(api): accept a seek index built off this deployment

Building a Matroska cue index means reading the file, and reading the
file on a serverless function reintroduces exactly the data transfer
direct play removes. Take the finished index instead and store it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Local mkv indexing script

**Files:**
- Create: `scripts/index-mkv.mjs`
- Modify: `package.json` (add the `index:mkv` script)

**Interfaces:**
- Consumes: `buildSeekIndex()` (or the equivalent export) from `lib/library/mkv-index.js`, `getAccessToken()` and `listFolderFiles()` from `lib/gdrive.js`, `PUT /api/media/[id]/seek-index` from Task 5
- Produces: `npm run index:mkv` — nothing later depends on it programmatically

- [ ] **Step 1: Read the existing indexer's interface**

Run: `grep -n "^export" lib/library/mkv-index.js`

Note the exported function's exact name and signature. The script below calls it `buildSeekIndex(readRange, size)` where `readRange(start, end)` resolves to a `Buffer`. **If the real export differs, adapt the call and keep the rest.** Do not rename the library export to match this plan — the existing `lib/library/mkv-index.test.js` covers it under its current name.

- [ ] **Step 2: Write the script**

```javascript
// scripts/index-mkv.mjs — builds Matroska seek indexes and uploads them.
//
// Run this on a machine with bandwidth, after adding .mkv titles to the Drive
// folder. It reads enough of each file to find the cues, which is why it does
// not live in the deployed app: those reads are the data transfer the direct
// play design exists to avoid.
//
//   npm run index:mkv                 index every mkv missing an index
//   npm run index:mkv -- --force      rebuild indexes that already exist
//
// Needs the same GOOGLE_* variables the app uses, plus KDRIVE_BASE_URL and
// KDRIVE_DEVICE_KEY so it can post results back.

import { getAccessToken } from '../lib/gdrive.js';

const BASE_URL = process.env.KDRIVE_BASE_URL;
const DEVICE_KEY = process.env.KDRIVE_DEVICE_KEY;
const force = process.argv.includes('--force');

if (!BASE_URL || !DEVICE_KEY) {
  console.error('Set KDRIVE_BASE_URL and KDRIVE_DEVICE_KEY before running this.');
  process.exit(1);
}

const headers = { 'x-kdrive-device-key': DEVICE_KEY };

async function api(path, init = {}) {
  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: { ...headers, ...(init.headers ?? {}) },
  });
  if (!res.ok) throw new Error(`${init.method ?? 'GET'} ${path} -> ${res.status}`);
  return res.json();
}

/** Reads one byte range of a Drive file, refreshing the token as it goes. */
function driveReader(driveFileId) {
  return async (start, end) => {
    const { token } = await getAccessToken();
    const res = await fetch(
      `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(driveFileId)}?alt=media`,
      { headers: { Authorization: `Bearer ${token}`, Range: `bytes=${start}-${end}` } }
    );
    if (!res.ok) throw new Error(`Drive range ${start}-${end} -> ${res.status}`);
    return Buffer.from(await res.arrayBuffer());
  };
}

const { items } = await api('/api/media/list');

const pending = items.filter(
  (item) => item.filename?.toLowerCase().endsWith('.mkv') && (force || !item.seekIndex)
);

if (pending.length === 0) {
  console.log('Nothing to index.');
  process.exit(0);
}

console.log(`Indexing ${pending.length} file(s).`);

const { buildSeekIndex } = await import('../lib/library/mkv-index.js');

for (const item of pending) {
  try {
    const seekIndex = await buildSeekIndex(driveReader(item.driveFileId), item.size);
    await api(`/api/media/${item.id}/seek-index`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ seekIndex }),
    });
    console.log(`ok: ${item.filename} (${seekIndex.length} cue points)`);
  } catch (err) {
    console.error(`failed: ${item.filename} — ${err.message}`);
  }
}
```

- [ ] **Step 3: Reconcile the list response**

Run: `grep -n "Response.json" -B 20 app/api/media/list/route.js`

Confirm the field names the script reads — `items`, `id`, `filename`, `size`, `driveFileId`, `seekIndex`. Adjust the script to the real shape, and if `driveFileId` or `seekIndex` are not currently returned by the list route, add them there. They are catalog metadata, not credentials.

- [ ] **Step 4: Add the npm script**

In `package.json`, inside `"scripts"`, after `"apk:build"`:

```json
    "index:mkv": "node scripts/index-mkv.mjs",
```

- [ ] **Step 5: Verify against one real file**

Run: `npm run index:mkv`

Expected: one `ok:` line per mkv with a non-zero cue count. Then open that title in the app and seek to the middle. It must land near where you clicked.

- [ ] **Step 6: Commit**

```bash
git add scripts/index-mkv.mjs package.json
git commit -m "$(cat <<'EOF'
feat(scripts): build mkv seek indexes where bandwidth is free

Finding Matroska cues means reading the file, so the job moves off the
deployment and onto a machine that can afford the reads. It posts the
finished index back, and playback seeks from the catalog.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Page the scan

The scan is sequential because TMDb rate-limits and because concurrent episodes of one series race to create duplicate parents. A folder large enough to exceed a serverless function's 300 s limit must therefore degrade into more calls, not a failure. Building the cursor now avoids retrofitting it against a half-imported library later.

**Files:**
- Create: `lib/library/scan-page.js`
- Test: `lib/library/scan-page.test.js`
- Modify: `app/api/media/scan/route.js`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `PAGE_SIZE` -> `number`
  - `takePage(pending, cursor, pageSize)` -> `{ page: File[], nextCursor: string | null }` where `cursor` is a `driveFileId` or `null`

- [ ] **Step 1: Write the failing test**

```javascript
// node lib/library/scan-page.test.js
import assert from 'node:assert/strict';
import { takePage } from './scan-page.js';

const files = [
  { driveFileId: 'a', name: 'A.mp4' },
  { driveFileId: 'b', name: 'B.mp4' },
  { driveFileId: 'c', name: 'C.mp4' },
  { driveFileId: 'd', name: 'D.mp4' },
];

// A first call with no cursor starts at the beginning and reports where to
// resume.
{
  const { page, nextCursor } = takePage(files, null, 2);
  assert.deepEqual(page.map((f) => f.driveFileId), ['a', 'b']);
  assert.equal(nextCursor, 'b');
  console.log('ok: the first page starts at the beginning');
}

// Resuming picks up after the cursor, never repeating it.
{
  const { page, nextCursor } = takePage(files, 'b', 2);
  assert.deepEqual(page.map((f) => f.driveFileId), ['c', 'd']);
  assert.equal(nextCursor, null);
  console.log('ok: resuming continues after the cursor');
}

// The last page reports no next cursor, which is how the caller stops.
{
  const { page, nextCursor } = takePage(files, 'c', 5);
  assert.deepEqual(page.map((f) => f.driveFileId), ['d']);
  assert.equal(nextCursor, null);
  console.log('ok: the final page ends the walk');
}

// A cursor naming a file that is gone restarts rather than silently importing
// nothing: files leave the pending list as they are imported, and a stale
// cursor must not strand the remainder.
{
  const { page, nextCursor } = takePage(files, 'zzz', 2);
  assert.deepEqual(page.map((f) => f.driveFileId), ['a', 'b']);
  assert.equal(nextCursor, 'b');
  console.log('ok: an unknown cursor restarts the walk');
}

// An empty pending list is finished, not paged.
{
  const { page, nextCursor } = takePage([], null, 2);
  assert.deepEqual(page, []);
  assert.equal(nextCursor, null);
  console.log('ok: nothing pending means nothing to resume');
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test lib/library/scan-page.test.js`

Expected: FAIL with `Cannot find module ... scan-page.js`.

- [ ] **Step 3: Write the implementation**

```javascript
// lib/library/scan-page.js — splits a scan into runs a function can finish.
//
// Matching is sequential by necessity: TMDb rate-limits, and two episodes of
// one series matched at once race to create duplicate parent documents. A
// folder can therefore grow past what a single invocation has time for, and
// when it does the scan should take more calls rather than fail on the last
// file. The panel drives the walk, calling again while a cursor comes back.

// Small enough that a page finishes well inside a function's time limit even
// when every file needs a TMDb round trip, large enough that a modest library
// imports in one call.
export const PAGE_SIZE = 25;

/**
 * The next run of pending files, and where to resume after it.
 *
 * `cursor` is the driveFileId of the last file of the previous page. A cursor
 * naming a file no longer pending restarts from the beginning: imported files
 * drop out of the pending list between calls, so a cursor pointing at one is
 * ordinary rather than exceptional, and stranding the remainder would be worse
 * than repeating the diff.
 */
export function takePage(pending, cursor = null, pageSize = PAGE_SIZE) {
  const at = cursor ? pending.findIndex((file) => file.driveFileId === cursor) : -1;
  const start = at === -1 ? 0 : at + 1;

  const page = pending.slice(start, start + pageSize);
  const done = start + page.length >= pending.length;

  return {
    page,
    nextCursor: done || page.length === 0 ? null : page[page.length - 1].driveFileId,
  };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test lib/library/scan-page.test.js`

Expected: PASS, five `ok:` lines.

- [ ] **Step 5: Wire it into the scan route**

In `app/api/media/scan/route.js`, add to the imports:

```javascript
import { takePage } from '@/lib/library/scan-page.js';
```

After the line that computes `pending` (currently the `files.filter(...)` call), insert:

```javascript
  // A large folder cannot be matched in one invocation, so the panel walks it
  // a page at a time and this route reports where to resume.
  const cursor = new URL(request.url).searchParams.get('cursor');
  const { page, nextCursor } = takePage(pending, cursor);
```

Change the matching loop to iterate `page` instead of `pending`, and add `nextCursor` and `remaining` to the response body the handler already returns:

```javascript
    nextCursor,
    remaining: pending.length - page.length,
```

- [ ] **Step 6: Verify by hand**

Run: `npm run dev`

```javascript
await (await fetch('/api/media/scan', { method: 'POST' })).json()
```

Expected: an import result with `nextCursor` — `null` if your folder holds 25 or fewer unimported videos, otherwise a Drive file id. If it is non-null, call again with `?cursor=<that value>` and confirm the second page imports different files.

- [ ] **Step 7: Run the whole suite and commit**

Run: `npm test`

```bash
git add lib/library/scan-page.js lib/library/scan-page.test.js app/api/media/scan/route.js
git commit -m "$(cat <<'EOF'
feat(scan): import a large folder across several calls

Matching has to stay sequential for TMDb's rate limit and for series
parents, so a big folder eventually outruns one invocation. Report where
to resume instead of failing on whatever file the clock lands on.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Manual catalog edits

Filename parsing gets some titles wrong, and TMDb has no entry for a home video. The panel needs to correct a match and to build a series out of loose files by hand.

**Files:**
- Create: `lib/library/edit.js`
- Test: `lib/library/edit.test.js`
- Modify: `app/api/media/[id]/route.js` (add `PATCH`)
- Create: `app/api/media/[id]/episodes/route.js`
- Create: `app/api/episode/[id]/route.js`

**Interfaces:**
- Consumes: `MediaStatus`, `mediaCollection()`, `episodeCollection()`
- Produces:
  - `validateMediaPatch(patch, current, episodeCount)` -> `{ ok: true, update }` or `{ ok: false, error }`
  - `PATCH /api/media/[id]` body `{ title?, year?, tmdbId?, posterPath?, description?, type? }`
  - `POST /api/media/[id]/episodes` body `{ driveFileId, season, episode, title?, size? }`
  - `PATCH /api/episode/[id]` body `{ season?, episode?, title? }`, and `DELETE`

- [ ] **Step 1: Write the failing test**

```javascript
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test lib/library/edit.test.js`

Expected: FAIL with `Cannot find module ... edit.js`.

- [ ] **Step 3: Write the implementation**

```javascript
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test lib/library/edit.test.js`

Expected: PASS, eight `ok:` lines.

- [ ] **Step 5: Add `PATCH` to the media route**

In `app/api/media/[id]/route.js`, add to the imports:

```javascript
import { validateMediaPatch } from '@/lib/library/edit.js';
```

and append this handler:

```javascript
export async function PATCH(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;

  let _id;
  try {
    _id = new ObjectId(id);
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }

  let patch;
  try {
    patch = await request.json();
  } catch (err) {
    return Response.json({ error: 'Expected a JSON body' }, { status: 400 });
  }

  const media = await mediaCollection();
  const current = await media.findOne({ _id });
  if (!current) return Response.json({ error: 'Not found' }, { status: 404 });

  const episodes = await episodeCollection();
  const episodeCount = await episodes.countDocuments({ mediaId: _id });

  const checked = validateMediaPatch(patch, current, episodeCount);
  if (!checked.ok) return Response.json({ error: checked.error }, { status: 400 });

  await media.updateOne({ _id }, { $set: checked.update });
  return Response.json({ ok: true, ...checked.update });
}
```

Ensure `ObjectId`, `requireDeviceOrSession`, `mediaCollection`, and `episodeCollection` are imported in that file; add whichever are missing.

- [ ] **Step 6: Write the episode-assignment route**

```javascript
// POST /api/media/[id]/episodes — files the panel could not place on its own.
//
// A folder of loose files rarely parses cleanly into seasons and episodes, so
// the panel lets a person say which title a file belongs to and where in it.
// The file is already in Drive; only the catalog changes here.

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function POST(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;

  let mediaId;
  try {
    mediaId = new ObjectId(id);
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }

  let body;
  try {
    body = await request.json();
  } catch (err) {
    return Response.json({ error: 'Expected a JSON body' }, { status: 400 });
  }

  const { driveFileId, season, episode, title = null, size = 0 } = body ?? {};

  if (!driveFileId || typeof driveFileId !== 'string') {
    return Response.json({ error: 'driveFileId is required' }, { status: 400 });
  }
  if (!Number.isInteger(season) || season < 0) {
    return Response.json({ error: 'season must be a whole number' }, { status: 400 });
  }
  if (!Number.isInteger(episode) || episode < 0) {
    return Response.json({ error: 'episode must be a whole number' }, { status: 400 });
  }

  const media = await mediaCollection();
  const parent = await media.findOne({ _id: mediaId });
  if (!parent) return Response.json({ error: 'Not found' }, { status: 404 });
  if (parent.type !== 'series') {
    return Response.json({ error: 'Only a series can hold episodes' }, { status: 400 });
  }

  const episodes = await episodeCollection();

  // The same file assigned twice is a mis-click, not a second episode.
  const already = await episodes.findOne({ driveFileId });
  if (already) {
    return Response.json({ error: 'That file is already an episode' }, { status: 409 });
  }

  const doc = {
    mediaId,
    season,
    episode,
    title,
    driveFileId,
    size,
    createdAt: new Date(),
  };
  const { insertedId } = await episodes.insertOne(doc);

  return Response.json({ ok: true, id: insertedId.toString() }, { status: 201 });
}
```

- [ ] **Step 7: Write the episode edit route**

```javascript
// PATCH and DELETE /api/episode/[id] — corrections to one episode.
//
// Season and episode numbers are the part filename parsing gets wrong most
// often, and an episode filed against the wrong show has to be removable
// without touching the file in Drive.

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { episodeCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function parseId(id) {
  try {
    return new ObjectId(id);
  } catch (err) {
    return null;
  }
}

export async function PATCH(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  const _id = parseId(id);
  if (!_id) return Response.json({ error: 'Invalid id' }, { status: 400 });

  let body;
  try {
    body = await request.json();
  } catch (err) {
    return Response.json({ error: 'Expected a JSON body' }, { status: 400 });
  }

  const update = {};
  if ('season' in body) {
    if (!Number.isInteger(body.season) || body.season < 0) {
      return Response.json({ error: 'season must be a whole number' }, { status: 400 });
    }
    update.season = body.season;
  }
  if ('episode' in body) {
    if (!Number.isInteger(body.episode) || body.episode < 0) {
      return Response.json({ error: 'episode must be a whole number' }, { status: 400 });
    }
    update.episode = body.episode;
  }
  if ('title' in body) update.title = body.title;

  if (Object.keys(update).length === 0) {
    return Response.json({ error: 'Nothing to change' }, { status: 400 });
  }

  const episodes = await episodeCollection();
  const result = await episodes.updateOne({ _id }, { $set: update });
  if (result.matchedCount === 0) return Response.json({ error: 'Not found' }, { status: 404 });

  return Response.json({ ok: true, ...update });
}

export async function DELETE(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  const _id = parseId(id);
  if (!_id) return Response.json({ error: 'Invalid id' }, { status: 400 });

  const episodes = await episodeCollection();
  const result = await episodes.deleteOne({ _id });
  if (result.deletedCount === 0) return Response.json({ error: 'Not found' }, { status: 404 });

  // The file itself stays in Drive. Removing it from the catalog puts it back
  // in the scan's pending list, which is where a misfiled episode belongs.
  return Response.json({ ok: true });
}
```

- [ ] **Step 8: Verify by hand**

Run: `npm run dev`

From a signed-in console, against real ids:

```javascript
// Rename a title.
await (await fetch('/api/media/REAL_ID/', { method: 'PATCH',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ title: 'Renamed' }) })).json()

// Refuse an unknown field.
await (await fetch('/api/media/REAL_ID/', { method: 'PATCH',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ nonsense: 1 }) })).json()
```

Expected: the first returns `{ ok: true, title: 'Renamed' }`; the second returns a 400 naming `nonsense`.

- [ ] **Step 9: Run the whole suite and commit**

Run: `npm test`

```bash
git add lib/library/edit.js lib/library/edit.test.js app/api/media/'[id]'/route.js app/api/media/'[id]'/episodes/route.js app/api/episode/'[id]'/route.js
git commit -m "$(cat <<'EOF'
feat(api): let a person correct what matching got wrong

Parsing misreads filenames and TMDb has no entry for a home video, so
the catalog needs an editable path. Refuse the one change that would
strand data: a series with episodes cannot quietly become a movie.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: The library management screen

**Files:**
- Create: `app/admin/library/page.js`
- Create: `app/admin/library/LibraryEditor.js`

**Interfaces:**
- Consumes: `GET /api/media/list`, `PATCH /api/media/[id]`, `POST /api/media/[id]/episodes`, `PATCH` and `DELETE /api/episode/[id]`, `POST /api/media/scan?cursor=`
- Produces: nothing programmatic

- [ ] **Step 1: Read the existing admin pages for their conventions**

Run: `cat app/admin/usage/page.js`

Match how it handles the session check, page shell, and Tailwind classes. Do not invent a second style of admin page.

- [ ] **Step 2: Write the server page**

```javascript
// /admin/library — where a person fixes what the automatic pipeline got wrong.
//
// Scanning matches most files and misreads some. This screen is for the rest:
// correcting a title or TMDb id, and filing loose files into a series with a
// season and episode number.

import { redirect } from 'next/navigation';

import { isAdmin } from '@/lib/admin-auth.js';

import LibraryEditor from './LibraryEditor.js';

export const dynamic = 'force-dynamic';

export default async function LibraryPage() {
  if (!(await isAdmin())) redirect('/login');

  return (
    <main className="mx-auto max-w-5xl p-6">
      <h1 className="mb-6 text-2xl font-semibold">Library</h1>
      <LibraryEditor />
    </main>
  );
}
```

- [ ] **Step 3: Write the client editor**

```javascript
'use client';

// The interactive half of /admin/library.
//
// Scanning is driven from here rather than from a cron: the route pages, so
// this component keeps calling with the cursor it was handed until none comes
// back, showing progress as it goes.

import { useCallback, useEffect, useState } from 'react';

export default function LibraryEditor() {
  const [items, setItems] = useState([]);
  const [scanning, setScanning] = useState(false);
  const [scanNote, setScanNote] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const res = await fetch('/api/media/list');
      if (!res.ok) throw new Error(`list -> ${res.status}`);
      const body = await res.json();
      setItems(body.items ?? []);
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function scan() {
    setScanning(true);
    setError('');
    let cursor = null;
    let imported = 0;

    try {
      do {
        const url = cursor
          ? `/api/media/scan?cursor=${encodeURIComponent(cursor)}`
          : '/api/media/scan';
        const res = await fetch(url, { method: 'POST' });
        if (!res.ok) throw new Error(`scan -> ${res.status}`);
        const body = await res.json();
        imported += body.imported?.length ?? 0;
        cursor = body.nextCursor;
        setScanNote(`Imported ${imported} so far, ${body.remaining ?? 0} left.`);
      } while (cursor);

      setScanNote(`Imported ${imported}.`);
      await load();
    } catch (err) {
      setError(err.message);
    } finally {
      setScanning(false);
    }
  }

  async function patch(id, body) {
    setError('');
    const res = await fetch(`/api/media/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const result = await res.json();
    if (!res.ok) {
      setError(result.error ?? `patch -> ${res.status}`);
      return;
    }
    await load();
  }

  const unmatched = items.filter((item) => item.status === 'unmatched');

  return (
    <div className="space-y-8">
      <section>
        <button
          type="button"
          onClick={scan}
          disabled={scanning}
          className="rounded bg-white px-4 py-2 font-medium text-black disabled:opacity-50"
        >
          {scanning ? 'Scanning...' : 'Scan Drive folder'}
        </button>
        {scanNote ? <p className="mt-2 text-sm opacity-70">{scanNote}</p> : null}
        {error ? <p className="mt-2 text-sm text-red-400">{error}</p> : null}
      </section>

      <section>
        <h2 className="mb-3 text-lg font-medium">
          Needs attention ({unmatched.length})
        </h2>
        {unmatched.length === 0 ? (
          <p className="text-sm opacity-70">Everything matched.</p>
        ) : (
          <ul className="space-y-3">
            {unmatched.map((item) => (
              <UnmatchedRow key={item.id} item={item} onPatch={patch} />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function UnmatchedRow({ item, onPatch }) {
  const [title, setTitle] = useState(item.title ?? '');
  const [tmdbId, setTmdbId] = useState('');

  return (
    <li className="rounded border border-white/15 p-3">
      <p className="mb-2 font-mono text-xs opacity-60">{item.filename}</p>
      <div className="flex flex-wrap gap-2">
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Title"
          className="rounded bg-black/40 px-2 py-1"
        />
        <input
          value={tmdbId}
          onChange={(e) => setTmdbId(e.target.value)}
          placeholder="TMDb id"
          inputMode="numeric"
          className="w-28 rounded bg-black/40 px-2 py-1"
        />
        <button
          type="button"
          onClick={() =>
            onPatch(item.id, {
              title,
              ...(tmdbId ? { tmdbId: Number(tmdbId) } : {}),
            })
          }
          className="rounded bg-white px-3 py-1 text-black"
        >
          Save
        </button>
        <button
          type="button"
          onClick={() => onPatch(item.id, { type: item.type === 'movie' ? 'series' : 'movie' })}
          className="rounded border border-white/25 px-3 py-1"
        >
          Make {item.type === 'movie' ? 'series' : 'movie'}
        </button>
      </div>
    </li>
  );
}
```

- [ ] **Step 4: Verify by hand**

Run: `npm run dev`

Open `http://localhost:3000/admin/library`. Confirm: the scan button runs and reports a count; an unmatched title appears with its filename; saving a title reloads the list with the new name; the "Make series" button flips the type; and an invalid edit shows the server's error rather than failing silently.

- [ ] **Step 5: Commit**

```bash
git add app/admin/library
git commit -m "$(cat <<'EOF'
feat(admin): a screen for the titles matching could not place

Scanning handles most of a folder and misreads the rest, and until now
the rest had nowhere to go. Correct a title or a TMDb id here, and drive
the paged scan from a button that reports its progress.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Browser direct play

**Skip this task if Task 1 recorded `blocked`.** Everything here depends on the browser being able to fetch Drive bytes cross-origin.

A `<video src>` cannot carry an `Authorization` header. A Service Worker can: the page points the element at an app-local path, and the worker reissues each range request to Google with the header attached. Native seeking and buffering keep working, and the token never sits in page JavaScript.

**Files:**
- Create: `lib/drive-url.js`
- Create: `public/drive-sw.js`
- Modify: `app/movies/[id]/page.js`, `app/series/[id]/page.js`
- Modify: `app/api/media/play-url/[id]/route.js`

**Interfaces:**
- Consumes: `GET /api/drive/token`
- Produces:
  - `driveBytesUrl(driveFileId)` -> `string` (the Google URL)
  - `localDrivePath(driveFileId)` -> `string` (the `/drive/<id>` path the worker intercepts)

- [ ] **Step 1: Write the URL helper**

```javascript
// lib/drive-url.js — the two forms of a file's address, in one place.
//
// The player asks for the local path; the Service Worker turns it into the
// Google one. Keeping both here means the worker and the pages cannot drift
// into disagreeing about the shape of either.

/** Where Drive actually serves the bytes. */
export function driveBytesUrl(driveFileId) {
  return `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(driveFileId)}?alt=media`;
}

/** The same file as a same-origin path, which is all a <video> element can use. */
export function localDrivePath(driveFileId) {
  return `/drive/${encodeURIComponent(driveFileId)}`;
}
```

- [ ] **Step 2: Write the Service Worker**

```javascript
// public/drive-sw.js — turns /drive/<fileId> into an authenticated Drive read.
//
// A <video> element cannot send an Authorization header, and Drive will not
// accept the token any other way, so the element points at a same-origin path
// and this worker performs the real request. Native seeking survives, because
// the browser still issues ordinary range requests and still receives 206s.
//
// The token lives here rather than in the page: nothing on the page needs it,
// and a worker's scope is a smaller place for a credential to sit.

const DRIVE_PATH = /^\/drive\/([^/?#]+)$/;

let token = null;
let expiresAt = 0;

// The same margin lib/drive-token.js uses. A range request issued at the very
// edge of a token's life can arrive after it has expired, and the player reads
// that as a broken seek rather than as something to retry.
const REFRESH_MARGIN_MS = 5 * 60 * 1000;

async function currentToken() {
  if (token && Date.now() < expiresAt - REFRESH_MARGIN_MS) return token;

  const res = await fetch('/api/drive/token', { credentials: 'include' });
  if (!res.ok) throw new Error(`token -> ${res.status}`);

  const body = await res.json();
  token = body.token;
  expiresAt = body.expiresAt;
  return token;
}

async function proxyToDrive(request, driveFileId) {
  const headers = new Headers();
  headers.set('Authorization', `Bearer ${await currentToken()}`);

  const range = request.headers.get('Range');
  if (range) headers.set('Range', range);

  const url = `https://www.googleapis.com/drive/v3/files/${driveFileId}?alt=media`;
  let res = await fetch(url, { headers });

  // A token can expire between the check above and Drive reading it. One
  // forced refresh distinguishes that from a real authorisation failure.
  if (res.status === 401) {
    token = null;
    headers.set('Authorization', `Bearer ${await currentToken()}`);
    res = await fetch(url, { headers });
  }

  return res;
}

self.addEventListener('install', (event) => {
  event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;

  const match = DRIVE_PATH.exec(url.pathname);
  if (!match) return;

  event.respondWith(
    proxyToDrive(event.request, match[1]).catch(
      (err) => new Response(`Drive read failed: ${err.message}`, { status: 502 })
    )
  );
});
```

- [ ] **Step 3: Register the worker and point the player at it**

In whichever client component renders the `<video>` element for `app/movies/[id]/page.js` and `app/series/[id]/page.js`, register the worker before playing and use the local path as the source:

```javascript
  useEffect(() => {
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.register('/drive-sw.js').catch((err) => {
      console.error('drive worker registration failed', err);
    });
  }, []);
```

```javascript
  <video src={localDrivePath(driveFileId)} controls autoPlay className="w-full" />
```

importing `localDrivePath` from `@/lib/drive-url.js`. The element must not be rendered until `navigator.serviceWorker.controller` is set, or the first request escapes the worker and 404s; gate it on a `ready` state set from the registration's `.then()` plus a `controllerchange` listener.

- [ ] **Step 4: Collapse `play-url` to always-direct**

In `app/api/media/play-url/[id]/route.js`, delete the `'browser-client'` branch of `proxyReason()` and the CORS comment block at the top of the file that asserted browsers cannot direct-play. Replace that comment with what Task 1 actually found. Leave the `mode` field in the response so a future fallback needs no client change.

- [ ] **Step 5: Verify by hand**

Run: `npm run dev`

Open a movie. Confirm all of: it plays; DevTools' Network tab shows requests to `googleapis.com` served by the worker and **no** requests to `/api/media/stream/`; dragging the scrubber to the middle seeks and resumes; and Application -> Service Workers shows `drive-sw.js` activated.

Leave it playing past the token's expiry — or temporarily set `REFRESH_MARGIN_MS` in the worker to `59 * 60 * 1000` to force a refresh within a minute — and confirm playback does not stall.

- [ ] **Step 6: Commit**

```bash
git add lib/drive-url.js public/drive-sw.js app/movies app/series app/api/media/'[id]' app/api/media/play-url
git commit -m "$(cat <<'EOF'
feat(player): read a film from Drive in the browser too

A video element cannot send the header Drive requires, so it asks a
same-origin path and a worker performs the real, authenticated read.
Seeking stays native and the last reason to proxy bytes disappears.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Move to Atlas and deploy to Vercel

Infrastructure, not code. The app runs on both hosts at the end of this task.

**Files:**
- Modify: `README.md` (deployment section)

**Interfaces:**
- Consumes: everything above
- Produces: a working `*.vercel.app` deployment

- [ ] **Step 1: Install the Vercel CLI**

```bash
npm i -g vercel
```

- [ ] **Step 2: Link the project**

```bash
vercel link
```

Answer the prompts to create or select a project in your personal scope. **Hobby is non-commercial-use only**; a personal media library qualifies, sharing access with others may not.

- [ ] **Step 3: Provision MongoDB Atlas through the Marketplace**

```bash
vercel integration add mongodb-atlas --yes --no-claim
```

If the CLI hands off to a browser to finish authorisation, complete it there and then continue. This injects the connection string as an environment variable on the linked project.

- [ ] **Step 4: Reconcile the connection variable name**

Run: `vercel env ls`

The integration names its variable on its own terms; `lib/db.js` expects `MONGODB_URI`. Check which name arrived, and if it differs, add `MONGODB_URI` pointing at the same value rather than renaming reads across the codebase.

- [ ] **Step 5: Add the remaining variables**

```bash
vercel env add GOOGLE_CLIENT_ID production
vercel env add GOOGLE_CLIENT_SECRET production
vercel env add GOOGLE_REFRESH_TOKEN production
vercel env add GOOGLE_DRIVE_FOLDER_ID production
vercel env add TMDB_API_KEY production
vercel env add ADMIN_PASSWORD production
vercel env add KDRIVE_DEVICE_KEY production
```

Take each value from the VPS's current environment. Repeat for `preview` if you want preview deployments to work.

- [ ] **Step 6: Migrate the data**

```bash
mongodump --uri="<current VPS MONGODB_URI>" --out=./dump
mongorestore --uri="<Atlas MONGODB_URI>" --nsInclude='<dbname>.*' ./dump
```

Then create the indexes on the new cluster:

```bash
MONGODB_URI="<Atlas MONGODB_URI>" npm run migrate
```

Verify:

```bash
MONGODB_URI="<Atlas MONGODB_URI>" npm run test:db
```

Expected: it connects and reports the collections with their document counts matching the VPS.

- [ ] **Step 7: Deploy**

```bash
vercel --prod
```

- [ ] **Step 8: Verify the deployment**

On the `*.vercel.app` URL: sign in; `/movies` and `/series` list the same titles as the VPS; `/admin/library` scans; a title plays end to end.

Then open Vercel's project dashboard and read the Usage panel. **Data transfer after watching a full film must be a few megabytes, not gigabytes.** If it is gigabytes, bytes are still crossing a function — stop and find which route before continuing.

- [ ] **Step 9: Update the README and commit**

Replace the Docker and VPS deployment instructions in `README.md` with the Vercel and Atlas steps above, and note that `npm run index:mkv` runs locally rather than on the host.

```bash
git add README.md
git commit -m "$(cat <<'EOF'
docs: describe the Vercel and Atlas deployment

The container and VPS instructions describe a host this app no longer
needs. Write down what actually deploys it, including the mkv indexing
step that stays on a local machine.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Repoint the TV client

**Files:**
- Modify: the base-URL constant in `android-tv/app/` (find it in Step 1)

**Interfaces:**
- Consumes: the deployed Vercel URL
- Produces: nothing

- [ ] **Step 1: Find the base URL**

Run: `grep -rn "https\?://" android-tv/app/src --include=*.kt --include=*.xml | grep -v schemas.android.com`

- [ ] **Step 2: Change it to the Vercel URL**

Replace the VPS host with the `*.vercel.app` host. Change nothing else — the client already plays direct (commit `bbc1083`) and already sends the token as a header (commit `293b420`).

- [ ] **Step 3: Build and verify on the device**

```bash
npm run apk:build
```

Install, open, and play a film. Confirm it starts and that seeking works.

- [ ] **Step 4: Commit**

```bash
git add android-tv
git commit -m "$(cat <<'EOF'
fix(tv): point the client at the Vercel deployment

The VPS is going away and the client already reads its bytes from
Drive, so the host it asks for metadata is the only thing that changes.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Delete the proxy and the VPS scaffolding

**Do this only after Tasks 11 and 12 are verified working.** This is the step with no easy undo short of a revert.

**Files:**
- Delete: `app/api/media/stream/`, `app/api/media/seek-index/`, `app/admin/monitor/`, `lib/monitor/`, `app/api/admin/metrics/`, `app/api/admin/playback-report/`, `Dockerfile`, `docker-compose.yml`, `deploy/`, `scripts/probe-drive-cors.mjs`
- Modify: any file importing from those paths

**Interfaces:**
- Consumes: nothing
- Produces: nothing

- [ ] **Step 1: Find every reference before deleting anything**

```bash
grep -rn "media/stream\|seek-index\|lib/monitor\|admin/monitor\|playback-report\|admin/metrics" app lib scripts android-tv middleware.js next.config.mjs package.json README.md
```

Read every hit. Anything under `app/movies`, `app/series`, or the TV client still pointing at `/api/media/stream/` means Task 10 or Task 12 is incomplete — fix that first.

- [ ] **Step 2: Delete**

```bash
git rm -r app/api/media/stream app/api/media/seek-index app/admin/monitor lib/monitor app/api/admin/metrics app/api/admin/playback-report deploy
git rm Dockerfile docker-compose.yml scripts/probe-drive-cors.mjs
```

**If Task 1 recorded `blocked`, do not delete `app/api/media/stream`** — browsers still need it. Delete everything else on this list.

- [ ] **Step 3: Remove the dangling references**

Fix any import or link the Step 1 grep found: a nav entry pointing at `/admin/monitor`, a `middleware.js` matcher for a deleted route, monitor-related npm scripts in `package.json`.

- [ ] **Step 4: Verify nothing broke**

```bash
npm test
npm run build
```

Expected: tests pass and the build completes with no unresolved import.

Then `vercel --prod` and play a film on the deployed URL one more time.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor: remove the byte proxy and the VPS it ran on

Players read from Drive themselves now, so the proxy route serves
nothing, and the container, deploy scripts, and host telemetry describe
a machine that is being cancelled.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Cancel the VPS**

Outside this repository. Confirm the Vercel deployment has served the library for a few days before doing it — a cancelled host is not recoverable, and the usage figure from Task 11 Step 8 is the evidence that it is no longer needed.

---

## Self-Review

**Spec coverage.** Architecture and placement: Tasks 4, 6, 11. Free-tier budget: verified in Task 11 Step 8. Session auth: Task 2. Token endpoint: Tasks 3, 4. Playback including the browser transport and the open CORS question: Tasks 1, 10. Ingest, scan paging, and the panel edits: Tasks 5, 6, 7, 8, 9. Progress: unchanged, no task needed. Data and the `seekIndex` field: Task 5. Removals: Task 13. Error handling: covered inside the routes in Tasks 4, 5, 8 and the worker in Task 10. Testing: Tasks 3, 7, 8 carry the new tests; the manual transfer check is Task 11 Step 8. Migration order: Tasks 1 through 13 follow the spec's sequence. Prerequisites: Task 11 Steps 1 through 5.

One spec item is deliberately unclaimed by a task: the debounce on progress writes. `/api/media/progress` already exists and its call frequency is set by the player component, so it is verified rather than built — check the interval while doing Task 10 Step 5 and adjust only if it writes more often than every 30 seconds.

**Type consistency.** `takePage(pending, cursor, pageSize)` returns `{ page, nextCursor }` in Task 7 and is consumed with those names in Task 9's scan loop. `validateMediaPatch(patch, current, episodeCount)` returns `{ ok, update }` or `{ ok, error }` in Task 8 and is consumed with those names in the same task's route. `cachedAccessToken(mint, now)` returns `{ token, expiresAt }` in Task 3 and is consumed under those names in Task 4 and by the worker in Task 10. `localDrivePath` is defined in Task 10 Step 1 and used in Step 3 under the same name.
