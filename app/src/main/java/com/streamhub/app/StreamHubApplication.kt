package com.streamhub.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.request.CachePolicy
import com.streamhub.app.data.SettingsStore
import com.streamhub.app.data.api.ApiClient
import com.streamhub.app.data.api.FeedCache
import com.streamhub.app.data.api.Repository
import com.streamhub.app.data.extensions.ExtensionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Holds the app-wide singletons. All content comes from the StreamHub Flask
 * API — there are no built-in providers and no demo data. Extensions are API
 * sources, enabled/disabled locally via [ExtensionStore].
 *
 * Also supplies the app-wide Coil [ImageLoader]: poster hosts (TMDB, MetaHub,
 * Aoneroom, …) are slow from the phone, so the loader uses longer timeouts
 * than Coil's 10s defaults plus a disk cache, so a poster fetched once
 * never has to be fetched again. Poster URLs themselves are routed through
 * the API proxy (see [ApiClient.posterUrl]) before they reach Coil.
 */
class StreamHubApplication : Application(), ImageLoaderFactory {

    companion object {
        lateinit var settings: SettingsStore
            private set

        lateinit var extensions: ExtensionStore
            private set

        lateinit var apiClient: ApiClient
            private set

        lateinit var repository: Repository
            private set

        lateinit var feedCache: FeedCache
            private set
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        extensions = ExtensionStore(this)
        apiClient = ApiClient()
        feedCache = FeedCache(this)
        repository = Repository(apiClient, extensions, feedCache)
        // Pre-warm the home feed in the background (disk cache + a silent
        // refresh), so the first screen the user opens already has content.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { repository.warmCache() }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }
}
