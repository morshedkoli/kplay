# Prompt 06 — Thumbnail generation + R2 caching

**Feed this to Claude Code / Antigravity after Prompt 05 (or before — this
module is independent, just needs to be wired into the upload route once
both exist).**

---

## Context

Regardless of which of the 12 backends holds the original file, thumbnails
always live in Cloudflare R2 so the gallery view is fast and doesn't depend
on MEGA/Dropbox/etc. rate limits. Reference `PRD.md` section 7
(`thumbnailR2Key` field) and `docs/01-architecture.md`.

## Task

1. Add `sharp` as a dependency for image thumbnailing.
2. Create `lib/thumbnail.js` exporting
   `async function generateAndCacheThumbnail(buffer, mimeType, fileId)`:
   - For images: resize to max 400px on the longest edge, output as WebP,
     quality 70.
   - For video: skip actual thumbnailing in v1 (note as TODO — needs
     ffmpeg frame extraction, out of scope per PRD open question #3 until
     video backup is confirmed in scope). Return `null` for video mimeTypes.
   - Upload the resulting WebP directly to the R2 bucket
     (`kdrive-thumbnails`, separate from any original-photo bucket) using
     the AWS S3 SDK (`@aws-sdk/client-s3`) configured for R2's endpoint —
     this is a direct R2 SDK call, not through rclone, since it's simple
     and doesn't need the pool abstraction.
   - Return the R2 object key.
3. Wire this into `app/api/backup/upload/route.js` — replace the
   TODO stub from Prompt 05 with a real call, update the file's
   `thumbnailR2Key` field after.
4. Create `app/api/backup/thumbnail/[fileId]/route.js` (GET) —
   looks up `thumbnailR2Key` from the manifest, streams it from R2, sets a
   long `Cache-Control` header (thumbnails never change once generated).
5. Add R2 thumbnail bucket credentials to `.env.example` if not already
   covered by the main R2 credentials from Prompt 01.

## Acceptance criteria

- Uploading a real JPEG through `/api/backup/upload` results in a WebP
  thumbnail appearing in the R2 `kdrive-thumbnails` bucket.
- `/api/backup/thumbnail/[fileId]` returns the thumbnail with correct
  `Content-Type: image/webp`.
- Uploading a video file doesn't crash the upload route — thumbnail is
  just `null`/skipped.
