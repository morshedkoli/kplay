# Prompt 09 — MediaStore observer + WorkManager scheduling

**Feed this to Claude Code / Antigravity after Prompt 08.**

---

## Context

The core "automatic" part of automatic backup: detect new photos/videos the
instant they're added to the device, without polling. Reference `PRD.md`
section 9.

## Task

1. Create `worker/MediaObserverService.kt` — a foreground service that
   registers a `ContentObserver` on
   `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` and
   `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`.
   - On change notification, query for media added since the last known
     timestamp (store this timestamp in `SharedPreferences` or a small
     Room table — pick one and be consistent).
   - For each new item found, enqueue a `BackupUploadWorker` (built in
     Prompt 10) via WorkManager, passing the content URI.
2. This service must run persistently with a low-priority foreground
   notification ("Watching for new photos") — required for reliable
   `ContentObserver` behavior on modern Android when the app isn't in the
   foreground.
3. Start this service on device boot (`BOOT_COMPLETED` receiver) and on
   app first launch, so it survives reboots without the user reopening the
   app.
4. Add a battery-optimization-exemption prompt on first launch — direct
   the user to the system settings screen to whitelist the app, since
   Doze mode can otherwise delay the ContentObserver callbacks
   significantly. Explain in a short in-app dialog why this permission is
   being requested before showing the system prompt.
5. Respect the Wi-Fi-only setting (stub the setting for now if Prompt 11's
   settings screen doesn't exist yet — default to `false`/off) when
   deciding whether to enqueue the worker immediately or wait for a
   `NetworkType.UNMETERED` WorkManager constraint.

## Acceptance criteria

- Taking a photo on a test device with the app installed and the service
  running results in a new `BackupUploadWorker` being enqueued within a
  few seconds (verify via Logcat, actual upload logic comes in Prompt 10).
- Rebooting the device and NOT opening the app still results in the
  service running (check via `adb shell dumpsys activity services`).
- The foreground notification is visible but low-priority (doesn't make
  sound/vibrate).
