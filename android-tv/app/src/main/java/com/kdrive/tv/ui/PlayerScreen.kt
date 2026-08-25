package com.kdrive.tv.ui

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.CueSeekMap
import com.kdrive.tv.data.loadControl
import com.kdrive.tv.data.mediaSourceFactory
import com.kdrive.tv.data.renderersFactory
import com.kdrive.tv.ui.theme.K
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

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
 * One selectable audio rendition, flattened out of the nested Tracks model
 * into something a menu can render directly.
 *
 * `group` and `indexInGroup` are what the override is built from; everything
 * else exists only to label the row.
 */
internal data class AudioOption(
    val group: Tracks.Group,
    val indexInGroup: Int,
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val supported: Boolean,
)

/**
 * Reads the audio renditions out of the current track list.
 *
 * A file with one audio track produces one entry, and the caller uses that to
 * hide the menu entirely — offering a "choose language" list of length one is
 * just a dead end for the remote to wander into.
 */
internal fun audioOptions(tracks: Tracks): List<AudioOption> =
    tracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { group ->
            (0 until group.length).map { i ->
                val format = group.getTrackFormat(i)
                AudioOption(
                    group = group,
                    indexInGroup = i,
                    label = audioLabel(format.label, format.language, i),
                    detail = audioDetail(format.channelCount, format.codecs),
                    selected = group.isTrackSelected(i),
                    supported = group.isTrackSupported(i),
                )
            }
        }

/**
 * What to call a rendition.
 *
 * The container's own label wins when it has one — a release that bothered to
 * name a track "Director commentary" is more use than "English". Otherwise the
 * language tag is expanded to a real language name, because "hin" means
 * nothing on a television screen.
 */
private fun audioLabel(label: String?, language: String?, index: Int): String {
    if (!label.isNullOrBlank()) return label
    val tag = language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
    if (tag != null) {
        val name = Locale.forLanguageTag(tag).displayLanguage
        if (name.isNotBlank() && !name.equals(tag, ignoreCase = true)) return name
        return tag.uppercase(Locale.ROOT)
    }
    return "Audio ${index + 1}"
}

/** Channel layout and codec, when known — what distinguishes two tracks that
 * are both called "English". */
private fun audioDetail(channelCount: Int, codecs: String?): String? {
    val parts = mutableListOf<String>()
    when {
        channelCount == 1 -> parts += "Mono"
        channelCount == 2 -> parts += "Stereo"
        channelCount > 2 -> parts += "${channelCount}ch"
    }
    codecs?.substringBefore('.')?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}


/**
 * Where a seek keypress should wind to, or null when this file cannot be
 * seeked at all.
 *
 * Split out from the screen so the rule can be tested without a player: the
 * refusal is the whole point of the fix, and a rule that silently stopped
 * refusing would put the bug straight back.
 *
 * `seekable` is null until the timeline arrives. Winding is allowed then —
 * the player queues the seek, and a file that turns out to be unseekable
 * loses nothing it would not have lost anyway.
 */
internal fun seekTargetFor(
    currentMs: Long,
    pendingMs: Long?,
    deltaMs: Long,
    durationMs: Long,
    seekable: Boolean?,
): Long? {
    if (seekable == false) return null
    val from = pendingMs ?: currentMs
    // An unknown duration must not become a ceiling of zero, which would pin
    // every forward wind to the start — the very thing being fixed.
    val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
    return (from + deltaMs).coerceIn(0L, limit)
}
/**
 * Turns a playback failure into something worth putting on a television.
 *
 * The distinction that matters to the viewer is whether the file can never
 * play here (no decoder for it) or merely did not play just now (network),
 * because only one of those is worth pressing retry on.
 */
private fun describe(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    ->
        "Lost the connection to your server."

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "The server refused the stream. It may still be reading this file from Drive."

    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
        "The server sent this file with a type the player will not accept."

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    ->
        "This container is not one this player can read."

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    ->
        "This file looks damaged or incomplete."

    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
        "This video is beyond what this device can decode — too high a resolution or profile."

    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    ->
        "This device has no working decoder for this video or audio format."

    else -> error.errorCodeName
}

