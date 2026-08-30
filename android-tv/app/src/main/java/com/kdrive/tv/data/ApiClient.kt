package com.kdrive.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val DEVICE_KEY_HEADER = "x-kdrive-device-key" // matches lib/auth.js

/**
 * How long before a direct URL's token expires to stop reusing it.
 *
 * A range request opened at the last second still has to run, so the URL has
 * to outlive the moment it was handed out. Two minutes covers a connection
 * that opens slowly on a long path.
 */
private const val TOKEN_REFRESH_MARGIN_MS = 2 * 60 * 1000L

class ApiError(message: String, val httpStatus: Int? = null) : Exception(message)

@Serializable
private data class ProgressResponse(val positionSeconds: Double = 0.0)

@Serializable
private data class ProgressRequest(
    val id: String,
    val positionSeconds: Double,
    // Omitted rather than sent as zero when the player does not yet know it:
    // the server treats a stored duration as the truth about whether a title
    // is finished, so a wrong one is worse than none at all.
    val durationSeconds: Double? = null,
)

/**
 * What the television reports back about how a playback actually went.
 *
 * The server can measure the bytes it sent but not what the box did with
 * them; this closes that gap so /admin/monitor can tell a slow link from a
 * struggling decoder. See data/PlaybackTelemetry.kt for where it is filled in.
 */
@Serializable
data class PlaybackReport(
    val mediaId: String,
    val title: String? = null,
    val rebuffers: Int = 0,
    val rebufferMs: Long = 0,
    val droppedFrames: Int = 0,
    val videoBitrate: Int = 0,
    val videoFormat: String? = null,
    val watchedMs: Long = 0,
    val estimatedBandwidth: Long = 0,
    val source: String = "tv",
)

/**
 * Where to fetch a title's bytes from — GET /api/media/play-url/[id].
 *
 * `mode` is "direct" for a URL that points at Google Drive itself, with the
 * server out of the byte path entirely, or "proxy" for the stream route that
 * pipes the bytes through the server. The client never decides which: whether
 * a file can be played directly depends on its container and on server
 * configuration, both of which change without the app being rebuilt.
 */
@Serializable
data class PlayUrlResponse(
    val mode: String = "proxy",
    val url: String = "",
    // Sent beside the URL rather than inside it: Google answers a request that
    // carries the token as a query parameter with 403, so a direct play has to
    // put it in an Authorization header.
    val token: String? = null,
    val contentType: String? = null,
    val size: Long = 0,
    val expiresAt: Long = 0,
    val reason: String? = null,
)

/**
 * A resolved place to stream from, with what the player needs to open it.
 *
 * `direct` decides two things beyond the URL. Whether the device key may be
 * attached to the request — it must never leave our own server, see
 * Playback.kt — and whether the cache may be keyed by URL, which a direct URL
 * cannot be, because the token inside it differs on every playback.
 */
