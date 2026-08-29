package com.kdrive.tv.data

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Watches one playback and reports, when it ends, what actually happened on
 * the television.
 *
 * The server measures its own throughput (lib/monitor/metrics.js), but a
 * server cannot see a stall it did not cause. Three cases look identical from
 * the VPS and completely different here:
 *
 *  - The link was slow: the player rebuffered and its bandwidth estimate is
 *    below the file's bitrate.
 *  - The box could not decode fast enough: frames were dropped, and playback
 *    never went back to buffering.
 *  - Nothing was wrong: no rebuffers, no dropped frames — whatever the viewer
 *    noticed was not this.
 *
 * The report is fire-and-forget and carries no personal data — an id, a
 * title, and counters. If the post fails, nothing about playback changes.
 */
@OptIn(UnstableApi::class)
class PlaybackTelemetry(
    private val api: ApiClient,
    private val mediaId: String,
    private val title: String?,
) {
    private var rebuffers = 0
    private var rebufferMs = 0L
    private var bufferingSince = 0L

    /** Set once the first frame has rendered: a wait before that is startup,
     * not a rebuffer, and counting it would make every playback look bad. */
    private var started = false

    private var droppedFrames = 0
    private var videoBitrate = 0
    private var videoFormat: String? = null
    private var watchedMs = 0L
    private var playingSince = 0L
    private var bandwidthBitsPerSec = 0L

    private var reported = false

    val analyticsListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrameCount: Int,
            elapsedMs: Long,
        ) {
            droppedFrames += droppedFrameCount
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            // peakBitrate first: `bitrate` is often unset on a progressive
            // file, and the peak is what the link actually has to sustain.
            videoBitrate = when {
                format.peakBitrate != Format.NO_VALUE -> format.peakBitrate
                format.averageBitrate != Format.NO_VALUE -> format.averageBitrate
                else -> 0
            }
            videoFormat = listOfNotNull(
                format.sampleMimeType,
                if (format.width > 0) "${format.width}x${format.height}" else null,
            ).joinToString(" ")
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            elapsedMs: Int,
            bytesTransferred: Long,
            bitrateEstimate: Long,
        ) {
            bandwidthBitsPerSec = bitrateEstimate
        }
    }

    val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            started = true
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = System.currentTimeMillis()
            if (isPlaying) {
                playingSince = now
            } else if (playingSince > 0L) {
                watchedMs += now - playingSince
                playingSince = 0L
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            val now = System.currentTimeMillis()
            when (state) {
                Player.STATE_BUFFERING -> {
                    // Only a return to buffering after the picture was up
                    // counts. Startup buffering is not a stall.
                    if (started && bufferingSince == 0L) {
                        bufferingSince = now
                        rebuffers += 1
                    }
                }

                Player.STATE_READY, Player.STATE_ENDED -> {
                    if (bufferingSince > 0L) {
                        rebufferMs += now - bufferingSince
                        bufferingSince = 0L
                    }
                }
            }
        }
    }

    /** Attaches both listeners. Call [detach] from the same DisposableEffect. */
    fun attach(player: ExoPlayer) {
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
    }

    /**
     * Removes the listeners and sends the report.
     *
     * Sending happens here rather than on a timer because the interesting
     * number is the total for the whole viewing, and because a report per
     * minute would bury the one that matters in the server's event ring.
     */
    fun detach(player: ExoPlayer) {
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)

        if (playingSince > 0L) {
            watchedMs += System.currentTimeMillis() - playingSince
            playingSince = 0L
        }
        if (bufferingSince > 0L) {
            rebufferMs += System.currentTimeMillis() - bufferingSince
            bufferingSince = 0L
        }

        // Nothing was watched — the viewer backed out of a title that never
        // started. No signal in that, and it would only add noise.
        if (reported || watchedMs < 1_000L) return
        reported = true

        api.postPlaybackReportAsync(
            PlaybackReport(
                mediaId = mediaId,
                title = title,
                rebuffers = rebuffers,
                rebufferMs = rebufferMs,
                droppedFrames = droppedFrames,
                videoBitrate = videoBitrate,
                videoFormat = videoFormat,
                watchedMs = watchedMs,
                estimatedBandwidth = bandwidthBitsPerSec,
            ),
        )
    }
}
