package com.kdrive.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.Episode
import com.kdrive.tv.data.MediaDetail
import com.kdrive.tv.ui.components.ActionButton
import com.kdrive.tv.ui.components.FocusBox
import com.kdrive.tv.ui.theme.K

/**
 * One title, in full.
 *
 * Movies get artwork, a blurb and a single action. Series get the same plus a
 * season selector and an episode list, so the screen answers "what is this"
 * and "which one do I play" without a second navigation step.
 *
 * `onPlay` receives the id to stream: a movie plays itself, an episode plays
 * its own id, and this screen is the only place that knows which.
 */
@Composable
fun DetailScreen(
    mediaId: String,
    api: ApiClient,
    imageLoader: ImageLoader,
    onPlay: (String) -> Unit,
) {
    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var season by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(mediaId) {
        try {
            val loaded = api.getMedia(mediaId)
            detail = loaded
            season = loaded.seasons().firstOrNull()?.first
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this title"
        }
    }

    Box(Modifier.fillMaxSize().background(K.Ink)) {
        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load this title", style = K.PageTitle, color = K.TextPrimary)
                    Text(error!!, style = K.Body, color = K.TextMuted)
                }
            }

            detail == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = K.Accent)
            }

            else -> {
                val item = detail!!
                Backdrop(item, api, imageLoader)

                LazyColumn(Modifier.fillMaxSize()) {
                    item { Spacer(Modifier.height(230.dp)) }

                    item {
                        Column(
                            Modifier.padding(horizontal = K.Gutter).fillMaxWidth(0.62f),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                item.title,
                                style = K.Hero,
                                color = K.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(metaLine(item), style = K.Eyebrow, color = K.TextMuted)

                            if (!item.description.isNullOrBlank()) {
                                Text(item.description, style = K.Body, color = K.TextMuted)
                            }

                            if (!item.isSeries) {
                                val playable = item.status != "processing" && item.driveFileId != null
                                ActionButton(
                                    label = if (playable) "Play" else "Not available",
                                    enabled = playable,
                                    onClick = { onPlay(item.id) },
                                    leading = { PlayGlyph() },
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }

                    if (item.isSeries) {
                        val seasons = item.seasons()

                        if (seasons.isEmpty()) {
                            item {
                                Text(
                                    "No episodes yet. Add a file named like Show S01E01.mkv, then scan.",
                                    style = K.Body,
                                    color = K.TextMuted,
                                    modifier = Modifier.padding(K.Gutter),
                                )
                            }
                        } else {
                            if (seasons.size > 1) {
                                item {
                                    SeasonPicker(
                                        seasons = seasons.map { it.first },
                                        selected = season,
                                        onSelect = { season = it },
                                        modifier = Modifier.padding(top = 26.dp),
                                    )
                                }
                            }

                            val shown = seasons.firstOrNull { it.first == season }
                                ?: seasons.first()

                            item {
                                Text(
                                    "Season ${shown.first}",
                                    style = K.Section,
                                    color = K.TextPrimary,
                                    modifier = Modifier.padding(
                                        start = K.Gutter,
                                        top = 26.dp,
                                        bottom = 12.dp,
                                    ),
                                )
                            }

                            items(shown.second, key = { it.id }) { episode ->
                                EpisodeRow(
                                    episode = episode,
                                    onPlay = { onPlay(episode.id) },
                                    modifier = Modifier.padding(
                                        horizontal = K.Gutter,
                                        vertical = 5.dp,
                                    ),
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(48.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Backdrop(item: MediaDetail, api: ApiClient, imageLoader: ImageLoader) {
    val art = api.heroImageUrl(item.backdropPath, item.posterPath)

    Box(Modifier.fillMaxWidth().height(460.dp)) {
        if (art != null) {
            AsyncImage(
                model = art,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Same two-scrim treatment as the browse hero, so moving between the
        // screens feels like staying in one place.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to K.Ink.copy(alpha = 0.3f),
                    0.6f to K.Ink.copy(alpha = 0.82f),
                    1f to K.Ink,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to K.Ink.copy(alpha = 0.9f),
                    0.7f to K.Ink.copy(alpha = 0f),
                )
            )
        )
    }
}

@Composable
private fun SeasonPicker(
    seasons: List<Int>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = K.Gutter),
    ) {
        items(seasons, key = { it }) { number ->
            ActionButton(
                label = "Season $number",
                onClick = { onSelect(number) },
                primary = number == selected,
            )
        }
    }
}

/**
 * One episode. The number is set apart in the accent-free faint grey so the
 * eye can run down the column and count, and the title carries the weight.
 */
@Composable
private fun EpisodeRow(
    episode: Episode,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playable = episode.driveFileId != null

    FocusBox(
        onClick = onPlay,
        enabled = playable,
        cornerRadius = 5,
        modifier = modifier.fillMaxWidth(),
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) K.SurfaceHi else K.Surface)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "%02d".format(episode.episode),
                style = K.PageTitle,
                color = if (focused) K.TextPrimary else K.TextFaint,
                modifier = Modifier.width(56.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    episode.title ?: "Episode ${episode.episode}",
                    style = K.Section,
                    color = K.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!episode.description.isNullOrBlank()) {
                    Text(
                        episode.description,
                        style = K.Body,
                        color = K.TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!playable) {
                    Text("File missing", style = K.Eyebrow, color = K.TextFaint)
                }
            }
            if (focused && playable) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(K.Accent)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("PLAY", style = K.Eyebrow, color = K.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun PlayGlyph() {
    // A triangle drawn from three stacked bars would be fussier than this is
    // worth; the character reads correctly at distance and costs no asset.
    Text("▶", style = K.CardTitle, color = K.Ink)
}

private fun metaLine(item: MediaDetail): String {
    val parts = mutableListOf<String>()
    parts += if (item.isSeries) "SERIES" else "MOVIE"
    if (item.isSeries) {
        val n = item.episodes.size
        parts += "$n " + if (n == 1) "EPISODE" else "EPISODES"
        val seasons = item.seasons().size
        if (seasons > 1) parts += "$seasons SEASONS"
    } else if (item.year != null) {
        parts += item.year.toString()
    }
    if (item.status == "unmatched") parts += "NO TMDB MATCH"
    return parts.joinToString("  ·  ")
}
