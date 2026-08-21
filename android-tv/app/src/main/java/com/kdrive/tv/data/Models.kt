package com.kdrive.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors a `movie`-type doc from the `media` collection (app/api/media/list,
// app/api/media/[id] — lib/models/media.js). TMDb match is automatic
// server-side; there's no manual title/poster override.
@Serializable
data class Movie(
    @SerialName("_id") val id: String,
    val title: String,
    val description: String? = null,
    val year: Int? = null,
    val posterPath: String? = null,
    val status: String, // processing | matched | unmatched
    val driveFileId: String? = null,
    val size: Long? = null,
)

@Serializable
data class MediaListResponse(
    val movies: List<Movie> = emptyList(),
)
