# Prompt 04 — Storage router (the core abstraction)

**Feed this to Claude Code / Antigravity after Prompt 03.**

---

## Context

This is the most important piece of the backend: a single module that
decides where to send an upload (rclone pool vs. Telegram fallback) and
exposes a uniform `upload()` / `download()` interface so the API routes
don't need to know which of 12 backends is involved. Reference
`docs/01-architecture.md`'s "Upload flow" and "Failure handling" sections.

## Task

1. Create `lib/storage-router.js` exporting:
   - `async function uploadFile(buffer, { deviceId, hash, mimeType, originalName })`
     → returns `{ fileId, backend: "pool"|"telegram", remoteName, remotePath }`
     Logic:
     a. Check cached pool status (call `checkPoolHealth()`, see below;
        cache result for 5 minutes to avoid hammering rclone on every
        upload).
     b. If pool looks healthy, write via rclone RC HTTP API
        (`POST /operations/copyurl` or write to a temp file + use
        `rc/noopauth` `operations/copyfile` — pick whichever rclone RC
        method handles a Node.js Buffer → remote path most cleanly; look
        at rclone's RC API docs for `operations/copyfile` or `Post form
        upload` if a direct buffer method isn't available).
     b2. On any error from the pool write (timeout, quota error, auth
        error), catch it, log a warning, and fall through to Telegram —
        never throw upward to the API route.
     c. If pool write fails or `checkPoolHealth()` says the pool is full,
        call the existing Bhandar GramJS upload function (import it from
        wherever the current Bhandar backend keeps it — ask me for the
        path if you can't find it in this repo, since Bhandar is a
        separate existing project).
   - `async function downloadFile(fileId)` → looks up the manifest record,
     streams from the correct backend (rclone RC `operations/copyfile` to a
     temp location and stream that, or the Telegram GramJS download
     function).
   - `async function checkPoolHealth()` → calls rclone RC
     `operations/about` on `pool:`, returns `{ healthy: boolean, freeBytes, usedBytes }`.
     Treat "healthy" as false if free space is under some small threshold
     (e.g. 50MB) or the RC call errors/times out.
2. Add basic retry logic (2 retries, exponential backoff) around the rclone
   RC HTTP calls specifically — network blips shouldn't immediately punt to
   Telegram.
3. Add structured logging (console.log with a consistent prefix like
   `[storage-router]`) at each decision point — which backend was chosen
   and why — since debugging "why did this photo end up on Dropbox" needs
   to be easy.

## Acceptance criteria

- `uploadFile()` successfully round-trips a test buffer to at least one
  real pool remote when I run it against my configured rclone rcd.
- Manually stopping the rclone rcd service and re-running `uploadFile()`
  results in the file landing in Telegram instead, without throwing.
- `checkPoolHealth()` returns sensible values I can verify against
  `rclone rc operations/about pool:` run manually.
