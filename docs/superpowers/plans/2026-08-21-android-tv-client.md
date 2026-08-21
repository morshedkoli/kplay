# Android TV Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adapt the existing `android-tv/` scaffold from the old movies-only API to the current KDrive media-server API (`/api/media/*`), adding series/episode browsing and playback-resume.

**Architecture:** Kotlin + Jetpack Compose for TV + Media3/ExoPlayer, unchanged project shell. Rewrite the data layer (`Models.kt`, `ApiClient.kt`) for the new endpoints and response shapes; extend `BrowseScreen`/`DetailScreen` for movies+series; wire resume-progress into `PlayerScreen`; fix the two call sites in `MainActivity.kt` that reference the old model fields.

**Tech Stack:** Kotlin, Jetpack Compose for TV (`androidx.tv:tv-foundation`/`tv-material` 1.0.0-alpha11), Media3/ExoPlayer 1.4.1, OkHttp 4.12.0, kotlinx.serialization 1.7.1, Coil 2.7.0 (all existing scaffold dependencies — no version changes).

**Spec:** `docs/superpowers/specs/2026-08-21-android-tv-client-design.md`

## Global Constraints

- Every HTTP request carries `x-kdrive-device-key: <device key>` (existing `DEVICE_KEY_HEADER` constant in `ApiClient.kt`) — no new auth mechanism.
- `/api/media/stream/[id]` accepts either a movie's `_id` or an episode's `_id` — the backend resolves both; the client never needs to know which kind an id is when building a stream URL.
- Poster images load directly from TMDb's CDN (`https://image.tmdb.org/t/p/w300{posterPath}`) — no server-side proxy, no auth header needed for image requests.
- `postProgress` failures are logged and swallowed — a missed progress beat must never interrupt or error out playback.
- No Android SDK/emulator exists in this environment — verification is static code review plus a compile attempt if a JDK/SDK turns out reachable; report explicitly if it isn't, never claim an unverified compile succeeded.
- Kept unchanged, do not touch: `MainActivity.kt`'s `AuthState` sealed class and overall structure (only two call sites inside it change — see Task 5), `ui/LoginScreen.kt`, `data/Prefs.kt`, gradle files, `AndroidManifest.xml`.

---

## Task 1: Rewrite data models (`data/Models.kt`)

**Files:**
- Modify: `android-tv/app/src/main/java/com/kdrive/tv/data/Models.kt`

**Interfaces:**
- Produces: `MediaItem(id, type, title, year, posterPath)`, `LibraryResponse(movies: List<MediaItem>, series: List<MediaItem>)`, `Episode(id, season, episode, title)`, `MediaDetail(id, type, title, year, description, episodes: List<Episode>)`, `ProgressResponse(positionSeconds: Double)` — all `@Serializable`, consumed by Task 2's `ApiClient`.

- [ ] **Step 1: Replace the file contents**

```kotlin
package com.kdrive.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors GET /api/media/list, GET /api/media/[id], GET /api/media/progress
// (app/api/media/list/route.js, app/api/media/[id]/route.js,
// app/api/media/progress/route.js).

@Serializable
data class MediaItem(
    @SerialName("_id") val id: String,
    val type: String, // "movie" | "series"
    val title: String,
    val year: Int? = null,
    val posterPath: String? = null,
)

@Serializable
data class LibraryResponse(
    val movies: List<MediaItem>,
    val series: List<MediaItem>,
)

@Serializable
data class Episode(
    @SerialName("_id") val id: String,
    val season: Int,
    val episode: Int,
    val title: String? = null,
)

@Serializable
data class MediaDetail(
    @SerialName("_id") val id: String,
    val type: String,
    val title: String,
    val year: Int? = null,
    val description: String? = null,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class ProgressResponse(val positionSeconds: Double)
```

- [ ] **Step 2: Verify it compiles in isolation**

