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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.kdrive.tv.data.ApiClient
import com.kdrive.tv.data.CueSeekMap
import com.kdrive.tv.data.PlaybackTelemetry
import com.kdrive.tv.data.loadControl
import com.kdrive.tv.data.mediaSourceFactory
import com.kdrive.tv.data.renderersFactory
import com.kdrive.tv.ui.components.KIcons
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

/**
 * How many times a dropped stream is picked back up on its own before the
 * viewer is shown an error.
 *
 * The bytes come from one long HTTP request, proxied through the server and
 * out to Drive, and hosts cap request duration — so a connection dying part
 * way through a film is routine rather than exceptional. Reconnecting is what
 * the viewer would do by hand anyway; three attempts is enough to ride out a
 * blip and few enough that a genuinely dead server still surfaces quickly.
 */
private const val MAX_STREAM_RETRIES = 3

/** Multiplied by the attempt number, so the gap grows if the server needs a
 * moment rather than hammering it three times in a second. */
private const val RETRY_BACKOFF_MS = 1_200L

/** How long a transient notice stays up. Long enough to read one line at
 * three metres, short enough not to sit over the picture. */
private const val NOTICE_TIMEOUT_MS = 5_000L

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
 * Whether the thing that failed was the soundtrack rather than the film.
 *
 * This is the single biggest cause of "that video just won't play": an MKV
 * carrying DTS, TrueHD or E-AC-3 on a box whose decoder cannot take it. The
 * video is perfectly playable, but a renderer failure is fatal to the whole
 * playback, so the viewer sees an error screen for a file that could have
 * played with sound off or on another one of its own tracks.
 *
 * Identified by the format the failing renderer was handed, not by the error
 * code — the same decoder codes arise for video, where dropping the track
 * would leave nothing to watch.
 */
internal fun isAudioRendererFailure(error: PlaybackException): Boolean {
    val exo = error as? ExoPlaybackException ?: return false
    if (exo.type != ExoPlaybackException.TYPE_RENDERER) return false
    return MimeTypes.isAudio(exo.rendererFormat?.sampleMimeType)
}

/**
 * Whether this is worth quietly trying again.
 *
 * Everything here is about the connection, not the file: the same request
 * repeated a second later plausibly succeeds. A malformed container or a
 * missing decoder is not on this list, because retrying those just replays
 * the same failure with the viewer watching.
 */
