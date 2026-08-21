# Android TV Client — Design

Status: approved for planning
Date: 2026-08-21

## Context

KDrive's backend was rebuilt (see `docs/superpowers/specs/2026-08-20-media-server-backend-design.md`,
now merged) into a Jellyfin-style media server: Google Drive storage,
MongoDB library index, TMDb metadata, direct-play streaming with Range
support, and watch-progress resume — all live and verified against real
credentials. This spec covers sub-project 2: a native Android TV client
to browse and play that library.

An earlier, unverified scaffold already exists at `android-tv/` — Kotlin,
Jetpack Compose for TV (`androidx.tv:*` 1.0.0-alpha11), Media3/ExoPlayer
1.4.1, OkHttp 4.12.0 + kotlinx.serialization for networking, Coil for
images. It was built against the OLD `/api/movies` + `/api/backup/download`
endpoints (both now removed) and has no concept of series/episodes. Its
project shell, auth mechanism, and player plumbing are sound and are kept;
its data layer and screens are rewritten against the current API.

## Out of scope for this spec

- Subtitles, DRM, Chromecast/casting.
- Offline downloads.
- Server-side transcoding (the backend is direct-play only; unchanged).
- CI/automated device testing — no Android SDK/emulator exists in this
  build environment. Verification is static review plus a compile check
  if a JDK/SDK turns out to be reachable.
- Multi-user profiles (the backend is single-user, single-Drive; the app
  has one device-key pairing, no account switching).

## Architecture

Single Android TV app, same shape as the existing scaffold: a Compose-for-TV
UI backed by a thin OkHttp/kotlinx.serialization API client, ExoPlayer for
playback. No local database — the app is a stateless client over the
KDrive HTTP API, with only server URL + device key persisted locally
(`Prefs.kt`, `SharedPreferences`, unchanged from the scaffold).

```
LoginScreen (server URL + device key, validated against GET /api/media/list)
   │
   ▼
BrowseScreen (Movies row + Shows row, GET /api/media/list)
   │  select item
   ▼
DetailScreen (GET /api/media/[id]; episode list if type == series)
   │  press Play (movie, or an episode row)
   ▼
PlayerScreen (GET /api/media/progress?id=… → seek; ExoPlayer streams
              /api/media/stream/[id]; periodic POST /api/media/progress)
```

Auth: unchanged from the scaffold — every request carries
`x-kdrive-device-key: <device key>`, the same header `lib/auth.js`'s
`requireDeviceOrSession` already accepts. No new auth mechanism.

## Components

### `data/Models.kt` (rewritten)

Replaces `Movie`/`MoviesPage` with types matching the real API responses
(`app/api/media/list/route.js`, `app/api/media/[id]/route.js`,
`app/api/media/progress/route.js`):

```kotlin
@Serializable
data class MediaItem(
    val id: String,        // Mongo _id, mapped via @SerialName("_id")
    val type: String,      // "movie" | "series"
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
    val id: String,        // @SerialName("_id")
    val season: Int,
    val episode: Int,
    val title: String? = null,
)

@Serializable
data class MediaDetail(
    val id: String,        // @SerialName("_id")
    val type: String,
    val title: String,
    val year: Int? = null,
    val description: String? = null,
    val episodes: List<Episode> = emptyList(),  // populated only for series
)

@Serializable
data class ProgressResponse(val positionSeconds: Double)
```

No pagination wrapper — `/api/media/list` returns the whole library in
one response (single-user, small-library assumption already made by the
web client).

### `data/ApiClient.kt` (rewritten)

```kotlin
class ApiClient(private val credentials: Credentials) {
    // existing OkHttpClient/json/getText/request() plumbing kept as-is

    suspend fun listLibrary(): LibraryResponse =
        json.decodeFromString(LibraryResponse.serializer(), getText("/api/media/list"))

    suspend fun getDetail(id: String): MediaDetail =
        json.decodeFromString(MediaDetail.serializer(), getText("/api/media/$id"))

    suspend fun getProgress(id: String): Double =
        json.decodeFromString(ProgressResponse.serializer(), getText("/api/media/progress?id=$id"))
            .positionSeconds

    suspend fun postProgress(id: String, positionSeconds: Double) = withContext(Dispatchers.IO) {
        val body = """{"id":"$id","positionSeconds":$positionSeconds}"""
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${credentials.serverUrl}/api/media/progress")
            .header(DEVICE_KEY_HEADER, credentials.deviceKey)
            .post(body)
            .build()
        http.newCall(req).execute().use { /* fire-and-forget; log on failure, don't throw */ }
    }

    fun streamUrl(id: String) = "${credentials.serverUrl}/api/media/stream/$id"
    fun posterUrl(posterPath: String) = "https://image.tmdb.org/t/p/w300$posterPath"
    fun authHeaders(): Map<String, String> = mapOf(DEVICE_KEY_HEADER to credentials.deviceKey)
}
```

