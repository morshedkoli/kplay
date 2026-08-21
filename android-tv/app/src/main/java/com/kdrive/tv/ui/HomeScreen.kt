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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.MediaItem
import com.kdrive.tv.ui.components.ActionButton
import com.kdrive.tv.ui.components.CarouselRow
import com.kdrive.tv.ui.components.NavRail
import com.kdrive.tv.ui.components.Section
import com.kdrive.tv.ui.theme.K

/**
 * The browse screen: a full-bleed hero above horizontal rows, with the rail
 * floating over the left edge.
 *
 * The signature behaviour is that the hero *follows focus* — move to any
 * poster and the backdrop, title and blurb behind the rows become that title's.
 * It costs nothing (the data is already loaded) and it turns a static header
 * into a preview surface, which is the thing that makes this feel like a
 * television app rather than a grid of links.
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
    var section by remember { mutableStateOf(Section.Home) }
    var spotlight by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(Unit) {
        try {
            val library = api.listLibrary()
            movies = library.movies
            series = library.series
            spotlight = library.movies.firstOrNull() ?: library.series.firstOrNull()
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach the server"
        }
    }

    Box(Modifier.fillMaxSize().background(K.Ink)) {
        when {
            error != null -> Message(
                headline = "Can't reach your library",
                detail = error!!,
            )

            movies == null || series == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = K.Accent) }

            movies!!.isEmpty() && series!!.isEmpty() -> Message(
                headline = "Nothing here yet",
                detail = "Drop video files into your Drive folder, then run a scan from the web app.",
            )

            else -> {
                Hero(item = spotlight, api = api, imageLoader = imageLoader)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    // Reserves the top of the screen for the hero. The rows
                    // scroll up over it, which is what gives the screen depth.
                    item { Spacer(Modifier.height(360.dp)) }

                    if (section != Section.Series && movies!!.isNotEmpty()) {
                        item {
                            CarouselRow(
                                title = "Movies",
                                items = movies!!,
                                api = api,
                                imageLoader = imageLoader,
                                onSelect = onSelect,
                                onFocusItem = { spotlight = it },
                            )
                        }
                    }
                    if (section != Section.Movies && series!!.isNotEmpty()) {
                        item {
                            CarouselRow(
                                title = "Series",
                                items = series!!,
                                api = api,
                                imageLoader = imageLoader,
                                onSelect = onSelect,
                                onFocusItem = { spotlight = it },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
            }
        }

        NavRail(
            selected = section,
            onSelect = { section = it },
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

/**
 * Backdrop, scrim and blurb for whatever currently holds focus.
 *
 * Crossfaded rather than cut, because focus moves fast under a D-pad and a
 * hard swap on every keypress reads as flicker.
 */
@Composable
private fun Hero(item: MediaItem?, api: ApiClient, imageLoader: ImageLoader) {
    Box(Modifier.fillMaxWidth().height(430.dp)) {
        Crossfade(
            targetState = item,
            animationSpec = tween(320),
            label = "heroArt",
        ) { current ->
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

        // Two scrims, not one: vertical so the rows below sit on solid ground,
        // horizontal so the title stays readable over a busy left edge.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to K.Ink.copy(alpha = 0.35f),
                    0.55f to K.Ink.copy(alpha = 0.75f),
                    1f to K.Ink,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to K.Ink.copy(alpha = 0.92f),
                    0.6f to K.Ink.copy(alpha = 0.1f),
                    1f to K.Ink.copy(alpha = 0f),
                )
            )
        )

        if (item != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = K.Gutter + K.RailCollapsed, bottom = 26.dp, end = K.Gutter)
                    .fillMaxWidth(0.55f),
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
