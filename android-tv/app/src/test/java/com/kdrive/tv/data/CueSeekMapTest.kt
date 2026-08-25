package com.kdrive.tv.data

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seek table is the whole fix: a wrong byte offset does not degrade
 * playback, it sends the extractor into the middle of a cluster and the film
 * stops. So the rules it has to obey are pinned here rather than discovered on
 * a television.
 */
class CueSeekMapTest {

    private fun map(vararg points: Pair<Long, Long>, durationMs: Long = 60_000) =
        CueSeekMap.from(points.map { listOf(it.first, it.second) }, durationMs * 1000)!!

    @Test
    fun `seeks to the cue at or before the requested time`() {
        val seekMap = map(0L to 100L, 10_000L to 5_000L, 20_000L to 9_000L)

        // Halfway between two cues must go back to the earlier one: playing
        // forward from there is correct, while landing after the target would
        // silently skip content the viewer asked to see.
        val points = seekMap.getSeekPoints(15_000_000L)
        assertEquals(10_000_000L, points.first.timeUs)
        assertEquals(5_000L, points.first.position)
    }

    @Test
    fun `an exact cue time seeks to that cue alone`() {
        val seekMap = map(0L to 100L, 10_000L to 5_000L, 20_000L to 9_000L)
        val points = seekMap.getSeekPoints(10_000_000L)
        assertEquals(10_000_000L, points.first.timeUs)
        assertEquals(points.first, points.second)
    }

    @Test
    fun `seeking past the last cue lands on the last cue, never past the file`() {
        val seekMap = map(0L to 100L, 10_000L to 5_000L)
        val points = seekMap.getSeekPoints(9_999_000_000L)
        assertEquals(5_000L, points.first.position)
    }

    @Test
    fun `seeking before the first cue lands at the start`() {
        val seekMap = map(0L to 100L, 10_000L to 5_000L)
        val points = seekMap.getSeekPoints(0L)
        assertEquals(100L, points.first.position)
    }

    @Test
    fun `out-of-order input is sorted rather than trusted`() {
        val seekMap = map(20_000L to 9_000L, 0L to 100L, 10_000L to 5_000L)
        assertEquals(100L, seekMap.getSeekPoints(0L).first.position)
        assertEquals(9_000L, seekMap.getSeekPoints(20_000_000L).first.position)
    }

    @Test
    fun `a table too thin to seek with is refused`() {
        assertNull(CueSeekMap.from(emptyList(), 60_000_000L))
        assertNull(CueSeekMap.from(listOf(listOf(0L, 100L)), 60_000_000L))
    }

    @Test
    fun `an unknown duration is reported as unset, not as zero`() {
        val seekMap = CueSeekMap.from(listOf(listOf(0L, 100L), listOf(10_000L, 5_000L)), 0L)!!
        assertEquals(C.TIME_UNSET, seekMap.durationUs)
        assertTrue(seekMap.isSeekable)
    }

    @Test
    fun `withDuration keeps the table and takes the duration`() {
        val seekMap = map(0L to 100L, 10_000L to 5_000L, durationMs = 0)
        val withDuration = seekMap.withDuration(90_000_000L)

        assertEquals(90_000_000L, withDuration.durationUs)
        assertEquals(5_000L, withDuration.getSeekPoints(10_000_000L).first.position)
    }

    @Test
    fun `withDuration ignores a duration that says nothing`() {
        val seekMap = map(0L to 100L, 10_000L to 5_000L, durationMs = 60_000)
        assertEquals(60_000_000L, seekMap.withDuration(C.TIME_UNSET).durationUs)
    }
}
