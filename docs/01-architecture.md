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

## Seeking a file the player says it cannot seek

```
1. Playback starts: the TV client asks GET /api/media/seek-index/[id]
2. lib/library/mkv-index.js reads a few byte ranges of the Drive file and
   returns a table of [timeMs, byteOffset] pairs, cached in Mongo
   (`seekIndex`) so the work happens once per file
3. The client wraps its extractors so the table replaces the unseekable
   SeekMap the extractor published, then prepares playback
4. Fast forward now moves the film instead of restarting it
```

A Matroska file keeps its seek index in a Cues element announced by a
SeekHead. ExoPlayer's MatroskaExtractor reads the first SeekHead and no
further, so when a muxer chains a second one the extractor finds no Cues,
publishes an unseekable SeekMap, and ProgressiveMediaPeriod then treats every
seek as a seek to t=0 — which from the sofa is fast-forward restarting the
film.

The index is in the file; only the route to it is one the player will not
walk. So the server walks it, over three strategies, cheapest first: follow
the SeekHead chain, then look for a Cues element at the end of the file, then
— for a file with no Cues at all — hop cluster to cluster reading each
cluster's header, which its size field makes arithmetic rather than a search.
No file is ever downloaded, re-encoded or re-uploaded.

Substituting the seek map is safe because every offset in the table is the
start of a Cluster, the same position the extractor's own Cues path would have
produced. A file that already seeks keeps its own index: the wrapper only
replaces a map that says it cannot seek.

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
