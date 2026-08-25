# Architecture — KDrive

This describes the media-server architecture that replaced the earlier
photo-backup design. The authoritative source for this iteration is
`docs/superpowers/specs/2026-08-20-media-server-backend-design.md` — this
file summarizes it for quick reference; consult the spec for full component
and error-handling detail.

## System diagram

```
Browser (web client, future TV client)
   │  HTTP (session cookie / device key)
   ▼
Next.js app (app/, lib/, middleware.js)
   │                         │
   ▼                         ▼
MongoDB                  Google Drive API
(media/episode docs,     (file bytes only,
 watch progress,          addressed by driveFileId)
 sessions)
   ▲
   │
TMDb API (metadata lookup, called from lib/library/)
```

One process, one deploy — the same Next.js 15 app serves both the web UI
and the API. Google Drive is the only storage backend; MongoDB never holds
file bytes, only metadata.

## Upload flow (happy path)

```
1. User picks a file on /upload in the browser
2. Client uploads to POST /api/media/upload
3. Route streams the file to lib/gdrive.js's uploadFile(), gets back
   a driveFileId
4. Route writes a `media` (or `episode`, if parsed as part of an existing
   series) doc to MongoDB: { driveFileId, filename, size,
   status: "processing" }
5. lib/library/match.js runs: parse.js extracts a candidate title/year
   (and season/episode, for series) from the filename using Jellyfin-style
   naming conventions, tmdb.js looks it up against the TMDb API
6. On a TMDb match: the doc is updated with poster/description/season-
   episode structure, status → "matched"
   On no match / ambiguous match / TMDb error: status → "unmatched" —
   the item still appears in the library under its raw filename, with no
   poster, and can be corrected manually later. Upload success is never
   blocked on match success.
7. Route returns the created doc to the browser; the web client shows the
   parse/match result and refreshes the library view
```

## Playback flow

```
1. Browser navigates to /title/[id], clicks play
2. Client requests GET /api/media/stream/[id], forwarding a Range header
   if seeking or resuming
3. Route resolves driveFileId from the media/episode Mongo doc
4. Route calls gdrive.js's streamFile(driveFileId, rangeHeader), which
   proxies the byte range directly from the Drive API
5. Route pipes the response back to the client with matching status
   (200 full file / 206 partial content) and Content-Range/Content-Length
   headers — no transcoding step anywhere in this path
6. Client periodically POSTs the current playback position to
   /api/media/progress for resume support
```

## Second source: DhakaFlix (the ISP index)

```
1. Sync crawls the configured DHAKAFLIX_ROOTS (h5ai directory listings on
   172.16.50.7/.9/.12/.14) breadth-first, reading only listings
2. Each video file found becomes a media/episode doc with
   source: 'dhakaflix' and sourceUrl: the file's http:// URL — the same
   filename -> TMDb -> Mongo pipeline a Drive file goes through
3. Playback: GET /api/media/stream/[id] answers a DhakaFlix item with a 302
   to sourceUrl. The player fetches the origin directly; no byte ever passes
   through this app
```

A doc carries exactly one address for its bytes — `driveFileId` or
`sourceUrl`, decided by `source` — and both are unique sparse indexes so a
re-crawl cannot import the same file twice.

### Why the bytes are not proxied

172.16.50.x is on the ISP's private network. A public deploy cannot reach it
at all, and even on-net, relaying feature films through a function would add a
bandwidth bill and a timeout to a job nginx already does properly (it serves
Range requests natively — verified 206 on a 1.8 GB file). The cost of the
redirect is that the origin is plain HTTP: a browser on an HTTPS page blocks
the redirected request as mixed content. The Android TV client (ExoPlayer,
which has no such rule) is unaffected, and the detail page says so rather than
letting the player fail silently.

### Why the import is sliced

One root alone holds thousands of files and every new title costs a TMDb
lookup. A sync imports at most `DHAKAFLIX_MAX_IMPORTS` (default 300) and
reports `remaining`, so the work is resumable instead of one request that
cannot finish.

## Why Google Drive as the sole backend, not a pool

The prior iteration used `rclone union` across eleven providers plus a
Telegram/GramJS overflow fallback, because the goal then was "never run out
of free storage." This iteration has a different goal — a real media
library backed by storage Murshed already pays for and trusts — so the pool
complexity (multi-provider auth, `mfs` balancing, fallback-on-full logic)
is removed entirely. `lib/gdrive.js` replaces `lib/r2.js` (for original file
bytes; posters/thumbnails for the separate, unrelated `movies/` feature
still use R2), `lib/rclone-rc.js`, `lib/storage-router.js`, and the
Telegram fallback module — all deleted. A Drive API error now surfaces
directly to the client; there is nothing to fall back to, by design.

## Why direct-play only, no transcoding

Transcoding requires either real-time server-side CPU/GPU work or a
pre-transcode pipeline, both of which are significant infrastructure for a
single-user server. Direct-play — proxying the original file bytes with
Range support — covers the common case (modern containers/codecs that
browsers and ExoPlayer already support) with none of that complexity.
Adaptive bitrate / HLS-DASH is explicitly out of scope for this iteration
(see the design doc's "Out of scope" section) and can be revisited later
if playback compatibility becomes a real problem.

## Why TMDb matching never blocks the upload

Upload success and metadata-match success are independent outcomes. A file
that fails to match (bad filename, TMDb outage, ambiguous title) still
needs to land in the library — as an `unmatched` item using the raw
filename — rather than being silently dropped or the whole upload request
failing. This mirrors the old PRD's principle that a partial failure should
leave a findable record, not an orphaned or lost item.

## Failure handling

| Failure | Behavior |
|---|---|
| Google Drive API error on upload (auth expired, quota exceeded, network) | Surfaced directly to the client as an upload failure — no fallback backend exists to retry against |
| Google Drive API error on stream | Surfaced directly to the client; playback fails rather than silently degrading |
| TMDb match failure (no result, ambiguous, API down) | Item stored with `status: "unmatched"`; still browsable/playable via raw filename; upload is never blocked |
| Range request with no `Range` header | Streams the full file, 200 |
| Range request that's invalid/out-of-bounds | 416, standard HTTP semantics, so ExoPlayer (the eventual Android TV client) behaves correctly |

## Retained from the prior iteration, unchanged

- `middleware.js` and `lib/auth.js` — page-level auth gate and session
  logic, single-user (`ADMIN_PASSWORD`).
- The `scripts/test-*.js` smoke-test pattern — `test-gdrive.js` and
  `test-tmdb.js` are new additions to it, exercising the real Drive and
  TMDb APIs respectively (no mocks), following the same approach as the
  pattern's earlier tests.

## Out of scope, not part of this architecture

- The separate, pre-existing `movies/` feature (`app/movies/`,
  `app/api/movies/*`, `lib/posters.js`, `lib/models/movies.js`) predates
  this media-server work and is untouched by it — a different effort
  living in the same repo.
- Multi-user profiles, per-user Drive accounts, live TV, and music are all
  out of scope for this iteration (see the design doc).
- The Android TV client (sub-project 2) does not exist yet; nothing here
  depends on it.
