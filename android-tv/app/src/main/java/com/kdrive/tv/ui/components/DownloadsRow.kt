package com.kdrive.tv.ui.components

import androidx.annotation.OptIn
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import coil.ImageLoader
import coil.compose.AsyncImage
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.fraction
import com.kdrive.tv.data.meta
import com.kdrive.tv.data.playableOffline
import com.kdrive.tv.ui.theme.K

/**
 * The Downloads shelf.
 *
 * The only row on the home screen that renders without a server: everything
 * it shows was written to the device when the download started. Artwork may
 * still be missing offline — TMDb is as unreachable as anything else — so
 * every card falls back to its title on a plain tile rather than to a hole.
 */
@OptIn(UnstableApi::class)
@Composable
fun DownloadsRow(
    title: String,
    downloads: List<Download>,
    api: ApiClient,
    imageLoader: ImageLoader,
    onPlay: (Download) -> Unit,
    onFocusItem: (Download) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
) {
    if (downloads.isEmpty()) return

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
            contentPadding = PaddingValues(
                start = K.Gutter,
                end = K.Gutter,
                top = 10.dp,
                bottom = 10.dp,
            ),
        ) {
            itemsIndexed(downloads, key = { _, it -> it.request.id }) { index, download ->
                DownloadCard(
                    download = download,
                    api = api,
                    imageLoader = imageLoader,
                    onClick = { onPlay(download) },
                    onFocused = { onFocusItem(download) },
                    focusRequester = if (index == 0) firstItemFocusRequester else null,
                )
            }
        }
    }
}

/**
 * One downloaded title.
 *
 * The bar underneath says how much of the file is on the device, and it stays
 * visible while the download runs — a card at 40% is playable but will run
 * out if the network is gone, and the only honest way to say so is to show
 * how much there is.
 */
@OptIn(UnstableApi::class)
@Composable
fun DownloadCard(
    download: Download,
    api: ApiClient,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val meta = download.meta
    val art = api.heroImageUrl(meta.backdropPath, meta.posterPath)
    val complete = download.state == Download.STATE_COMPLETED

    FocusBox(
        onClick = onClick,
        onFocused = onFocused,
        focusRequester = focusRequester,
        // Not disabled below 10%: pressing it still plays, it just streams
        // the part that is not there yet. Disabling would make a card that
        // works look broken.
        modifier = Modifier.size(width = K.WatchCardW, height = K.WatchCardH),
    ) { focused ->
        if (art != null) {
            AsyncImage(
                model = art,
                imageLoader = imageLoader,
                contentDescription = meta.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(K.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    meta.title,
                    style = K.CardTitle,
                    color = K.TextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to K.Scrim.copy(alpha = 0f),
                    0.45f to K.Scrim.copy(alpha = 0.55f),
                    1f to K.Scrim.copy(alpha = 0.92f),
                )
            )
        )

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
                    contentDescription = "Play",
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
                meta.title,
                style = K.CardTitle,
                color = K.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLine(download, meta.subtitle),
                style = K.Eyebrow,
                color = if (download.playableOffline) K.TextMuted else K.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!complete) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(K.TextPrimary.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(download.fraction.coerceAtLeast(0.02f))
                            .clip(CircleShape)
                            .background(if (download.playableOffline) K.Accent else K.TextMuted),
                    )
                }
            }
        }
    }
}

/** What state this download is in, in the fewest words that are still true. */
@OptIn(UnstableApi::class)
private fun statusLine(download: Download, subtitle: String?): String {
    val state = when (download.state) {
        Download.STATE_COMPLETED -> "ON THIS DEVICE"
        Download.STATE_FAILED -> "DOWNLOAD FAILED"
        Download.STATE_QUEUED -> "QUEUED"
        Download.STATE_STOPPED -> "PAUSED"
        Download.STATE_REMOVING -> "REMOVING"
        else -> "${download.percentDownloaded.toInt()}% DOWNLOADED"
    }
    return listOfNotNull(subtitle?.uppercase()?.takeIf { it.isNotBlank() }, state)
        .joinToString("  ·  ")
}
