package com.kdrive.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.kdrive.tv.data.Downloads

/**
 * The process that actually fetches a title to disk.
 *
 * A foreground service rather than a coroutine in the activity: a film is
 * gigabytes, the viewer will leave the app while it downloads, and anything
 * not in a foreground service is killed the moment they do. That is also why
 * there is a notification — the system requires one, and it doubles as the
 * only place progress is visible once the app is in the background.
 */
@OptIn(UnstableApi::class)
class KDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    /* channelDescriptionResourceId = */ 0,
) {

    override fun getDownloadManager(): DownloadManager {
        val manager = Downloads.manager(this)
        // The notification has to be told about state changes by something;
        // the service itself is the only thing guaranteed to be alive for the
        // whole download.
        manager.addListener(TerminalStateNotificationHelper())
        return manager
    }

    /**
     * Resumes queued downloads after a reboot or a lost network.
     *
     * PlatformScheduler is JobScheduler underneath, which is the only thing
     * that survives the app being killed. Without it a download interrupted
     * by turning the television off would simply sit unstarted until someone
     * opened the app and noticed.
     */
    override fun getScheduler(): Scheduler? =
        if (Build.VERSION.SDK_INT >= 21) PlatformScheduler(this, JOB_ID) else null

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification {
        ensureChannel()
        return notificationHelper.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            /* contentIntent = */ null,
            /* message = */ null,
            downloads,
            notMetRequirements,
        )
    }

    private val notificationHelper: DownloadNotificationHelper by lazy {
        DownloadNotificationHelper(this, CHANNEL_ID)
    }

    /**
     * The channel Media3 would normally create for us.
     *
     * Created by hand because DownloadService only creates it when it starts
     * in the foreground, and a notification posted before that — which the
     * terminal-state one below can be — is silently dropped on a channel that
     * does not exist yet.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    /** Says once, at the end, whether the title is on the device or is not.
     * A download that failed silently is the worst outcome available: the
     * viewer finds out on the aeroplane. */
    private inner class TerminalStateNotificationHelper : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            ensureChannel()
            val notification = when (download.state) {
                Download.STATE_COMPLETED -> notificationHelper.buildDownloadCompletedNotification(
                    this@KDownloadService,
                    android.R.drawable.stat_sys_download_done,
                    /* contentIntent = */ null,
                    /* message = */ null,
                )

                Download.STATE_FAILED -> notificationHelper.buildDownloadFailedNotification(
                    this@KDownloadService,
                    android.R.drawable.stat_notify_error,
                    /* contentIntent = */ null,
                    /* message = */ null,
                )

                else -> return
            }
            NotificationUtil.setNotification(this@KDownloadService, nextNotificationId++, notification)
        }
    }

    private var nextNotificationId = FOREGROUND_NOTIFICATION_ID + 1

    companion object {
        private const val CHANNEL_ID = "kplay_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val JOB_ID = 1
    }
}
