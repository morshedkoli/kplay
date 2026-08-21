# PRD — KDrive
**A single-user, self-hosted movie/TV media server backed by Google Drive**

Owner: Murshed
Status: Draft v2 — media-server pivot
Last updated: 2026-08-20

This is a full replacement of the earlier photo-backup PRD (v1, preserved in
git history). The authoritative design for this iteration is
`docs/superpowers/specs/2026-08-20-media-server-backend-design.md` — read it
alongside this document for implementation-level detail (component
responsibilities, data flow, error handling, migration notes).

---

## 1. Problem

Commercial streaming services don't carry everything Murshed owns or has
downloaded, and self-hosting a "real" media server (Plex/Jellyfin on a home
NAS) is more infrastructure than a single person needs. Google Drive is
already paid-for storage that's just sitting there. This project turns it
into a Jellyfin-style library: upload once, get automatic poster/metadata
matching, and stream back to any browser with no transcoding step.

## 2. Goals

- Add a movie or TV episode file through the web app in one upload step.
- Metadata (poster, description, season/episode structure) is fetched
  automatically from TMDb — no manual data entry for the common case.
- Play back any title directly in the browser via Range-based streaming
  (seek works, no waiting for a transcode).
- Browse the library like Jellyfin/Plex: poster grid, title detail pages,
  resume-from-last-position.
- Single storage backend (Google Drive) — no pool to manage, no fallback
  logic to reason about.

## 3. Non-goals (this iteration)

- **Not a multi-user product.** Single Google account, single Drive, single
  shared library. No per-user accounts beyond the existing `ADMIN_PASSWORD`
  session gate, no per-household isolation.
- **No transcoding or adaptive bitrate (HLS/DASH).** Direct-play only —
  the server proxies original Drive bytes with Range support. If a client
  can't play the source codec/container, that's out of scope for v1.
- **No live TV, music, or other media types.** Movies and TV series only.
- **No Android TV client yet.** That's sub-project 2 — specced separately
  once this backend's `app/api/media/*` surface is stable. Nothing in this
  iteration depends on it existing.
- **Not a data migration.** The old photo-backup Mongo collections
  (`files`, `storage_accounts`) are not carried forward into the media
  domain — this is a domain change, not a migration (see the design doc's
  "Migration Notes").

## 4. Users

Just Murshed. Personal media server, gated by the existing single-password
session login.

## 5. Architecture summary

One Next.js 15 project serves both the web client and the API — same
process, same origin, no separate frontend service and no separate backend
service. Google Drive replaces the old rclone pool as the only storage
backend; MongoDB holds all metadata (library index, episodes, watch
progress), never file bytes.

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

See `docs/01-architecture.md` for the full data flow diagram, and the design
doc for per-component responsibilities.

## 6. Storage backend

| Backend | Role |
|---|---|
| Google Drive | Sole storage for original media file bytes. Addressed by `driveFileId`, accessed via `lib/gdrive.js` (Drive API v3, OAuth2 refresh token). |

No pool, no union remote, no Telegram/GramJS fallback — a Drive API error on
upload or stream surfaces directly to the client as a failure. There is no
second backend to retry against.

## 7. Data model

### `media` collection (movies and series)
```
{
  _id: ObjectId,
  driveFileId: string | null,   // null for a series (episodes hold theirs)
  type: "movie" | "series",
  title: string,
  year: number | null,
  tmdbId: number | null,
  posterUrl: string | null,
  description: string | null,
  status: "processing" | "matched" | "unmatched",
  filename: string,             // original uploaded filename, always kept
  sizeBytes: number | null,
  createdAt: Date,
  updatedAt: Date
}
```

### `episode` collection (series only)
```
{
  _id: ObjectId,
  mediaId: ObjectId,            // parent `media` doc
  driveFileId: string,
  season: number,
  episode: number,
  title: string | null,
  sizeBytes: number,
  createdAt: Date
}
```

