package com.streamhub.app.data

import android.content.Context
import java.io.File

/**
 * Storage breakdown for Settings (v1.0.3): how much disk the app's caches
 * and downloads use, and a safe "Clear cache" that wipes caches WITHOUT
 * touching completed downloads.
 *
 * - Images: Coil poster disk cache (cacheDir/image_cache).
 * - Lists: feed JSON cache (cacheDir/feed_cache).
 * - Video cache: streamed segments (filesDir/player_cache, 300MB LRU).
 * - Downloads: completed/in-progress downloads (filesDir/download_cache)
 *   — shown for transparency, NEVER cleared by "Clear cache".
 */
object StorageStats {

    data class Breakdown(
        val imageCacheBytes: Long,
        val feedCacheBytes: Long,
        val playerCacheBytes: Long,
        val downloadsBytes: Long,
    ) {
        val cachesBytes: Long get() = imageCacheBytes + feedCacheBytes + playerCacheBytes
        val totalBytes: Long get() = cachesBytes + downloadsBytes
    }

    fun dirSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        file.listFiles()?.forEach { total += dirSize(it) }
        return total
    }

    fun breakdown(context: Context): Breakdown = Breakdown(
        imageCacheBytes = dirSize(File(context.cacheDir, "image_cache")),
        feedCacheBytes = dirSize(File(context.cacheDir, "feed_cache")),
        playerCacheBytes = dirSize(File(context.filesDir, "player_cache")),
        downloadsBytes = dirSize(File(context.filesDir, "download_cache")),
    )

    /** Delete cache dirs (images + lists + video cache). Downloads untouched. */
    fun clearCaches(context: Context) {
        listOf(
            File(context.cacheDir, "image_cache"),
            File(context.cacheDir, "feed_cache"),
            File(context.filesDir, "player_cache"),
        ).forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }

    fun format(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