Run: `"C:\Program Files\nodejs\node.exe" --version` is irrelevant here — this
is Kotlin, not Node. Instead check for a reachable JDK/Android toolchain:

Run: `where javac` (or `javac -version`)

If a JDK is found, this file has no external references besides
`kotlinx.serialization` (already a project dependency), so it will compile
once the project builds as a whole (Task 6's build check). If no JDK is
reachable, note that in this task's report and rely on manual review:
confirm every field name matches the real API JSON keys exactly (`_id`,
`type`, `title`, `year`, `posterPath`, `movies`, `series`, `season`,
`episode`, `description`, `episodes`, `positionSeconds`) by re-reading
`app/api/media/list/route.js`, `app/api/media/[id]/route.js`, and
`app/api/media/progress/route.js` in the main repo.

- [ ] **Step 3: Commit**

```bash
git add android-tv/app/src/main/java/com/kdrive/tv/data/Models.kt
git commit -m "feat(tv): rewrite data models for the media-server API"
```

---

## Task 2: Rewrite the API client (`data/ApiClient.kt`)

**Files:**
- Modify: `android-tv/app/src/main/java/com/kdrive/tv/data/ApiClient.kt`

**Interfaces:**
- Consumes: `MediaItem`, `LibraryResponse`, `Episode`, `MediaDetail`, `ProgressResponse` (Task 1)
- Produces: `ApiClient.listLibrary(): LibraryResponse`, `ApiClient.getDetail(id: String): MediaDetail`, `ApiClient.getProgress(id: String): Double`, `ApiClient.postProgress(id: String, positionSeconds: Double)`, `ApiClient.streamUrl(id: String): String`, `ApiClient.posterUrl(posterPath: String): String`, `ApiClient.authHeaders(): Map<String,String>` (kept) — consumed by Tasks 3, 4, 5, 6.

- [ ] **Step 1: Replace the file contents**

```kotlin
package com.kdrive.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val DEVICE_KEY_HEADER = "x-kdrive-device-key" // matches lib/auth.js

class ApiError(message: String, val httpStatus: Int? = null) : Exception(message)

/** Thin client for the KDrive media-server API — GET /api/media/list,
 * GET /api/media/[id], GET/POST /api/media/progress. Streaming and poster
 * URLs are built directly (no fetch), see streamUrl()/posterUrl() below. */
class ApiClient(private val credentials: Credentials) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun request(path: String) = Request.Builder()
        .url("${credentials.serverUrl}$path")
        .header(DEVICE_KEY_HEADER, credentials.deviceKey)
        .build()

    private suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request(path)).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw ApiError("Request to $path failed (HTTP ${res.code})", res.code)
                }
                body
            }
        } catch (e: IOException) {
            throw ApiError("Network error reaching ${credentials.serverUrl}: ${e.message}")
        }
    }

    suspend fun listLibrary(): LibraryResponse =
        json.decodeFromString(LibraryResponse.serializer(), getText("/api/media/list"))

    suspend fun getDetail(id: String): MediaDetail =
        json.decodeFromString(MediaDetail.serializer(), getText("/api/media/$id"))

    suspend fun getProgress(id: String): Double =
        json.decodeFromString(
            ProgressResponse.serializer(),
            getText("/api/media/progress?id=$id"),
        ).positionSeconds

    /** Fire-and-forget — a failed progress beat must never interrupt playback. */
    suspend fun postProgress(id: String, positionSeconds: Double) = withContext(Dispatchers.IO) {
        try {
            val body = """{"id":"$id","positionSeconds":$positionSeconds}"""
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${credentials.serverUrl}/api/media/progress")
                .header(DEVICE_KEY_HEADER, credentials.deviceKey)
                .post(body)
                .build()
            http.newCall(req).execute().close()
        } catch (e: IOException) {
            // swallow — see class doc
        }
    }

    /** URL Media3 streams from — resolves either a movie or an episode id
     * (app/api/media/stream/[id]/route.js). */
    fun streamUrl(id: String) = "${credentials.serverUrl}/api/media/stream/$id"

    /** TMDb CDN, no auth needed. */
    fun posterUrl(posterPath: String) = "https://image.tmdb.org/t/p/w300$posterPath"

    fun authHeaders(): Map<String, String> = mapOf(DEVICE_KEY_HEADER to credentials.deviceKey)
}
```

- [ ] **Step 2: Manual verification against the real routes**

Re-read (in the main repo, not `android-tv/`) `app/api/media/list/route.js`,
`app/api/media/[id]/route.js`, `app/api/media/stream/[id]/route.js`, and
`app/api/media/progress/route.js`. Confirm: `listLibrary()`'s path/response
shape matches `list/route.js`'s `Response.json({movies, series})`;
`getDetail()`'s path matches `[id]/route.js`'s route param and response
shape; `getProgress()`/`postProgress()`'s query-param and JSON-body shapes
match `progress/route.js`'s `GET`/`POST` handlers exactly (field names
`id`, `positionSeconds`).

