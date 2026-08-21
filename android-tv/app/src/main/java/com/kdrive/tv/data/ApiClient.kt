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

/** Thin client for the KDrive media API — GET /api/media/list, GET /api/media/[id]. */
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

    /** Movies only — series aren't part of this app's scope. */
    suspend fun listMovies(): List<Movie> {
        return json.decodeFromString(MediaListResponse.serializer(), getText("/api/media/list")).movies
    }

    suspend fun getMovie(id: String): Movie {
        return json.decodeFromString(Movie.serializer(), getText("/api/media/$id"))
    }

    /** Resume position, keyed by media _id — same key the web player (/movies/[id]) uses. */
    suspend fun getProgress(id: String): Double = withContext(Dispatchers.IO) {
        try {
            val body = getText("/api/media/progress?id=$id")
            json.decodeFromString(ProgressResponse.serializer(), body).positionSeconds
        } catch (e: Exception) {
            0.0
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

    /** TMDb-hosted poster — public CDN, no auth header needed or sent. */
    fun posterUrl(posterPath: String) = "https://image.tmdb.org/t/p/w342$posterPath"

    /** URL Media3 streams the actual video from — Range/206-capable proxy in
     * front of the Drive file (app/api/media/stream/[id]/route.js). */
    fun streamUrl(id: String) = "${credentials.serverUrl}/api/media/stream/$id"

    fun authHeaders(): Map<String, String> = mapOf(DEVICE_KEY_HEADER to credentials.deviceKey)
}