Exact field shapes live in `lib/models/media.js` — this is the conceptual
model, not a schema dump.

## 8. API surface

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/media/upload` | POST | Accepts a file stream, uploads to Drive via `gdrive.js`, creates a `media`/`episode` doc, kicks off TMDb matching. |
| `/api/media/list` | GET | Returns the library (movies + series, grouped) from MongoDB. |
| `/api/media/[id]` | GET | Item detail: metadata + episode list (for series). |
| `/api/media/stream/[id]` | GET | Proxies `gdrive.streamFile`, forwarding the client's `Range` header and relaying `Content-Range`/`Content-Length`/206 status back. |
| `/api/media/progress` | GET, POST | Reads/writes watch position (resume support). |
| `/api/session` | POST, DELETE | Web sign-in/sign-out with `ADMIN_PASSWORD`; sets an HttpOnly session cookie. |

## 9. Deferred: Android TV client

Out of scope for this iteration. Planned as sub-project 2, to be specced
once `app/api/media/*`'s shape is stable and proven against the web client.
No details are assumed here beyond "it will be a fresh build against this
API, likely using ExoPlayer for Range-aware playback."

## 9b. Web app requirements

Same Next.js deployment as the API — no separate origin, no CORS.

- `/` — library browse (`app/page.js`, `app/Library.js`). Movies and Shows,
  Jellyfin-style poster grid.
- `/title/[id]` — detail page: poster, description, cast (where TMDb
  provides it), season/episode list for series, play button.
- `/upload` — add media: file picker; shows the parse/match result once
  `library/match.js` finishes processing.
- `/admin/usage` — Google Drive storage usage (used/total, where the Drive
  API exposes it), gated by `ADMIN_PASSWORD` like the rest of the admin
  surface.
- `/login` — the only unauthenticated page. Single user, so the password
  *is* the account (section 4).
- Auth: unchanged from the prior iteration — `ADMIN_PASSWORD` exchanged for
  an HttpOnly cookie, `middleware.js` gates page requests.

## 10. Non-functional requirements

- **Cost**: $0/month beyond the existing Google Drive storage plan and
  TMDb's free API tier.
- **Playback**: direct-play only — no transcoding, no adaptive bitrate.
  Range requests must behave correctly (200 for no-Range, 206 + correct
  `Content-Range` for a valid Range, 416 for an invalid one) so seeking
  works in the browser and, eventually, ExoPlayer on the TV client.
- **Metadata reliability**: a failed or ambiguous TMDb match never blocks
  the upload — the item still appears in the library (raw filename as
  title, no poster) with `status: "unmatched"`, correctable later.
- **Security**: Drive OAuth credentials and the TMDb API key live in
  backend env vars only, never sent to the client.

## 11. Open questions

1. OAuth refresh token vs. a Google service account for `lib/gdrive.js`'s
   auth — decided per the implementation plan; either is acceptable as long
   as it's tied to the single Drive account and doesn't require re-auth in
   normal operation.
2. Where does re-matching/manual-edit for `status: "unmatched"` items live
   in the UI — a dedicated edit form, or inline on `/title/[id]`? Not yet
   decided; either is workable, doesn't block v1.
3. Should the TMDb match step run inline in the upload request, post-
   response, or via a lightweight queue? Current implementation runs it
   as part of the upload flow (see `lib/library/match.js` and the design
   doc's Data Flow section) — revisit only if upload latency becomes an
   issue with larger files.

## 12. Success criteria

- Uploading a correctly-named file (e.g. `The Matrix (1999).mkv`) results
  in a matched library entry with poster and description within the same
  upload request/response cycle, without manual data entry.
- Any matched title plays back in the browser with working seek, streamed
  directly from Google Drive with no transcoding step.
- `/admin/usage` reports real Google Drive usage, gated behind
  `ADMIN_PASSWORD`.
- A full month goes by with $0 in incremental cloud storage charges beyond
  the existing Google Drive plan.
