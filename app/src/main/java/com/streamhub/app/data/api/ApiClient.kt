package com.streamhub.app.data.api

import com.streamhub.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the StreamHub Flask API (BUILD-CONTRACT.md v1.0).
 *
 * - Base URL comes from [SettingsStore] (default: the tunnel URL) and is
 *   re-read on every call, so a Settings change applies immediately.
 * - Never throws: every call catches its own failures and returns an empty
 *   result, so the UI always starts from an empty list, never a crash.
 * - EXCLUDED sources (pkdramas, animedekho, rareanimes) are filtered out
 *   client-side from every response, even if the server ever returns them.
 * - Streams are sorted best quality first; `proxy` and `headers` are kept
 *   intact — callers (Repository/player) decide how to play them.
 */
class ApiClient(
    private val settings: SettingsStore,
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        const val DEFAULT_BASE_URL = "https://strategy-terry-masters-king.trycloudflare.com"

        /** Sources that must never be queried, listed, or displayed. */
        val EXCLUDED_SOURCES = setOf("pkdramas", "animedekho", "rareanimes")

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Last base URL read from Settings. Refreshed on every API call; used by
     * the synchronous [proxyUrl] so it never needs a suspend context.
     * Starts at the default until the first call reads Settings.
     */
    @Volatile
    private var cachedBaseUrl: String = DEFAULT_BASE_URL

    /** Reads the current base URL from Settings and refreshes the cache. */
    suspend fun currentBaseUrl(): String {
        val base = runCatching { settings.currentBaseUrl() }
            .getOrDefault(DEFAULT_BASE_URL)
            .trimEnd('/')
            .ifBlank { DEFAULT_BASE_URL }
        cachedBaseUrl = base
        return base
    }

    /**
     * Proxied playback/download URL for a raw stream URL.
     * `proxy:true` streams must ALWAYS be played via this URL, never raw.
     */
    fun proxyUrl(rawUrl: String): String =
        "$cachedBaseUrl/api/proxy?url=${URLEncoder.encode(rawUrl, "UTF-8")}"

    // ---- URL building ----

    private fun buildUrl(base: String, path: String, params: Map<String, String?>): String? {
        val httpUrl = "$base$path".toHttpUrlOrNull()?.newBuilder() ?: return null
        params.forEach { (key, value) ->
            if (!value.isNullOrBlank()) httpUrl.addQueryParameter(key, value)
        }
        return httpUrl.build().toString()
    }

    private suspend fun getBody(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend inline fun <reified T> getJson(path: String, params: Map<String, String?>): T? {
        val base = currentBaseUrl()
        val url = buildUrl(base, path, params) ?: return null
        val body = getBody(url) ?: return null
        return runCatching { json.decodeFromString<T>(body) }.getOrNull()
    }

    private fun filterExcluded(response: ApiListResponse): ApiListResponse =
        response.copy(
            results = response.results.filter { it.source !in EXCLUDED_SOURCES },
        )

    // ---- Sources ----

    /** All sources minus EXCLUDED. Empty list on any failure. */
    suspend fun sources(): List<ApiSource> {
        val base = currentBaseUrl()
        val url = buildUrl(base, "/api/sources", emptyMap()) ?: return emptyList()
        val body = getBody(url) ?: return emptyList()
        val list = runCatching { json.decodeFromString<List<ApiSource>>(body) }
            .getOrDefault(emptyList())
        return list.filter { it.id !in EXCLUDED_SOURCES }
    }

    // ---- Lists ----

    suspend fun trending(lang: String = "all", page: Int = 1): ApiListResponse =
        runCatching {
            getJson<ApiListResponse>(
                "/api/trending",
                mapOf("lang" to lang, "source" to "all", "page" to page.toString()),
            )
        }.getOrNull()?.let(::filterExcluded) ?: ApiListResponse()

    suspend fun popular(lang: String = "all", page: Int = 1): ApiListResponse =
        runCatching {
            getJson<ApiListResponse>(
                "/api/popular",
                mapOf("lang" to lang, "source" to "all", "page" to page.toString()),
            )
        }.getOrNull()?.let(::filterExcluded) ?: ApiListResponse()

    suspend fun latest(
        lang: String = "all",
        page: Int = 1,
        category: String? = null,
    ): ApiListResponse =
        runCatching {
            getJson<ApiListResponse>(
                "/api/latest",
                mapOf(
                    "lang" to lang,
                    "source" to "all",
                    "page" to page.toString(),
                    "category" to category,
                ),
            )
        }.getOrNull()?.let(::filterExcluded) ?: ApiListResponse()

    /**
     * Movies list. [section] is sent as the API's `category` parameter
     * (e.g. "hollywood", "bollywood").
     */
    suspend fun movies(
        lang: String = "all",
        source: String = "all",
        page: Int = 1,
        section: String? = null,
    ): ApiListResponse =
        runCatching {
            getJson<ApiListResponse>(
                "/api/movies",
                mapOf(
                    "lang" to lang,
                    "source" to source,
                    "page" to page.toString(),
                    "category" to section,
                ),
            )
        }.getOrNull()?.let(::filterExcluded) ?: ApiListResponse()

    suspend fun search(
        q: String,
        lang: String = "all",
        source: String = "all",
        page: Int = 1,
        type: String = "all",
    ): ApiListResponse {
        if (q.isBlank()) return ApiListResponse()
        return runCatching {
            getJson<ApiListResponse>(
                "/api/search",
                mapOf(
                    "q" to q,
                    "lang" to lang,
                    "source" to source,
                    "page" to page.toString(),
                    "type" to type,
                ),
            )
        }.getOrNull()?.let(::filterExcluded) ?: ApiListResponse()
    }

    // ---- Details & streams ----

    /** Details dict, or null on any failure. `id` is URL-encoded (often a full URL). */
    suspend fun details(source: String, id: String): ApiDetails? {
        if (source in EXCLUDED_SOURCES) return null
        return runCatching {
            getJson<ApiDetails>(
                "/api/anime",
                mapOf("source" to source, "id" to id),
            )
        }.getOrNull()
    }

    /**
     * Streams for one episode (`data` is the opaque episode data, verbatim).
     * Sorted best quality first (null quality last); `proxy`/`headers` kept
     * intact. Empty response on any failure.
     */
    suspend fun streams(source: String, data: String): ApiStreamsResponse {
        if (source in EXCLUDED_SOURCES) return ApiStreamsResponse()
        val response = runCatching {
            getJson<ApiStreamsResponse>(
                "/api/streams",
                mapOf("source" to source, "data" to data),
            )
        }.getOrNull() ?: return ApiStreamsResponse()
        return response.copy(streams = sortStreams(response.streams))
    }

    /** Best quality first; null/unknown quality sorts last, stable otherwise. */
    fun sortStreams(streams: List<ApiStream>): List<ApiStream> =
        streams.sortedWith(compareByDescending<ApiStream> { it.quality ?: -1 })
}
