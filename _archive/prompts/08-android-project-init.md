# Prompt 08 — Android project setup (Kotlin + Jetpack Compose)

**Feed this to Claude Code / Antigravity, working inside the `android/`
folder. A basic Gradle scaffold already exists — open it in Android Studio
first to let Gradle sync, then run this prompt.**

---

## Context

Native Android app, minimum SDK 26 (Android 8, needed for reliable
background work via WorkManager + notification channels), target latest
stable SDK. Jetpack Compose for UI. Reference `PRD.md` section 9.

## Task

1. Verify/complete `android/app/build.gradle.kts` dependencies:
   - `androidx.work:work-runtime-ktx` (WorkManager)
   - `androidx.room:room-runtime` + `room-ktx` + `room-compiler` (ksp)
   - `androidx.compose` BOM + Material3
   - `com.squareup.retrofit2:retrofit` + a JSON converter (moshi or
     kotlinx.serialization — pick one and be consistent)
   - `com.squareup.okhttp3:okhttp` with a logging interceptor for debug
     builds only
2. Set up `AndroidManifest.xml` with required permissions:
   - `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` (Android 13+) and the legacy
     `READ_EXTERNAL_STORAGE` for older versions, correctly scoped by SDK
     version
   - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`
   - `POST_NOTIFICATIONS` (Android 13+)
   - `INTERNET`, `ACCESS_NETWORK_STATE`
3. Create the base package structure under
   `com.murshed.kdrive`:
   - `data/` (Room entities/DAOs — filled in Prompt 10)
   - `worker/` (WorkManager workers — filled in Prompt 09/10)
   - `network/` (Retrofit API interface — filled in Prompt 10)
   - `ui/` (Compose screens — filled in Prompt 11)
   - `MainActivity.kt` — minimal Compose scaffold with a placeholder
     "KDrive" home screen, runtime permission request flow for the
     media + notification permissions listed above.
4. Add a `BuildConfig` field or `local.properties`-sourced value for the
   backend base URL and the `x-kdrive-device-key` from Prompt 05, so it's
   not hardcoded in source.

## Acceptance criteria

- Project builds and installs on a device/emulator (`./gradlew
  installDebug`) showing the placeholder home screen.
- Runtime permission prompts appear correctly on first launch for media
  access and notifications.
- No hardcoded secrets in committed source — backend URL/key read from
  `local.properties` (gitignored) or `BuildConfig`.
