package com.kdrive.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.ImageLoader
import coil.compose.AsyncImage
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.MediaItem
import com.kdrive.tv.ui.components.CarouselRow
import com.kdrive.tv.ui.components.NavRail
import com.kdrive.tv.ui.components.Section
import com.kdrive.tv.ui.theme.K

/**
 * The browse screen: a rail on the left, and to its right a hero above
 * horizontal rows.
 *
 * The rail is a layout sibling, not an overlay. Overlaying it looked better
 * but made it unreachable: focus search on Android is geometric, so a rail
 * drawn on top of the content it shares coordinates with gives the D-pad no
 * unambiguous direction to travel in. Real columns, real edges.
 *
 * The hero follows focus — move to any poster and the backdrop, title and
 * blurb become that title's. The data is already loaded, so a static header
 * becomes a preview surface for free.
 */
@Composable
fun HomeScreen(
    api: ApiClient,
    imageLoader: ImageLoader,
    onSelect: (MediaItem) -> Unit,
) {
    var movies by remember { mutableStateOf<List<MediaItem>?>(null) }
    var series by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val library = api.listLibrary()
            movies = library.movies
            series = library.series
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach the server"
        }
    }

    when {
        error != null -> Framed { Message("Can't reach your library", error!!) }

        movies == null || series == null -> Framed {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = K.Accent)
            }
        }

        else -> BrowseContent(
            movies = movies!!,
            series = series!!,
            api = api,
            imageLoader = imageLoader,
            onSelect = onSelect,
        )
    }
}

/**
 * The browse screen with its data already in hand.
 *
 * Split out from the fetching wrapper so it can be rendered from a screenshot
 * test with fixed content — this machine has no hypervisor and therefore no
 * emulator, so JVM rendering is the only way to actually look at the result.
 */
@Composable
internal fun BrowseContent(
    movies: List<MediaItem>,
    series: List<MediaItem>,
    api: ApiClient,
    imageLoader: ImageLoader,
    onSelect: (MediaItem) -> Unit,
) {
    var section by remember { mutableStateOf(Section.Home) }
    var spotlight by remember {
        mutableStateOf(movies.firstOrNull() ?: series.firstOrNull())
    }

    // Something must hold focus or the remote does nothing at all: Android
    // delivers key events to the focused view, and with no focus there is
    // nowhere for them to go. The first card claims it as soon as data lands.
    val firstCard = remember { FocusRequester() }
    var focusClaimed by remember { mutableStateOf(false) }

    LaunchedEffect(movies, series) {
        if (!focusClaimed && (movies.isNotEmpty() || series.isNotEmpty())) {
            focusClaimed = true
            // Guarded: requestFocus throws if the node isn't attached yet, and
            // a race here would take the whole screen down.
            runCatching { firstCard.requestFocus() }
        }
    }

    Row(Modifier.fillMaxSize().background(K.Ink)) {
        NavRail(
            selected = section,
            onSelect = { section = it },
            // Above the content so the expanded labels are not painted over,
            // while still occupying its own column in the layout.
            modifier = Modifier.zIndex(1f),
        )

        Box(Modifier.fillMaxSize()) {
            if (movies.isEmpty() && series.isEmpty()) {
                Message(
                    "Nothing here yet",
                    "Drop video files into your Drive folder, then run a scan from the web app.",
                )
            } else {
                Hero(item = spotlight, api = api, imageLoader = imageLoader)

                // A plain scrolling Column, not a LazyColumn. With only a few
                // rows there is nothing to gain from laziness, and a
                // LazyColumn does not compose off-screen rows — so focus
                // search moving down would find no target and simply stop,
                // which is exactly the "can't move the page" symptom.
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    // Holds the top of the screen open for the hero. Rows
                    // scroll up over it, which is what gives depth.
                    Spacer(Modifier.height(252.dp))

                    val showMovies = section != Section.Series && movies.isNotEmpty()
                    val showSeries = section != Section.Movies && series.isNotEmpty()

                    if (showMovies) {
                        CarouselRow(
                            title = "Movies",
                            items = movies,
                            api = api,
                            imageLoader = imageLoader,
                            onSelect = onSelect,
                            onFocusItem = { spotlight = it },
                            firstItemFocusRequester = firstCard,
                        )
                    }
                    if (showSeries) {
                        CarouselRow(
                            title = "Series",
                            items = series,
                            api = api,
                            imageLoader = imageLoader,
                            onSelect = onSelect,
                            onFocusItem = { spotlight = it },
                            // Only when Movies isn't showing, so exactly one
                            // card ever claims the initial focus.
                            firstItemFocusRequester = if (showMovies) null else firstCard,
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

/** Page background for the states that render before any content exists. */
@Composable
private fun Framed(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(K.Ink)) { content() }
}

/**
 * Backdrop, scrim and blurb for whatever currently holds focus.
 *
 * Crossfaded rather than cut, because focus moves fast under a D-pad and a
 * hard swap on every keypress reads as flicker.
 */
@Composable
private fun Hero(item: MediaItem?, api: ApiClient, imageLoader: ImageLoader) {
    Box(Modifier.fillMaxWidth().height(264.dp)) {
        Crossfade(targetState = item, animationSpec = tween(320), label = "heroArt") { current ->
            val art = current?.let { api.heroImageUrl(it.backdropPath, it.posterPath) }
            if (art != null) {
                AsyncImage(
                    model = art,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(K.Surface))
            }
        }

        // Two scrims: vertical so the rows below sit on solid ground,
        // horizontal so the title stays readable over a busy left edge.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to K.Ink.copy(alpha = 0.3f),
                    0.55f to K.Ink.copy(alpha = 0.75f),
                    1f to K.Ink,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to K.Ink.copy(alpha = 0.9f),
                    0.62f to K.Ink.copy(alpha = 0.08f),
                    1f to K.Ink.copy(alpha = 0f),
                )
            )
        )

        if (item != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = K.Gutter, bottom = 24.dp, end = K.Gutter)
                    .fillMaxWidth(0.58f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    Text(
                        item.description,
                        style = K.Body,
                        color = K.TextMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** "SERIES · 2 EPISODES" / "MOVIE · 2010" — uppercase, tracked, never a sentence. */
private fun metaLine(item: MediaItem): String {
    val parts = mutableListOf<String>()
    parts += if (item.isSeries) "SERIES" else "MOVIE"
    if (item.isSeries) {
        parts += "${item.episodeCount} " + if (item.episodeCount == 1) "EPISODE" else "EPISODES"
    } else if (item.year != null) {
        parts += item.year.toString()
    }
    if (item.status == "unmatched") parts += "NO MATCH"
    return parts.joinToString("  ·  ")
}

@Composable
private fun Message(headline: String, detail: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(headline, style = K.PageTitle, color = K.TextPrimary)
            Text(detail, style = K.Body, color = K.TextMuted)
        }
    }
}
