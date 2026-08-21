# KDrive → Media Server: Backend + Google Drive Storage — Design

Status: approved for planning
Date: 2026-08-20

## Context

KDrive is currently a photo-backup web app: multi-cloud storage pool
(R2/B2/Storj/IDrive/Oracle/Filebase/MEGA/GDrive/OneDrive/Dropbox via
`rclone union`) with a Telegram/GramJS overflow fallback, MongoDB for
file manifests, and a Next.js 15 full-stack app (`/`, `/upload`,
`/storage`, `/admin/usage`).

This spec covers a full replacement of that product with a
Jellyfin-style movie/TV media server:

- Google Drive is the sole storage backend (multi-cloud pool and
  Telegram fallback are removed).
- Media is added through the app's own upload flow (not manual Drive
  folder placement).
- Metadata (posters, descriptions, season/episode structure) is
  auto-fetched from TMDb.
- Playback is direct-play only — the server proxies original file
  bytes from Drive with Range support; no server-side transcoding.
- MongoDB is retained for the library index, episode data, and watch
  progress. Auth/session plumbing is retained as-is.
- Single Google account / single storage pool. The app can still gate
  access with the existing login, but everyone shares one Drive and
  one library (no per-user Drive, no per-household isolation).

This is sub-project 1 of 3. Sub-project 2 (Android TV client) will be
specced separately once this backend's API surface exists to build
against. (A prior scope note in this repo mentioned an Android
scaffold parked in `_archive/` — out of scope here; the new TV client
is a fresh build against this API.)

## Out of scope for this spec

- Transcoding / adaptive bitrate streaming (HLS/DASH) — direct-play
  only for v1.
- Multi-user profiles or per-user Drive accounts.
- The Android TV client itself.
- Live TV, music, or any media type other than movies/TV series.

## Architecture

One process, one deploy — same shape as today's KDrive: a single
Next.js 15 app serves both the web UI and the API. Google Drive
replaces the rclone pool as the only storage backend. MongoDB keeps
all metadata; Drive holds only file bytes, addressed by Drive file ID.

```
Browser/TV client
   │  HTTP (session cookie / device key)
   ▼
Next.js app (app/, lib/, middleware.js)
   │                         │
   ▼                         ▼
MongoDB                  Google Drive API
(library index,          (file bytes only,
 episodes, progress,      addressed by fileId)
 users, sessions)
   ▲
   │
TMDb API (metadata lookup, called from lib/library/)
```

## Components

### `lib/gdrive.js` (new — replaces `lib/r2.js`, `lib/rclone-rc.js`, `lib/storage-router.js`, Telegram fallback module)

Thin wrapper over the Google Drive API v3, authenticated via a single
OAuth2 refresh token (or service account, whichever proves simpler
during setup — decided in the implementation plan) tied to the one
Drive account. Exposes:

- `uploadFile(stream, name, mimeType) → { driveFileId, size }`
- `getFileMetadata(driveFileId) → { size, mimeType, name }`
- `streamFile(driveFileId, rangeHeader) → { stream, contentRange, contentLength, status }`
- `deleteFile(driveFileId)`

No pool, no union, no fallback backend — a Drive API error surfaces
directly as an upload/stream failure.

### `lib/library/` (new)

- `parse.js` — parses an uploaded filename/folder name into a
  candidate title, year, and (for series) season/episode numbers,
  using Jellyfin-style naming conventions as the parsing target (e.g.
  `Title (Year).ext`, `Title/Season 01/Title S01E02.ext`).
- `tmdb.js` — thin TMDb API client: search by parsed title/year,
  fetch poster/description/cast, fetch season/episode metadata for
  series.
- `match.js` — orchestrates: takes a parsed name, calls TMDb, writes
  a `media` (and `episode`, for series) document to MongoDB. Runs
  after upload completes; a failed match leaves the item in the
  library flagged `unmatched` with manual-edit fields, rather than
  blocking or failing the upload.

### `app/api/media/*` (new — replaces `app/api/backup/*`)

