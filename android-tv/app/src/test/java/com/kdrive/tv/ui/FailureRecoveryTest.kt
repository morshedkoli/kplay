package com.kdrive.tv.ui

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Which failures the player handles by itself.
 *
 * Both rules decide whether the viewer sees an error screen or sees nothing
 * at all, and both are easy to break in a way no screenshot would reveal —
 * an audio failure misread as fatal kills a film whose picture is fine, and a
 * malformed file misread as transient retries three times before admitting
 * it. Pinned here rather than discovered on a sofa.
 *
 * Robolectric, because PlaybackException stamps itself with SystemClock on
 * construction and a bare JVM has no such clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FailureRecoveryTest {

    private fun rendererFailure(mimeType: String) = ExoPlaybackException.createForRenderer(
        IOException("decoder init failed"),
        "renderer",
        /* rendererIndex = */ 0,
        Format.Builder().setSampleMimeType(mimeType).build(),
        C.FORMAT_UNSUPPORTED_TYPE,
        /* isRecoverable = */ false,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    )

    private fun ioFailure(errorCode: Int) =
        PlaybackException("io", IOException("boom"), errorCode)

    /** The case the recovery exists for: DTS or TrueHD on a box with no
     * decoder for it. */
    @Test
    fun `an audio renderer failure is recognised`() {
        assertTrue(isAudioRendererFailure(rendererFailure(MimeTypes.AUDIO_DTS)))
    }

    /** Dropping the video track would leave nothing to watch, so a video
     * renderer failure must stay fatal. */
    @Test
    fun `a video renderer failure is not treated as an audio one`() {
        assertFalse(isAudioRendererFailure(rendererFailure(MimeTypes.VIDEO_H265)))
    }

    @Test
    fun `a network failure is not treated as an audio one`() {
        assertFalse(
            isAudioRendererFailure(
                ioFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            )
        )
    }

    @Test
    fun `connection failures are retried`() {
        assertTrue(
            isTransientFailure(ioFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        )
        assertTrue(
            isTransientFailure(ioFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        )
        // The stream proxy answers 5xx while it is still opening the file on
        // Drive, and a second later serves it — the exact shape of a retry.
        assertTrue(isTransientFailure(ioFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
    }

    /** Retrying these replays the same failure with the viewer watching. */
    @Test
    fun `broken files and missing decoders are not retried`() {
        assertFalse(
            isTransientFailure(ioFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        )
        assertFalse(
            isTransientFailure(ioFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        )
        assertFalse(
            isTransientFailure(
                ioFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
            )
        )
    }
}
