package com.streamhub.app.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Shared API models (BUILD-CONTRACT.md v1.0). Every field the server may omit
 * is nullable or has a default, and decoding ignores unknown keys, so a
 * single unexpected/missing field never breaks a whole response.
 */

@Serializable
data class ApiSource(
    val id: String = "",
    val name: String = "",
    val language: String? = null,
)

@Serializable
data class ApiItem(
    val id: String = "",
    val title: String = "",
    val poster: String? = null,
    val year: Int? = null,
    val type: String? = null,
    val audio: List<String>? = null,
    val source: String? = null,
)

@Serializable
data class ApiListResponse(
    val results: List<ApiItem> = emptyList(),
    // Shape of individual error entries is not guaranteed by the API
    // (strings or objects), so keep them as raw JSON.
    val errors: List<JsonElement>? = null,
)

@Serializable
data class ApiSeason(
    val number: Int = 0,
    val name: String? = null,
    val episodes: List<ApiEpisode>? = null,
)

@Serializable
data class ApiEpisode(
    val number: Int = 0,
    val title: String? = null,
    // Opaque: pass verbatim (URL-encoded) to /api/streams.
    val data: String? = null,
)

@Serializable
data class ApiDetails(
    val id: String = "",
    val title: String = "",
    val poster: String? = null,
    val banner: String? = null,
    val plot: String? = null,
    val genres: List<String>? = null,
    val year: Int? = null,
    val status: String? = null,
    val score: Double? = null,
    val type: String? = null,
    val audio: List<String>? = null,
    val seasons: List<ApiSeason>? = null,
)

@Serializable
data class ApiStream(
    val name: String = "",
    val url: String = "",
    val quality: Int? = null,
    val type: String? = null,
    val headers: Map<String, String>? = null,
    val proxy: Boolean = false,
    val label: String? = null,
)

@Serializable
data class ApiSubtitle(
    val label: String = "",
    val url: String = "",
)

@Serializable
data class ApiStreamsResponse(
    val streams: List<ApiStream> = emptyList(),
    val subtitles: List<ApiSubtitle>? = null,
)
