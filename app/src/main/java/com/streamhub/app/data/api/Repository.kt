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

/** Browse/Discover filter (see [Repository.browseFiltered]). */
data class BrowseFilter(
    /** "anime" | "movie" | "series" | null (all types). */
    val type: String? = null,
    /** Country display name ("USA", "Germany", "Korea", "Japan", "China", "India") or null. */
    val country: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
) {
    /** True when no filter is set at all (plain latest). */
    fun isEmpty(): Boolean =
        type == null && country == null && yearFrom == null && yearTo == null

    /** Cache key — letters, digits and ':' only (FeedCache filenames). */
    fun cacheKey(): String =
        "browse:${type ?: "all"}:${country ?: "all"}:${yearFrom ?: 0}:${yearTo ?: 0}"
}

/** Countries the Browse screen offers (each verified live to return content). */
val BROWSE_COUNTRIES: List<String> = listOf(
    "USA", "Germany", "Korea", "Japan", "China", "India",
)

/** One category's API request, in pure form (unit-testable). */
data class CategoryRequest(
    val type: String? = null,
    val category: String? = null,
    val lang: String? = null,
)

/**
 * Maps a category id to its API request (v1.0.3). Every entry is verified
 * live against /api/latest — a category only exists here if it returns
 * real items of that category:
 * - anime: ?type=anime (all Japan anime, 158 items p1).
 * - movies: ?type=movie (all movies + anime movies).
 * - series: ?type=series (drama/series sources; the server ALSO returns
 *   the excluded pkdramas/rareanimes here, which the client strips —
 *   reported to the API track 2026-11-18).
 * - hollywood/bollywood/kdrama/cdrama: ?category=<id> (all correct
 *   country/type, verified live).
 * - hindi: ?lang=hindi.
 * - donghua: ?category=donghua (40 items p1, all China — Link Click,
 *   To Be Hero X; verified live 2026-11-18 after the API track fixed it).
 */
fun categoryRequest(categoryId: String): CategoryRequest? = when (categoryId) {
    "anime" -> CategoryRequest(type = "anime")
    "movies" -> CategoryRequest(type = "movie")
    "series" -> CategoryRequest(type = "series")
    "hindi" -> CategoryRequest(lang = "hindi")
    "hollywood", "bollywood", "kdrama", "cdrama", "donghua" -> CategoryRequest(category = categoryId)
    else -> null
}

/** Host of a stream URL (for mirror dedupe); falls back to the raw URL. */
fun streamHost(url: String): String =
    runCatching { java.net.URI(url).host?.lowercase().orEmpty().ifBlank { url } }.getOrDefault(url)

/** Dedupe mirrors by CDN host, keeping the first (server orders working CDNs first). */
fun dedupeStreamsByHost(streams: List<ApiStream>): List<ApiStream> {
    val seen = mutableSetOf<String>()
    return streams.filter { stream -> seen.add(streamHost(stream.url)) }
}

/**
 * Move the server's `fast_start` stream (a URL or a CDN host) to the
 * front; anything else keeps its order. No-op when it matches nothing.
 */
fun applyFastStart(streams: List<ApiStream>, fastStart: String?): List<ApiStream> {
    if (fastStart.isNullOrBlank() || streams.isEmpty()) return streams
    val target = fastStart.trim()
    val idx = streams.indexOfFirst { stream ->
        stream.url == target ||
            streamHost(stream.url) == streamHost(target) ||
            streamHost(stream.url).equals(target, ignoreCase = true)
    }
    if (idx <= 0) return streams
    return listOf(streams[idx]) + streams.filterIndexed { i, _ -> i != idx }
}

/**
 * Real browse categories (v1.0.3). Every entry is verified live against the
 * API — a category only exists here if it returns real items:
 * - Anime: /api/latest?type=anime (semantic: anime sources only).
 * - Movies: /api/latest?type=movie (all movie sources + anime movies).
 * - Series: /api/latest?type=series (drama/series sources).
 * - Hollywood / Bollywood / K-Drama / C-Drama: the API's own `category`
 *   parameter on /api/latest (moviebox serves the movie ones, dramacool +
 *   ckdub the dramas; reanime serves donghua). Tamil/Telugu are NOT listed: their only source is
 *   animedekho (brand must never appear). Pakistani is excluded by user order.
 */
