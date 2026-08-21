plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Reads a build-time value from a Gradle property (`-PsomeName=…` or
 * gradle.properties) falling back to an environment variable, then to "".
 * Trailing slashes are stripped so the client can concatenate paths safely.
 */
fun resolveConfig(gradleProperty: String, envVar: String): String =
    (project.findProperty(gradleProperty) as String? ?: System.getenv(envVar) ?: "")
        .trim()
        .trimEnd('/')

android {
    namespace = "com.kdrive.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kdrive.tv"
        minSdk = 25 // Android TV (Leanback) minimum
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Baked-in server config, so an installed APK runs with no setup
        // screen at all — a TV remote is a miserable way to type a URL and a
        // random key.
        //
        // Values come from -P flags, gradle.properties, or the environment,
        // in that order. Both default to empty, and when either is empty the
        // app falls back to asking on first launch — that keeps a plain
        // `gradlew assembleDebug` working for anyone without the values.
        //
        // NOTE: the device key ends up readable inside the APK. Anyone who
        // gets the file can extract it and reach the server. That is the
        // trade for zero-setup install; rotate KDRIVE_DEVICE_KEY if an APK
        // leaks.
        buildConfigField(
            "String",
            "SERVER_URL",
            "\"${resolveConfig("kdriveServerUrl", "KDRIVE_SERVER_URL")}\"",
        )
        buildConfigField(
            "String",
            "DEVICE_KEY",
            "\"${resolveConfig("kdriveDeviceKey", "KDRIVE_DEVICE_KEY")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))

    // Declared explicitly rather than relied on transitively through the
    // androidx.tv artifacts — those pull compose in as an implementation
    // detail, and dropping tv-foundation took the transitive path with it.
    // Versions come from the BOM above.
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // No androidx.tv artifacts. Their lazy layouts are deprecated and their
    // material components are still moving between releases; the UI is built
    // on stable compose-foundation with hand-rolled focus treatment instead,
    // which is also what lets focus drive the browse hero.

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Playback — same Range/206-capable download endpoint the web player uses.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Networking — plain OkHttp + kotlinx.serialization, no Retrofit needed
    // for this small an API surface.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Focus traversal is the thing most likely to be broken and the thing
    // hardest to eyeball, so it gets tests that run on the JVM — Robolectric
    // means no device or emulator is needed to prove the D-pad works.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
