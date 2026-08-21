# Prompt 10 — Room upload queue + BackupUploadWorker

**Feed this to Claude Code / Antigravity after Prompt 09.**

---

## Context

Local persistent queue so uploads survive app kills/reboots, plus the
actual worker that talks to the backend from Prompt 05. Reference `PRD.md`
section 9.

## Task

1. Create `data/BackupItem.kt` (Room entity):
   `id, contentUri, hash, status (PENDING/UPLOADING/UPLOADED/FAILED),
   retries, createdAt, uploadedAt`.
2. Create `data/BackupDao.kt` with queries: insert, `getPending()`,
   `updateStatus()`, `findByHash()`, `incrementRetries()`.
3. Create `data/AppDatabase.kt` — standard Room database singleton.
4. Create `network/BhandarApi.kt` — Retrofit interface matching the
   backend routes from Prompt 05: `uploadFile()` (multipart),
   `getManifest(deviceId)`, plus the auth header interceptor for
   `x-kdrive-device-key`.
5. Create `worker/BackupUploadWorker.kt` (a `CoroutineWorker`):
   - Given a content URI, compute SHA-256 hash by streaming the file
     (don't load the whole thing into memory for large videos).
   - Check the hash against the local Room cache first; if not found
     locally, check against `getManifest()` (in case this is a fresh
     install / reinstall scenario) before uploading.
   - If genuinely new, call `uploadFile()`, update the Room row to
     `UPLOADED` on success.
   - On failure, increment retries and let WorkManager's built-in retry
     policy (exponential backoff, configured at enqueue time) handle
     re-attempts, up to a max retry count (e.g. 5) after which mark
     `FAILED` and stop retrying automatically (surfaced in the UI from
     Prompt 11 for manual retry).
6. On first app launch (or a manual "sync existing photos" action),
   backfill the queue with all existing photos on the device, not just new
   ones going forward — otherwise only photos taken after install ever get
   backed up.

## Acceptance criteria

- A photo taken on the test device successfully appears in the backend's
  `files` MongoDB collection with `status=uploaded` within a reasonable
  time on Wi-Fi.
- Force-killing the app mid-upload and reopening it later results in the
  upload eventually completing (WorkManager persistence working
  correctly).
- Running the "sync existing photos" backfill on a device with existing
  photos enqueues all of them without duplicating any that get added
  concurrently.
- Airplane mode during a photo capture: the item stays `PENDING`/retries
  until connectivity returns, then completes without manual intervention.