/**
 * Full-screen playback with a remote-driven transport.
 *
 * The stock Media3 controller is built for a touchscreen and is awkward under
 * a D-pad, so the overlay here is ours and every key is handled explicitly:
 *
 *   Centre / Enter / Play-Pause  toggle playback
 *   Left / Right                 wind ∓10s, accumulating
 *   Rewind / Fast-forward        wind ∓30s, accumulating
 *   Up                           show the controls
 *   Menu / Info                  audio track list, when the file has more than one
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
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    mediaId: String,
    title: String?,
    api: ApiClient,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Built from the shared playback configuration: cached HTTP, deep
    // buffers, decoder fallback. data/Playback.kt has the reasoning for why
    // none of those are left at their defaults.
    // Built empty: what it plays is decided once the server has been asked
    // for a seek table, because that table has to be in the extractor before
    // preparation, not after. See the LaunchedEffect below.
    val player = remember {
        ExoPlayer.Builder(context, renderersFactory(context))
            .setLoadControl(loadControl())
            .build()
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
    // Non-null once playback has failed. Until this existed a failure showed
    // as a spinner that never stopped, which is what "some videos do not
    // play" looked like from the sofa: no message, no retry, no clue.
    var failure by remember { mutableStateOf<String?>(null) }
    var audio by remember { mutableStateOf<List<AudioOption>>(emptyList()) }
    // Null until the timeline arrives, then whether this file can be seeked
    // at all. A Matroska file with no Cues index reports false, and every
    // seek into it lands at zero.
    var seekable by remember { mutableStateOf<Boolean?>(null) }
    // Shown briefly when a wind was refused, so the remote does not feel dead.
    var seekRefused by remember { mutableStateOf(false) }
    // True once a server-built seek table has been handed to the extractor, so
    // a file that would have reported itself unseekable now seeks.
    var indexed by remember { mutableStateOf(false) }
    // The resume position, held until we know whether seeking to it will work.
    var pendingResumeMs by remember { mutableStateOf<Long?>(null) }
    var audioMenuVisible by remember { mutableStateOf(false) }
    var audioCursor by remember { mutableIntStateOf(0) }

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


            /**
             * The timeline is where seekability becomes known.
             *
             * ProgressiveMediaSource reports a window as unseekable when the
             * extractor could not build a seek map — a Matroska file whose
             * Cues index is missing, or is only reachable through a second
             * SeekHead that MatroskaExtractor does not follow. Six of
             * thirteen files in the library at the time of writing were in
             * that state.
             */
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (timeline.isEmpty) return
                val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
                seekable = window.isSeekable
            }

            override fun onTracksChanged(tracks: Tracks) {
                audio = audioOptions(tracks)
                val selected = audio.indexOfFirst { it.selected }
                if (selected >= 0) audioCursor = selected
            }

            override fun onPlayerError(error: PlaybackException) {
                failure = describe(error)
                buffering = false
                controlsVisible = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Everything that has to happen before the first frame, in one place
    // because the order matters: the seek table has to reach the extractor
    // before preparation, and the resume position has to be known before the
    // timeline decides whether it can be honoured.
    LaunchedEffect(mediaId) {
        val resumeSeconds = api.getProgress(mediaId)
        if (resumeSeconds > 0) pendingResumeMs = (resumeSeconds * 1000).toLong()

        // A Matroska file whose Cues the extractor cannot reach reports itself
        // unseekable, and every wind then lands at zero. The server rebuilds
        // the table from the file itself; a null answer just means there was
        // nothing to recover, and playback proceeds exactly as before.
        val index = api.getSeekIndex(mediaId)
        val seekMap = index
            ?.takeIf { it.seekable }
            ?.let { CueSeekMap.from(it.cues, (it.durationMs ?: 0L) * 1000) }
        indexed = seekMap != null

        player.setMediaSource(
            mediaSourceFactory(context, api, seekMap)
                .createMediaSource(MediaItem.fromUri(api.streamUrl(mediaId)))
        )
        player.prepare()
        player.playWhenReady = true
        focusRequester.requestFocus()
    }

    // Resuming waits for the timeline, because resuming into an unseekable
    // file lands at zero — which looked like "it forgot where I was" and then
    // overwrote the saved position with the opening minute.
    LaunchedEffect(seekable, indexed, pendingResumeMs) {
        val resumeMs = pendingResumeMs ?: return@LaunchedEffect
        when (if (indexed) true else seekable) {
            null -> return@LaunchedEffect // not known yet; ask again when it is
            true -> {
                player.seekTo(resumeMs)
                position = resumeMs
            }

            false -> Unit // start from the beginning, the only place it can start
        }
        pendingResumeMs = null
    }

    // The refusal notice clears itself; it is a nudge, not a state to escape.
    LaunchedEffect(seekRefused) {
        if (!seekRefused) return@LaunchedEffect
        delay(2_500)
        seekRefused = false
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

    LaunchedEffect(lastInteraction, isPlaying, audioMenuVisible, failure) {
        // Controls stay up while paused, while the audio menu is open, and
        // after a failure — in all three the screen is showing something the
        // user still has to act on.
        if (!isPlaying || audioMenuVisible || failure != null) return@LaunchedEffect
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
        val target = seekTargetFor(
            currentMs = player.currentPosition,
            pendingMs = seekTargetMs,
            deltaMs = deltaMs,
            durationMs = player.duration,
            // A server-built table makes the file seekable whatever the
            // extractor concluded on its own, and it is handed over before
            // preparation — so it is known good before the timeline arrives.
            seekable = if (indexed) true else seekable,
        )
        if (target == null) {
            // Refusing beats obeying. ExoPlayer treats a seek into unseekable
            // media as a seek to t=0, so the press the user just made would
            // throw them back to the opening titles — which is exactly the
            // bug this guard exists to stop.
            seekRefused = true
            touch()
            return
        }
        seekTargetMs = target
        touch()
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        showActionGlyph = true
        touch()
    }

    /** Re-preparing keeps the current position, so a retry after a network
     * drop resumes where the picture stopped rather than at the start. */
    fun retry() {
        failure = null
        buffering = true
        player.prepare()
        player.play()
    }

    /**
     * Pins playback to one audio rendition.
     *
     * An override rather than a preferred-language parameter: the user picked
     * this exact track, and a preference would let the selector quietly choose
     * a different one whenever the track list is rebuilt.
     */
    fun chooseAudio(option: AudioOption) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(option.group.mediaTrackGroup, option.indexInGroup),
            )
            .build()
        audioMenuVisible = false
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

                // The menu owns every key while it is open, so the transport
                // underneath cannot seek out from under the list being read.
                if (audioMenuVisible) {
                    return@onKeyEvent when (event.key) {
                        Key.DirectionUp -> {
                            audioCursor = (audioCursor - 1).coerceAtLeast(0)
                            true
                        }

                        Key.DirectionDown -> {
                            audioCursor = (audioCursor + 1).coerceAtMost(audio.lastIndex)
                            true
                        }

                        Key.DirectionCenter, Key.Enter -> {
                            audio.getOrNull(audioCursor)?.let(::chooseAudio)
                            true
                        }

                        Key.Back, Key.Escape, Key.Menu, Key.DirectionLeft -> {
                            audioMenuVisible = false
                            true
                        }

                        // Swallowed rather than passed through: a stray key
                        // must not seek the film behind the open menu.
                        else -> true
                    }
                }

                // Likewise after a failure. There is nothing to seek in, and
                // the only two useful answers are try again and leave.
                if (failure != null) {
                    return@onKeyEvent when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.MediaPlay, Key.MediaPlayPause -> {
                            retry()
                            true
                        }

                        Key.Back, Key.Escape -> {
                            leave()
                            true
                        }

                        else -> true
                    }
                }

                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.Spacebar,
                    Key.MediaPlayPause -> { togglePlay(); true }

                    Key.MediaPlay -> { player.play(); touch(); true }
                    Key.MediaPause -> { player.pause(); touch(); true }

                    Key.DirectionLeft -> { seekBy(-SEEK_STEP_MS); true }
                    Key.DirectionRight -> { seekBy(SEEK_STEP_MS); true }

                    Key.MediaRewind -> { seekBy(-SEEK_JUMP_MS); true }
                    Key.MediaFastForward -> { seekBy(SEEK_JUMP_MS); true }

                    // Remotes disagree about which of these they carry, so all
                    // three open the audio list. A file with one soundtrack
                    // has nothing to show, and the key falls through instead.
                    Key.Menu, Key.Info, Key.M -> {
                        if (audio.size > 1) {
                            audioMenuVisible = true
                            touch()
                            true
                        } else {
                            false
                        }
                    }

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

        if (buffering && failure == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = K.Accent)
            }
        }

        if (showActionGlyph && !buffering && failure == null) {
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

        failure?.let { message -> PlaybackFailure(title = title, message = message) }

        if (audioMenuVisible) AudioMenu(options = audio, cursor = audioCursor)

        AnimatedVisibility(
            visible = controlsVisible && failure == null,
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
                hasAudioChoice = audio.size > 1,
                seekable = seekable != false,
                seekRefused = seekRefused,
            )
        }
    }
}

