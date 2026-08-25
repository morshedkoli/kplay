package com.kdrive.tv.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import java.io.File

/**
 * Playback plumbing shared by every ExoPlayer this app builds.
 *
 * Everything here exists for one of two reasons: a file that would not play
 * at all, or a file that played worse than the network could support.
 */

/** Long enough for the server to wake up, open a Drive connection and send a
 * first byte. Media3's own defaults are eight seconds, which a cold serverless
 * function plus a Drive round trip routinely exceeds — the read simply timed
 * out and the title looked broken. */
private const val HTTP_CONNECT_TIMEOUT_MS = 30_000
private const val HTTP_READ_TIMEOUT_MS = 30_000

/** The stream is proxied through one server that in turn talks to Drive, so a
 * transient 5xx or a dropped socket is normal and recoverable. The default
 * policy gives up after three tries; a long film deserves more patience. */
private const val LOAD_ERROR_RETRIES = 6

/** Buffer targets, well above Media3's 50s/50s defaults. Video files here are
 * direct-play originals at bitrates a stock buffer drains through in seconds,
 * and the bytes come over one HTTP connection whose throughput varies — a deep
 * buffer is what turns a bandwidth dip into nothing at all instead of a stall. */
private const val MIN_BUFFER_MS = 60_000
private const val MAX_BUFFER_MS = 240_000
private const val BUFFER_FOR_PLAYBACK_MS = 2_500
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

/** Kept behind the playhead so a short rewind replays from memory rather than
 * re-seeking the network. */
private const val BACK_BUFFER_MS = 30_000

/** Disk budget for the read-through cache. Big enough to hold an episode or
 * most of a film, small enough to leave a TV box's storage alone. */
private const val DISK_CACHE_BYTES = 2L * 1024 * 1024 * 1024

private const val CACHE_DIR_NAME = "media-cache"

/**
 * Process-wide SimpleCache.
 *
 * SimpleCache locks its directory, so a second instance over the same folder
 * throws. One instance, created once, held for the life of the process.
 */
@OptIn(UnstableApi::class)
object PlaybackCache {
    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, CACHE_DIR_NAME),
                LeastRecentlyUsedCacheEvictor(DISK_CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
}

/**
 * The data source every load goes through: HTTP with the device key attached,
 * wrapped in a cache that reads what it already has and writes what it fetches.
 *
 * `setFlagIgnoreCacheOnError` matters: a corrupt or locked cache must degrade
 * to a plain network read, never to a failed playback.
 */
@OptIn(UnstableApi::class)
fun cachingDataSourceFactory(context: Context, api: ApiClient): DataSource.Factory {
    val http = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(api.authHeaders())
        .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
        // A dropped connection mid-film should rebuffer, not end playback.
        // That matters on a host that caps request duration: the transfer is
        // killed server-side and ExoPlayer has to reconnect from the last
        // position it read.
        .setAllowCrossProtocolRedirects(true)

    return CacheDataSource.Factory()
        .setCache(PlaybackCache.get(context))
        .setUpstreamDataSourceFactory(http)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/**
 * Extractors tuned for the containers people actually drop into a Drive
 * folder rather than for the streaming formats Media3 defaults at.
 *
 *  - Constant-bitrate seeking rescues files with no seek index (a lot of
 *    TS and MP3-audio AVI rips): without it, seeking is refused or lands
 *    nowhere.
 *  - Ignoring MP4 edit lists is Media3's own recommended workaround for the
 *    edit lists many encoders write wrong, which otherwise show as a black
 *    first few seconds or audio out of sync for the whole film.
 *  - Detecting access units in TS costs a little startup time and is the
 *    difference between a broadcast capture playing and not.
 */
@OptIn(UnstableApi::class)
private fun extractorsFactory() = DefaultExtractorsFactory()
    .setConstantBitrateSeekingEnabled(true)
    .setConstantBitrateSeekingAlwaysEnabled(true)
    .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
    .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)

/**
 * Media source factory for a progressive (non-adaptive) file.
 *
 * DefaultMediaSourceFactory rather than ProgressiveMediaSource.Factory: the
 * library holds whatever the user put in it, and a manifest-based file
 * (HLS/DASH) handed to a progressive-only factory fails outright. This picks
 * the right source per URI and still uses the progressive path for the plain
 * files that are the norm here.
 */
@OptIn(UnstableApi::class)
fun mediaSourceFactory(context: Context, api: ApiClient): MediaSource.Factory =
    DefaultMediaSourceFactory(cachingDataSourceFactory(context, api), extractorsFactory())
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_ERROR_RETRIES))

/**
 * Renderers with decoder fallback on.
 *
 * This is the single biggest cause of "some videos just don't play" on TV
 * hardware: a box advertises an HEVC or AC-3 decoder, the decoder then fails
 * to initialise for that particular profile, and without fallback ExoPlayer
 * treats the first failure as fatal instead of trying the next decoder that
 * claims the format.
 */
@OptIn(UnstableApi::class)
fun renderersFactory(context: Context): RenderersFactory =
    DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

@OptIn(UnstableApi::class)
fun loadControl(): LoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .build()
