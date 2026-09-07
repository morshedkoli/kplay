# Serverless kPlay: Vercel control plane, Drive direct play

**Date:** 2026-09-07
**Status:** Approved design, not yet implemented
**Supersedes the deployment model in:** `2026-08-20-media-server-backend-design.md`

## Problem

The VPS proxies every video byte: Drive -> VPS -> player. A film the server
never needs to inspect consumes the server's bandwidth twice over, and the
hosting plan's data cap is the binding constraint on how much can be watched.

`app/api/media/play-url/[id]/route.js` already solves this for the Android TV
client, which reads bytes straight from Drive. Browsers still proxy, and the
VPS still exists to serve them.

## Goal

Retire the VPS. Host the control plane on Vercel's free (Hobby) plan, serve
every video byte directly from Google Drive to the player, and keep the app a
single-user personal media library.

Success means: a full film plays end to end while Vercel's usage dashboard
shows no measurable data transfer, and the VPS can be cancelled.

## Non-goals

- Multi-user accounts, per-user libraries, or sharing.
- Server-side transcoding. The library remains direct-play only.
- Replacing Drive as the storage backend.
- Commercial use. Vercel Hobby forbids it; this is a personal library.

## Architecture

Three parties. Video bytes touch exactly one.

```
                  +-----------------------------+
   browser / TV ->|  Vercel (Hobby)             |
                  |  * library + admin panel UI |
                  |  * /api/media/*   (JSON)    |
                  |  * /api/drive/token         |
                  +-------+---------------------+
                          | metadata only (KB)
              +-----------v------+   +------------------+
              | MongoDB Atlas M0 |   | TMDb API         |
              +------------------+   +------------------+

   browser / TV ==========================> Google Drive
        video bytes, Range requests, Authorization: Bearer
```

### Placement

| Concern | Home | Rationale |
|---|---|---|
| Library and admin UI | Vercel | Static bundles, negligible transfer |
| Catalog API | Vercel functions | JSON responses of a few KB |
| Drive access tokens | Vercel function | Mints a 1-hour token per play |
| Catalog data | MongoDB Atlas M0 | `lib/models/media.js` works unchanged |
| Poster images | `image.tmdb.org`, hotlinked | No bytes and no storage of our own |
| Video bytes | Drive to player, direct | The entire point of the redesign |
| mkv indexing, ffmpeg | Local `scripts/` on the developer's PC | Reads bytes; cannot run on Hobby |

### Free-tier budget

| Hobby limit | Expected use |
|---|---|
| 100 GB/month data transfer | Under 1 GB: UI bundles and JSON |
| 1M function invocations/month | ~3 per play, plus progress writes |
| 300 s function duration | Only the Drive scan approaches this; it pages |
| 2 cron jobs | None used; scanning is triggered from the panel |

Poster images are served by TMDb's CDN and deliberately bypass Next.js image
optimization, whose Hobby transformation cap is a real ceiling and which buys
nothing over TMDb's own pre-sized variants.

## Components

### 1. Session auth

`GOOGLE_REFRESH_TOKEN` already exists as an environment variable
(`lib/gdrive.js`), obtained once out of band. Drive credentials therefore need
no user-facing flow.

What the user asked for -- signing in once rather than on every app open -- is a
session concern only. The existing `ADMIN_PASSWORD` login stays; its cookie
lifetime extends from its current session length to one year, signed,
`httpOnly`, `SameSite=Lax`, `Secure`.

The Android TV client keeps the `KDRIVE_DEVICE_KEY` header. `lib/auth.js`
keeps `hasDeviceKey()` and `requireDeviceOrSession()` as they are.

**Rejected alternative:** a full Google OAuth authorization-code flow with a
consent screen, a `/api/auth/google/callback` route, and an encrypted refresh
token in Mongo. For a single-user app it terminates at the same credential the
environment variable already holds, while adding a route, a secret store, and a
dependency on keeping the OAuth app's publishing status out of *Testing* (where
restricted-scope refresh tokens expire after seven days). Revisit only if the
library ever gains a second user.