internal fun isTransientFailure(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_TIMEOUT,
    -> true

    else -> false
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
 *   Up                           audio track list when the file carries more
 *                                than one soundtrack, otherwise the controls
 *   Menu / Info                  the same list, on remotes that have those keys
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
            .apply {
                // Seeks land on the nearest keyframe instead of decoding
                // forward to an exact frame. On a file streamed over one HTTP
                // connection, exact seeking means fetching and decoding
                // everything between the previous keyframe and the target —
                // seconds of black screen for a wind the viewer wanted to be
                // instant. A keyframe is at most a second or two off, which
                // nobody winding through a film can perceive.
                setSeekParameters(SeekParameters.CLOSEST_SYNC)

                // Take audio focus properly: without this the soundtrack
                // keeps playing over anything else the television starts, and
                // does not pause for a call or an alarm.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
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
    // Non-null once playback has failed. Until this existed a failure showed
    // as a spinner that never stopped, which is what "some videos do not
    // play" looked like from the sofa: no message, no retry, no clue.
    var failure by remember { mutableStateOf<String?>(null) }
    var audio by remember { mutableStateOf<List<AudioOption>>(emptyList()) }
    // The failure the listener last reported, handed to the recovery effect
    // below. Deliberately separate from `failure`, which is the message on
    // screen: most failures are recoverable and should never get that far.
    var pendingError by remember { mutableStateOf<PlaybackException?>(null) }
    var streamRetries by remember { mutableIntStateOf(0) }
    // True once a soundtrack this device cannot decode has been switched off
    // to keep the picture alive. Nothing re-enables audio but the user
    // choosing a track by hand.
    var audioDisabled by remember { mutableStateOf(false) }
    // One line, briefly, for things the viewer should know but must not be
    // stopped by: a reconnection, a soundtrack that had to be dropped, a
    // track the hardware refuses.
    var notice by remember { mutableStateOf<String?>(null) }
    // The automatic "pick a soundtrack that actually works" pass runs once per
    // file. Without the latch a track the selector refuses to honour would
    // have it firing on every track update, forever.
    var autoAudioTried by remember { mutableStateOf(false) }
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

    // Android TV runs an idle-input sleep timer, and a remote nobody is
    // pressing during a film counts as idle — which is why playback was being
    // cut off mid-title. Holding the screen awake for as long as this screen
    // is composed prevents that; the flag is dropped on the way out, so the
    // television still sleeps when nothing is playing.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Playback diagnostics, reported to the server when this screen goes away.
    // Keyed on the id so switching titles starts a fresh count rather than
    // attributing one film's stalls to the next. See data/PlaybackTelemetry.kt.
    val telemetry = remember(mediaId) { PlaybackTelemetry(api, mediaId, title) }
    DisposableEffect(player, telemetry) {
        telemetry.attach(player)
        onDispose { telemetry.detach(player) }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) controlsVisible = true
                // Playing again means the last drop was survived, so the
                // budget is for consecutive failures rather than for the
                // whole film — a two-hour title over a flaky link may need
                // reconnecting a dozen times and should get it.
                if (state == Player.STATE_READY) streamRetries = 0
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
                val options = audioOptions(tracks)
                audio = options
                val selected = options.indexOfFirst { it.selected }
                if (selected >= 0) audioCursor = selected

                // A file whose first soundtrack is DTS or TrueHD hands this
                // device a track it cannot decode, and the result is either
                // silence or a fatal renderer error — on a file that often
                // also carries a perfectly ordinary AAC track further down
                // the list. Moving to that track before playback settles is
                // the difference between "no sound on this one" and it just
                // working.
                val playable = options.any { it.selected && it.supported }
                if (!playable && !audioDisabled && !autoAudioTried) {
                    val alternative = options.firstOrNull { it.supported }
                    if (alternative != null) {
                        autoAudioTried = true
                        player.trackSelectionParameters =
                            player.trackSelectionParameters.buildUpon()
                                .setOverrideForType(
                                    TrackSelectionOverride(
                                        alternative.group.mediaTrackGroup,
                                        alternative.indexInGroup,
                                    ),
                                )
                                .build()
                        notice = "Switched to ${alternative.label} — this device " +
                            "cannot decode the file's first soundtrack."
                    }
                }
            }

            // Handed on rather than shown. Most failures here are a dropped
            // connection or a soundtrack this box cannot decode, and both are
            // recoverable without the viewer doing anything.
            override fun onPlayerError(error: PlaybackException) {
                pendingError = error
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
        // Guarded: requestFocus throws if the node is not attached yet, and
        // an exception here takes down the whole screen — a black player
        // instead of a film, which is exactly what it looks like from the
        // sofa when a title "won't play".
        runCatching { focusRequester.requestFocus() }
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

    /**
     * What happens after a failure, before the viewer is told about one.
     *
     * Three outcomes, in order of how little they cost:
     *
     *  1. The soundtrack failed. Move to another one the device can decode,
     *     or switch audio off entirely — the film keeps playing either way.
     *     An error screen for a film whose picture is fine is the worst
     *     answer available.
     *  2. The connection dropped. Reconnect, up to a few times, backing off.
     *     `prepare()` after an error keeps the position, so the picture
     *     resumes where it stopped without a seek — which matters, because a
     *     file with no seek index could not be wound back there.
     *  3. Anything else, or a budget spent: say so, and offer retry.
     */
    LaunchedEffect(pendingError) {
        val error = pendingError ?: return@LaunchedEffect
        pendingError = null

        if (isAudioRendererFailure(error)) {
            val alternative = audio.firstOrNull { it.supported && !it.selected }
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .apply {
                    if (alternative != null) {
                        setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        setOverrideForType(
                            TrackSelectionOverride(
                                alternative.group.mediaTrackGroup,
                                alternative.indexInGroup,
                            ),
                        )
                    } else {
                        // No soundtrack this box can play. Silence beats a
                        // film that refuses to start, and the notice says
                        // which of the two happened.
                        setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    }
                }
                .build()
            audioDisabled = alternative == null
            notice = if (alternative != null) {
                "Soundtrack failed — switched to ${alternative.label}."
            } else {
                "No soundtrack in this file can be decoded here. Playing without sound."
            }
            buffering = true
            player.prepare()
            player.play()
            return@LaunchedEffect
        }

        if (isTransientFailure(error) && streamRetries < MAX_STREAM_RETRIES) {
            streamRetries += 1
            notice = "Lost the stream — reconnecting ($streamRetries of $MAX_STREAM_RETRIES)."
            buffering = true
            delay(RETRY_BACKOFF_MS * streamRetries)
            player.prepare()
            player.play()
            return@LaunchedEffect
        }

        failure = describe(error)
        buffering = false
        controlsVisible = true
    }

    // Notices are transient by definition — nothing about them is a state the
    // viewer has to leave.
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_TIMEOUT_MS)
        notice = null
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
                api.postProgress(
                    mediaId,
                    player.currentPosition / 1000.0,
                    // Only once the player has actually resolved it. Before
                    // that Media3 reports TIME_UNSET, and durationSeconds is
                    // what the Watching shelf uses to decide a title is
                    // finished — a wrong one hides it for good.
                    player.duration.takeIf { it > 0 }?.let { it / 1000.0 },
                )
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
        // A deliberate press is a fresh start: the automatic budget was spent
        // on the last drop, and refusing to reconnect after the viewer asked
        // would make the retry key look broken.
        streamRetries = 0
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
        // Picking a track this device has no decoder for used to be allowed:
        // the row was greyed but OK still selected it, and the result was
        // silence, or the whole film dying on a renderer error. The menu now
        // says no and stays open, so the next press lands on a track that
        // will actually play.
        if (!option.supported) {
            notice = "\"${option.label}\" is a format this device cannot decode."
            touch()
            return
        }

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            // Clears the switch-off that an earlier undecodable soundtrack
            // may have left behind; without it a deliberate choice would be
            // overridden by the recovery from a previous one.
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(
                TrackSelectionOverride(option.group.mediaTrackGroup, option.indexInGroup),
            )
            .build()
        audioDisabled = false
        audioMenuVisible = false
        touch()
    }

    /** Leaving saves position first — otherwise up to ten seconds of watching
     * is lost every time someone backs out. */
    fun leave() {
        api.postProgressAsync(
            mediaId,
            player.currentPosition / 1000.0,
            player.duration.takeIf { it > 0 }?.let { it / 1000.0 },
        )
        onBack()
    }

    // Normally there is nothing to choose between with one soundtrack. The
    // exception is a lone track that had to be switched off to save the
    // picture: the list is then the only way back to trying it again, so a
    // single entry is worth showing.
    val audioChoosable = audio.size > 1 || (audio.isNotEmpty() && audioDisabled)

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
                        if (audioChoosable) {
                            audioMenuVisible = true
                            touch()
                            true
                        } else {
                            false
                        }
                    }

                    // Up is the dependable way onto the audio list. Plenty of
                    // TV remotes have no MENU or INFO key at all, so a menu
                    // bound only to those keys could not be reached from the
                    // sofa; the arrow keys are the one part of a remote that
                    // always exists.
                    Key.DirectionUp -> {
                        if (audioChoosable) {
                            audioCursor = audio.indexOfFirst { it.selected }.coerceAtLeast(0)
                            audioMenuVisible = true
                        }
                        touch()
                        true
                    }

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
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.PlayArrow else KIcons.Pause,
                        contentDescription = if (isPlaying) "Playing" else "Paused",
                        tint = K.TextPrimary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }

        failure?.let { message -> PlaybackFailure(title = title, message = message) }

        // Above the picture, out of the way of the transport at the bottom
        // and of the audio panel at the right. It reports something that has
        // already been handled, so it must never look like a dialog waiting
        // on an answer.
        notice?.takeIf { failure == null }?.let { message ->
            Text(
                message,
                style = K.Body,
                color = K.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(K.Gutter)
                    .clip(RoundedCornerShape(6.dp))
                    .background(K.Scrim.copy(alpha = 0.82f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

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
                hasAudioChoice = audioChoosable,
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = KIcons.Audio,
                    contentDescription = null,
                    tint = K.Accent,
                    modifier = Modifier.size(22.dp),
                )
                Text("Audio", style = K.Section, color = K.TextPrimary)
            }
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
            // seeking needs. Each entry is the icon for what the key does
            // beside the key that does it — a glyph reads across a room in a
            // way a row of words does not.
            Row(
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hint(
                    icon = if (isPlaying) KIcons.Pause else Icons.Filled.PlayArrow,
                    description = if (isPlaying) "Pause" else "Play",
                    key = "OK",
                )
                // No point advertising keys that this file will refuse. The
                // D-pad entry keeps its arrows as text — a remote's direction
                // pad has no icon of its own, and drawing one would invent a
                // symbol the viewer has never seen.
                if (seekable) {
                    Text("◀ ▶  10s", style = K.Eyebrow, color = K.TextFaint)
                    Hint(KIcons.FastRewind, "Rewind", "30s")
                    Hint(KIcons.FastForward, "Fast forward", "30s")
                }
                // Only when the file actually offers a choice.
                if (hasAudioChoice) Hint(KIcons.Audio, "Audio track", "▲")
            }
        }
    }
}

/**
 * One entry in the transport legend: what the key does, then which key.
 *
 * The icon carries the meaning and the label carries the key, which is the
 * right split — the viewer already knows what a pause symbol means and needs
 * telling only which button on the remote produces it.
 */
@Composable
private fun Hint(icon: ImageVector, description: String, key: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = K.TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Text(key, style = K.Eyebrow, color = K.TextFaint)
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
