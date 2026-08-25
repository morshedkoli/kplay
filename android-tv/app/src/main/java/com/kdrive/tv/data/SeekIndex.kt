package com.kdrive.tv.data

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput

/**
 * Makes a file seekable that the extractor said it could not seek.
 *
 * A Matroska file keeps its seek index in a Cues element announced by a
 * SeekHead. MatroskaExtractor reads the first SeekHead and no further, so when
 * a muxer chains a second one — a common enough layout — the extractor finds no
 * Cues, publishes an unseekable SeekMap, and ProgressiveMediaPeriod then does
 * what its own comment says it does: treats every seek into non-seekable media
 * as a seek to zero. On a television that is fast-forward restarting the film.
 *
 * The index is in the file; only the route to it is one the player will not
 * walk. The server walks it instead (lib/library/mkv-index.js) and returns a
 * table of time-to-byte positions. Everything here is about getting that table
 * in front of ExoPlayer:
 *
 *  - [CueSeekMap] presents the table as a SeekMap.
 *  - [SeekIndexExtractor] wraps a real extractor and swaps the SeekMap it
 *    publishes, leaving every other part of extraction untouched.
 *
 * The substitution is safe because each byte offset in the table is the start
 * of a Cluster — the same position the extractor's own Cues path would have
 * produced. Seeking to one is a thing MatroskaExtractor already knows how to
 * do; it was only ever missing the address.
 */

/** A seek table built from [timeMs, byteOffset] pairs, ascending by time. */
@OptIn(UnstableApi::class)
class CueSeekMap(
    private val timesUs: LongArray,
    private val positions: LongArray,
    private val durationUs: Long,
) : SeekMap {

    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = durationUs

    /**
     * The same table with a duration taken from elsewhere.
     *
     * The map being replaced is usually SeekMap.Unseekable, which carries the
     * duration MatroskaExtractor read from the file's Info element — more
     * reliable than anything derived here, and the value the scrubber and the
     * end-of-file check both depend on. Losing it would leave the player with
     * an unknown duration and no progress bar.
     */
    fun withDuration(newDurationUs: Long): CueSeekMap =
        if (newDurationUs <= 0 || newDurationUs == durationUs) this
        else CueSeekMap(timesUs, positions, newDurationUs)

    /**
     * The cue at or before the requested time, which is what a player wants:
     * seeking backwards to the nearest keyframe and playing forward is correct,
     * while landing after the target would silently skip content.
     *
     * The following cue is offered as the second point so ExoPlayer can pick
     * whichever is closer when it is allowed to.
     */
    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val index = floorIndexFor(timeUs)
        val before = SeekPoint(timesUs[index], positions[index])
        if (index + 1 >= timesUs.size || timesUs[index] >= timeUs) {
            return SeekMap.SeekPoints(before)
        }
        return SeekMap.SeekPoints(before, SeekPoint(timesUs[index + 1], positions[index + 1]))
    }

    /** Binary search for the last entry whose time is <= [timeUs]. */
    private fun floorIndexFor(timeUs: Long): Int {
        var low = 0
        var high = timesUs.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (timesUs[mid] <= timeUs) low = mid else high = mid - 1
        }
        return low
    }

    companion object {
        /**
         * Builds a map from the server's table, or null when the table is too
         * thin to seek with. Points are sorted and de-duplicated because a
         * SeekMap with times out of order would send the player backwards.
         */
        fun from(cues: List<List<Long>>, durationUs: Long): CueSeekMap? {
            val points = cues
                .filter { it.size >= 2 && it[0] >= 0 && it[1] >= 0 }
                .map { it[0] * 1000 to it[1] }
                .sortedBy { it.first }
                .distinctBy { it.first }
            if (points.size < 2) return null
            return CueSeekMap(
                timesUs = LongArray(points.size) { points[it].first },
                positions = LongArray(points.size) { points[it].second },
                durationUs = if (durationUs > 0) durationUs else C.TIME_UNSET,
            )
        }
    }
}

/**
 * Wraps an extractor and replaces an unseekable SeekMap with one built from
 * the server's table. Everything else is passed straight through.
 *
 * A SeekMap the extractor found for itself is always left alone: the file's own
 * index is more precise than anything reconstructed around it, and a file that
 * already seeks needs no help.
 */
@OptIn(UnstableApi::class)
class SeekIndexExtractor(
    private val delegate: Extractor,
    private val seekMap: CueSeekMap,
) : Extractor {

    override fun init(output: ExtractorOutput) {
        delegate.init(SubstitutingOutput(output, seekMap))
    }

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    private class SubstitutingOutput(
        private val delegate: ExtractorOutput,
        private val replacement: CueSeekMap,
    ) : ExtractorOutput {

        override fun track(id: Int, type: Int): TrackOutput = delegate.track(id, type)

        override fun endTracks() = delegate.endTracks()

        override fun seekMap(seekMap: SeekMap) {
            if (seekMap.isSeekable) {
                delegate.seekMap(seekMap)
                return
            }
            // Keep the duration the discarded map knew; only its refusal to
            // seek is being replaced.
            delegate.seekMap(replacement.withDuration(seekMap.durationUs))
        }
    }
}

/**
 * The extractors of [delegate], each wrapped so an unseekable file picks up
 * [seekMap].
 *
 * Wrapping every extractor rather than only the Matroska one keeps this
 * container-agnostic: the wrapper defers to any seek map its delegate produces,
 * so an extractor that seeks perfectly well is unaffected by being wrapped.
 */
@OptIn(UnstableApi::class)
class SeekIndexExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val seekMap: CueSeekMap,
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map { SeekIndexExtractor(it, seekMap) }.toTypedArray()

    override fun createExtractors(
        uri: android.net.Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders)
            .map { SeekIndexExtractor(it, seekMap) }
            .toTypedArray()
}
