# KDrive

A Jellyfin-style movie/TV media server. Upload a video file, and the app
stores the bytes in **Google Drive** (the sole storage backend), auto-matches
it against **TMDb** for poster/description/season-episode metadata, and
serves it back over HTTP with Range support for **direct-play** streaming —
no server-side transcoding.

This is a pivot of what was previously a multi-cloud photo-backup app. The
old pool (Cloudflare R2, Backblaze B2, Storj, IDrive e2, Oracle Cloud,
Filebase, MEGA, Google Drive, OneDrive, Dropbox, pCloud via `rclone union`)
and the Telegram/GramJS overflow fallback have been removed. See
`docs/superpowers/specs/2026-08-20-media-server-backend-design.md` for the
design doc this rebuild was built from.

The **web client** splits the library into two sections: `/movies` and
`/series`, each with its own grid and `[id]` detail page, plus `/add` for
uploads (`/` redirects to `/movies`). A movie plays straight from its
detail page; a series detail page lists its episodes by season and plays the
one you pick. `/admin/usage` shows Google Drive storage usage instead of
pool usage.

It is **one full-stack Next.js project at the repo root**: the same app
serves the web UI and the `/api/media/*` routes. One process, one origin, one
deploy — no separate frontend, no separate backend, no CORS.

Single Google account, single Drive, single shared library — no per-user
Drive, no per-household isolation. The existing `ADMIN_PASSWORD` session gate
is retained as-is.

An Android TV client is planned as a future, separate sub-project — not yet
built, not yet specced. Nothing in the running app depends on it.

The `prompts/` directory and `_archive/` are leftovers from the original
photo-backup build and no longer describe the running app; they are not
maintained as part of this iteration.

## What's in here

```
kdrive/                       ← the Next.js 15 app itself (full stack)
├── app/
│   ├── page.js                ← / redirects to /movies
│   ├── movies/  series/       ← the two library sections: page.js grid +
│   │                             [id]/ detail, both sharing LibraryGrid.js
│   │                             and MediaDetail.js
│   ├── add/                   ← upload form, shared by both sections
│   ├── Sidebar.js             ← app nav (movies / series / add / usage /
│   │                             sign out)
│   ├── admin/usage/           ← Google Drive storage usage
│   ├── login/                 ← session sign-in
│   └── api/
│       ├── media/            ← upload, list, [id], stream/[id], progress
│       └── session/          ← web sign-in / sign-out
├── lib/
│   ├── gdrive.js              ← Google Drive API v3 wrapper (upload/stream/
│   │                             metadata/delete) — the only storage backend
│   ├── library/               ← parse.js, tmdb.js, match.js — filename
│   │                             parsing + TMDb auto-matching
│   ├── models/media.js        ← media/episode Mongo schema helpers
│   └── db.js  auth.js  session-cookie.js  admin-auth.js  env.js
│                                format.js
├── scripts/                  ← smoke tests: test-gdrive, test-tmdb, test-db,
│                                show-indexes
├── middleware.js             ← page-level auth gate
├── PRD.md                    ← full product requirements doc, read this first
├── docs/01-architecture.md   ← system diagram + data flow
└── docs/superpowers/specs/2026-08-20-media-server-backend-design.md
                               ← source-of-truth design doc for this iteration
```

## Quick start

1. Read `PRD.md` and `docs/01-architecture.md` — 10 minutes, saves you rework.
2. `npm install && cp .env.example .env.local` — fill in:
   - `MONGODB_URI` / `MONGODB_DB_NAME`
   - `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` / `GOOGLE_REFRESH_TOKEN` /
     `GOOGLE_DRIVE_FOLDER_ID` — the single Drive account's OAuth credentials
   - `TMDB_API_KEY` — for metadata auto-matching
   - `ADMIN_PASSWORD` — the app's single-user login
3. `npm run dev` and open http://localhost:3000. That is the whole stack —
   there is nothing else to run (no rclone daemon, no separate services).
4. `node scripts/test-gdrive.js` and `node scripts/test-tmdb.js` exercise the
   Drive and TMDb integrations against the real APIs (need the credentials
   above in `.env.local`); `npm run test:db` covers the Mongo indexes.

## Cost

$0/month beyond your existing Google Drive storage plan and free-tier TMDb
API usage. There is no pool, no per-provider quota juggling, and no Telegram
fallback to reason about — Drive's own quota is the only limit.

## Status

Last updated: 2026-08-20. Tasks 1-9 of the media-server pivot are committed;
this task (10) is documentation only.

- **Google Drive storage (`lib/gdrive.js`): done.** Replaces the rclone pool,
  R2 originals path, and Telegram fallback for media. Upload, stream (with
  Range support), metadata, and delete are implemented.
  `node scripts/test-gdrive.js` exercises the real Drive API — needs `GOOGLE_CLIENT_ID` /
  `GOOGLE_CLIENT_SECRET` / `GOOGLE_REFRESH_TOKEN` in `.env.local` to run.
- **MongoDB library index (`lib/models/media.js`): done.** `media` and
  `episode` collections; indexes extended alongside the existing
  `test-db.js`/migration pattern.
- **TMDb auto-matching (`lib/library/parse.js`, `tmdb.js`, `match.js`):
  done.** Parses Jellyfin-style filenames, looks up TMDb, and writes matched
  (or `unmatched`, for manual correction) records.
  `node scripts/test-tmdb.js` hits the real TMDb API — needs `TMDB_API_KEY`.
- **Media API (`app/api/media/*`): done.** `upload`, `list`, `[id]`,
  `stream/[id]` (Range-aware direct-play proxy), `progress` (watch position).
- **Web client: done.** `/movies` and `/series` browse (both render
  `app/LibraryGrid.js` with a `kind` prop), `/movies/[id]` and
  `/series/[id]` detail + play (both render `app/MediaDetail.js`, which
  shows a season/episode list for a series), `/add` upload form,
  `app/Sidebar.js` nav. `/` redirects to `/movies`. Which section an
  upload lands in is decided by the filename, not the page it was started
  from — the upload response reports the resulting `type`.
- **Admin usage (`app/admin/usage/page.js`): done.** Now reports Google Drive
  usage instead of pool usage, still gated by `ADMIN_PASSWORD`.
- **Old photo-backup code: removed.** `app/api/backup/*`, the old `/`
  gallery/`/storage` pages, `lib/storage-router.js`, `lib/rclone-rc.js`, the
  Telegram/GramJS fallback module, and the `rclone/` directory are all
  deleted. The last leftovers — `lib/r2.js`, `lib/posters.js`,
  `lib/local-store.js`, `lib/thumbnail.js`, `lib/stats.js`,
  `lib/client-upload.js`, `lib/models/{files,movies,storageAccounts}.js`,
  `app/api/movies/*`, and `scripts/test-thumbnail.js` — are now gone too,
  along with the `sharp` and `@aws-sdk/client-s3` dependencies they were
  the only users of.
- **Android TV client: planned, not yet built.** Sub-project 2, to be specced
  once this backend's API surface is stable (see the design doc's scope
  note).