- `upload` — accepts a file stream, calls `gdrive.uploadFile`, creates
  the Mongo doc, kicks off `library/match.js`.
- `list` — returns the library (movies + series, grouped) from Mongo.
- `[id]` — item detail (metadata + episode list for series).
- `stream/[id]` — proxies `gdrive.streamFile`, forwarding the
  client's `Range` header and relaying `Content-Range` /
  `Content-Length` / 206 status back to the client.
- `progress` — reads/writes watch position (resume support) per item
  per session/user.

### `app/(web)/` (replaces `/`, `/upload`, `/storage`)

- `/` — library browse: Movies and Shows rows (poster grid),
  Jellyfin-style.
- `/title/[id]` — detail page: poster, description, cast, season/
  episode list (for series), play button.
- `/upload` — add media: file picker, shows parse/match result once
  processed.
- `/admin/usage` — repurposed to show Drive storage usage instead of
  pool usage (Drive API quota/usage endpoint).

### Retained as-is

- `middleware.js`, `lib/auth.js`, session/login flow.
- The `scripts/test-*.js` smoke-test pattern (see Testing below).

## Data Flow

1. User uploads a file via `/upload` → `app/api/media/upload`.
2. Route streams the file to `gdrive.uploadFile`, gets back a
   `driveFileId`.
3. Route writes a `media` doc to MongoDB: `{ driveFileId, filename,
   size, status: 'processing' }`.
4. `library/match.js` runs (inline, post-response, or via a simple
   queue — decided in the implementation plan): parses the filename,
   queries TMDb, updates the doc with poster/description/season-
   episode structure, sets `status: 'matched'` or `'unmatched'`.
5. `/` and `/title/[id]` read directly from MongoDB.
6. Play button → client requests `/api/media/stream/[id]` with a
   `Range` header → route resolves `driveFileId` from Mongo, calls
   `gdrive.streamFile(driveFileId, range)`, pipes the response back
   with matching status/headers.
7. Client periodically posts watch position to `/api/media/progress`.

## Error Handling

- **Drive API errors** (auth expired, quota exceeded, network) on
  upload or stream: surfaced directly to the client as a failure —
  no fallback backend exists to retry against, per the full-
  replacement decision.
- **TMDb match failures** (no result, ambiguous match, API down): the
  media doc is stored with `status: 'unmatched'`; it still appears in
  the library (using the raw filename as title, no poster) and can be
  corrected via manual edit. Upload success is never blocked on match
  success.
- **Range request edge cases**: a request with no `Range` header
  streams the full file (200); an invalid/out-of-bounds range returns
  416, mirroring standard HTTP semantics so ExoPlayer (the eventual
  Android TV client) behaves correctly.

## Testing

Following the existing `scripts/test-*.js` smoke-test pattern:

- `scripts/test-gdrive.js` — upload/download/delete round-trip
  against the real Drive API (throwaway test file), no mocks —
  mirrors `test-local-store.js`'s approach.
- `scripts/test-tmdb.js` — match a known filename (e.g.
  `The Matrix (1999).mkv`) against the real TMDb API, assert poster/
  description populate.
- `scripts/test-db.js` — extended with the new `media`/`episode`
  collections' indexes (title, driveFileId, status).
- Drop: `test-router.js`, `test-local-store.js`, `test-thumb.js`'s
  pool/local-backend-specific logic (superseded by `test-gdrive.js`).

## Migration Notes

This is a full replacement, not an additive feature:

- Remove: `lib/r2.js`, `lib/rclone-rc.js`, `lib/storage-router.js`,
  the Telegram/GramJS fallback module, `rclone/` directory, the
  multi-cloud pool env vars.
- Remove: `app/api/backup/*`, `app/page.js` (photo gallery),
  `app/upload/`, `app/storage/` (photo versions).
- Existing MongoDB collections for the photo manifest are not
  migrated — this is a domain change, not a data migration. (If any
  existing photo data must be preserved, that's a separate, explicit
  follow-up — not assumed here.)
- New env vars needed: Google Drive OAuth credentials (client ID/
  secret + refresh token, or service account key), TMDb API key.