- [ ] **Step 3: Commit**

```bash
git add android-tv/app/src/main/java/com/kdrive/tv/data/ApiClient.kt
git commit -m "feat(tv): rewrite API client for /api/media/* endpoints"
```

---

## Task 3: Drop the poster-proxy image loader, simplify `MainActivity.kt`'s image plumbing

**Files:**
- Delete: `android-tv/app/src/main/java/com/kdrive/tv/data/ImageLoading.kt`

**Interfaces:**
- Consumes: nothing
- Produces: nothing (removal only) — Task 4's `BrowseScreen` uses Coil's default `AsyncImage` (no custom `ImageLoader` needed) since TMDb's CDN needs no auth header; Task 5 removes the corresponding `authenticatedImageLoader(...)` call and its import from `MainActivity.kt`.

- [ ] **Step 1: Delete the file**

`ImageLoading.kt` existed only because posters used to be served from
KDrive's own server behind the device-key auth gate. Posters now come
directly from TMDb's public CDN (`ApiClient.posterUrl()`, Task 2) — no
auth header is needed for image requests, so the custom `ImageLoader`
with its auth interceptor is no longer used by anything.

```bash
git rm android-tv/app/src/main/java/com/kdrive/tv/data/ImageLoading.kt
```

- [ ] **Step 2: Confirm nothing else references it**

Run: `grep -rn "authenticatedImageLoader\|ImageLoading" android-tv/app/src/main/java`

