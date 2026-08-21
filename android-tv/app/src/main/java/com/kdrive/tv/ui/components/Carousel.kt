package com.kdrive.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.MediaItem
import com.kdrive.tv.ui.theme.K

/**
 * A titled horizontal strip of posters — the unit the whole browse experience
 * is built from.
 *
 * `onFocusItem` reports whichever card currently holds focus so the screen
 * above can react; that is what drives the hero.
 */
@Composable
fun CarouselRow(
    title: String,
    items: List<MediaItem>,
    api: ApiClient,
    imageLoader: ImageLoader,
    onSelect: (MediaItem) -> Unit,
    onFocusItem: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            title,
            style = K.Section,
            color = K.TextPrimary,
            modifier = Modifier.padding(start = K.Gutter, bottom = 14.dp),
        )
        LazyRow(
            // One stop in the vertical traversal: focus enters the row, moves
            // along it, and leaves from wherever it got to.
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // The focus ring and the 6% scale both overflow the poster bounds,
            // so the row needs slack or the first and last cards get clipped.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = K.Gutter,
                end = K.Gutter,
                top = 10.dp,
                bottom = 10.dp,
            ),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                PosterCard(
                    item = item,
                    api = api,
                    imageLoader = imageLoader,
                    onClick = { onSelect(item) },
                    onFocused = { onFocusItem(item) },
                    focusRequester = if (index == 0) firstItemFocusRequester else null,
                )
            }
        }
    }
}

/**
 * A single 2:3 poster tile.
 *
 * The title is drawn over the artwork only while focused. Permanent captions
 * under every tile would turn a wall of artwork into a wall of text, and the
 * poster is already the label for anything the user recognises.
 */
@Composable
fun PosterCard(
    item: MediaItem,
    api: ApiClient,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val posterUrl = item.posterPath?.let { api.posterUrl(it) }

    FocusBox(
        onClick = onClick,
        onFocused = onFocused,
        focusRequester = focusRequester,
        modifier = Modifier.size(width = K.PosterW, height = K.PosterH),
    ) { focused ->
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                imageLoader = imageLoader,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // No artwork: the title becomes the tile. Centred and wrapped so a
            // long filename still reads, rather than a generic film glyph that
            // tells the user nothing.
            Box(
                Modifier.fillMaxSize().background(K.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.title,
                    style = K.CardTitle,
                    color = K.TextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        if (item.isSeries) {
            EpisodeBadge(
                count = item.episodeCount,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }

        if (focused && posterUrl != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(K.Scrim.copy(alpha = 0f), K.Scrim.copy(alpha = 0.92f)),
                        )
                    ),
            )
            Text(
                item.title,
                style = K.CardTitle,
                color = K.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun EpisodeBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(K.Scrim.copy(alpha = 0.78f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text("$count EP", style = K.Eyebrow, color = K.TextPrimary)
    }
}
