package com.streamhub.app.download

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.streamhub.app.R

/** Media3 offline download service: downloads episodes (MP4 + HLS) for offline play. */
@UnstableApi
class StreamHubDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description,
) {
    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val JOB_ID = 1
        private const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "streamhub_downloads"
    }

    private lateinit var notificationHelper: DownloadNotificationHelper

    override fun getDownloadManager(): DownloadManager =
        DownloadCache.getDownloadManager(this)

    override fun getScheduler(): Scheduler? = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        if (!::notificationHelper.isInitialized) {
            notificationHelper = DownloadNotificationHelper(this, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
        }
        return notificationHelper.buildProgressNotification(
            this,
            R.drawable.ic_launcher_foreground,
            null,
            null,
            downloads,
            notMetRequirements,
        )
    }
}
