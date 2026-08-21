# KDrive TV

Native Android TV app (Kotlin, Jetpack Compose for TV, Media3/ExoPlayer) for browsing and
playing movies added through the KDrive web app's Library.

## What this is

- **Login** — enter your KDrive server URL and device key (`KDRIVE_DEVICE_KEY` from `.env.local`).
  Validated against `GET /api/media/list` before saving.
- **Browse** — a D-pad-navigable poster grid from `GET /api/media/list` (movies only; posters load
  straight from TMDb's public CDN, no auth header sent to it).
- **Detail** — title, description, year, a "Play" button (disabled while TMDb matching is still
  `processing`).
- **Player** — Media3 `ExoPlayer` streaming from `/api/media/stream/[id]`, a Range/206-capable
  proxy in front of the Drive file — the same endpoint the web video player uses, so seeking works
  with zero extra server work.
- **Continue-watching sync** — position is fetched/saved through `/api/media/progress`, keyed by
  the media `_id`, the same endpoint and key the web player (`/movies/[id]`) uses — resume carries
  over between TV and web.

Auth is the same `x-kdrive-device-key` header the server already accepts for non-browser clients
(`lib/auth.js`) — no new auth mechanism.

## Getting it running

This project was written directly (not scaffolded from Android Studio). `gradlew` / `gradlew.bat`
and `gradle/wrapper/gradle-wrapper.properties` (pinned to Gradle 8.7) are committed, but
`gradle/wrapper/gradle-wrapper.jar` is not — it's a binary, not something to hand-author. Easiest
path:

1. Open this `android-tv/` folder directly in **Android Studio** (File → Open). Studio will
   regenerate the missing wrapper jar and sync automatically.
2. Or, if you have Gradle installed locally: run `gradle wrapper` once inside this directory (this
   fills in the missing jar using the pinned version from `gradle-wrapper.properties`), then
   `./gradlew assembleDebug`.
3. Sideload the resulting `app/build/outputs/apk/debug/app-debug.apk` onto your Android TV (e.g.
   `adb install app-debug.apk` with the TV in developer mode, or a sideloading app like
   "Send Files to TV").

## Known limitations / explicitly out of scope

This was scoped as a first pass, not a finished product:

- **No subtitles, no DRM, no Chromecast/cast support.**
- **No offline downloads.**
- **Not verified against a real build** — there's no Android SDK/emulator in the environment this
  was written in, so this has been reviewed carefully but not compiled or run on a device. Expect
  to fix minor issues (dependency version bumps, TV-Compose API drift) on first build — the
  `androidx.tv:*` artifacts are still in alpha and their API surface moves between releases.
- **First play of a new movie may buffer** — the server has no transcode/pre-fetch pipeline (by
  design, see the main repo's plan history); it relies on a disk cache that only warms up after
  the first full play.