val APP_CATEGORIES: List<CategoryDef> = listOf(
    CategoryDef("anime", "Anime"),
    CategoryDef("movies", "Movies"),
    CategoryDef("series", "Series"),
    CategoryDef("hollywood", "Hollywood"),
    CategoryDef("bollywood", "Bollywood"),
    CategoryDef("kdrama", "K-Drama"),
    CategoryDef("cdrama", "C-Drama"),
    CategoryDef("donghua", "Donghua"),
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
     * Browse/Discover results for a [BrowseFilter] (v1.0.2). Pages 1–3 are
     * fetched and merged (deduped by source+id): a single page is NOT
     * enough — e.g. Germany returns 0 items on pages 1–2 of /api/latest
     * and only appears from page 3, because the movie source's latest
     * listing is ordered by recency, not by country. Cached per filter
     * (FeedCache), so revisiting a filter shows instantly.
     */
    suspend fun browseFiltered(filter: BrowseFilter, cached: Boolean = false): List<ApiItem> {
        val enabled = enabledIds()
        if (enabled.isEmpty()) return emptyList()
        val key = filter.cacheKey()
        if (cached) return feedCache?.get(key).orEmpty().onlyEnabled(enabled)
        val merged = coroutineScope {
            (1..3).map { page ->
                async {
                    runCatching {
                        api.browse(
                            type = filter.type,
                            country = filter.country,
                            yearFrom = filter.yearFrom,
                            yearTo = filter.yearTo,
                            page = page,
                        ).results
                    }.getOrDefault(emptyList())
                }
            }.flatMap { it.await() }
        }
        val seen = mutableSetOf<String>()
        val items = merged
            .filter { item -> seen.add((item.source ?: "") + "|" + item.id) }
            .onlyEnabled(enabled)
        if (items.isNotEmpty()) feedCache?.put(key, items)
        return items
    }

    /** Browse results from cache only — instant, may be empty. */
    suspend fun cachedBrowseFiltered(filter: BrowseFilter): List<ApiItem> {
        ensureCacheLoaded()
        return browseFiltered(filter, cached = true)
    }

    /** Browse results from the network (cache updated). */
    suspend fun refreshBrowseFiltered(filter: BrowseFilter): List<ApiItem> =
        browseFiltered(filter, cached = false)

    /**
     * One category's items. `cached=true` reads the cache only.
     * Pages 1–3 are fetched and merged (deduped by source+id): a single
     * page is NOT the whole category (Anime returns ~158 items on page 1
     * alone, and more follow). Loaders use [categoryRequest] — every
     * mapping verified live (see its KDoc).
     */
    suspend fun categoryItems(categoryId: String, cached: Boolean = false): List<ApiItem> {
        val enabled = enabledIds()
        if (enabled.isEmpty()) return emptyList()
        val request = categoryRequest(categoryId) ?: return emptyList()
        val key = "category:$categoryId"
        if (cached) return feedCache?.get(key).orEmpty().onlyEnabled(enabled)
        val merged = coroutineScope {
            (1..3).map { page ->
                async {
                    runCatching {
                        when {
                            request.type != null ->
                                api.browse(type = request.type, page = page).results
                            request.category != null ->
                                api.latest(lang = "all", page = page, category = request.category).results
                            request.lang != null ->
                                api.latest(lang = request.lang, page = page).results
                            else -> emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
            }.flatMap { it.await() }
        }
        val seen = mutableSetOf<String>()
        val items = merged
            .filter { item -> seen.add((item.source ?: "") + "|" + item.id) }
            .onlyEnabled(enabled)
        if (items.isNotEmpty()) feedCache?.put(key, items)
        return items
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
     * browse = a broad search ("a" returns ~30 titles per source).
     * [page] paginates that search (v1.0.3: "Load more" in the Lock page);
     * page 1 is cached per source, so the section opens instantly after
     * the first load. Adult items are never filtered by [onlyEnabled] —
     * the caller passes exactly the adult ids to include.
     */
    suspend fun adultBrowse(
        adultIdsToInclude: Set<String>,
        cached: Boolean = false,
        page: Int = 1,
    ): List<ApiItem> =
        coroutineScope {
            if (adultIdsToInclude.isEmpty()) return@coroutineScope emptyList()
            val jobs = adultIdsToInclude.map { sourceId ->
                async {
                    if (page == 1) {
                        fetchRail("adult:browse:$sourceId", cached) {
                            api.search(q = "a", lang = "all", source = sourceId, page = 1).results
                        }
                    } else {
                        runCatching {
                            api.search(q = "a", lang = "all", source = sourceId, page = page).results
                        }.getOrDefault(emptyList())
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

    /** One more adult-browse page (v1.0.3 "Load more"); empty when exhausted. */
    suspend fun adultBrowsePage(adultIdsToInclude: Set<String>, page: Int): List<ApiItem> =
        adultBrowse(adultIdsToInclude, cached = false, page = page)

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
     * - `proxy == true` stream URLs are rewritten via [ApiClient.proxyUrl]
     *   WITH the stream's headers (v1.0.3: Referer/User-Agent forwarded —
     *   dropping them was the #1 playback bug, 403 on every segment).
     *   Direct streams keep their raw URL plus their `headers`.
     * - `fresh=1` bypasses the server streams cache (signed URLs expire
     *   in ~1 min). `lang` asks the server for that audio language.
     * - Best quality first (already sorted by [ApiClient]; re-sorted here
     *   after filtering so the order stays correct).
     */
    suspend fun streams(
        source: String,
        data: String,
        lang: String? = null,
    ): ApiStreamsResponse {
        val response = runCatching { api.streams(source, data, fresh = true, lang = lang) }.getOrNull()
            ?: return ApiStreamsResponse()
        return response.copy(streams = playableStreams(response.streams))
    }

    /** Filter + proxy-wrap + host-dedupe + fast_start ordering, shared by [streams] and [playStreams]. */
    private fun playableStreams(
        raw: List<ApiStream>,
        fastStart: String? = null,
    ): List<ApiStream> {
        val playable = raw
            .filter { !it.type.equals("embed", ignoreCase = true) }
            .map { stream ->
                if (stream.proxy) stream.copy(url = api.proxyUrl(stream.url, stream.headers)) else stream
            }
        return applyFastStart(dedupeStreamsByHost(api.sortStreams(playable)), fastStart)
    }

    /**
     * Playable streams for one episode, aggregated across ALL sources
     * (v1.0.3 — the play-time path the player uses).
     * 1. Try `/api/streams_all?title=&type=&source=&data=&fresh=1` — fresh
     *    streams for the same title from every source, deduped by host,
     *    working-CDN-first, `fast_start` honoured.
     * 2. Fall back to single-source [streams] when streams_all is
     *    unavailable (404) or returns nothing — the endpoint is new and
     *    the player must never depend on it existing.
     * Never throws; empty list means "no playable stream anywhere".
     */
    suspend fun playStreams(
        source: String,
        data: String,
        title: String,
        type: String? = null,
    ): ApiStreamsResponse {
        val aggregated = runCatching {
            api.streamsAll(title = title, type = type, source = source, data = data, fresh = true)
        }.getOrNull()
        if (aggregated != null && aggregated.streams.isNotEmpty()) {
            return ApiStreamsResponse(
                streams = playableStreams(aggregated.streams, aggregated.fastStart),
                subtitles = aggregated.subtitles,
                fastStart = aggregated.fastStart,
            )
        }
        return streams(source, data)
    }

    /**
     * Audio switching (BUILD-CONTRACT.md LANGUAGE rule): there is no
     * in-stream multi-audio track, so re-search the same title in
     * [targetLang], load details, and match [episodeNumber].
     * Returns the matching episode's opaque `data` plus its source, or null
     * when no same-episode match exists in that language.
     *
     * Movies ([episodeNumber] == 0): the details carry a single
     * pseudo-episode (number 1 in the MovieBox details, 0 in the app's
     * own pseudo-episode) — match the FIRST episode with data instead of
     * an exact number, since a movie has exactly one playable item.
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
            val episodes = details.seasons.orEmpty().flatMap { it.episodes.orEmpty() }
            val match = if (episodeNumber == 0) {
                episodes.firstOrNull { !it.data.isNullOrBlank() }
            } else {
                episodes.firstOrNull {
                    it.number == episodeNumber && !it.data.isNullOrBlank()
                }
            }
            if (match != null) {
                return SameEpisode(
                    source = source,
                    data = match.data!!,
                    title = match.title,
                )
            }
        }
        return null
    }
}
