package com.mazharansari.streamhub.download

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService

/**
 * Enqueue / pause / resume / list / remove per-episode and per-movie downloads.
 *
 * PROXY RULE — READ THIS BEFORE CALLING [enqueue]: [enqueue] downloads the URL it is
 * given verbatim and performs NO proxy wrapping itself. For streams whose API entry has
 * `"proxy": true`, the caller MUST pass the proxied URL
 * (`{apiBase}/api/proxy?url=<url-encoded>&ua=<...>&referer=<...>`) as [enqueue]'s
 * `playbackUrl` — never the raw stream URL. The raw host is usually hotlink/IP-bound
 * and a bare download of it will 403. For `"proxy": false` streams, pass the direct
 * stream URL. The caller (Details screen) owns the stream choice and therefore owns
 * the proxy decision; this helper only records whatever URL it is handed.
 *
 * The download's title, source, content id, episode number and poster URL are stored
 * in the [DownloadRequest] data payload (fields separated by U+001F), so [list] can
 * rebuild [DownloadItem]s from the offline [DownloadIndex] without any other store.
 */
@UnstableApi
object DownloadHelper {

    /**
     * App-defined stop reason meaning "paused by the user".
     * Media3 defines only [Download.STOP_REASON_NONE] (0); any non-zero value stops the
     * download, and the value is surfaced again via [Download.stopReason].
     */
    const val STOP_REASON_PAUSED_BY_USER: Int = 1

    /** The five states the Downloads UI shows. */
    enum class DownloadState {
        QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED;

        companion object {
            /** Maps a Media3 [Download] to a UI state. STATE_STOPPED = paused by us. */
            fun fromMedia3(download: Download): DownloadState = when (download.state) {
                Download.STATE_COMPLETED -> COMPLETED
                Download.STATE_FAILED -> FAILED
                Download.STATE_DOWNLOADING -> DOWNLOADING
                Download.STATE_STOPPED -> PAUSED
                // QUEUED, REMOVING, RESTARTING: still in the queue from the user's view.
                else -> QUEUED
            }
        }
    }

    /** Stable id for one downloadable item: "<sourceId>_<contentId hash>_<episode>". */
    fun downloadId(sourceId: String, contentId: String, episodeNumber: Int): String =
        "${sourceId}_${contentId.hashCode()}_$episodeNumber"

    /**
     * Enqueue a download of [playbackUrl].
     *
     * @param playbackUrl the URL to download. MUST already be the proxied URL
     *  (`{apiBase}/api/proxy?url=...`) for `proxy:true` streams — see the class KDoc.
     * @param mimeType Media3 mime type, e.g. [MimeTypes.APPLICATION_M3U8] for HLS or
     *  [MimeTypes.VIDEO_MP4] for progressive MP4.
     * @param posterUrl poster/banner URL shown in the Downloads list; may be null.
     * @param episodeNumber episode number, or 0 for a movie (single-item title).
     *
     * Re-tapping download on an already queued / downloading / completed item is a
     * no-op; re-tapping a paused item resumes it instead of queueing a duplicate.
     */
    fun enqueue(
        context: Context,
        title: String,
        sourceId: String,
        contentId: String,
        episodeNumber: Int,
        playbackUrl: String,
        mimeType: String = MimeTypes.VIDEO_MP4,
        posterUrl: String? = null,
    ) {
        val id = downloadId(sourceId, contentId, episodeNumber)
        when (stateOf(context, id)) {
            Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_COMPLETED -> return
            Download.STATE_STOPPED -> {
                resume(context, id)
                return
            }
            else -> Unit // FAILED / REMOVING / unknown: (re-)enqueue below.
        }
        val data = listOf(
            title,
            sourceId,
            contentId,
            episodeNumber.toString(),
            posterUrl.orEmpty(),
        ).joinToString("\u001F")
        val request = DownloadRequest.Builder(id, android.net.Uri.parse(playbackUrl))
            .setCustomCacheKey(playbackUrl)
            .setData(data.toByteArray(Charsets.UTF_8))
            .setMimeType(mimeType.ifBlank { MimeTypes.VIDEO_MP4 })
            .build()
        DownloadService.sendAddDownload(
            context,
            StreamHubDownloadService::class.java,
            request,
            false,
        )
    }

