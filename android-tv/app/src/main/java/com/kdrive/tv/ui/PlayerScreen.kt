package com.kdrive.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.ui.theme.K
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val PROGRESS_POST_INTERVAL_SECONDS = 10L

/** How far the arrow keys jump. Matches what most TV apps do, so muscle
 * memory from other apps transfers. */
private const val SEEK_STEP_MS = 10_000L

/** The dedicated transport keys on a remote imply a bigger jump than the
 * D-pad does. */
private const val SEEK_JUMP_MS = 30_000L

/** Controls fade out after this long untouched, so they stop covering the
 * picture without the user having to dismiss them. */
private const val CONTROLS_TIMEOUT_MS = 4_000L

/**
 * Full-screen playback with a remote-driven transport.
 *
 * Media3's stock controller is built for a touchscreen and is awkward under a
 * D-pad, so the overlay here is ours and every key is handled explicitly:
 *
 *   Centre / Enter / Play-Pause  toggle playback
 *   Left / Right                 seek ∓10s
 *   Rewind / Fast-forward        seek ∓30s
 *   Up                           show the controls
 *   Down / Back                  hide the controls, then leave
 *
 * `mediaId` is whatever is playing — a media _id for a movie, an episode _id
 * for an episode. Position is saved under that same id, so the web player
 * resumes where the TV left off and vice versa.
 */
@Composable
fun PlayerScreen(
    mediaId: String,
    title: String?,
    api: ApiClient,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val player = remember {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(api.authHeaders())
            // A dropped connection mid-film should rebuffer, not end playback.
            // That matters on a host that caps request duration: the transfer
            // is killed server-side and ExoPlayer has to reconnect from the
            // last position it read.
            .setAllowCrossProtocolRedirects(true)

        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(api.streamUrl(mediaId)))

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    // Bumped on every keypress; the auto-hide timer restarts whenever it
    // changes, which is simpler than cancelling and re-launching a job.
    var lastInteraction by remember { mutableLongStateOf(0L) }

    val focusRequester = remember { FocusRequester() }

    fun touch() {
        controlsVisible = true
        lastInteraction += 1
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) controlsVisible = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(mediaId) {
        val resumeSeconds = api.getProgress(mediaId)
        if (resumeSeconds > 0) player.seekTo((resumeSeconds * 1000).toLong())
        player.playWhenReady = true
        focusRequester.requestFocus()
    }

    // Drives the scrubber. Polling beats a listener here: Player has no
    // per-frame position callback, and twice a second is enough for a bar
    // that is ~1500px wide.
    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    LaunchedEffect(mediaId) {
        while (isActive) {
            delay(PROGRESS_POST_INTERVAL_SECONDS * 1000)
            if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                api.postProgress(mediaId, player.currentPosition / 1000.0)
            }
        }
    }

    LaunchedEffect(lastInteraction, isPlaying) {
        // Controls stay up while paused — a paused screen with no controls
        // gives the user nothing to act on.
        if (!isPlaying) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        controlsVisible = false
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs)
            .coerceIn(0L, if (player.duration > 0) player.duration else Long.MAX_VALUE)
        player.seekTo(target)
        position = target
        touch()
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        touch()
    }

    /** Leaving saves position first — otherwise up to ten seconds of watching
     * is lost every time someone backs out. */
    fun leave() {
        api.postProgressAsync(mediaId, player.currentPosition / 1000.0)
        onBack()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(K.Scrim)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.Spacebar,
                    Key.MediaPlayPause -> { togglePlay(); true }

                    Key.MediaPlay -> { player.play(); touch(); true }
                    Key.MediaPause -> { player.pause(); touch(); true }

                    Key.DirectionLeft -> { seekBy(-SEEK_STEP_MS); true }
                    Key.DirectionRight -> { seekBy(SEEK_STEP_MS); true }

                    Key.MediaRewind -> { seekBy(-SEEK_JUMP_MS); true }
                    Key.MediaFastForward -> { seekBy(SEEK_JUMP_MS); true }

                    Key.DirectionUp -> { touch(); true }

                    Key.DirectionDown -> {
                        if (controlsVisible) { controlsVisible = false; true } else false
                    }

                    Key.Back, Key.Escape -> {
                        // First Back dismisses the controls, second leaves —
                        // so an accidental press never drops you out of a film.
                        if (controlsVisible && isPlaying) {
                            controlsVisible = false
                            true
                        } else {
                            leave()
                            true
                        }
                    }

                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    // Our overlay replaces it; leaving it on would put two
                    // transports on screen fighting for the same keys.
                    useController = false
                }
            },
        )

        if (buffering) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = K.Accent)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Controls(
                title = title,
                position = position,
                duration = duration,
                isPlaying = isPlaying,
            )
        }
    }
}

@Composable
private fun Controls(
    title: String?,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(K.Scrim.copy(alpha = 0f), K.Scrim.copy(alpha = 0.92f)),
                )
            )
            .padding(horizontal = K.Gutter, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                title,
                style = K.Section,
                color = K.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Scrubber(position = position, duration = duration)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${formatTime(position)}  /  ${formatTime(duration)}",
                style = K.Body,
                color = K.TextMuted,
            )
            // A legend rather than buttons: the remote already has these keys,
            // and focusable on-screen buttons would steal the arrow keys that
            // seeking needs.
            Text(
                if (isPlaying) "OK PAUSE   ◀ ▶ 10s" else "OK PLAY   ◀ ▶ 10s",
                style = K.Eyebrow,
                color = K.TextFaint,
            )
        }
    }
}

@Composable
private fun Scrubber(position: Long, duration: Long) {
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(K.TextPrimary.copy(alpha = 0.22f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(K.Accent),
        )
    }
}

/** h:mm:ss past an hour, m:ss below it — a 42-minute episode showing
 * "0:42:15" wastes the two most prominent characters on a zero. */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