### 2. Token endpoint

```
GET /api/drive/token
  auth: session cookie, or KDRIVE_DEVICE_KEY header
  200:  { token, expiresAt }
  headers: Cache-Control: private, no-store
```

Wraps the existing `getAccessToken()` in `lib/gdrive.js`. Caches the access
token in module scope and refreshes when within five minutes of expiry.

The response body carries a live credential. It must never reach a shared
cache, a disk cache, or a browser's back-forward store.

### 3. Playback

Both clients follow the same sequence:

1. `GET /api/media/[id]` -> title, `driveFileId`, `contentType`, `size`,
   `seekIndex` when one exists.
2. `GET /api/drive/token` -> `{ token, expiresAt }`.
3. Player fetches
   `https://www.googleapis.com/drive/v3/files/{driveFileId}?alt=media`
   with `Range` and `Authorization: Bearer {token}`.
4. Before `expiresAt`, refetch step 2 and swap the header without interrupting
   playback.

The token travels as a header, never as `?access_token=`: Google no longer
honours that parameter on this endpoint and answers with its automated-traffic
page, which a player sees as a load failure on every range request.

Step 4 is already implemented in the TV client (commit `bbc1083`).

**Browser transport.** A bare `<video src>` cannot attach an `Authorization`
header. A Service Worker intercepts requests to an app-local path
(`/drive/{driveFileId}`) and reissues them to Google with the header and the
original `Range`. This preserves `<video src>` and native seeking, and keeps the
token out of reach of page JavaScript.

`play-url`'s `mode: 'direct' | 'proxy'` field is retained in the response shape
so a future fallback needs no client change, but it always answers `'direct'`
and no proxy remains to fall back to.

**Open question, resolved before anything else is built:** whether
`googleapis.com/drive/v3/files/{id}?alt=media` returns
`Access-Control-Allow-Origin` for a Bearer-authenticated cross-origin fetch.
The comment at `app/api/media/play-url/[id]/route.js:20-26` asserts it does not,
but that conclusion was drawn from the rejected `?access_token=` form. If the
assertion holds after retesting, browser playback stays proxy-shaped and the
browser half of this section is dropped; the TV path and every other section are
unaffected.

### 4. Ingest

Split by whether a job reads file bytes.

**Metadata-only, on Vercel.** `POST /api/media/scan` keeps its current logic:
list the Drive folder, diff against known `driveFileId`s, filename-parse,
TMDb-match, write documents. It stays sequential because TMDb rate-limits and
because concurrent episodes of one series race to create duplicate parent
documents.

A `?cursor=` parameter is added at implementation time rather than retrofitted:
the panel calls scan in pages and renders progress, so a folder that grows past
the 300 s function limit degrades into more calls instead of a failure.

**New panel capabilities:**

- `PATCH /api/media/[id]` -- set `tmdbId` by hand, correct title or year, change
  `type` between `movie` and `series`.
- `POST /api/media/[id]/episodes` and `PATCH /api/episode/[id]` -- assign an
  unmatched file to a series with a season and episode number. This backs the
  panel screen for structuring a folder of loose files into a series.
- Rematch, exposed as a button over the existing `rematchUnmatchedMovies`.

**Byte-reading, local.** `scripts/index-mkv.mjs` runs on the developer's PC when
.mkv titles are added. It authenticates with the same refresh token from a local
`.env`, pulls cue data over Range requests, and `PUT`s the finished index to
`/api/media/[id]/seek-index`, which stores it in Mongo. Playback then reads a
precomputed index. `lib/library/mkv-index.js` moves under `scripts/` largely
intact; `app/api/media/seek-index/` is deleted.

A library kept entirely in MP4 never needs this script.

### 5. Progress

