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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.kdrive.tv.data.fraction
import com.kdrive.tv.data.playableOffline
import com.kdrive.tv.ui.theme.K

/**
 * What pressing the download control should do next, given where the download
 * has got to.
 *
 * Derived rather than stored, so the button, the chip and anything added
 * later cannot disagree about what a state means — which is exactly the kind
 * of drift that produces a button labelled "Pause" that deletes a film.
 */
internal enum class DownloadAction(val icon: ImageVector) {
    Start(KIcons.Download),
    Retry(KIcons.Download),
    Pause(KIcons.Pause),
    Resume(Icons.Filled.PlayArrow),
    Remove(KIcons.DownloadDone),
}

@OptIn(UnstableApi::class)
internal val Download?.action: DownloadAction
    get() = when {
        this == null -> DownloadAction.Start
        state == Download.STATE_FAILED -> DownloadAction.Retry
        state == Download.STATE_COMPLETED -> DownloadAction.Remove
        // STOPPED covers both a viewer's pause and a requirement that is not
        // met (no network). Resume is right either way: it clears the stop
        // reason, and a download with no network then waits rather than
        // sitting stopped forever after the connection returns.
        state == Download.STATE_STOPPED -> DownloadAction.Resume
        else -> DownloadAction.Pause
    }

/**
 * The control that puts a title on the device, and reports on it afterwards.
 *
 * One button carrying the state and the obvious next action, with removal
 * kept separate — see the comment on its onClick.
 */
@OptIn(UnstableApi::class)
@Composable
fun DownloadButton(
    download: Download?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val action = download.action

    // One word each. The state and the percentage live on the line under the
    // buttons instead: three buttons plus a sentence does not fit the column,
    // and the first attempt at this silently pushed Remove off the screen.
    val label = when (action) {
        DownloadAction.Start -> "Download"
        DownloadAction.Retry -> "Retry"
        DownloadAction.Resume -> "Resume"
        DownloadAction.Remove -> "Downloaded"
        DownloadAction.Pause -> "Pause"
    }

    Box(modifier) {
        ActionButton(
            label = label,
            // One key, and always the thing the viewer most likely wants next
            // for a download in this state. Removal is its own button beside
            // this one rather than a state of this one, because deleting
            // gigabytes should never be what a key does by surprise.
            onClick = when (action) {
                DownloadAction.Start, DownloadAction.Retry -> onDownload
                DownloadAction.Pause -> onPause
                DownloadAction.Resume -> onResume
                DownloadAction.Remove -> onRemove
            },
            enabled = enabled,
            primary = false,
            focusRequester = focusRequester,
            leading = {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = if (download?.playableOffline == true) K.Accent else K.TextPrimary,
                    modifier = Modifier.size(16.dp),
                )
            },
        )

        // A bar hugging the underside of the button, so progress reads
        // without a second line of text and without the button changing size
        // as the number grows.
        //
        // matchParentSize, not fillMaxWidth: a child that fills the width
        // takes the whole incoming constraint and drags the Box out with it,
        // which drew a progress bar stretching far past the button it belongs
        // to. matchParentSize measures against the button and has no say in
        // how wide the Box ends up.
        if (download != null && download.state != Download.STATE_COMPLETED) {
            Box(Modifier.matchParentSize(), contentAlignment = androidx.compose.ui.Alignment.BottomStart) {
                Box(
                    Modifier
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
}

/**
 * The download's state in words, for the line under the buttons.
 *
 * Null when there is nothing to say — no download, or one that has finished
 * and whose button already says so.
 */
@OptIn(UnstableApi::class)
fun downloadStatusLine(download: Download?): String? {
    if (download == null) return null
    val percent = download.percentDownloaded.toInt()
    return when (download.state) {
        Download.STATE_COMPLETED -> "Downloaded — this plays with no network at all."
        Download.STATE_FAILED -> "The download failed. Press retry to pick it up again."
        Download.STATE_QUEUED -> "Queued."
        Download.STATE_REMOVING -> "Removing…"
        Download.STATE_STOPPED -> if (download.playableOffline) {
            "Paused at $percent% — enough to start watching."
        } else {
            "Paused at $percent%."
        }

        else -> if (download.playableOffline) {
            "Downloading $percent% — enough downloaded to start watching now."
        } else {
            "Downloading $percent%."
        }
    }
}

/**
 * Removal, on its own key.
 *
 * Only rendered when there is something to remove. Deleting gigabytes is the
 * one download action that cannot be undone without downloading them again,
 * so it never shares a key with pause, resume or retry.
 */
@Composable
fun RemoveDownloadButton(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    ActionButton(
        label = "Remove",
        onClick = onRemove,
        primary = false,
        modifier = modifier,
        leading = {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = K.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        },
    )
}

/**
 * A compact version for an episode row, where there is no space for a word.
 *
 * The glyph is the action the key will perform, not the state it is in — a
 * pause bar while it downloads, a play triangle while it is paused — and the
 * bar underneath carries the state instead. A completed download shows the
 * tick and removes, which is the only way to delete one episode.
 */
@OptIn(UnstableApi::class)
@Composable
fun DownloadChip(
    download: Download?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = download.action

    FocusBox(
        onClick = when (action) {
            DownloadAction.Start, DownloadAction.Retry -> onDownload
            DownloadAction.Pause -> onPause
            DownloadAction.Resume -> onResume
            DownloadAction.Remove -> onRemove
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
                imageVector = action.icon,
                contentDescription = when (action) {
                    DownloadAction.Start -> "Download"
                    DownloadAction.Retry -> "Retry download"
                    DownloadAction.Pause -> "Pause download"
                    DownloadAction.Resume -> "Resume download"
                    DownloadAction.Remove -> "Remove download"
                },
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
