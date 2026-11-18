package com.streamhub.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the StreamHub Flask API (BUILD-CONTRACT.md v1.0).
 *
 * - Base URL is the build-time constant [DEFAULT_BASE_URL] ONLY. There is
 *   no in-app setting and no override: the API stays behind the scenes,
 *   and a URL change ships with a new build.
 * - Never throws: every call catches its own failures and returns an empty
 *   result, so the UI always starts from an empty list, never a crash.
 * - EXCLUDED sources (pkdramas, animedekho, rareanimes) are filtered out
 *   client-side from every response, even if the server ever returns them.
 * - Streams are sorted best quality first; `proxy` and `headers` are kept
 *   intact — callers (Repository/player) decide how to play them.
 */
class ApiClient(
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
     * The base URL is the build-time constant, always. Kept as a function
     * (instead of inlining the constant) so callers need no change.
     */
    fun currentBaseUrl(): String = DEFAULT_BASE_URL

    /**
     * Proxied playback/download URL for a raw stream URL.
     * `proxy:true` streams must ALWAYS be played via this URL, never raw.
     *
     * v1.0.3 — THE #1 PLAYBACK BUG: the old version built
     * `/api/proxy?url=<raw>` and DROPPED the stream's Referer/User-Agent,
     * so segments hit hotlink-protected CDNs with no Referer → 403 →
     * proxy 502 → ExoPlayer error on every title. The proxy accepts
     * `&referer=` + `&ua=` (BUILD-CONTRACT.md); pass the stream's
     * `headers` here and they are forwarded (URL-encoded) to the CDN.
     * Headers are looked up case-insensitively ("Referer"/"referer",
     * "User-Agent"/"user-agent"). No headers → plain `?url=` as before.
     */
    fun proxyUrl(rawUrl: String, headers: Map<String, String>? = null): String {
        // v1.0.5 — idempotent: the API already returns proxy:true stream
        // URLs pre-wrapped (see api_streams/api_streams_all), so wrap the
        // UNWRAPPED raw URL, never a proxy URL again. (The server's
        // _proxy_url_for does the same unwrap; both sides stay safe
        // against double-wrapping.)
        val raw = unwrapProxyUrl(rawUrl)
        val base = "$DEFAULT_BASE_URL/api/proxy?url=${URLEncoder.encode(raw, "UTF-8")}"
        if (headers.isNullOrEmpty()) return base
        val referer = headerValue(headers, "Referer")
        val ua = headerValue(headers, "User-Agent")
        val extra = StringBuilder()
        if (!referer.isNullOrBlank()) {
            extra.append("&referer=").append(URLEncoder.encode(referer, "UTF-8"))
        }
        if (!ua.isNullOrBlank()) {
            extra.append("&ua=").append(URLEncoder.encode(ua, "UTF-8"))
        }
        return base + extra.toString()
    }

    /** Case-insensitive header lookup (servers return either casing). */
    fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * Poster/artwork URL, routed through `/api/proxy` (same fast origin as
     * the API). Direct poster hosts (TMDB, MetaHub, Aoneroom, …) are slow
     * or flaky from the phone — Coil's default 10s timeouts then fail and
     * every card falls back to the title card. The proxy fetches from the
     * server (fast: TMDB ~0.4s, MetaHub ~0.5s from the server) and serves
     * the bytes over the one connection the phone already uses, so posters
     * load reliably. Verified live: proxy returns the image bytes with
     * `Content-Type: image/jpeg`. Blank / non-http(s) URLs stay null;
     * an already-proxied URL is returned untouched (never double-wrapped).
     */
    fun posterUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isBlank() || !url.startsWith("http://") && !url.startsWith("https://")) return null
        if ("/api/proxy?url=" in url) return url
        return proxyUrl(url)
    }

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
     * Movies list. [section] is the API's `section` parameter
     * ("trending" | "popular" | "latest"; anything else means "latest").
     * There is no hollywood/bollywood section here — those are categories
     * on /api/latest (see [Repository]); do not pass them as [section].
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
                    "section" to section,
                ),
            )
        }.getOrNull()?.let(::filterExcluded) ?: ApiListResponse()

    /**
     * Browse / Discover list (v1.0.2): /api/latest with the semantic
     * browse filters — type=anime|series|movie (source-based, NOT the
     * unreliable per-item type), country (USA, Germany, Korea, Japan,
     * China, India, …), year_from/year_to. All params are optional;
     * omitting them is a plain /api/latest call.
     */
    suspend fun browse(
        type: String? = null,
        country: String? = null,
        yearFrom: Int? = null,
        yearTo: Int? = null,
        lang: String = "all",
        page: Int = 1,
    ): ApiListResponse =
        runCatching {
            getJson<ApiListResponse>(
                "/api/latest",
                mapOf(
                    "lang" to lang,
                    "source" to "all",
                    "page" to page.toString(),
                    "type" to type,
                    "country" to country,
                    "year_from" to yearFrom?.toString(),
                    "year_to" to yearTo?.toString(),
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
     *
     * v1.0.3: `fresh=1` bypasses the server's 10-min streams cache —
     * signed stream URLs expire in ~1 min, so a cached URL is usually
     * dead by play time. `lang` asks the server for that audio language
     * when it can serve it (accepted by the API; the app still falls back
     * to re-searching the title in that language — see
     * [Repository.findSameEpisode]).
     */
    suspend fun streams(
        source: String,
        data: String,
        fresh: Boolean = true,
        lang: String? = null,
    ): ApiStreamsResponse {
        if (source in EXCLUDED_SOURCES) return ApiStreamsResponse()
        val response = runCatching {
            getJson<ApiStreamsResponse>(
                "/api/streams",
                mapOf(
                    "source" to source,
                    "data" to data,
                    "fresh" to if (fresh) "1" else null,
                    "lang" to lang,
                ),
            )
        }.getOrNull() ?: return ApiStreamsResponse()
        return response.copy(streams = sortStreams(response.streams))
    }

    /**
     * Aggregated streams for one title across ALL sources (v1.0.3).
     * GET /api/streams_all?title=&type=&source=&data=&fresh=1 — deduped by
     * host, working-CDN-first, with `fast_start`. Returns an empty
     * response when the endpoint is unavailable (404) or fails; callers
     * fall back to [streams]. Never throws.
     */
    suspend fun streamsAll(
        title: String,
        type: String? = null,
        source: String? = null,
        data: String? = null,
        fresh: Boolean = true,
    ): ApiStreamsAllResponse {
        if (title.isBlank()) return ApiStreamsAllResponse()
        val response = runCatching {
            getJson<ApiStreamsAllResponse>(
                "/api/streams_all",
                mapOf(
                    "title" to title,
                    "type" to type,
                    "source" to source,
                    "data" to data,
                    "fresh" to if (fresh) "1" else null,
                ),
            )
        }.getOrNull() ?: return ApiStreamsAllResponse()
        return response.copy(streams = sortStreams(response.streams))
    }

    /** Best quality first; null/unknown quality sorts last, stable otherwise. */
    fun sortStreams(streams: List<ApiStream>): List<ApiStream> =
        streams.sortedWith(compareByDescending<ApiStream> { it.quality ?: -1 })
}

/**
 * Raw URL behind a (possibly multiply-wrapped) `/api/proxy?url=`
 * URL (v1.0.5): the API returns proxy:true stream URLs already
 * pre-wrapped, and [ApiClient.proxyUrl] must never wrap a proxy URL
 * again. Returns the URL itself when it is not a proxy URL. Top-level
 * and pure JVM (manual query parsing) so unit tests and the
 * Repository's top-level helpers can call it without Robolectric.
 */
fun unwrapProxyUrl(url: String): String {
    var current = url.trim()
    repeat(3) {
        if ("/api/proxy?url=" !in current) return current
        val query = current.substringAfter("?", "")
        val inner = query.split("&")
            .firstOrNull { it.startsWith("url=") }
            ?.removePrefix("url=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: return current
        current = inner
    }
    return current
}
