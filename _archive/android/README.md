# KDrive — Android app

## First open

The Gradle wrapper JAR binary (`gradle/wrapper/gradle-wrapper.jar`) isn't
included in this zip — binary files don't survive well outside a real git
repo / IDE export. To fix on first open:

1. Open this `android/` folder directly in Android Studio.
2. Android Studio will offer to regenerate the wrapper automatically, or
   run: `gradle wrapper --gradle-version 8.7` from a terminal if you have
   any local Gradle install (even a different version — it only needs to
   bootstrap the wrapper once).
3. Let Gradle sync. It should resolve cleanly against the dependencies in
   `app/build.gradle.kts`.

## Then

Copy `local.properties.example` → `local.properties`, fill in your backend
URL and device key (must match `KDRIVE_DEVICE_KEY` in the backend's
`.env.local`).

Then work through `../prompts/08-android-project-init.md` onward with
Claude Code or Antigravity.
