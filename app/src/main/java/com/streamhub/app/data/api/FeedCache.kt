package com.streamhub.app.data.api

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Two-level list cache (v1.0.2): memory + disk (JSON files under
 * cacheDir/feed_cache). Screens show the cached list INSTANTLY (no spinner)
 * and refresh silently in the background, so navigating around the app
 * feels immediate after the first load. The disk copy survives a cold
 * start; the server pre-warm + 30-min refresh keeps it fresh.
 *
 * Keys are plain strings such as "trending:all" or "category:kdrama".
 * A cache entry is only ever a list the API really returned — never demo.
 */
class FeedCache(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.cacheDir, "feed_cache").apply { mkdirs() }
    private val memory = mutableMapOf<String, List<ApiItem>>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = ApiClient.json

    @Volatile
    private var sourcesMemory: List<ApiSource>? = null

    // Keys use only letters and ':' — ':' becomes '-' in filenames, which
    // reverses exactly on load (no key contains a literal '-').
    private fun safeKey(key: String): String = key.replace(':', '-')

    private fun rawKey(fileName: String): String =
        fileName.removeSuffix(".json").replace('-', ':')

    private fun fileFor(key: String): File = File(dir, safeKey(key) + ".json")

    fun get(key: String): List<ApiItem>? = memory[key]

    fun put(key: String, items: List<ApiItem>) {
        memory[key] = items
        ioScope.launch {
            runCatching {
                fileFor(key).writeText(json.encodeToString<List<ApiItem>>(items))
            }
        }
    }

    fun getSources(): List<ApiSource>? = sourcesMemory

    fun putSources(list: List<ApiSource>) {
        sourcesMemory = list
        ioScope.launch {
            runCatching {
                File(dir, "sources.json").writeText(json.encodeToString<List<ApiSource>>(list))
            }
        }
    }

    /** Load every disk entry into memory (cold start). Safe to call twice. */
    suspend fun loadFromDisk() = withContext(Dispatchers.IO) {
        runCatching {
            val sourcesFile = File(dir, "sources.json")
            if (sourcesFile.exists()) {
                sourcesMemory = runCatching {
                    json.decodeFromString<List<ApiSource>>(sourcesFile.readText())
                }.getOrNull()
            }
            dir.listFiles()?.forEach { file ->
                if (!file.name.endsWith(".json") || file.name == "sources.json") return@forEach
                val items = runCatching {
                    json.decodeFromString<List<ApiItem>>(file.readText())
                }.getOrNull()
                if (items != null) memory[rawKey(file.name)] = items
            }
        }
    }
}
