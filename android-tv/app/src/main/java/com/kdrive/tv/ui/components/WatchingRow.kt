package com.kdrive.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.WatchingItem
import com.kdrive.tv.ui.theme.K

/**
 * The Watching shelf.
 *
 * Landscape cards rather than the posters used everywhere else, and that is
 * deliberate: a poster is an identity, but these cards carry a position, a
 * time remaining and an episode number, and none of that fits legibly down
 * the side of a 2:3 tile. The different shape also does the labelling work —
 * the row reads as "where I left off" before its heading is read.
 */
@Composable
fun WatchingRow(
    title: String,
    items: List<WatchingItem>,
    api: ApiClient,
    imageLoader: ImageLoader,
    onResume: (WatchingItem) -> Unit,
    onFocusItem: (WatchingItem) -> Unit,
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
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // Slack for the focus ring and the 6% scale, which both overflow
            // the card bounds.
            contentPadding = PaddingValues(
                start = K.Gutter,
                end = K.Gutter,
                top = 10.dp,
                bottom = 10.dp,
            ),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                WatchingCard(
                    item = item,
                    api = api,
                    imageLoader = imageLoader,
                    onClick = { onResume(item) },
                    onFocused = { onFocusItem(item) },
                    focusRequester = if (index == 0) firstItemFocusRequester else null,
                )
            }
        }
    }
}

/**
 * One 16:9 card: backdrop, a play badge while focused, the title and the
 * position bar.
 *
 * The bar is drawn whether or not the card has focus. It is the one thing
 * that distinguishes these cards from ordinary artwork, and hiding it until
 * focus would mean the shelf could not be scanned at all.
 */
@Composable
fun WatchingCard(
    item: WatchingItem,
    api: ApiClient,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val art = api.heroImageUrl(item.backdropPath, item.posterPath)

    FocusBox(
        onClick = onClick,
        onFocused = onFocused,
        focusRequester = focusRequester,
        modifier = Modifier.size(width = K.WatchCardW, height = K.WatchCardH),
    ) { focused ->
        if (art != null) {
            AsyncImage(
                model = art,
                imageLoader = imageLoader,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(K.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.title,
                    style = K.CardTitle,
                    color = K.TextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        // A permanent scrim across the lower half, because the text below is
        // permanent too — it has to stay readable over whatever frame TMDb
        // happened to pick for the backdrop.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to K.Scrim.copy(alpha = 0f),
                    0.45f to K.Scrim.copy(alpha = 0.55f),
                    1f to K.Scrim.copy(alpha = 0.92f),
                )
            )
        )

        // Says what pressing OK will do. Only while focused, so the shelf
        // stays artwork rather than becoming a row of identical buttons.
        if (focused) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(K.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Resume",
                    tint = K.TextPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                item.title,
                style = K.CardTitle,
                color = K.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = item.subtitle()
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = K.Eyebrow,
                    color = K.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProgressBar(item.progress)
        }
    }
}

/**
 * How far into this title the viewer got.
 *
 * A null fraction means the position was recorded before the client started
 * sending durations, so there is no honest percentage to draw. The track is
 * still drawn empty rather than skipped, because a card that loses its bar
 * changes height and makes the whole row jump.
 */
@Composable
private fun ProgressBar(fraction: Float?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(K.TextPrimary.copy(alpha = 0.25f)),
    ) {
        if (fraction != null && fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    // At least a sliver, so "barely started" still reads as
                    // started rather than as an untouched track.
                    .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                    .clip(CircleShape)
                    .background(K.Accent),
            )
        }
    }
}
