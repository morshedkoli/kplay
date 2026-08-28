package com.kdrive.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The labels and the bar on a Watching card.
 *
 * All of it is arithmetic over two numbers the server sends, and all of it is
 * wrong in a way nobody would notice from a screenshot — "1h 0m left", a bar
 * at 140%, an episode label on a film. Cheap to pin down here.
 */
class WatchingItemTest {

    private fun item(
        position: Double,
        duration: Double?,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        year: Int? = null,
    ) = WatchingItem(
        id = "i",
        type = type,
        mediaId = "m",
        title = "Title",
        year = year,
        positionSeconds = position,
        durationSeconds = duration,
        season = season,
        episode = episode,
    )

    @Test
    fun `progress is the fraction watched`() {
        assertEquals(0.5f, item(position = 50.0, duration = 100.0).progress!!, 0.001f)
    }

    /** A position past the duration is possible — the player posts on the way
     * out, and a file whose real length differs from the stored one can leave
     * a position beyond it. The bar must clamp rather than overflow its track. */
    @Test
    fun `progress clamps to the track`() {
        assertEquals(1f, item(position = 200.0, duration = 100.0).progress!!, 0.001f)
    }

    @Test
    fun `no duration means no bar and no remaining time`() {
        val old = item(position = 900.0, duration = null)
        assertNull(old.progress)
        assertNull(old.remainingLabel)
    }

    @Test
    fun `remaining reads in minutes below an hour`() {
        assertEquals("22 min left", item(position = 600.0, duration = 1_920.0).remainingLabel)
    }

    @Test
    fun `remaining reads in hours and minutes above one`() {
        assertEquals("1h 30m left", item(position = 0.0, duration = 5_400.0).remainingLabel)
    }

    /** Not "1h 0m left". */
    @Test
    fun `a whole number of hours drops the minutes`() {
        assertEquals("2h left", item(position = 0.0, duration = 7_200.0).remainingLabel)
    }

    /** Seconds from the end still says something rather than nothing: the
     * card is on screen, so its line has to read. */
    @Test
    fun `the last seconds round up to a minute`() {
        assertEquals("1 min left", item(position = 99.0, duration = 100.0).remainingLabel)
    }

    @Test
    fun `an episode is labelled by season and number`() {
        val ep = item(position = 0.0, duration = 1_200.0, type = "episode", season = 1, episode = 2)
        assertEquals("S01E02", ep.episodeLabel)
        assertEquals("Title  ·  S01E02", ep.playerTitle())
        assertEquals("S01E02  ·  20 min left", ep.subtitle())
    }

    @Test
    fun `a movie is labelled by year and carries no episode label`() {
        val movie = item(position = 0.0, duration = 1_200.0, year = 2016)
        assertNull(movie.episodeLabel)
        assertEquals("Title", movie.playerTitle())
        assertEquals("2016  ·  20 min left", movie.subtitle())
    }
}
