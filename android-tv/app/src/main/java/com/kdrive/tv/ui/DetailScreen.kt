package com.kdrive.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.MediaDetail

/**
 * One library item. A movie gets a Play button that streams the media _id; a
 * series gets a focusable episode list, and picking an episode streams that
 * episode's _id. Mirrors app/MediaDetail.js.
 *
 * `onPlay` receives the id to stream, which is why it is a String rather than
 * the item — for a series the thing played is never the item itself.
 */
@Composable
fun DetailScreen(
    mediaId: String,
    api: ApiClient,
    onPlay: (String) -> Unit,
) {
    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaId) {
        try {
            detail = api.getMedia(mediaId)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load this title"
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
            val item = detail!!
            Column(Modifier.fillMaxSize().padding(48.dp)) {
                Text(
                    if (item.year != null) "${item.title} (${item.year})" else item.title,
                    style = MaterialTheme.typography.headlineLarge,
                )

                Text(
                    subtitleFor(item),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )

                if (item.description != null) {
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp).width(720.dp),
                    )
                }

                if (item.isSeries) {
                    EpisodeList(item, onPlay, Modifier.padding(top = 24.dp))
                } else {
                    // A movie with no driveFileId has no bytes to play — the
                    // doc exists but the file is missing or still processing.
                    val playable = item.status != "processing" && item.driveFileId != null
                    Button(
                        onClick = { onPlay(item.id) },
                        enabled = playable,
                        modifier = Modifier.padding(top = 32.dp),
                    ) {
                        Text(if (playable) "Play" else "Not ready yet")
                    }
                }
            }
        }
    }
}

private fun subtitleFor(item: MediaDetail): String {
    if (!item.isSeries) {
        return if (item.status == "unmatched") "Not matched on TMDb" else item.status
    }
    val count = item.episodes.size
    val plural = if (count == 1) "episode" else "episodes"
    val unmatched = if (item.status == "unmatched") " · not matched on TMDb" else ""
    return "$count $plural$unmatched"
}

@Composable
private fun EpisodeList(
    item: MediaDetail,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seasons = item.seasons()

    if (seasons.isEmpty()) {
        Text(
            "No episodes yet — add one named like Show S01E01.mkv",
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        return
    }

    TvLazyColumn(modifier = modifier.fillMaxWidth()) {
        seasons.forEach { (season, episodes) ->
            item {
                Text(
                    "Season $season",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }
            items(episodes) { episode ->
                Button(
                    onClick = { onPlay(episode.id) },
                    enabled = episode.driveFileId != null,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text("${episode.label}  ${episode.title ?: "Episode ${episode.episode}"}")
                }
            }
        }
    }
}
