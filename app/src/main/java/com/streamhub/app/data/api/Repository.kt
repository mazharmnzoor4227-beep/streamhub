package com.streamhub.app.data.api

import com.streamhub.app.data.extensions.ExtensionStore
import com.streamhub.app.data.extensions.ExtensionsCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

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

/**
 * Aggregates [ApiClient] list/detail/stream calls into what the screens need.
 * Extension rules (BUILD-CONTRACT.md):
 * - Queries use `source=all` (server fans out); results are then filtered to
 *   the locally enabled extension ids from [ExtensionStore].
 * - Excluded sources are already stripped by [ApiClient]; this is a second
 *   line of defence, not a replacement.
 * - Never throws: a failed section is dropped, never crashes home.
 */
class Repository(
    private val api: ApiClient,
    private val extensions: ExtensionStore,
) {

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
     * Home rails, in display order. Each rail is fetched independently so one
     * failing endpoint never takes down the rest. Only non-empty rails return.
     */
    suspend fun homeSections(lang: String = "all"): List<HomeSection> = coroutineScope {
        val enabled = enabledIds()
        if (enabled.isEmpty()) return@coroutineScope emptyList()

        val trending = async {
            runCatching { api.trending(lang) }.getOrNull()?.results.orEmpty()
        }
        val hindiAnime = async {
            // type=series is filtered client-side (latest() has no type param).
            runCatching { api.latest(lang = "hindi", page = 1) }.getOrNull()
                ?.results.orEmpty()
                .filter { it.type.equals("series", ignoreCase = true) }
        }
        val hindiMovies = async {
            runCatching { api.movies(lang = "hindi", page = 1) }.getOrNull()
                ?.results.orEmpty()
                .filter { it.type.isNullOrBlank() || it.type.equals("movie", ignoreCase = true) }
        }
        val kdrama = async {
            runCatching { api.latest(lang = lang, page = 1, category = "kdrama") }
                .getOrNull()?.results.orEmpty()
        }
        val cdrama = async {
            runCatching { api.latest(lang = lang, page = 1, category = "cdrama") }
                .getOrNull()?.results.orEmpty()
        }
        val hollywood = async {
            runCatching { api.movies(lang = lang, page = 1, section = "hollywood") }
                .getOrNull()?.results.orEmpty()
        }
        val bollywood = async {
            runCatching { api.movies(lang = lang, page = 1, section = "bollywood") }
                .getOrNull()?.results.orEmpty()
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

    /** Search across enabled sources only. Empty query returns no results. */
    suspend fun search(query: String, lang: String = "all"): List<ApiItem> {
        if (query.isBlank()) return emptyList()
        val enabled = enabledIds()
        val response = runCatching { api.search(q = query, lang = lang) }.getOrNull()
            ?: return emptyList()
        return response.results.onlyEnabled(enabled)
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
