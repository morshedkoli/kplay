package com.kdrive.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors a doc from the `media` collection (app/api/media/list,
// app/api/media/[id] — lib/models/media.js). One shape covers both halves of
// the library: `type` is "movie" or "series". TMDb matching is automatic
// server-side; there's no manual title/poster override.
//
// A movie carries its own driveFileId and streams by its own _id. A series
// carries none — its bytes live on the episode docs, and `episodeCount` is
// what the list endpoint reports instead.
@Serializable
data class MediaItem(
    @SerialName("_id") val id: String,
    val type: String = "movie", // movie | series
    val title: String,
    val description: String? = null,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val status: String, // processing | matched | unmatched
    val driveFileId: String? = null,
    val size: Long? = null,
    val episodeCount: Int = 0,
) {
    val isSeries: Boolean get() = type == "series"
}

// One episode of a series. `id` is what streams and what watch progress is
// keyed by — the same key the web player uses, so a part-watched episode
// resumes at the same spot on either client.
@Serializable
data class Episode(
    @SerialName("_id") val id: String,
    val mediaId: String,
    val season: Int = 0,
    val episode: Int = 0,
    val title: String? = null,
    val description: String? = null,
    val driveFileId: String? = null,
    val size: Long? = null,
) {
    /** "S01E02" — zero-padded, matching the web detail page's labelling. */
    val label: String
        get() = "S%02dE%02d".format(season, episode)
}

// GET /api/media/[id]. Same fields as a list entry plus the episode list,
// which is empty for a movie.
@Serializable
data class MediaDetail(
    @SerialName("_id") val id: String,
    val type: String = "movie",
    val title: String,
    val description: String? = null,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val status: String,
    val driveFileId: String? = null,
    val size: Long? = null,
    val episodes: List<Episode> = emptyList(),
) {
    val isSeries: Boolean get() = type == "series"

    /** Episodes grouped into seasons, both in ascending order. */
    fun seasons(): List<Pair<Int, List<Episode>>> =
        episodes.groupBy { it.season }
            .toSortedMap()
            .map { (season, eps) -> season to eps.sortedBy { it.episode } }
}

// GET /api/media/list. The server splits by type already and also returns the
// combined `items`; taking the split lists directly avoids re-filtering.
@Serializable
data class MediaListResponse(
    val items: List<MediaItem> = emptyList(),
    val movies: List<MediaItem> = emptyList(),
    val series: List<MediaItem> = emptyList(),
)

/**
 * What POST /api/media/scan reports back.
 *
 * Only the counts are read here — the per-file detail the route returns is
 * for the web client, which has room to list it.
 */
@Serializable
data class ScanResult(
    val scanned: Int = 0,
    val skipped: Int = 0,
    val imported: List<ScanEntry> = emptyList(),
    val rematched: List<ScanEntry> = emptyList(),
    val failed: List<ScanEntry> = emptyList(),
) {
    /** One line for the rail, in the same words the web sidebar uses. */
    fun summary(): String {
        val parts = buildList {
            if (imported.isNotEmpty()) add("${imported.size} added")
            if (rematched.isNotEmpty()) add("${rematched.size} matched")
            if (failed.isNotEmpty()) add("${failed.size} failed")
        }
        if (parts.isEmpty()) return "Up to date — $scanned file${if (scanned == 1) "" else "s"}"
        return parts.joinToString(" · ")
    }

    /** True when anything about the library actually changed, i.e. when the
     * caller has a reason to re-fetch it. */
    val changed: Boolean get() = imported.isNotEmpty() || rematched.isNotEmpty()
}

/** Entries differ in shape between the three lists; every field is optional
 * so one class covers all of them without the parse ever failing. */
@Serializable
data class ScanEntry(
    val filename: String? = null,
    val title: String? = null,
    val status: String? = null,
    val type: String? = null,
    val error: String? = null,
)
