package com.streamhub.app.data.extensions

/**
 * Static metadata for every extension (one extension = one API source).
 * The live list of sources still comes from `GET /api/sources`; this catalog
 * only supplies display info and the first-run default-enabled set.
 *
 * NOTE: the adult entries are listed here for the hidden folder (18+)
 * only. Their sources ARE in the API (v1.0.3, verified live 2026-11-18:
 * search + streams + proxy all work), but they must only ever be
 * queried from the hidden page — never from home/search/trending.
 * HentaiHaven is adult anime (English subtitles, no Hindi dub);
 * Pornhub / XNXX / Xvideos / RedTube / YouPorn are adult video sites.
 * Eporner stays OUT: its video CDN 403s the server IP (v1.0.3).
 */
data class ExtensionInfo(
    val id: String,
    val name: String,
    val language: String,
    val category: String,
    val description: String,
    val isAdult: Boolean = false,
    val available: Boolean = true,
)

object ExtensionsCatalog {

    val all: List<ExtensionInfo> = listOf(
        ExtensionInfo(
            id = "animeworld",
            name = "AnimeWorld",
            language = "Hindi",
            category = "Anime",
            description = "Hindi-dubbed anime series and movies.",
        ),
        ExtensionInfo(
            id = "anisuge",
            name = "AniSuge",
            language = "English",
            category = "Anime",
            description = "Anime streaming with sub and dub options.",
        ),
        ExtensionInfo(
            id = "nineanime",
            name = "9Anime",
            language = "English",
            category = "Anime",
            description = "Large anime catalogue with sub and dub.",
        ),
        ExtensionInfo(
            id = "anikoto",
            name = "AniKoto",
            language = "English",
            category = "Anime",
            description = "Anime series and movies with sub and dub.",
        ),
        ExtensionInfo(
            id = "reanime",
            name = "ReAnime",
            language = "English",
            category = "Anime",
            description = "Anime series and movies with subtitles.",
        ),
        ExtensionInfo(
            id = "xanime",
            name = "XAnime",
            language = "Hindi",
            category = "Anime",
            description = "Hindi-dubbed anime series and movies.",
        ),
        ExtensionInfo(
            id = "vegamovies",
            name = "VegaMovies",
            language = "Hindi",
            category = "Movies",
            description = "Hindi-dubbed and Bollywood movies.",
        ),
        ExtensionInfo(
            id = "multimovies",
            name = "MultiMovies",
            language = "Hindi",
            category = "Movies",
            description = "Hindi-dubbed movies and series.",
        ),
        ExtensionInfo(
            id = "indstream",
            name = "IndStream",
            language = "Hindi",
            category = "Movies",
            description = "Hindi-dubbed movies and web series.",
        ),
        ExtensionInfo(
            id = "ckdub",
            name = "CKDub",
            language = "Hindi",
            category = "Movies",
            description = "Hindi-dubbed movies and shows.",
        ),
        ExtensionInfo(
            id = "dramacool",
            name = "DramaCool",
            language = "English",
            category = "Drama",
            description = "Asian dramas with English subtitles.",
        ),
        ExtensionInfo(
            id = "moviebox",
            name = "MovieBox",
            language = "English",
            category = "Movies",
            description = "Hollywood movies, some with Hindi dub.",
        ),
        // Hidden folder only — live in the API (v1.0.3), gated by the password.
        // v1.0.4: the 5 video adult sources were in the API since v1.0.3
        // but were never registered here, so the Lock page only ever
        // showed HentaiHaven. Registering them flows them into the
        // Extensions rows, the source filter chips and the browse fan-out.
        ExtensionInfo(
            id = "hentaihaven",
            name = "HentaiHaven",
            language = "English",
            category = "Adult",
            description = "Adult anime (18+). English subtitles.",
            isAdult = true,
            available = true,
        ),
        ExtensionInfo(
            id = "pornhub",
            name = "Pornhub",
            language = "English",
            category = "Adult",
            description = "Adult videos (18+). Largest catalogue.",
            isAdult = true,
            available = true,
        ),
        ExtensionInfo(
            id = "xnxx",
            name = "XNXX",
            language = "English",
            category = "Adult",
            description = "Adult videos (18+).",
            isAdult = true,
            available = true,
        ),
        ExtensionInfo(
            id = "xvideos",
            name = "Xvideos",
            language = "English",
            category = "Adult",
            description = "Adult videos (18+).",
            isAdult = true,
            available = true,
        ),
        ExtensionInfo(
            id = "redtube",
            name = "RedTube",
            language = "English",
            category = "Adult",
            description = "Adult videos (18+).",
            isAdult = true,
            available = true,
        ),
        ExtensionInfo(
            id = "youporn",
            name = "YouPorn",
            language = "English",
            category = "Adult",
            description = "Adult videos (18+).",
            isAdult = true,
            available = true,
        ),
    )

    /** The 12 verified, working sources — enabled by default on first run. */
    val verified: List<ExtensionInfo> = all.filter { !it.isAdult && it.available }

    /** Ids of the verified sources, for the first-run default. */
    val verifiedIds: Set<String> = verified.map { it.id }.toSet()

    /** Non-adult extensions only (the main Extensions screen). */
    val standard: List<ExtensionInfo> = all.filter { !it.isAdult }

    /** 18+ extensions only (the hidden page, Settings → Lock → password). */
    val adult: List<ExtensionInfo> = all.filter { it.isAdult }

    fun byId(id: String): ExtensionInfo? = all.find { it.id == id }
}
