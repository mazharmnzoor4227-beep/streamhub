package com.mazharansari.streamhub.download

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.File
import java.util.concurrent.Executors

/** Shared on-disk cache used by both the player and the download service. */
object DownloadCache {
    private var cache: SimpleCache? = null
    private var databaseProvider: DatabaseProvider? = null
    private var downloadManager: DownloadManager? = null

    @Synchronized
    private fun getDatabaseProvider(context: Context): DatabaseProvider {
        return databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext)
            .also { databaseProvider = it }
    }

    @Synchronized
    fun get(context: Context): SimpleCache {
        return cache ?: SimpleCache(
            File(context.applicationContext.filesDir, "download_cache"),
            LeastRecentlyUsedCacheEvictor(2L * 1024 * 1024 * 1024),
            getDatabaseProvider(context),
        ).also { cache = it }
    }

    /**
     * Single shared [DownloadManager], built with the Media3 1.4.1 constructor
     * (Context, DatabaseProvider, Cache, DataSource.Factory, Executor).
     * The same database provider backs both the cache and the download index,
     * as in the Media3 demo. Both [StreamHubDownloadService] and
     * [DownloadHelper] use this holder — Media3's DownloadService exposes no
     * static getDownloadManager(context) helper.
     */
    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        val appContext = context.applicationContext
        return downloadManager ?: DownloadManager(
            appContext,
            getDatabaseProvider(appContext),
            get(appContext),
            DefaultHttpDataSource.Factory(),
            Executors.newSingleThreadExecutor(),
        ).also { downloadManager = it }
    }
}
