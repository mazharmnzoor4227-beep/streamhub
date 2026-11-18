package com.streamhub.app.data.api

import com.streamhub.app.data.extensions.ExtensionStore
import com.streamhub.app.data.extensions.ExtensionsCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One home rail: a title plus the real items the API returned for it.
 * Only rails with non-empty items are ever emitted (no demo/empty rails).
 */
data class HomeSection(
    val title: String,
    val items: List<ApiItem>,
)

/** Points at the same episode in another audio language (see [Repository.findSameEpisode]). */
data class SameEpisode(
    val source: String,
    val data: String,
    val title: String?,
)

/** One browse category (see [Repository.categoryItems]). */
data class CategoryDef(
    val id: String,
    val title: String,
)

/**
 * Real browse categories (v1.0.2). Every entry is verified live against the
 * API — a category only exists here if it returns real items:
 * - Anime / Hindi Dubbed: client-side type/lang filter over /api/latest.
 * - Movies / Hollywood / Bollywood / K-Drama / C-Drama: the API's own
 *   `category` parameter on /api/latest (moviebox serves the movie ones,
 *   dramacool + ckdub the dramas). Tamil/Telugu are NOT listed: their only
 *   source is animedekho (brand must never appear). Donghua has no source
 *   at all. Pakistani is excluded by user order.
 */
val APP_CATEGORIES: List<CategoryDef> = listOf(
    CategoryDef("anime", "Anime"),
    CategoryDef("movies", "Movies"),
    CategoryDef("hollywood", "Hollywood"),
    CategoryDef("bollywood", "Bollywood"),
    CategoryDef("kdrama", "K-Drama"),
    CategoryDef("cdrama", "C-Drama"),
    CategoryDef("hindi", "Hindi Dubbed"),
)

/**
 * Aggregates [ApiClient] list/detail/stream calls into what the screens need.
 * Extension rules (BUILD-CONTRACT.md):
 * - Queries use `source=all` (server fans out); results are then filtered to
 *   the locally enabled extension ids from [ExtensionStore].
 * - Excluded sources are already stripped by [ApiClient]; this is a second
 *   line of defence, not a replacement.
 * - Never throws: a failed section is dropped, never crashes home.
 *
 * CACHING (v1.0.2): every list goes through [FeedCache] (memory + disk).
 * Screens show the cached list instantly and refresh silently in the
 * background — no full-screen spinner when cached data exists.
 * [cachedHomeSections] reads cache only (no network);
 * [refreshHomeSections] hits the network and updates the cache.
 */
