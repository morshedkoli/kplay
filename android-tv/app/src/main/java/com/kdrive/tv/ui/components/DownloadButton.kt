package com.kdrive.tv.ui.components

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.kdrive.tv.data.fraction
import com.kdrive.tv.data.playableOffline
import com.kdrive.tv.ui.theme.K

/**
 * The control that puts a title on the device, and reports on it afterwards.
 *
 * One button with four states rather than a row of them. Downloading is not a
 * thing the viewer manages — it is a thing they start, watch fill up, and
 * eventually delete — so the same spot on the screen carries all of it.
 */
@OptIn(UnstableApi::class)
@Composable
fun DownloadButton(
    download: Download?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val state = download?.state

    val label = when {
        download == null -> "Download"
        state == Download.STATE_COMPLETED -> "Downloaded"
        state == Download.STATE_FAILED -> "Download failed — retry"
        state == Download.STATE_QUEUED -> "Queued"
        state == Download.STATE_STOPPED -> "Paused"
        // The percentage is the point while it runs: it is what tells the
        // viewer whether to wait or to start watching now.
        else -> "Downloading  ${download.percentDownloaded.toInt()}%"
    }

    Box(modifier) {
        ActionButton(
            label = label,
            // A finished or running download offers removal; a missing or
            // failed one offers a fetch. The same key does both because there
            // is never more than one sensible thing to do with a download.
            onClick = if (download != null && state != Download.STATE_FAILED) onRemove else onDownload,
            enabled = enabled,
            primary = false,
            focusRequester = focusRequester,
            leading = {
                Icon(
                    imageVector = when {
                        download?.playableOffline == true -> KIcons.DownloadDone
                        else -> KIcons.Download
                    },
                    contentDescription = null,
                    tint = if (download?.playableOffline == true) K.Accent else K.TextPrimary,
                    modifier = Modifier.size(16.dp),
                )
            },
        )

        // A bar hugging the underside of the button, so progress reads
        // without a second line of text and without the button changing size
        // as the number grows.
        if (download != null && state != Download.STATE_COMPLETED) {
            Box(
                Modifier
                    .align(androidx.compose.ui.Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(K.TextPrimary.copy(alpha = 0.2f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(download.fraction.coerceAtLeast(0.01f))
                        .clip(CircleShape)
                        .background(if (download.playableOffline) K.Accent else K.TextMuted),
                )
            }
        }
    }
}

/**
 * A compact version for an episode row, where there is no space for a word.
 *
 * Same four states, said with a glyph and a ring: the ring fills as the
 * download does, and turns accent-coloured once there is enough on disk to
 * start watching.
 */
@OptIn(UnstableApi::class)
@Composable
fun DownloadChip(
    download: Download?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusBox(
        onClick = if (download != null && download.state != Download.STATE_FAILED) {
            onRemove
        } else {
            onDownload
        },
        cornerRadius = 4,
        modifier = modifier.width(96.dp).height(40.dp),
    ) { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(if (focused) K.SurfaceHi else K.Surface.copy(alpha = 0.6f)),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Icon(
                imageVector = if (download?.state == Download.STATE_COMPLETED) {
                    KIcons.DownloadDone
                } else {
                    KIcons.Download
                },
                contentDescription = if (download == null) "Download" else "Remove download",
                tint = when {
                    download?.playableOffline == true -> K.Accent
                    focused -> K.TextPrimary
                    else -> K.TextFaint
                },
                modifier = Modifier.size(20.dp),
            )
        }

        if (download != null && download.state != Download.STATE_COMPLETED) {
            Box(
                Modifier
                    .align(androidx.compose.ui.Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(K.TextPrimary.copy(alpha = 0.2f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(download.fraction.coerceAtLeast(0.01f))
                        .background(if (download.playableOffline) K.Accent else K.TextMuted),
                )
            }
        }
    }
}