data class PlaySource(
    val url: String,
    val direct: Boolean,
    val mimeType: String?,
    /** Google's access token for a direct source, null for the proxy path. */
    val token: String? = null,
) {
    /** What a direct request has to send to be served. Empty for the proxy
     * path, whose own credential is the device key and is attached elsewhere. */
    fun requestHeaders(): Map<String, String> =
        if (direct && token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
}

/**
 * The server's time-to-byte table for a file the extractor cannot seek in.
 * `cues` is a list of [timeMs, byteOffset] pairs, ascending by time.
 */
@Serializable
data class SeekIndexResponse(
    val seekable: Boolean = false,
    val method: String = "",
    val durationMs: Long? = null,
    val cues: List<List<Long>> = emptyList(),
)

/** Thin client for the kPlay media API — GET /api/media/list, GET /api/media/[id],
 * and the progress endpoints. Auth is the shared device key header. */
class ApiClient(private val credentials: Credentials) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // A scan is minutes of sequential TMDb lookups, not a request. Its own
    // client so raising the ceiling for it does not make every other call
    // hang for ten minutes when the server is simply unreachable.
    private val scanHttp = http.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
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

    /**
     * Imports whatever is new in the Drive folder, and re-runs TMDb matching
     * over anything that missed on an earlier pass — the same POST the web
     * sidebar's Sync button fires.
     *
     * It runs on its own OkHttp client because the shared one reads with a
     * 30-second timeout, and a scan is sequential by design: one TMDb lookup
     * per new file, because the API rate-limits and because two episodes of
     * the same show would otherwise race to create the parent. A folder with
     * a few dozen new files takes minutes, and timing that out would leave a
     * half-finished import with no report.
     */
    suspend fun scanLibrary(): ScanResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${credentials.serverUrl}/api/media/scan")
            .header(DEVICE_KEY_HEADER, credentials.deviceKey)
            .post(ByteArray(0).toRequestBody(null, 0, 0))
            .build()
        try {
            scanHttp.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw ApiError("Sync failed (HTTP ${res.code})", res.code)
                }
                json.decodeFromString(ScanResult.serializer(), body)
            }
        } catch (e: IOException) {
            throw ApiError("Network error reaching ${credentials.serverUrl}: ${e.message}")
        }
    }

    /** The whole library, already split into movies and series by the server. */
    suspend fun listLibrary(): MediaListResponse {
        return json.decodeFromString(MediaListResponse.serializer(), getText("/api/media/list"))
    }

    /**
     * Everything part-watched, most recent first — what the Watching section
     * shows.
     *
     * A failure comes back as an empty list rather than an exception. This is
     * one extra shelf on a screen that already has the library on it, and
     * losing the shelf must never cost the user the rest of the page.
     */
    suspend fun listWatching(): List<WatchingItem> {
        return try {
            json.decodeFromString(
                WatchingResponse.serializer(),
                getText("/api/media/watching"),
            ).items
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** One item with its episodes — the episode list is empty for a movie. */
    suspend fun getMedia(id: String): MediaDetail {
        return json.decodeFromString(MediaDetail.serializer(), getText("/api/media/$id"))
    }

    /**
     * Resume position. The key is whatever is playing: a media _id for a
     * movie, an episode _id for an episode — the same key the web player
     * posts under, so a position carries across clients.
     */
    suspend fun getProgress(id: String): Double = withContext(Dispatchers.IO) {
        try {
            val body = getText("/api/media/progress?id=$id")
            json.decodeFromString(ProgressResponse.serializer(), body).positionSeconds
        } catch (e: Exception) {
            0.0
        }
    }

    /**
     * The seek table for one file, or null when the server has none to give.
     *
     * Building one can mean walking a large file's cluster headers, so this
     * gets the patient client rather than the 30-second one — but a failure
     * is never fatal: playback proceeds with whatever seekability the
     * extractor worked out for itself.
     */
    suspend fun getSeekIndex(id: String): SeekIndexResponse? = withContext(Dispatchers.IO) {
        try {
            scanHttp.newCall(request("/api/media/seek-index/$id")).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body?.string().orEmpty()
                json.decodeFromString(SeekIndexResponse.serializer(), body)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Fire-and-forget position save — mirrors the web player's periodic POST. */
    suspend fun postProgress(
        id: String,
        positionSeconds: Double,
        durationSeconds: Double? = null,
    ) = withContext(Dispatchers.IO) {
        try {
            val payload = json.encodeToString(
                ProgressRequest.serializer(),
                ProgressRequest(id, positionSeconds, durationSeconds),
            )
            val body = payload.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${credentials.serverUrl}/api/media/progress")
                .header(DEVICE_KEY_HEADER, credentials.deviceKey)
                .post(body)
                .build()
            http.newCall(req).execute().close()
        } catch (e: Exception) {
            // best-effort — resume is a convenience, not critical
        }
    }

    /**
     * Same save as postProgress, callable from somewhere that isn't a
     * coroutine — the player's key handler runs on the main thread and has to
     * fire this off as the user leaves. Enqueued on OkHttp's own dispatcher so
     * it neither blocks the keypress nor dies with the composition.
     */
    fun postProgressAsync(id: String, positionSeconds: Double, durationSeconds: Double? = null) {
        try {
            val payload = json.encodeToString(
                ProgressRequest.serializer(),
                ProgressRequest(id, positionSeconds, durationSeconds),
            )
            val req = Request.Builder()
                .url("${credentials.serverUrl}/api/media/progress")
                .header(DEVICE_KEY_HEADER, credentials.deviceKey)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) = Unit
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            // Resume position is a convenience, never worth surfacing.
        }
    }

    /**
     * Posts a playback report. Fire-and-forget on OkHttp's dispatcher: it is
     * sent as the player is being torn down, must not block that, and a
     * diagnostic that fails is simply a diagnostic that is missing.
     */
    fun postPlaybackReportAsync(report: PlaybackReport) {
        try {
            val payload = json.encodeToString(PlaybackReport.serializer(), report)
            val req = Request.Builder()
                .url("${credentials.serverUrl}/api/admin/playback-report")
                .header(DEVICE_KEY_HEADER, credentials.deviceKey)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) = Unit
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            // Monitoring must never be able to break playback.
        }
    }

    /** TMDb-hosted poster — public CDN, no auth header needed or sent. */
    fun posterUrl(posterPath: String) = "https://image.tmdb.org/t/p/w342$posterPath"

    /**
     * Artwork for the full-width hero, at a size worth sending to a 1080p
     * panel.
     *
     * Prefers the 16:9 backdrop and falls back to the poster, because items
     * matched before backdrops were stored have only a poster — a cropped
     * portrait behind the scrim still beats an empty rectangle.
     */
    fun heroImageUrl(backdropPath: String?, posterPath: String?): String? = when {
        backdropPath != null -> "https://image.tmdb.org/t/p/w1280$backdropPath"
        posterPath != null -> "https://image.tmdb.org/t/p/w780$posterPath"
        else -> null
    }

    /**
     * URL Media3 streams from. Accepts a media _id or an episode _id — the
     * route resolves either (app/api/media/stream/[id]/route.js).
     *
     * Still the only URL a download uses: a direct Drive URL carries an access
     * token that expires within the hour, which downloading a film outlives
     * comfortably.
     */
    fun streamUrl(id: String) = "${credentials.serverUrl}/api/media/stream/$id"

    /**
     * Last direct answer per id, with the moment it stops being usable.
     *
     * A direct URL is resolved once per HTTP connection, not once per
     * playback (see Playback.kt), and ExoPlayer opens a new connection on
     * every seek and every reconnect. Asking the server each time would put a
     * round trip on the long path in front of each of those. The answer is
     * good until its token expires, so it is held until then.
     */
    private val directCache = java.util.concurrent.ConcurrentHashMap<String, Pair<PlaySource, Long>>()

    /**
     * Blocking variant for ExoPlayer's loading thread, which is not a
     * coroutine and must not have one launched onto it.
     *
     * Returns a cached URL while its token has comfortably long left, and
     * fetches a fresh one otherwise — which is what keeps a film longer than
     * the token's lifetime playing straight through.
     */
    fun playUrlBlocking(id: String): PlaySource {
        val cached = directCache[id]
        if (cached != null && cached.second > System.currentTimeMillis() + TOKEN_REFRESH_MARGIN_MS) {
            return cached.first
        }
        return runBlocking { playUrl(id) }
    }

    /**
     * Asks the server where this title's bytes should be fetched from.
     *
     * Any failure — the route missing on an older server, a network blip, JSON
     * that does not parse — falls back to the proxy URL. Streaming through the
     * server is what this app did before direct play existed, so the degraded
     * path is the previously working one rather than an error.
     */
    suspend fun playUrl(id: String): PlaySource = withContext(Dispatchers.IO) {
        val fallback = PlaySource(streamUrl(id), direct = false, mimeType = null)
        try {
            val answer = json.decodeFromString(
                PlayUrlResponse.serializer(),
                getText("/api/media/play-url/$id"),
            )
            if (answer.url.isBlank()) return@withContext fallback

            if (answer.mode == "direct") {
                // Without a token the URL is unusable — Google refuses an
                // unauthenticated alt=media read — so an answer missing one
                // (an older server, say) takes the proxy rather than a URL
                // that would 401 on every range request.
                if (answer.token.isNullOrBlank()) return@withContext fallback

                PlaySource(
                    answer.url,
                    direct = true,
                    mimeType = answer.contentType,
                    token = answer.token,
                ).also {
                    if (answer.expiresAt > 0) directCache[id] = it to answer.expiresAt
                }
            } else {
                // The proxy url arrives server-relative, so the host goes back
                // on. Guarded against an absolute one in case the server ever
                // starts sending those instead.
                val url = if (answer.url.startsWith("http")) {
                    answer.url
                } else {
                    "${credentials.serverUrl}${answer.url}"
                }
                PlaySource(url, direct = false, mimeType = answer.contentType)
            }
        } catch (e: Exception) {
            fallback
        }
    }

    fun authHeaders(): Map<String, String> = mapOf(DEVICE_KEY_HEADER to credentials.deviceKey)
}
