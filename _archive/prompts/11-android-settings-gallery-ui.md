# Prompt 11 — Settings screen + gallery/restore UI

**Feed this to Claude Code / Antigravity after Prompt 10.**

---

## Context

The user-facing part: a settings screen to control backup behavior, and a
simple gallery to browse/restore what's already backed up. Reference
`PRD.md` section 9.

## Task

1. `ui/SettingsScreen.kt` (Compose):
   - Toggle: Wi-Fi-only uploads (wire this into the WorkManager constraint
     from Prompt 09/10 — currently defaulted off/stubbed).
   - Folder include/exclude list — show detected media folders (query
     `MediaStore` for distinct `BUCKET_DISPLAY_NAME` values), let the user
     toggle which ones get backed up. Store the excluded list in
     `SharedPreferences` or DataStore; `MediaObserverService` from Prompt
     09 should check this before enqueueing.
   - Pause/resume backup toggle (stops the `MediaObserverService` and
     cancels pending WorkManager jobs when paused).
   - A "Sync existing photos now" button triggering the backfill logic
     from Prompt 10.
   - Show queue status: counts of pending / uploaded / failed items,
     pulled from the Room DAO.
2. `ui/GalleryScreen.kt` (Compose):
   - Grid of thumbnails, fetched via `GET /api/backup/thumbnail/[fileId]`
     (paginate — don't try to load the whole manifest at once; add a
     paginated version of the manifest endpoint if the current one from
     Prompt 05 doesn't support it, or fetch it as-is and paginate
     client-side if the total count is expected to stay small).
   - Tap a thumbnail to view full-size (fetch original via
     `GET /api/backup/download/[fileId]` on demand, don't pre-fetch every
     original).
   - A "Failed uploads" filter/tab showing items stuck at `FAILED` with a
     manual retry button.
3. Wire `MainActivity.kt`'s navigation to move between Home / Gallery /
   Settings using standard Compose Navigation.

## Acceptance criteria

- Toggling Wi-Fi-only in settings actually changes upload behavior on
  cellular vs Wi-Fi in a real test.
- Excluding a folder in settings stops new photos in that folder from
  being enqueued.
- Gallery screen shows real thumbnails from the backend, loads
  incrementally rather than all at once.
- Tapping a failed item's retry button re-enqueues it and it moves out of
  the failed list on success.
