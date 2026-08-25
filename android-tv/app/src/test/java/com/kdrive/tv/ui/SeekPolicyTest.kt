package com.kdrive.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The seek rule, pinned.
 *
 * The bug this guards against: ExoPlayer's ProgressiveMediaPeriod documents
 * that it will "treat all seeks into non-seekable media as being to t=0", and
 * a Matroska file whose Cues index is absent — or is only reachable through a
 * second SeekHead, which MatroskaExtractor does not follow — reports exactly
 * that. Pressing fast forward on one of those threw the viewer back to the
 * opening titles. Refusing the seek is the fix, so the refusal is what gets a
 * test.
 */
class SeekPolicyTest {

    private val tenMinutes = 600_000L
    private val twoHours = 7_200_000L

    @Test
    fun `refuses to seek when the file has no seek index`() {
        val target = seekTargetFor(
            currentMs = tenMinutes,
            pendingMs = null,
            deltaMs = 10_000,
            durationMs = twoHours,
            seekable = false,
        )
        assertNull("an unseekable file must refuse, not seek to zero", target)
    }

    @Test
    fun `winds forward from the current position`() {
        val target = seekTargetFor(
            currentMs = tenMinutes,
            pendingMs = null,
            deltaMs = 10_000,
            durationMs = twoHours,
            seekable = true,
        )
        assertEquals(tenMinutes + 10_000, target)
    }

    @Test
    fun `accumulates from the pending target, not the playhead`() {
        // Four presses of right must move forty seconds, not ten — the whole
        // reason a pending target exists.
        var pending: Long? = null
        repeat(4) {
            pending = seekTargetFor(tenMinutes, pending, 10_000, twoHours, seekable = true)
        }
        assertEquals(tenMinutes + 40_000, pending)
    }

    @Test
    fun `never winds past the end or before the start`() {
        assertEquals(
            twoHours,
            seekTargetFor(twoHours - 5_000, null, 30_000, twoHours, seekable = true),
        )
        assertEquals(
            0L,
            seekTargetFor(5_000, null, -30_000, twoHours, seekable = true),
        )
    }

    @Test
    fun `an unknown duration does not pin every seek to the start`() {
        // player.duration is C.TIME_UNSET before the timeline arrives. Treating
        // that as a ceiling would clamp every forward wind to zero, which is
        // the same symptom by a different route.
        val target = seekTargetFor(
            currentMs = tenMinutes,
            pendingMs = null,
            deltaMs = 30_000,
            durationMs = -9_223_372_036_854_775_807L,
            seekable = null,
        )
        assertEquals(tenMinutes + 30_000, target)
    }

    @Test
    fun `allows winding before seekability is known`() {
        val target = seekTargetFor(tenMinutes, null, 10_000, twoHours, seekable = null)
        assertEquals(tenMinutes + 10_000, target)
    }
}