class Repository(
    private val api: ApiClient,
    private val extensions: ExtensionStore,
    private val feedCache: FeedCache? = null,
) {

    private val cacheLoadMutex = Mutex()
    private var cacheLoaded = false

    /** Load the disk cache into memory once (cold start). */
    suspend fun ensureCacheLoaded() {
        val cache = feedCache ?: return
        if (cacheLoaded) return
        cacheLoadMutex.withLock {
            if (!cacheLoaded) {
                cache.loadFromDisk()
                cacheLoaded = true
            }
        }
    }

    /** Pre-warm on app start: disk cache + a silent home refresh. */
    suspend fun warmCache() {
        ensureCacheLoaded()
        runCatching { refreshHomeSections("all") }
        runCatching { refreshTrending() }
    }

    private suspend fun enabledIds(): Set<String> =
        runCatching { extensions.enabledIds.first() }.getOrDefault(emptySet())

    /** Adult source ids — never in home/search, even if enabled in the adult section. */
    private val adultIds: Set<String> = ExtensionsCatalog.adult.map { it.id }.toSet()

    private fun List<ApiItem>.onlyEnabled(enabled: Set<String>): List<ApiItem> =
        filter { item ->
            // Items without a source tag cannot be attributed; keep them only
            // when the caller did not restrict by source (defensive: the API
            // always tags items, so this path should not occur in practice).
            val source = item.source
            source == null || (source in enabled && source !in adultIds)
        }

    /**
     * One rail fetch: cached mode returns the cached list (or empty — never
     * the network); refresh mode calls the network and stores the result.
     */
    private suspend fun fetchRail(
        key: String,
        cached: Boolean,
        call: suspend () -> List<ApiItem>,
    ): List<ApiItem> {
        if (cached) return feedCache?.get(key).orEmpty()
        val items = runCatching { call() }.getOrNull().orEmpty()
        if (items.isNotEmpty()) feedCache?.put(key, items)
        return items
    }

    /**
     * Home rails, in display order. Each rail is fetched independently so one
     * failing endpoint never takes down the rest. Only non-empty rails return.
     *
     * Hollywood/Bollywood use the API's real `category` parameter on
     * /api/latest (moviebox serves them: 18/20 items). The old code called
     * /api/movies with a `category` param the API ignores, so both rails
     * showed the same "latest movies" list.
     */
    suspend fun homeSections(lang: String = "all", cached: Boolean = false): List<HomeSection> =
        coroutineScope {
            val enabled = enabledIds()
            if (enabled.isEmpty()) return@coroutineScope emptyList()

            val trending = async {
                fetchRail("trending:$lang", cached) {
                    api.trending(lang).results
                }
            }
            val hindiAnime = async {
                // type=series is filtered client-side (latest() has no type param).
                fetchRail("latest:hindi", cached) {
                    api.latest(lang = "hindi", page = 1).results
                }.filter { it.type.equals("series", ignoreCase = true) }
            }
            val hindiMovies = async {
                fetchRail("movies:hindi", cached) {
                    api.movies(lang = "hindi", page = 1).results
                }.filter { it.type.isNullOrBlank() || it.type.equals("movie", ignoreCase = true) }
            }
            val kdrama = async {
                fetchRail("latest:all:kdrama", cached) {
                    api.latest(lang = lang, page = 1, category = "kdrama").results
                }
            }
            val cdrama = async {
                fetchRail("latest:all:cdrama", cached) {
                    api.latest(lang = lang, page = 1, category = "cdrama").results
                }
            }
            val hollywood = async {
                fetchRail("latest:all:hollywood", cached) {
                    api.latest(lang = lang, page = 1, category = "hollywood").results
                }
            }
            val bollywood = async {
                fetchRail("latest:all:bollywood", cached) {
                    api.latest(lang = lang, page = 1, category = "bollywood").results
                }
            }

            listOf(
                "Trending Now" to trending.await(),
                "Hindi Dubbed Anime" to hindiAnime.await(),
                "Hindi Dubbed Movies" to hindiMovies.await(),
                "K-Drama" to kdrama.await(),
                "C-Drama" to cdrama.await(),
                "Hollywood" to hollywood.await(),
                "Bollywood" to bollywood.await(),
            ).mapNotNull { (title, items) ->
                val visible = items.onlyEnabled(enabled)
                if (visible.isEmpty()) null else HomeSection(title, visible)
            }
        }

    /** Home rails from cache only — instant, no network, may be empty. */
    suspend fun cachedHomeSections(lang: String = "all"): List<HomeSection> {
        ensureCacheLoaded()
        return homeSections(lang, cached = true)
    }

    /** Home rails from the network (cache updated). */
    suspend fun refreshHomeSections(lang: String = "all"): List<HomeSection> =
        homeSections(lang, cached = false)

    /** Trending list from cache only (Search "Popular"), may be empty. */
    fun cachedTrending(): List<ApiItem> =
        feedCache?.get("trending:all").orEmpty()

    /** Trending list from the network (cache updated). */
    suspend fun refreshTrending(): List<ApiItem> {
        val items = runCatching { api.trending(lang = "all") }.getOrNull()?.results.orEmpty()
        if (items.isNotEmpty()) feedCache?.put("trending:all", items)
        return items
    }

    /**
     * One category's items. `cached=true` reads the cache only.
     * Loaders (all verified live):
     * - anime: /api/latest filtered to series/anime client-side.
     * - movies/hollywood/bollywood/kdrama/cdrama: /api/latest?category=<id>.
     * - hindi: /api/latest?lang=hindi.
     */
    suspend fun categoryItems(categoryId: String, cached: Boolean = false): List<ApiItem> {
        val enabled = enabledIds()
        if (enabled.isEmpty()) return emptyList()
        val key = "category:$categoryId"
        val items = when (categoryId) {
            "anime" -> fetchRail(key, cached) {
                api.latest(lang = "all", page = 1).results
            }.filter {
                it.type.equals("series", ignoreCase = true) ||
                    it.type.equals("anime", ignoreCase = true)
            }
            "hindi" -> fetchRail(key, cached) {
                api.latest(lang = "hindi", page = 1).results
            }
            "movies", "hollywood", "bollywood", "kdrama", "cdrama" -> fetchRail(key, cached) {
                api.latest(lang = "all", page = 1, category = categoryId).results
            }
            else -> return emptyList()
        }
        return items.onlyEnabled(enabled)
    }

    /** Category items from cache only — instant, may be empty. */
    suspend fun cachedCategoryItems(categoryId: String): List<ApiItem> {
        ensureCacheLoaded()
        return categoryItems(categoryId, cached = true)
    }

    /** Category items from the network (cache updated). */
    suspend fun refreshCategoryItems(categoryId: String): List<ApiItem> =
        categoryItems(categoryId, cached = false)

    /** Extension sources from cache only (Extensions screen), may be empty. */
    fun cachedSources(): List<ApiSource> =
        feedCache?.getSources().orEmpty()

    /** Extension sources from the network (cache updated). */
    suspend fun refreshSources(): List<ApiSource> {
        val list = runCatching { api.sources() }.getOrDefault(emptyList())
        if (list.isNotEmpty()) feedCache?.putSources(list)
        return list
    }

    /**
     * Adult browse list across the ENABLED adult extensions (key-gated
     * section only). The backends are search-only (no latest/trending), so
     * browse = a broad search ("a" returns ~30 titles per source). Cached
     * per source, so the section opens instantly after the first load.
     * Adult items are never filtered by [onlyEnabled] — the caller passes
     * exactly the adult ids to include.
     */
    suspend fun adultBrowse(adultIdsToInclude: Set<String>, cached: Boolean = false): List<ApiItem> =
        coroutineScope {
            if (adultIdsToInclude.isEmpty()) return@coroutineScope emptyList()
            val jobs = adultIdsToInclude.map { sourceId ->
                async {
                    fetchRail("adult:browse:$sourceId", cached) {
                        api.search(q = "a", lang = "all", source = sourceId).results
                    }
                }
            }
            val merged = jobs.flatMap { it.await() }
            // Dedupe by (source, id); keep first occurrence.
            val seen = mutableSetOf<String>()
            merged.filter { item -> seen.add((item.source ?: "") + "|" + item.id) }
        }

    /** Adult browse from cache only — instant, may be empty. */
    suspend fun cachedAdultBrowse(adultIdsToInclude: Set<String>): List<ApiItem> {
        ensureCacheLoaded()
        return adultBrowse(adultIdsToInclude, cached = true)
    }

    /** Adult browse from the network (cache updated). */
    suspend fun refreshAdultBrowse(adultIdsToInclude: Set<String>): List<ApiItem> =
        adultBrowse(adultIdsToInclude, cached = false)

    /** Search across enabled sources only. Empty query returns no results. */
    suspend fun search(query: String, lang: String = "all"): List<ApiItem> {
        if (query.isBlank()) return emptyList()
        val enabled = enabledIds()
        val response = runCatching { api.search(q = query, lang = lang) }.getOrNull()
            ?: return emptyList()
        return response.results.onlyEnabled(enabled)
    }

    /** Search inside the adult section only (explicit adult source ids). */
    suspend fun adultSearch(query: String, adultIdsToInclude: Set<String>): List<ApiItem> {
        if (query.isBlank() || adultIdsToInclude.isEmpty()) return emptyList()
        val found = coroutineScope {
            adultIdsToInclude.map { sourceId ->
                async {
                    runCatching {
                        api.search(q = query, lang = "all", source = sourceId)
                    }.getOrNull()?.results.orEmpty()
                }
            }.flatMap { it.await() }
        }
        val seen = mutableSetOf<String>()
        return found.filter { item -> seen.add((item.source ?: "") + "|" + item.id) }
    }

    suspend fun details(source: String, id: String): ApiDetails? =
        runCatching { api.details(source, id) }.getOrNull()

    /**
     * Playable streams for one episode.
     * - `type == "embed"` is filtered out entirely (cannot play in ExoPlayer).
     * - `proxy == true` stream URLs are rewritten via [ApiClient.proxyUrl];
     *   direct streams keep their raw URL plus their `headers`.
     * - Best quality first (already sorted by [ApiClient]; re-sorted here
     *   after filtering so the order stays correct).
     */
    suspend fun streams(source: String, data: String): ApiStreamsResponse {
        // Refresh the cached base URL so proxyUrl() uses the current setting.
        runCatching { api.currentBaseUrl() }
        val response = runCatching { api.streams(source, data) }.getOrNull()
            ?: return ApiStreamsResponse()
        val playable = response.streams
            .filter { !it.type.equals("embed", ignoreCase = true) }
            .map { stream ->
                if (stream.proxy) stream.copy(url = api.proxyUrl(stream.url)) else stream
            }
        return response.copy(
            streams = api.sortStreams(playable),
        )
    }

    /**
     * Audio switching (BUILD-CONTRACT.md LANGUAGE rule): there is no
     * in-stream multi-audio track, so re-search the same title in
     * [targetLang], load details, and match [episodeNumber].
     * Returns the matching episode's opaque `data` plus its source, or null
     * when no same-episode match exists in that language.
     */
    suspend fun findSameEpisode(
        title: String,
        episodeNumber: Int,
        targetLang: String,
    ): SameEpisode? {
        if (title.isBlank()) return null
        val enabled = enabledIds()
        val results = runCatching { api.search(q = title, lang = targetLang) }
            .getOrNull()?.results.orEmpty()
            .onlyEnabled(enabled)
            .take(8)
        for (item in results) {
            val source = item.source ?: continue
            val details = runCatching { api.details(source, item.id) }.getOrNull()
                ?: continue
            for (season in details.seasons.orEmpty()) {
                for (episode in season.episodes.orEmpty()) {
                    if (episode.number == episodeNumber && !episode.data.isNullOrBlank()) {
                        return SameEpisode(
                            source = source,
                            data = episode.data,
                            title = episode.title,
                        )
                    }
                }
            }
        }
        return null
    }
}
