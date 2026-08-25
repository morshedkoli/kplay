package com.kdrive.tv.data

import kotlinx.coroutines.Dispatchers
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

class ApiError(message: String, val httpStatus: Int? = null) : Exception(message)

@Serializable
private data class ProgressResponse(val positionSeconds: Double = 0.0)

@Serializable
private data class ProgressRequest(val id: String, val positionSeconds: Double)

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
    suspend fun postProgress(id: String, positionSeconds: Double) = withContext(Dispatchers.IO) {
        try {
            val payload = json.encodeToString(
                ProgressRequest.serializer(),
                ProgressRequest(id, positionSeconds),
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
    fun postProgressAsync(id: String, positionSeconds: Double) {
        try {
            val payload = json.encodeToString(
                ProgressRequest.serializer(),
                ProgressRequest(id, positionSeconds),
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
     */
    fun streamUrl(id: String) = "${credentials.serverUrl}/api/media/stream/$id"

    fun authHeaders(): Map<String, String> = mapOf(DEVICE_KEY_HEADER to credentials.deviceKey)
}
