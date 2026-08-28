package com.kdrive.tv.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.kdrive.tv.KDownloadService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeping a title on the device.
 *
 * The library lives behind one server and one Drive account, so anything the
 * viewer wants on a plane, or on a connection that cannot sustain the
 * bitrate, has to be on the box's own disk first. Media3's DownloadManager
 * does the fetching; everything here is about giving it one cache, one set of
 * credentials, and a shape the UI can read.
 *
 * The download cache is deliberately separate from the streaming cache in
 * Playback.kt. That one evicts least-recently-used spans to stay under a
 * budget, which is right for bytes nobody asked to keep and catastrophic for
 * bytes somebody did: a film downloaded for a journey would be quietly eaten
 * by the next evening's browsing. This cache has no evictor at all — only
 * DownloadManager removes from it, and only when asked.
 */
@OptIn(UnstableApi::class)
object Downloads {

    private const val CACHE_DIR_NAME = "downloads"

    /** Enough of the file on disk to be worth starting.
     *
     * Downloads run strictly forward from byte zero, so once a tenth is
     * present the viewer can start watching it while the rest arrives behind
     * them — the way every "download and watch" feature works. Below that
     * there is not enough runway for the download to stay ahead of the
     * playhead, and pressing play would simply stream. */
    const val PLAYABLE_PERCENT = 10f

    /** One concurrent download.
     *
     * The bytes come through the same server, from the same Drive account, as
     * playback does. Two downloads at once would halve the throughput of each
     * and compete with whatever is being watched — this is a household media
     * box, not a download manager.
     */
    private const val MAX_PARALLEL_DOWNLOADS = 1

    /** Any non-zero value means stopped; this one means a person stopped it,
     * as opposed to a requirement like the network going away. */
    private const val STOP_REASON_PAUSED = 1

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var manager: DownloadManager? = null

    /**
     * Credentials for the downloader.
     *
     * DownloadService is constructed by the system — after a reboot, or when
     * a queued download resumes — with no activity to hand it anything, so
     * the key it needs cannot arrive as a constructor argument. MainActivity
     * publishes it here as soon as it knows it, and the service falls back to
     * reading it off disk when it started cold.
     */
    @Volatile
    var credentials: Credentials? = null

    /**
     * The download store.
     *
     * `filesDir`, not `cacheDir`: Android empties cacheDir whenever storage
     * runs short, and a file the viewer explicitly saved is not the system's
     * to reclaim.
     *
     * SimpleCache locks its directory, so this is created once and held for
     * the life of the process.
     */
    fun cache(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.filesDir, CACHE_DIR_NAME),
                NoOpCacheEvictor(),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }

    fun manager(context: Context): DownloadManager =
        manager ?: synchronized(this) {
            manager ?: build(context.applicationContext).also { manager = it }
        }

    private fun build(context: Context): DownloadManager {
        val http = DefaultHttpDataSource.Factory()
            // Resolved per request rather than captured once: the service can
            // outlive the activity that set them, and a download started
            // before the key was known must not carry an empty header.
            .setDefaultRequestProperties(authHeaders(context))
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

        return DownloadManager(
            context,
            StandaloneDatabaseProvider(context),
            cache(context),
            http,
            // A download is long and mostly waiting on the network; a single
            // pooled thread is what it needs, not the main one.
            Executors.newFixedThreadPool(2),
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            // Downloads wait for a network and resume on their own when one
            // returns, rather than failing the moment the box goes offline.
            requirements = Requirements(Requirements.NETWORK)
        }
    }

    private fun authHeaders(context: Context): Map<String, String> {
        val creds = credentials ?: Credentials.loadBlocking(context)
        credentials = creds
        return creds?.let { ApiClient(it).authHeaders() } ?: emptyMap()
    }

    val Context.downloadIndex: DownloadIndex get() = manager(this).downloadIndex

    /**
     * Starts, or resumes, keeping this id on the device.
     *
     * The id is the same one everything else is keyed by — a media _id for a
     * movie, an episode _id for an episode — so a download, a resume position
     * and a stream URL all agree about what "this" is without a mapping table.
     */
    fun start(context: Context, id: String, streamUrl: String, meta: DownloadMeta) {
        val request = DownloadRequest.Builder(id, android.net.Uri.parse(streamUrl))
            // The title and artwork ride along inside the request.
            //
            // The whole point of a download is that it works with no server,
            // and the library list — which is where every other screen gets a
            // title from — needs one. Anything the Downloads screen must
            // render offline has to be on the device already, so it is
            // written here, once, while the network is still available.
            .setData(meta.encode())
            .build()
        DownloadService.sendAddDownload(
            context,
            KDownloadService::class.java,
            request,
            /* foreground = */ false,
        )
    }

    /**
     * Stops fetching without discarding what has arrived.
     *
     * A stop reason is how Media3 expresses "paused": anything non-zero puts
     * the download in STATE_STOPPED and leaves its bytes in the cache, so
     * resuming carries on from where it stopped rather than starting the
     * gigabytes again. The number itself is ours to choose and means only
     * "the viewer asked".
     */
    fun pause(context: Context, id: String) = setStopReason(context, id, STOP_REASON_PAUSED)

    /** Puts it back in the queue, keeping everything already downloaded. */
    fun resume(context: Context, id: String) = setStopReason(context, id, Download.STOP_REASON_NONE)

    private fun setStopReason(context: Context, id: String, reason: Int) {
        DownloadService.sendSetStopReason(
            context,
            KDownloadService::class.java,
            id,
            reason,
            /* foreground = */ false,
        )
    }

    /** Removes it and its bytes. */
    fun remove(context: Context, id: String) {
        DownloadService.sendRemoveDownload(
            context,
            KDownloadService::class.java,
            id,
            /* foreground = */ false,
        )
    }

    /**
     * Every download, re-read whenever any of them changes.
     *
     * DownloadManager reports changes one download at a time; the UI wants
     * the whole set, and the set is a handful of rows, so each change simply
     * re-reads it. That keeps the screens declarative rather than having them
     * patch a list from events.
     */
    fun flow(context: Context): Flow<List<Download>> = callbackFlow {
        val downloadManager = manager(context)

        fun publish() {
            trySend(downloadManager.currentDownloads.snapshotAll(downloadManager))
        }

        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) = publish()

            override fun onDownloadRemoved(
                downloadManager: DownloadManager,
                download: Download,
            ) = publish()

            override fun onInitialized(downloadManager: DownloadManager) = publish()
        }

        downloadManager.addListener(listener)
        publish()
        awaitClose { downloadManager.removeListener(listener) }
    }

    /**
     * Reads the full set out of the index.
     *
     * `currentDownloads` holds only what is in flight, so a finished download
     * would vanish from the list the moment it completed — which is exactly
     * when it becomes worth showing. The index is the durable record; the
     * in-flight list is merged over it because only it carries live progress.
     */
    private fun List<Download>.snapshotAll(downloadManager: DownloadManager): List<Download> {
        val live = associateBy { it.request.id }
        val all = mutableListOf<Download>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val stored = cursor.download
                all += live[stored.request.id] ?: stored
            }
        }
        return all
    }
}

