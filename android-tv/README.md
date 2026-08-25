# kPlay for Android TV

Native Android TV app (Kotlin, Jetpack Compose for TV, Media3/ExoPlayer) for browsing and playing
everything in your kPlay library — movies and series alike.

## What this is

- **Login** — enter your kPlay server URL and device key (`KDRIVE_DEVICE_KEY` from your server's
  env). Validated against `GET /api/media/list` before saving.
- **Browse** — a D-pad-navigable poster grid from `GET /api/media/list`, showing **Movies** and
  **Series** as two labelled sections. Series tiles carry an episode count. Posters load straight
  from TMDb's public CDN, with no auth header sent to it.
- **Detail** — title, year, description, and either a Play button (movie) or a focusable
  season-by-season episode list (series).
- **Player** — Media3 `ExoPlayer` streaming from `/api/media/stream/[id]`, the same Range-capable
  endpoint the web player uses. The route accepts a media `_id` or an episode `_id`, so one player
  screen covers both. Playback is configured in `data/Playback.kt`: a 2 GB on-disk read-through
  cache, deep buffers with a 30-second back-buffer, decoder fallback, and extractor workarounds for
  the containers people actually own. A failure now shows what went wrong and offers a retry
  instead of spinning forever.
- **Audio tracks** — press **Menu** (or **Info**) during playback on a file with more than one
  soundtrack to pick a language. Tracks are labelled by name, then by language, with channel count
  and codec underneath; a track this device can't decode is listed and marked, not hidden.
- **Sync** — the bottom entry in the nav rail runs `POST /api/media/scan`, importing anything new in
  the Drive folder and re-matching whatever missed TMDb earlier, then reloads the library. Same
  request the web sidebar's Sync button fires, so a file dropped into Drive reaches the TV without
  going anywhere near a browser.
- **Continue-watching sync** — position is fetched and saved through `/api/media/progress`, keyed
  by whatever is playing (media `_id` for a movie, episode `_id` for an episode). That's the same
  key the web player posts under, so a part-watched episode resumes at the same spot on either
  client.

Auth is the same `x-kdrive-device-key` header the server accepts for non-browser clients
(`lib/auth.js`) — no separate auth mechanism.

Uploading media is not part of this app. Files go into the Drive folder directly; **Sync** in the
nav rail imports them.

## Getting an installable APK

**Easiest — let CI build it.** Pushing any change under `android-tv/` runs
`.github/workflows/android-tv.yml`, which builds a debug APK on a GitHub runner and attaches it to
the run. You can also start it by hand: Actions → *Android TV APK* → **Run workflow**. Download the
`kplay-tv-debug-apk` artifact when it finishes.

This route exists because building an APK needs a JDK and the Android SDK, and the runners already
have both.

**Locally**, if you have an Android toolchain: `gradle/wrapper/gradle-wrapper.jar` is deliberately
not committed (it's a binary), so either

1. open this `android-tv/` folder in **Android Studio** — it regenerates the wrapper and syncs, or
2. run `gradle wrapper` once here to fill in the jar, then `./gradlew assembleDebug`.

## Installing on the TV

The APK is debug-signed, which is all a sideloaded app needs.

With the TV in developer mode (Settings → Device Preferences → About → tap Build 7 times, then
enable USB/network debugging):

```bash
adb connect YOUR_TV_IP:5555
```

```bash
adb install -r app-debug.apk
```

Without adb, copy the APK to the TV with a sideloading app such as *Send Files to TV*, then open it
with a file manager and allow installs from that source.

It appears on the Android TV home row — the manifest declares `LEANBACK_LAUNCHER` and marks
touchscreen as not required.

## Server note: streaming chunk cap

The server's stream route supports `STREAM_MAX_CHUNK_BYTES`, which truncates each response so no
single request approaches a serverless function's time limit.

**Leave it unset for this app.** ExoPlayer's `ProgressiveMediaSource` opens an open-ended range and
expects it to run to the end of the file; a truncated range may end playback early. Unset (the
default) is correct for a VPS deployment and for both clients.

If your server runs on Vercel and you have set that variable, the web player works but this app may
stop partway through a title. Deploying the server on a VPS avoids the trade entirely.

## Known limitations

- **No subtitles, no DRM, no Chromecast/cast support.**
- **No offline downloads.**
- **Not verified against a real build.** There's no Android SDK or emulator in the environment this
  was written in, so the code has been reviewed but not compiled or run on a device. Expect to fix
  minor issues on first build — the `androidx.tv:*` artifacts are still in alpha and their API
  surface moves between releases. The CI workflow is the fastest way to find out.
- **Playback depends on the container and codec.** Files are streamed as-is with no transcoding, so
  ExoPlayer has to support what's in them. It handles MKV/H.264 natively; exotic codecs may not
  play.
