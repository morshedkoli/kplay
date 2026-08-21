# Prompt 05 — API routes (upload, manifest, download, usage)

**Feed this to Claude Code / Antigravity after Prompt 04.**

---

## Context

`lib/storage-router.js` and the MongoDB models exist. Now wire up
the actual Next.js API routes per `PRD.md` section 8. Route file stubs
already exist under `app/api/backup/`.

## Task

1. `app/api/backup/upload/route.js` (POST):
   - Accept multipart/form-data: `file`, `deviceId`, `hash`, `mimeType`.
   - Check `files.findByHash(hash, deviceId)` first — if it already exists
     with status `uploaded`, return the existing record immediately
     (idempotent, don't re-upload).
   - Otherwise insert a `pending` record, call
     `storage-router.uploadFile()`, update the record to `uploaded` (or
     `failed` on error) with the returned backend/remoteName/remotePath.
   - Kick off thumbnail generation (call the function from Prompt 06 —
     if that prompt hasn't been run yet, stub it as a TODO that no-ops).
   - Return `{ fileId, backend, status }`.
2. `app/api/backup/manifest/route.js` (GET):
   - Query param `deviceId`.
   - Return `{ hashes: string[] }` — every hash already backed up for that
     device, for client-side dedupe before the app even attempts an
     upload.
3. `app/api/backup/download/[fileId]/route.js` (GET):
   - Look up the file, call `storage-router.downloadFile()`, stream the
     result back with correct `Content-Type` and `Content-Disposition`.
4. `app/api/backup/usage/route.js` (GET):
   - Return per-provider used/free space from `storage_accounts`, plus a
     live `checkPoolHealth()` call for the pool total. This powers the
     admin dashboard in Prompt 07.
5. Add basic input validation (missing fields → 400, not a crash) and
   consistent JSON error shape `{ error: string }` across all routes.
6. Add a simple shared-secret auth check (a header like
   `x-kdrive-device-key` compared against an env var) on the upload and
   download routes — this is a personal single-user app, not full OAuth,
   but it shouldn't be wide open on the internet.

## Acceptance criteria

- I can `curl -F "file=@test.jpg" -F "deviceId=test" -F "hash=abc123" -F "mimeType=image/jpeg" -H "x-kdrive-device-key: $KEY" http://localhost:3000/api/backup/upload`
  and get back a valid `{fileId, backend, status}`.
- Re-running the same curl with the same hash returns the existing record
  without re-uploading (check the logs from Prompt 04's structured
  logging to confirm no second upload happened).
- `/api/backup/manifest?deviceId=test` includes that hash afterward.
- `/api/backup/download/[fileId]` returns the actual file bytes.
- Missing `x-kdrive-device-key` returns 401 on upload/download.
