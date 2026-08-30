package com.kdrive.tv.data

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
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

/**
 * How many bytes the buffer is allowed to hold.
 *
 * This is the setting that decides whether the durations above mean anything.
 * DefaultLoadControl stops buffering at whichever limit it reaches first, and
 * its default byte target is computed per track type — around 13 MB for
 * video. These are direct-play originals, frequently 15-25 Mbit/s, so 13 MB
 * is roughly six seconds: the player was buffering seconds, not minutes, and
 * every dip in throughput between the VPS and the television became a stall.
 *
 * The ceiling is the process heap, not the device's RAM: these are Java byte
 * arrays, and Android caps an app's heap well below physical memory —
 * commonly 192-256 MB on a television box. 64 MB leaves room for the rest of
 * the app while still being five times the default: around 35 seconds of a
 * 15 Mbit/s film, and several minutes of an ordinary 2.5 Mbit/s one. Paired
 * with prioritizeTimeOverSizeThresholds below, the duration targets are what
 * bind at ordinary bitrates and this is the backstop for extreme ones.
 */
private const val TARGET_BUFFER_BYTES = 64 * 1024 * 1024

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
 * The data source every load goes through: HTTP, wrapped in a cache that reads
 * what it already has and writes what it fetches.
 *
 * `setFlagIgnoreCacheOnError` matters: a corrupt or locked cache must degrade
 * to a plain network read, never to a failed playback.
 *
 * `source` decides whether the device key is attached and how the cache is
 * keyed — see streamingDataSourceFactory. `cacheKey` is the id everything
 * else in the app is keyed by, and is what a direct play is cached under.
 */
@OptIn(UnstableApi::class)
fun cachingDataSourceFactory(
    context: Context,
    source: PlaySource,
    cacheKey: String,
): DataSource.Factory =
    // Downloads first, and read-only. A title kept for offline is checked
    // before anything touches the network, which is what makes a downloaded
    // film play with the box disconnected — and what makes a partly
    // downloaded one play the part it has from disk while fetching the rest.
    downloadCacheReader(context, streamingDataSourceFactory(context, source, cacheKey))

/** The read-through cache used for everything that was not downloaded. */
@OptIn(UnstableApi::class)
private fun streamingDataSourceFactory(
    context: Context,
    source: PlaySource,
    cacheKey: String,
): DataSource.Factory {
    val http = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
        // A dropped connection mid-film should rebuffer, not end playback.
        // That matters on a host that caps request duration: the transfer is
        // killed server-side and ExoPlayer has to reconnect from the last
        // position it read.
        .setAllowCrossProtocolRedirects(true)

    // The device key authenticates this app to our own server and to nothing
    // else. A direct play fetches from googleapis.com, and these are default
    // request properties — set once, sent on every request the factory makes —
    // so attaching them here would hand our shared secret to Google on every
    // range request of every film. Only the proxy path gets them.
    if (source.direct) {
        // Google's own token, and only Google's. It is what authenticates the
        // read: alt=media refuses an unauthenticated request, and refuses one
        // that carries the token as a query parameter too, so the header is
        // the only way in. Set as a default as well as per connection so the
        // first open is authenticated even before the resolver below runs.
        http.setDefaultRequestProperties(source.requestHeaders())
    } else {
        http.setDefaultRequestProperties(api(context)?.authHeaders() ?: emptyMap())
    }

    // A direct URL carries an access token that Google expires within the
    // hour, and a film runs longer than that. Resolving the URI once at
    // preparation would leave the player holding a URL that stops working
    // partway through: every subsequent range request 401s, the retry path
    // re-prepares against the same dead URL, and the film ends in an error
    // screen an hour in.
    //
    // ResolvingDataSource asks again on every connection instead. ExoPlayer
    // already opens a new one per seek and per reconnect, so a fresh token
    // arrives naturally as playback proceeds, and the URL the network sees is
    // never one that has expired. ApiClient caches the answer until its token
    // is nearly out, so this is a round trip per token, not per request.
    val upstream: DataSource.Factory = if (source.direct) {
        ResolvingDataSource.Factory(http) { dataSpec ->
            val fresh = api(context)?.playUrlBlocking(cacheKey)
            // A refusal to resolve must not kill playback: keeping the spec
            // as it is retries the URL in hand, which is still valid whenever
            // the failure was the network rather than the token.
            if (fresh != null && fresh.direct) {
                // Both halves travel together: the token is what expires, and
                // the header carrying it is what the request is served on.
                dataSpec
                    .withUri(Uri.parse(fresh.url))
                    .withRequestHeaders(fresh.requestHeaders())
            } else {
                dataSpec
            }
        }
    } else {
        http
    }

    val cache = CacheDataSource.Factory()
        .setCache(PlaybackCache.get(context))
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // Media3 keys the cache by URI unless told otherwise. A direct URL carries
    // an access token that is different on every playback, so the default key
    // would never hit: the same film would be re-downloaded from Google each
    // time and written to disk again under a new key, filling the 2 GB budget
    // with copies of one title. The media id is stable and is what the rest of
    // the app already keys by.
    if (source.direct) {
        cache.setCacheKeyFactory(CacheKeyFactory { cacheKey })
    }

    return cache
}

/**
 * One ApiClient for the playback path, built once.
 *
 * Read here rather than passed in because the download service builds data
 * sources without an ApiClient to hand, and a missing key must degrade to an
 * unauthenticated request the server rejects cleanly — not to a crash.
 *
 * Held rather than rebuilt because the direct-URL resolver runs on every
 * connection and a fresh instance each time would mean a fresh empty token
 * cache each time: a round trip to the server, over the long path, in front
 * of every single range request. It also re-reads credentials from disk on
 * each call, which is not something to do on a loading thread.
 */
@Volatile
private var playbackApi: ApiClient? = null

private fun api(context: Context): ApiClient? =
    playbackApi ?: synchronized(PlaybackCache) {
        playbackApi ?: Credentials.loadBlocking(context.applicationContext)
            ?.let { ApiClient(it) }
            ?.also { playbackApi = it }
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
internal fun extractorsFactory() = DefaultExtractorsFactory()
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
fun mediaSourceFactory(
    context: Context,
    source: PlaySource,
    cacheKey: String,
    seekMap: CueSeekMap? = null,
): MediaSource.Factory {
    // With a table from the server, every extractor is wrapped so a file the
    // extractor calls unseekable picks it up instead. Without one, nothing
    // changes — see data/SeekIndex.kt for what the wrapping does and why the
    // byte offsets it seeks to are safe ones.
    val extractors = extractorsFactory()
        .let { if (seekMap != null) SeekIndexExtractorsFactory(it, seekMap) else it }

    return DefaultMediaSourceFactory(cachingDataSourceFactory(context, source, cacheKey), extractors)
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_ERROR_RETRIES))
}

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
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        // Without this the byte target wins and the durations above are
        // decoration. With it the player keeps buffering towards the minute
        // targets and only falls back on the byte ceiling for a bitrate high
        // enough to make those minutes unaffordable — which is the behaviour
        // the durations were written for.
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
