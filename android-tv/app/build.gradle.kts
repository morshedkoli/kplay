plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.kdrive.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kdrive.tv"
        minSdk = 25 // Android TV (Leanback) minimum
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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

    buildFeatures {
        compose = true
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

    // Compose for TV — focus-aware, D-pad-friendly components.
    //
    // tv-material only: tv-foundation's TvLazyColumn/TvLazyVerticalGrid were
    // deprecated after 1.0.0-alpha12 once focus handling landed in ordinary
    // compose-foundation, so the standard LazyColumn/LazyVerticalGrid are the
    // supported way to build a D-pad-navigable list now.
    implementation("androidx.tv:tv-material:1.0.0")

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
}
