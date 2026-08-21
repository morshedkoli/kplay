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

/** How long the keys must be quiet before a wound-to position is committed.
 * Long enough to collect a burst of presses, short enough that a deliberate
 * single press does not feel laggy. */
private const val SEEK_COMMIT_DELAY_MS = 450L

/**
 * Full-screen playback with a remote-driven transport.
 *
 * Media3's stock controller is built for a touchscreen and is awkward under a
 * D-pad, so the overlay here is ours and every key is handled explicitly:
 *
 *   Centre / Enter / Play-Pause  toggle playback
 *   Left / Right                 wind ∓10s, accumulating
 *   Rewind / Fast-forward        wind ∓30s, accumulating
 *   Up                           show the controls
 *   Down / Back                  hide the controls, then leave
 *
 * Seeking accumulates the way it does on a phone: presses move a target that
 * the scrubber previews, and the player is told once, after the keys go
 * quiet. Holding a key therefore scrubs smoothly instead of stuttering
 * through a seek per repeat.
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
    var buffered by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    // Non-null while the user is scrubbing: the position they have wound to
    // but not yet committed.
    var seekTargetMs by remember { mutableStateOf<Long?>(null) }
    // Brief play/pause flash in the centre of the screen, as confirmation that
    // the keypress registered even when the controls are hidden.
    var showActionGlyph by remember { mutableStateOf(false) }
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
            buffered = player.bufferedPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    // Commits the wound-to position once the keys go quiet. Restarting on
    // every change is the debounce: hold the key and this simply never fires
    // until you let go.
    LaunchedEffect(seekTargetMs) {
        val target = seekTargetMs ?: return@LaunchedEffect
        delay(SEEK_COMMIT_DELAY_MS)
        player.seekTo(target)
        position = target
        seekTargetMs = null
    }

    LaunchedEffect(showActionGlyph) {
        if (!showActionGlyph) return@LaunchedEffect
        delay(600)
        showActionGlyph = false
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

    /**
     * Accumulates a seek instead of performing one per keypress.
     *
     * Pressing right four times should move forty seconds, and the obvious
     * implementation — seek to currentPosition + 10s each time — does not do
     * that: every press reads a position the earlier seeks have not settled
     * into yet, so the presses overwrite each other and the picture lands ten
     * seconds away no matter how many times the key was hit.
     *
     * So each press only moves a target, the scrubber previews it, and the
     * player is told once the user stops pressing. That is also what makes a
     * held key scrub smoothly rather than stutter through repeated seeks.
     */
    fun seekBy(deltaMs: Long) {
        val from = seekTargetMs ?: player.currentPosition
        val limit = if (player.duration > 0) player.duration else Long.MAX_VALUE
        seekTargetMs = (from + deltaMs).coerceIn(0L, limit)
        touch()
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        showActionGlyph = true
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

        if (showActionGlyph && !buffering) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(42.dp))
                        .background(K.Scrim.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isPlaying) "▶" else "❚❚",
                        style = K.PageTitle,
                        color = K.TextPrimary,
                    )
                }
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
                buffered = buffered,
                duration = duration,
                isPlaying = isPlaying,
                seekTargetMs = seekTargetMs,
            )
        }
    }
}

@Composable
internal fun Controls(
    title: String?,
    position: Long,
    buffered: Long,
    duration: Long,
    isPlaying: Boolean,
    seekTargetMs: Long?,
) {
    // While scrubbing the bar and the clock show where you are winding to,
    // not where playback still is — otherwise the numbers argue with the
    // thumbnail the user is steering by.
    val shown = seekTargetMs ?: position
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

        Scrubber(position = shown, buffered = buffered, duration = duration, scrubbing = seekTargetMs != null)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${formatTime(shown)}  /  ${formatTime(duration)}",
                    style = K.Body,
                    color = K.TextPrimary,
                )
                if (seekTargetMs != null) {
                    val offset = seekTargetMs - position
                    Text(
                        (if (offset >= 0) "+" else "−") + formatTime(kotlin.math.abs(offset)),
                        style = K.Body,
                        color = K.Accent,
                    )
                }
            }
            // A legend rather than buttons: the remote already has these keys,
            // and focusable on-screen buttons would steal the arrow keys that
            // seeking needs.
            Text(
                if (isPlaying) "OK  PAUSE      ◀ ▶  10s      ◀◀ ▶▶  30s"
                else "OK  PLAY      ◀ ▶  10s      ◀◀ ▶▶  30s",
                style = K.Eyebrow,
                color = K.TextFaint,
            )
        }
    }
}

@Composable
private fun Scrubber(position: Long, buffered: Long, duration: Long, scrubbing: Boolean) {
    val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val ahead = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Box(
        Modifier
            .fillMaxWidth()
            .height(if (scrubbing) 8.dp else 5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(K.TextPrimary.copy(alpha = 0.22f)),
    ) {
        // What has downloaded, behind what has played — the same three-layer
        // bar every streaming player uses, and the only honest way to show
        // that a seek past this point will have to buffer.
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(ahead)
                .background(K.TextPrimary.copy(alpha = 0.35f)),
        )
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(played)
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
