package com.kdrive.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.ApiError
import com.kdrive.tv.data.Episode
import com.kdrive.tv.data.MediaDetail
import com.kdrive.tv.ui.components.ActionButton
import com.kdrive.tv.ui.components.FocusBox
import com.kdrive.tv.ui.theme.K

/**
 * One title, in full.
 *
 * Movies get artwork, a blurb and a single action. Series get the same plus a
 * season picker and an episode list, so the screen answers "what is this" and
 * "which one do I play" without a second navigation step.
 *
 * Everything scrolls in a plain Column rather than a LazyColumn. That is not
 * a style preference: a LazyColumn does not compose items that are off-screen,
 * so pressing down from the last visible episode found no focus target and
 * the page simply refused to move. With every child composed, focus search
 * always has somewhere to go and Compose scrolls the newly focused row into
 * view on its own.
 *
 * `onPlay` receives the id to stream and a label for the player: a movie plays
 * itself, an episode plays its own id, and this screen is the only place that
 * knows which.
 */
@Composable
fun DetailScreen(
    mediaId: String,
    api: ApiClient,
    imageLoader: ImageLoader,
    onPlay: (id: String, title: String) -> Unit,
) {
    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Set when the server no longer has this title at all, which reads
    // differently from a server that could not be reached.
    var removed by remember { mutableStateOf(false) }
    // Saveable, not plain remember: navigating into the player disposes this
    // screen, and coming back must land on the same season — not season one.
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    // The last thing played from this screen. It is what focus returns to on
    // the way back, so a viewer who finishes episode 7 is standing on episode
    // 7 rather than at the top of the list.
    var lastPlayedId by rememberSaveable { mutableStateOf<String?>(null) }

    // Without an explicit claim nothing on this screen holds focus, and a
    // screen with no focus ignores the remote completely.
    val firstAction = remember { FocusRequester() }
    var focusClaimed by remember { mutableStateOf(false) }

    LaunchedEffect(mediaId) {
        try {
            val loaded = api.getMedia(mediaId)
            detail = loaded
            // Only when there is nothing to restore — otherwise a reload on
            // the way back from the player would reset the season picker.
            if (season == null) season = loaded.seasons().firstOrNull()?.first
        } catch (e: ApiError) {
            // 404 is not a failure to report as one: it means the title was
            // deleted on the server after this rail was loaded. Say that,
            // rather than showing an HTTP status for something the viewer did
            // nothing wrong to cause.
            removed = e.httpStatus == 404
            error = if (removed) "This title is no longer in your library." else e.message
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this title"
        }
    }

    LaunchedEffect(detail) {
        if (detail != null && !focusClaimed) {
            focusClaimed = true
            runCatching { firstAction.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(K.Ink)) {
        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (removed) "Removed from your library" else "Couldn't load this title",
                        style = K.PageTitle,
                        color = K.TextPrimary,
                    )
                    Text(error!!, style = K.Body, color = K.TextMuted)
                }
            }

            detail == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = K.Accent)
            }

            else -> DetailContent(
                item = detail!!,
                api = api,
                imageLoader = imageLoader,
                onPlay = { id, label ->
                    lastPlayedId = id
                    onPlay(id, label)
                },
                season = season,
                onSeason = { season = it },
                firstAction = firstAction,
                focusEpisodeId = lastPlayedId,
            )
        }
    }
}

/**
 * The detail screen with its data already in hand. Split out from the
 * fetching wrapper so a screenshot test can render it with fixed content.
 */
@Composable
internal fun DetailContent(
    item: MediaDetail,
    api: ApiClient,
    imageLoader: ImageLoader,
    onPlay: (id: String, title: String) -> Unit,
    season: Int? = item.seasons().firstOrNull()?.first,
    onSeason: (Int) -> Unit = {},
    firstAction: FocusRequester? = null,
    /** Episode to put focus on instead of the first one — the episode this
     * screen last launched, so returning from the player lands where the
     * viewer left rather than at the top of the season. */
    focusEpisodeId: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(K.Ink)) {
        run {
                Backdrop(item, api, imageLoader)

                // Movies only. A movie page has nothing but one paragraph, so
                // the poster earns the right half; a series page has an
                // episode list, and that list needs the full width more than
                // the artwork does — sharing it put rows under the poster.
                if (!item.isSeries) item.posterPath?.let { path ->
                    AsyncImage(
                        model = api.posterUrl(path),
                        imageLoader = imageLoader,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = K.Gutter)
                            .width(232.dp)
                            .height(348.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(140.dp))

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
                                onClick = { onPlay(item.id, item.title) },
                                leading = { PlayGlyph() },
                                focusRequester = firstAction,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }

                    if (item.isSeries) {
                        val seasons = item.seasons()

                        if (seasons.isEmpty()) {
                            Text(
                                "No episodes yet. Add a file named like Show S01E01.mkv, then scan.",
                                style = K.Body,
                                color = K.TextMuted,
                                modifier = Modifier.padding(K.Gutter),
                            )
                        } else {
                            if (seasons.size > 1) {
                                SeasonPicker(
                                    seasons = seasons.map { it.first },
                                    selected = season,
                                    onSelect = onSeason,
                                    modifier = Modifier.padding(top = 26.dp),
                                )
                            }

                            val shown = seasons.firstOrNull { it.first == season } ?: seasons.first()

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

                            // Where focus should land when this list appears:
                            // the episode last played if it is in the season
                            // on screen, otherwise the first one.
                            val focusIndex = shown.second
                                .indexOfFirst { it.id == focusEpisodeId }
                                .takeIf { it >= 0 }

                            Column(
                                Modifier.focusGroup(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                shown.second.forEachIndexed { index, episode ->
                                    EpisodeRow(
                                        episode = episode,
                                        onPlay = {
                                            onPlay(
                                                episode.id,
                                                "${item.title}  ·  ${episode.label}",
                                            )
                                        },
                                        // With no Play button above, the first
                                        // episode is the screen's entry point
                                        // — unless we are coming back from
                                        // one, which claims focus instead.
                                        focusRequester = when {
                                            focusIndex != null ->
                                                if (index == focusIndex) firstAction else null
                                            index == 0 && seasons.size == 1 -> firstAction
                                            else -> null
                                        },
                                        modifier = Modifier.padding(horizontal = K.Gutter),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(48.dp))
                }
        }
    }
}

@Composable
private fun Backdrop(item: MediaDetail, api: ApiClient, imageLoader: ImageLoader) {
    val art = api.heroImageUrl(item.backdropPath, item.posterPath)

    Box(Modifier.fillMaxWidth().height(360.dp)) {
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = K.Gutter),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        seasons.forEach { number ->
            ActionButton(
                label = "Season $number",
                onClick = { onSelect(number) },
                primary = number == selected,
            )
        }
    }
}

/**
 * One episode. The number is set apart in faint grey so the eye can run down
 * the column and count, and the title carries the weight.
 */
@Composable
private fun EpisodeRow(
    episode: Episode,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val playable = episode.driveFileId != null

    FocusBox(
        onClick = onPlay,
        enabled = playable,
        cornerRadius = 5,
        focusRequester = focusRequester,
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