/**
 * What a downloaded thing is called, stored on the device beside its bytes.
 *
 * Kept deliberately small: a title, a line under it, and the two artwork
 * paths. Anything more would be duplicating the library into the download
 * index, and the library is the server's job whenever the server is there.
 */
@kotlinx.serialization.Serializable
data class DownloadMeta(
    val title: String,
    val subtitle: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
) {
    fun encode(): ByteArray =
        json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /**
         * Reads the metadata back, or invents the least-wrong stand-in.
         *
         * A download made by an older build carries no data at all, and a
         * card with no title is worse than a card labelled by its id — but
         * only just, so the fallback says "Downloaded" rather than showing a
         * raw ObjectId to someone sitting on a sofa.
         */
        fun decode(data: ByteArray): DownloadMeta = runCatching {
            json.decodeFromString(serializer(), data.toString(Charsets.UTF_8))
        }.getOrElse { DownloadMeta(title = "Downloaded") }
    }
}

/** The label and artwork this download was saved with. */
@OptIn(UnstableApi::class)
val Download.meta: DownloadMeta get() = DownloadMeta.decode(request.data)

/** True once there is enough on disk to start watching from it. */
@OptIn(UnstableApi::class)
val Download.playableOffline: Boolean
    get() = state == Download.STATE_COMPLETED ||
        (state != Download.STATE_FAILED && percentDownloaded >= Downloads.PLAYABLE_PERCENT)

/** 0f..1f for a progress bar. Media3 reports -1 until it knows the length. */
@OptIn(UnstableApi::class)
val Download.fraction: Float
    get() = (percentDownloaded / 100f).coerceIn(0f, 1f)

/**
 * A read-only view of the download cache, to sit in front of the streaming
 * one during playback.
 *
 * Read-only matters: DownloadManager owns the accounting for what is in this
 * cache, and letting ordinary playback write into it would leave spans it
 * never agreed to keep and cannot be asked to remove.
 */
@OptIn(UnstableApi::class)
fun downloadCacheReader(
    context: Context,
    upstream: androidx.media3.datasource.DataSource.Factory,
): CacheDataSource.Factory =
    CacheDataSource.Factory()
        .setCache(Downloads.cache(context))
        .setUpstreamDataSourceFactory(upstream)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
