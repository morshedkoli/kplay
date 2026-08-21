package com.kdrive.tv.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.kdrive.tv.data.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val PROGRESS_POST_INTERVAL_SECONDS = 10L

/**
 * Plays from /api/media/stream/[id] (app/api/media/stream/[id]/route.js) — a
 * Range/206-capable proxy in front of the Drive file, same endpoint the web
 * player uses.
 *
 * `mediaId` is whatever is being played: a media _id for a movie, an episode
 * _id for a series episode. The stream route resolves either.
 *
 * Resume position is fetched/saved through /api/media/progress under that same
 * id — the key the web player uses too, so a part-watched episode resumes at
 * the same spot on either client.
 */
@Composable
fun PlayerScreen(mediaId: String, api: ApiClient) {
    val context = LocalContext.current

    val player = remember {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(api.authHeaders())

        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(api.streamUrl(mediaId)))

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
        }
    }

    LaunchedEffect(mediaId) {
        val resumeSeconds = api.getProgress(mediaId)
        if (resumeSeconds > 0) {
            player.seekTo((resumeSeconds * 1000).toLong())
        }
        player.playWhenReady = true
    }

    // Periodically save position while playing, mirroring the web player's
    // PROGRESS_POST_INTERVAL_SECONDS cadence.
    LaunchedEffect(mediaId) {
        while (isActive) {
            delay(PROGRESS_POST_INTERVAL_SECONDS * 1000)
            if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                api.postProgress(mediaId, player.currentPosition / 1000.0)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            PlayerView(context).apply {
                this.player = player
                useController = true
            }
        },
    )
}