`streamUrl(id)` accepts either a movie's id or an episode's id — the
backend's `stream/[id]` route already resolves both (tries `media`, falls
back to `episode`). Poster URLs no longer go through the server at all;
they hit TMDb's CDN directly with the `posterPath` the API already
returns, same as the web client (`app/Library.js`).

### `ui/BrowseScreen.kt` (rewritten)

Two `TvLazyVerticalGrid` rows — "Movies" and "Shows" — each populated
from `LibraryResponse.movies` / `.series`, replacing the scaffold's single
flat grid. Poster card logic (focus, `AsyncImage` via Coil, title
fallback when no poster) is kept, pointed at the new `posterUrl()`.

### `ui/DetailScreen.kt` (rewritten)

Fetches `GET /api/media/[id]`. For `type == "movie"`: title, description,
year, one Play button → `PlayerScreen(detail.id)`. For `type == "series"`:
same header plus a focusable, D-pad-navigable list of episodes (season/
episode/title), each row's Play → `PlayerScreen(episode.id)`.

### `ui/PlayerScreen.kt` (extended)

Keeps the scaffold's ExoPlayer/`PlayerView` setup and auth-header data
source unchanged. Adds:
- On entry: `LaunchedEffect` fetches `api.getProgress(id)`; once
  `Player.Listener.onPlaybackStateChanged` reports `STATE_READY`, call
  `player.seekTo(positionMs)` (converted from the fetched seconds).
- A coroutine loop (cancelled in the existing `DisposableEffect.onDispose`)
  that every ~10 seconds, while playing, calls
  `api.postProgress(id, player.currentPosition / 1000.0)` — mirrors the
  web client's throttled `onTimeUpdate` behavior
  (`app/title/[id]/page.js`).

### `ui/LoginScreen.kt`, `data/Prefs.kt`, `MainActivity.kt` (unchanged)

Device-key + server-URL pairing stays exactly as scaffolded; validate
against `GET /api/media/list` instead of the old `GET /api/movies` (only
the validation endpoint changes, not the flow).

## Data Flow

1. `LoginScreen` collects server URL + device key, validates with
   `GET /api/media/list`, persists via `Prefs.kt`.
2. `BrowseScreen` calls `listLibrary()`, renders Movies/Shows rows.
3. Selecting a poster navigates to `DetailScreen(item.id)`, which calls
   `getDetail(id)`.
4. Pressing Play (movie or an episode row) navigates to
   `PlayerScreen(id)`.
5. `PlayerScreen` fetches saved progress, builds the ExoPlayer media
   source from `streamUrl(id)` with the device-key header, seeks once
   ready, plays, and periodically reports position back.

## Error Handling

Kept from the scaffold: `ApiError` wraps network/HTTP failures, surfaced
as inline error text on `BrowseScreen`/`DetailScreen`. A stream failure
(404 missing item, 502 Drive read failure) surfaces as ExoPlayer's own
player-error UI — no bespoke handling added, matching the scaffold's
existing "let Media3 handle it" approach. `postProgress` failures are
logged and swallowed (fire-and-forget) — a missed progress beat must
never interrupt playback.

## Testing

No Android SDK/emulator exists in this environment — same constraint the
scaffold's own README already discloses. Verification is: careful manual
code review of every changed/new file against the real API contracts
(cross-checked against the actual Next.js route handlers), plus a
`./gradlew assembleDebug` compile attempt if a JDK/SDK is reachable when
this is implemented. If not reachable, the plan must say so explicitly
rather than claim untested code compiles. First real verification happens
when the user builds and sideloads the APK per the existing README's
"Getting it running" instructions (kept as-is).

## Migration Notes

This is an adaptation, not a rewrite from zero:
- Kept unchanged: project/gradle structure, `MainActivity.kt`,
  `LoginScreen.kt`, `Prefs.kt`, dependency versions, the auth header
  mechanism, ExoPlayer/PlayerView setup in `PlayerScreen.kt`.
- Replaced: `Models.kt`, `ApiClient.kt` (new endpoints, new shapes, no
  more poster-proxy route).
- Extended: `BrowseScreen.kt` (two rows instead of one grid),
  `DetailScreen.kt` (episode list for series), `PlayerScreen.kt` (resume
  progress).
- `android-tv/README.md` needs its endpoint references updated
  (`/api/backup/download/[fileId]?inline` → `/api/media/stream/[id]`,
  `/api/movies` → `/api/media/list`) and its "Known limitations" section
  updated to note resume-progress is now implemented, not missing.
