plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    // Renders Compose to PNG on the JVM, so the UI can be reviewed without a
    // device — this machine has no hypervisor, so no emulator can boot.
    id("io.github.takahirom.roborazzi") version "1.26.0" apply false
}