Expected: only the `MainActivity.kt` call site remains (fixed in Task 5 —
don't fix it here, just confirm it's the only remaining reference so
Task 5's removal is complete).

- [ ] **Step 3: Commit**

```bash
git commit -m "refactor(tv): drop poster-proxy image loader, posters load from TMDb directly"
```

---

## Task 4: Rewrite `BrowseScreen.kt` for movies + series rows

**Files:**
- Modify: `android-tv/app/src/main/java/com/kdrive/tv/ui/BrowseScreen.kt`

**Interfaces:**
- Consumes: `ApiClient.listLibrary()`, `ApiClient.posterUrl()` (Task 2), `MediaItem`, `LibraryResponse` (Task 1)
- Produces: `BrowseScreen(api: ApiClient, onSelect: (MediaItem) -> Unit)` — consumed by Task 6's `MainActivity.kt` nav graph. Note the signature drops the `imageLoader` parameter Task 3 made obsolete.

- [ ] **Step 1: Replace the file contents**

```kotlin
package com.kdrive.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text as TvText
import coil.compose.AsyncImage
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.LibraryResponse
import com.kdrive.tv.data.MediaItem

/** TV-focusable Movies/Shows rows, D-pad navigable. Mirrors app/Library.js's
 * two-row layout (Movies row, Shows row). */
@Composable
fun BrowseScreen(
    api: ApiClient,
    onSelect: (MediaItem) -> Unit,
) {
    var library by remember { mutableStateOf<LibraryResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            library = api.listLibrary()
        } catch (e: Exception) {
            error = e.message ?: "Failed to load library"
        }
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Couldn't load library: $error", color = MaterialTheme.colorScheme.error)
        }
        library == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        library!!.movies.isEmpty() && library!!.series.isEmpty() -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("No media yet — add some from the KDrive web app.")
        }
        else -> Column(Modifier.fillMaxSize().padding(vertical = 32.dp)) {
            val lib = library!!
            if (lib.movies.isNotEmpty()) {
                MediaRow(title = "Movies", items = lib.movies, api = api, onSelect = onSelect)
            }
            if (lib.series.isNotEmpty()) {
                MediaRow(title = "Shows", items = lib.series, api = api, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun MediaRow(
    title: String,
    items: List<MediaItem>,
    api: ApiClient,
    onSelect: (MediaItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        TvText(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 32.dp, bottom = 12.dp),
        )
        TvLazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items) { item ->
                PosterCard(item = item, api = api, onClick = { onSelect(item) })
            }
        }
    }
}

@Composable
private fun PosterCard(item: MediaItem, api: ApiClient, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.aspectRatio(2f / 3f)) {
        Box(Modifier.fillMaxSize()) {
            if (item.posterPath != null) {
                AsyncImage(
                    model = api.posterUrl(item.posterPath),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TvText(item.title, color = Color.White, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android-tv/app/src/main/java/com/kdrive/tv/ui/BrowseScreen.kt
git commit -m "feat(tv): rewrite BrowseScreen with Movies/Shows rows"
```

---

## Task 5: Rewrite `DetailScreen.kt` for movies + series episode lists

**Files:**
- Modify: `android-tv/app/src/main/java/com/kdrive/tv/ui/DetailScreen.kt`

**Interfaces:**
- Consumes: `ApiClient.getDetail()` (Task 2), `MediaDetail`, `Episode` (Task 1)
- Produces: `DetailScreen(id: String, api: ApiClient, onPlay: (id: String) -> Unit)` — consumed by Task 6's nav graph. Note: `onPlay` now takes a plain `String` id (movie id or episode id), replacing the old `(Movie) -> Unit` shape, since `streamUrl()` only needs an id.

- [ ] **Step 1: Replace the file contents**

```kotlin
package com.kdrive.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.Episode
import com.kdrive.tv.data.MediaDetail

@Composable
fun DetailScreen(
    id: String,
    api: ApiClient,
    onPlay: (id: String) -> Unit,
) {
    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        try {
            detail = api.getDetail(id)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load title"
        }
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        detail == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> {
            val d = detail!!
            Column(Modifier.fillMaxSize().padding(48.dp)) {
                Text(
                    if (d.year != null) "${d.title} (${d.year})" else d.title,
                    style = MaterialTheme.typography.headlineLarge,
                )
                if (d.description != null) {
                    Text(
                        d.description,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp).width(720.dp),
                    )
                }

                if (d.type == "movie") {
                    Button(
                        onClick = { onPlay(d.id) },
                        modifier = Modifier.padding(top = 32.dp),
                    ) {
                        Text("Play")
                    }
                } else {
                    EpisodeList(episodes = d.episodes, onPlay = onPlay)
                }
            }
        }
    }
}

@Composable
private fun EpisodeList(episodes: List<Episode>, onPlay: (id: String) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().padding(top = 32.dp)) {
        items(episodes) { ep ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "S${ep.season}E${ep.episode} — ${ep.title ?: "Episode ${ep.episode}"}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(560.dp),
                )
                Button(onClick = { onPlay(ep.id) }) {
                    Text("Play")
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android-tv/app/src/main/java/com/kdrive/tv/ui/DetailScreen.kt
git commit -m "feat(tv): rewrite DetailScreen with series episode list"
```

---

## Task 6: Wire playback resume into `PlayerScreen.kt`

**Files:**
- Modify: `android-tv/app/src/main/java/com/kdrive/tv/ui/PlayerScreen.kt`

**Interfaces:**
- Consumes: `ApiClient.getProgress()`, `ApiClient.postProgress()`, `ApiClient.streamUrl()`, `ApiClient.authHeaders()` (Task 2)
- Produces: `PlayerScreen(id: String, api: ApiClient)` — same signature shape as before but the parameter is now a generic `id` (movie or episode id) rather than `fileId`, consumed by Task 7's nav graph.

- [ ] **Step 1: Replace the file contents**

```kotlin
package com.kdrive.tv.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.kdrive.tv.data.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val PROGRESS_POST_INTERVAL_MS = 10_000L

/**
 * Plays from /api/media/stream/[id] (Range/206-capable — direct-play only,
 * no server-side transcoding). Resumes from the last saved position and
 * periodically reports progress back, mirroring app/title/[id]/page.js's
 * web behavior.
 */
@Composable
fun PlayerScreen(id: String, api: ApiClient) {
    val context = LocalContext.current

    val player = remember {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(api.authHeaders())

        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(ExoMediaItem.fromUri(api.streamUrl(id)))

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    // Seek to the saved position once the player is ready to accept it.
    LaunchedEffect(id) {
        val resumeSeconds = try {
            api.getProgress(id)
        } catch (e: Exception) {
            0.0
        }
        if (resumeSeconds > 0) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        player.seekTo((resumeSeconds * 1000).toLong())
                        player.removeListener(this)
                    }
                }
            }
            player.addListener(listener)
        }
    }

    // Periodically report position while playing.
    LaunchedEffect(id) {
        while (isActive) {
            delay(PROGRESS_POST_INTERVAL_MS)
            if (player.isPlaying) {
                api.postProgress(id, player.currentPosition / 1000.0)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            PlayerView(context).apply {
                this.player = player
                useController = true
            }
        },
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add android-tv/app/src/main/java/com/kdrive/tv/ui/PlayerScreen.kt
git commit -m "feat(tv): resume playback from saved position, report progress periodically"
```

---

## Task 7: Fix `MainActivity.kt`'s two stale call sites and nav graph

**Files:**
- Modify: `android-tv/app/src/main/java/com/kdrive/tv/MainActivity.kt`

**Interfaces:**
- Consumes: `ApiClient.listLibrary()` (Task 2), `BrowseScreen(api, onSelect)` (Task 4), `DetailScreen(id, api, onPlay)` (Task 5), `PlayerScreen(id, api)` (Task 6), `MediaItem.id` (Task 1)
- Produces: nothing new — this wires everything else together into the app's nav graph.

The file's `AuthState` sealed class, `MainActivity` class shell, and
overall `setContent { ... }` structure are unaffected — only the login
validation call and the `AppNav` composable's body change.

- [ ] **Step 1: Fix the login validation call**

Find this line inside the `onSubmit` lambda:

```kotlin
ApiClient(Credentials(serverUrl.trimEnd('/'), deviceKey)).listMovies()
```

Replace with:

```kotlin
ApiClient(Credentials(serverUrl.trimEnd('/'), deviceKey)).listLibrary()
```

- [ ] **Step 2: Replace the `AppNav` composable**

Replace the entire `AppNav` function with:

```kotlin
@androidx.compose.runtime.Composable
private fun AppNav(credentials: Credentials) {
    val api = remember(credentials) { ApiClient(credentials) }
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "browse") {
        composable("browse") {
            BrowseScreen(
                api = api,
                onSelect = { item -> navController.navigate("detail/${item.id}") },
            )
        }
        composable(
            "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")!!
            DetailScreen(
                id = id,
                api = api,
                onPlay = { playId -> navController.navigate("player/${playId}") },
            )
        }
        composable(
            "player/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")!!
            PlayerScreen(id = id, api = api)
        }
    }
}
```

Note this drops the `imageLoader`/`authenticatedImageLoader(context, credentials)`
line and its now-unused `context`/`LocalContext.current` lookup (Task 3
made this obsolete) and the now-unused `import com.kdrive.tv.data.authenticatedImageLoader`
import at the top of the file — remove that import line too.

- [ ] **Step 3: Confirm no remaining references to old model fields**

Run: `grep -n "movieId\|fileId\|fileStatus\|posterKey\|listMovies\|getMovie(" android-tv/app/src/main/java/com/kdrive/tv/MainActivity.kt`

Expected: no matches. If any remain, they were missed in Steps 1-2 — fix
them following the same id-based pattern used above.

- [ ] **Step 4: Commit**

```bash
git add android-tv/app/src/main/java/com/kdrive/tv/MainActivity.kt
git commit -m "fix(tv): update MainActivity nav graph and login check for new API"
```

---

## Task 8: Whole-project consistency check and compile attempt

**Files:**
- No new files — verification only.

**Interfaces:**
- Consumes: everything from Tasks 1-7.
- Produces: a pass/fail report on whether the rewritten client is internally consistent and (if a toolchain is reachable) compiles.

- [ ] **Step 1: Grep for any remaining reference to the old API/model shape anywhere in the module**

Run: `grep -rn "movieId\|fileId\|posterKey\|fileStatus\|/api/movies\|/api/backup\|listMovies\|getMovie(\|MoviesPage\|data class Movie\b" android-tv/app/src/main/java`

Expected: no matches anywhere in `android-tv/app/src/main/java`. Any match
is a missed spot from Tasks 1-7 — fix it in the corresponding file,
following that task's established pattern, before proceeding.

- [ ] **Step 2: Attempt a build if a toolchain is reachable**

Run: `where javac` and check for an Android SDK (e.g. `echo $env:ANDROID_HOME` /
`echo $ANDROID_SDK_ROOT`, or look for `android-tv/local.properties`).

If both a JDK and an Android SDK are present: `cd android-tv && ./gradlew assembleDebug`
(or `gradlew.bat` on Windows) and report the real result.

If either is missing: report clearly that no compile was possible in this
environment, and that manual review (Step 1's grep, plus a careful
line-by-line read of Tasks 1-7's diffs against the real API route files
in the main repo) is the verification that was actually performed. Do
NOT claim a successful build without having run one.

- [ ] **Step 3: Update `android-tv/README.md`**

Per the spec's Migration Notes: replace
`/api/backup/download/[fileId]?inline` references with
`/api/media/stream/[id]`, replace `/api/movies` references with
`/api/media/list`, and update the "Known limitations" section to remove
"No continue-watching sync" (now implemented — Task 6) and note that
series/episode browsing is now supported (no longer movies-only).

- [ ] **Step 4: Commit**

```bash
git add android-tv/README.md
git commit -m "docs(tv): update README for the media-server API and resume support"
```

---

## Notes for the executor

- Tasks 1 and 2 must land before Tasks 4, 5, 6 (data layer before the
  screens that consume it) — execute in the numbered order.
- Task 3 (deleting `ImageLoading.kt`) can run any time after Task 2, but
  must land before Task 7 removes its call site in `MainActivity.kt`, so
  Task 7 depends on Task 3 having already happened. Keep the numbered
  order.
- No Android SDK/emulator exists in this environment as of this plan's
  writing — every task's verification is manual/static unless Task 8
  discovers a reachable toolchain. Do not let this stall the plan; report
  honestly and move on, per this project's established practice for
  unverifiable-live-path gaps (see the backend plan's precedent).
- The real first verification of this app happens when the user builds
  and sideloads it per `android-tv/README.md`'s existing "Getting it
  running" instructions (unchanged by this plan) against the live,
  credentialed KDrive server.