`/api/media/progress` and `/api/media/watching` are unchanged. Writes are
debounced to at most one per 30 s of playback, which keeps a long viewing
session far below the invocation budget.

## Data

No schema change. `media` and `episode` keep their shapes from
`lib/models/media.js`. One field is added:

- `media.seekIndex` / `episode.seekIndex` -- optional, written by the local mkv
  script, read at playback.

Migration is `mongodump` from the VPS and `mongorestore` into Atlas.

## Removals

| Path | Reason |
|---|---|
| `app/api/media/stream/` | The proxy. Removing it is the point. |
| `app/api/media/seek-index/` | Replaced by a precomputed field |
| `app/admin/monitor/`, `lib/monitor/`, `app/api/admin/metrics/` | VPS telemetry with no VPS |
| `app/api/admin/playback-report/` | Same |
| `Dockerfile`, `docker-compose.yml`, `deploy/` | No container host |
| `lib/admin-auth.js` session length | Extended, not removed |

`app/api/media/[id]`, `list`, `progress`, `watching`, `scan`, `play-url`, and
all of `lib/library/` carry over.

## Error handling

- **Drive metadata read fails** -- 502 from the catalog route; the panel shows the
  title as unavailable rather than silently omitting it.
- **Token mint fails** -- 503 from `/api/drive/token`. There is no proxy fallback,
  so this surfaces to the player as a playback error with a retry.
- **Token expires mid-playback** -- the client refreshes ahead of expiry; a range
  request that 401s triggers an immediate refresh and one retry before failing.
- **Drive rate-limits (403 `userRateLimitExceeded`)** -- surfaced to the player.
  Google's per-file daily download cap is the ceiling on this design and cannot
  be worked around from the client.
- **Atlas M0 connection exhaustion** -- the Mongo client is reused across warm
  invocations; a cold start opening a fresh pool per request would exhaust M0's
  connection limit.

## Testing

Existing `lib/library/parse.test.js`, `match.test.js`, and `mkv-index.test.js`
stay green; the parsing logic does not change.

New tests:

- `/api/drive/token` returns a freshly minted token when the cached one is
  within the refresh window, and the cached one otherwise.
- A tampered session cookie is rejected.
- `PATCH /api/media/[id]` refuses a `type` change that would orphan episodes.
- The scan cursor resumes without reimporting or skipping a file.

Manual verification that the goal is met: watch a complete film, then confirm
Vercel's usage dashboard shows no measurable transfer for the period.

## Migration order

Each step leaves a working system, and stopping at any step is safe.

1. Retest the CORS assertion in section 3. Record the result; it decides whether
   browser direct play is in scope.
2. Add `/api/drive/token` and extend the session cookie lifetime, deployed on
   the existing VPS.
3. Migrate Mongo to Atlas, provisioned through the Vercel Marketplace so
   environment variables are injected into the linked project.
4. Deploy to Vercel alongside the running VPS. Verify the panel, scan, and match
   from the Vercel origin.
5. Add the browser Service Worker transport. Watch a full film with the proxy
   reachable but unused.
6. Repoint the Android TV client's base URL. It already plays direct; nothing
   else changes.
7. Delete the proxy and the removals listed above. Cancel the VPS.

## Costs

Vercel Hobby, MongoDB Atlas M0, and TMDb are all free at this scale. Drive
storage is the existing Google One subscription. The VPS is cancelled.

## Prerequisites

- Vercel CLI installed (`npm i -g vercel`) for linking the project, provisioning
  the Atlas integration, and pulling environment variables.
- Environment variables on Vercel: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
  `GOOGLE_REFRESH_TOKEN`, `GOOGLE_DRIVE_FOLDER_ID`, `TMDB_API_KEY`,
  `ADMIN_PASSWORD`, `KDRIVE_DEVICE_KEY`, `MONGODB_URI` (injected by the Atlas
  integration).