/** Replaces the picture entirely — a half-visible frozen frame behind an
 * error reads as a glitch, while a plain panel reads as a decision. */
@Composable
private fun PlaybackFailure(title: String?, message: String) {
    Box(
        Modifier.fillMaxSize().background(K.Ink.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 120.dp),
        ) {
            if (!title.isNullOrBlank()) {
                Text(title, style = K.Eyebrow, color = K.TextFaint)
            }
            Text("Can't play this", style = K.PageTitle, color = K.TextPrimary)
            Text(message, style = K.Body, color = K.TextMuted)
            Text("OK  TRY AGAIN      BACK  LEAVE", style = K.Eyebrow, color = K.TextFaint)
        }
    }
}

/**
 * Audio track list.
 *
 * A right-hand panel rather than a centred dialog: the user is looking at a
 * picture and choosing a soundtrack for it, so covering as little of the frame
 * as possible is the point.
 *
 * The rows are drawn, not focusable. Focus stays on the player box that owns
 * the key handler — real focusable rows would take the D-pad away from it and
 * break seeking the moment the menu closed.
 */
@Composable
internal fun AudioMenu(options: List<AudioOption>, cursor: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .padding(end = K.Gutter)
                .width(380.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(K.Ink.copy(alpha = 0.95f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Audio", style = K.Section, color = K.TextPrimary)
            Text(
                "▲ ▼  choose      OK  select      BACK  close",
                style = K.Eyebrow,
                color = K.TextFaint,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            options.forEachIndexed { index, option ->
                val highlighted = index == cursor
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (highlighted) K.SurfaceHi else Color.Transparent)
                        .border(
                            width = if (highlighted) 2.dp else 0.dp,
                            color = if (highlighted) K.Accent else Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            option.label,
                            style = K.Body,
                            // A track this device cannot decode is still
                            // listed, greyed. Hiding it would leave the user
                            // hunting for a language the file demonstrably
                            // has.
                            color = if (option.supported) K.TextPrimary else K.TextFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail = if (option.supported) {
                            option.detail
                        } else {
                            listOfNotNull(option.detail, "not supported").joinToString(" · ")
                        }
                        if (!detail.isNullOrBlank()) {
                            Text(detail, style = K.Eyebrow, color = K.TextFaint)
                        }
                    }
                    if (option.selected) Text("●", style = K.Body, color = K.Accent)
                }
            }
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
    hasAudioChoice: Boolean = false,
    seekable: Boolean = true,
    seekRefused: Boolean = false,
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

        // Said plainly, and only when it matters. A viewer pressing fast
        // forward on a file that cannot be seeked deserves to be told why
        // nothing happened rather than left pressing harder.
        if (!seekable) {
            Text(
                if (seekRefused) {
                    "This file has no seek index, so it can only play straight through."
                } else {
                    "No seek index in this file — seeking is unavailable."
                },
                style = K.Body,
                color = if (seekRefused) K.Accent else K.TextMuted,
            )
        }

        Scrubber(
            position = shown,
            buffered = buffered,
            duration = duration,
            scrubbing = seekTargetMs != null,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
            // seeking needs. The audio entry appears only when the file
            // actually carries a second soundtrack.
            Text(
                buildString {
                    append(if (isPlaying) "OK  PAUSE" else "OK  PLAY")
                    // No point advertising keys that this file will refuse.
                    if (seekable) append("      ◀ ▶  10s      ◀◀ ▶▶  30s")
                    if (hasAudioChoice) append("      MENU  AUDIO")
                },
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