    /** Pause a download (queued or downloading). No-op for completed / unknown ids. */
    fun pause(context: Context, downloadId: String) {
        DownloadService.sendSetStopReason(
            context,
            StreamHubDownloadService::class.java,
            downloadId,
            STOP_REASON_PAUSED_BY_USER,
            false,
        )
    }

    /** Resume a paused download. */
    fun resume(context: Context, downloadId: String) {
        DownloadService.sendSetStopReason(
            context,
            StreamHubDownloadService::class.java,
            downloadId,
            Download.STOP_REASON_NONE,
            false,
        )
    }

    /**
     * Delete a download. Media3's [DownloadManager] removes the download AND deletes
     * all of its cached content — including every partially downloaded HLS segment or
     * partial MP4 bytes — so deleting mid-download leaves 0 segments behind. There is
     * no separate partial file to clean up: the download's content lives only inside
     * the shared SimpleCache under its cache key, and removal drops those spans.
     */
    fun remove(context: Context, downloadId: String) {
        DownloadService.sendRemoveDownload(
            context,
            StreamHubDownloadService::class.java,
            downloadId,
            false,
        )
    }

    data class DownloadItem(
        val id: String,
        val title: String,
        val sourceId: String,
        val contentId: String,
        val episodeNumber: Int,
        val posterUrl: String?,
        val state: DownloadState,
        val progress: Float,
        val bytesDownloaded: Long,
        /** Total bytes, or -1 when the length is unknown (e.g. open-ended HLS). */
        val totalBytes: Long,
    )

    /**
     * Current downloads from the offline index, active downloads first, then
     * completed, then failed. Progress is `bytesDownloaded / contentLength`
     * (0 when the length is unknown).
     */
    fun list(context: Context): List<DownloadItem> {
        val manager = try {
            DownloadCache.getDownloadManager(context)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<DownloadItem>()
        val cursor = try {
            manager.downloadIndex.getDownloads()
        } catch (_: Exception) {
            return emptyList()
        }
        try {
            while (cursor.moveToNext()) {
                val d: Download = cursor.download
                val total = d.contentLength
                val progress = if (total > 0) {
                    (d.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val parts = d.request.data.decodeToString().split("\u001F")
                out.add(
                    DownloadItem(
                        id = d.request.id,
                        title = parts.getOrNull(0).orEmpty().ifBlank { d.request.id },
                        sourceId = parts.getOrNull(1).orEmpty(),
                        contentId = parts.getOrNull(2).orEmpty(),
                        episodeNumber = parts.getOrNull(3)?.toIntOrNull() ?: 0,
                        posterUrl = parts.getOrNull(4)?.ifBlank { null },
                        state = DownloadState.fromMedia3(d),
                        progress = progress,
                        bytesDownloaded = d.bytesDownloaded,
                        totalBytes = total,
                    ),
                )
            }
        } finally {
            cursor.close()
        }
        val rank = mapOf(
            DownloadState.DOWNLOADING to 0,
            DownloadState.QUEUED to 1,
            DownloadState.PAUSED to 2,
            DownloadState.FAILED to 3,
            DownloadState.COMPLETED to 4,
        )
        return out.sortedBy { rank[it.state] ?: 5 }
    }

    /** Raw Media3 [Download.STATE_*] for one download id, or null if unknown. */
    fun stateOf(context: Context, downloadId: String): Int? {
        val manager = try {
            DownloadCache.getDownloadManager(context)
        } catch (_: Exception) {
            return null
        }
        return try {
            manager.downloadIndex.getDownload(downloadId)?.state
        } catch (_: Exception) {
            null
        }
    }
}
