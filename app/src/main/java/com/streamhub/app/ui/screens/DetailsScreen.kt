package com.streamhub.app.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.WatchlistStore
import com.streamhub.app.data.api.ApiDetails
import com.streamhub.app.data.api.ApiEpisode
import com.streamhub.app.data.api.ApiItem
import com.streamhub.app.download.DownloadHelper
import com.streamhub.app.download.HlsEstimate
import com.streamhub.app.ui.components.SectionHeading
import com.streamhub.app.ui.components.SmallTag
import com.streamhub.app.ui.player.WatchProgressStore
import com.streamhub.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Title details: banner, meta, plot, seasons + episode list.
 * Movies (no episode list from the API) play/download as a single episode
 * whose data is the title id. Downloads use [Repository.streams], which
 * already wraps `proxy:true` URLs — the proxied URL is what gets enqueued.
 */
@UnstableApi
@Composable
fun DetailsScreen(
    item: ApiItem,
    sourceId: String,
    onBack: () -> Unit,
    onPlay: (ApiDetails, ApiEpisode, String) -> Unit,
    // Hoisted by the app root: scroll position survives leaving for the
    // player and coming back.
    scrollState: ScrollState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = StreamHubApplication.repository
    var details by remember { mutableStateOf<ApiDetails?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var selectedSeason by remember { mutableStateOf(0) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    val downloadStates = remember { mutableStateMapOf<String, Int>() }
    // My List (v1.0.2): saved locally via WatchlistStore.
    var inMyList by remember { mutableStateOf(false) }

    LaunchedEffect(item.id, sourceId) {
        inMyList = WatchlistStore.isSaved(context, sourceId, item.id)
    }

    LaunchedEffect(item.id, sourceId) {
        loading = true
        loadFailed = false
        details = runCatching { repository.details(sourceId, item.id) }.getOrNull()
        loadFailed = details == null
        loading = false
        selectedSeason = 0
    }

    val seasons = details?.seasons.orEmpty()
    val currentSeason = seasons.getOrNull(selectedSeason) ?: seasons.firstOrNull()
    val episodes = currentSeason?.episodes.orEmpty()
    // Movies: no episode list — one pseudo-episode carrying the title id.
    val playEpisodes = if (episodes.isNotEmpty()) episodes else listOf(
        ApiEpisode(number = 0, title = item.title, data = item.id),
    )
    val firstEpisode = playEpisodes.firstOrNull()

    LaunchedEffect(episodes, item.id) {
        episodes.forEach { ep ->
            val id = DownloadHelper.downloadId(sourceId, item.id, ep.number)
            val s = DownloadHelper.stateOf(context, id)
            if (s != null) downloadStates[ep.number.toString()] = s
        }
    }

    // Resume prompt (v1.0.2): the furthest episode with saved progress
    // (position > 10s), with its position + duration. Recomputed whenever
    // details load — returning from the player re-runs this, so the
    // prompt always shows the latest saved position.
    val resumeEntry = remember(details, item.id, sourceId) {
        WatchProgressStore.loadAll(context)
            .filter { it.sourceId == sourceId && it.detailsId == item.id && it.positionMs > 10_000 }
            .maxByOrNull { it.episodeNumber }
    }
    val resumeEpisode = resumeEntry?.let { entry ->
        val allEps = details?.seasons.orEmpty().flatMap { it.episodes.orEmpty() }
        allEps.firstOrNull { it.number == entry.episodeNumber }
            ?: ApiEpisode(number = entry.episodeNumber, title = item.title, data = item.id)
    }

    fun downloadEpisode(ep: ApiEpisode) {
        scope.launch {
            // Already downloaded in BOTH languages? Then there is nothing to do.
            val hindiState = DownloadHelper.stateOf(
                context,
                DownloadHelper.downloadId(sourceId, item.id, ep.number, "hindi"),
            )
            val englishState = DownloadHelper.stateOf(
                context,
                DownloadHelper.downloadId(sourceId, item.id, ep.number, "english"),
            )
            if (hindiState == Download.STATE_COMPLETED && englishState == Download.STATE_COMPLETED) {
                downloadMessage = "Already downloaded — find it in Downloads."
                return@launch
            }
            val data = ep.data
            if (data.isNullOrBlank()) {
                downloadMessage = "No stream available to download."
                return@launch
            }
            downloadMessage = "Finding streams for download…"
            // ALL languages, Hindi first (always included): the same
            // episode is resolved in each language via findSameEpisode
            // (the API's lang= search); the direct stream is the fallback
            // when a language cannot be resolved. A language whose stream
            // is identical to one already queued is skipped (same file).
            val seenStreams = mutableSetOf<String>()
            var started = 0
            for (lang in listOf("hindi", "english")) {
                val found = runCatching {
                    repository.findSameEpisode(item.title, ep.number, lang)
                }.getOrNull()
                val (src, epData) = if (found != null) found.source to found.data else sourceId to data
                if (!seenStreams.add("$src|$epData")) continue
                val response = runCatching { repository.streams(src, epData) }.getOrNull()
                val best = response?.streams?.maxByOrNull { it.quality ?: -1 }
                if (best == null) continue
                // HLS total is unknown (-1): estimate it from the playlist
                // so Downloads can show a real % (see HlsEstimate).
                val estimate = if (best.type == "m3u8") {
                    runCatching { HlsEstimate.estimate(best.url) }.getOrNull()
                } else null
                DownloadHelper.enqueue(
                    context = context,
                    title = item.title,
                    sourceId = src,
                    contentId = item.id,
                    episodeNumber = ep.number,
                    playbackUrl = best.url, // already proxy-wrapped by Repository when needed
                    mimeType = if (best.type == "m3u8") MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4,
                    posterUrl = item.poster,
                    lang = lang,
                    estimatedTotalBytes = estimate,
                )
                started++
            }
            if (started == 0) {
                downloadMessage = "No stream available to download."
            } else {
                downloadStates[ep.number.toString()] = Download.STATE_QUEUED
                downloadMessage = "Download started in Hindi + English — see Downloads tab."
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Night).statusBarsPadding()
            .verticalScroll(scrollState),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceElevated)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(Modifier.weight(1f))
            Text("TITLE DETAILS", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp))
        }

        val artUrl = remember(details?.banner, details?.poster, item.poster) {
            StreamHubApplication.apiClient.posterUrl(
                details?.banner ?: details?.poster ?: item.poster,
            )
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(255.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF26262B), Color(0xFF17171A), Night))),
        ) {
            if (artUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                SmallTag((details?.type ?: item.type ?: "TITLE").uppercase())
                Spacer(Modifier.height(8.dp))
                Text(item.title.uppercase(), color = Color.White, fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black)
            }
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                details?.score?.let { score ->
                    SmallTag("★ $score", Color(0xFFFFC857))
                }
                (details?.year ?: item.year)?.let { year ->
                    Text(year.toString(), color = TextMuted, fontSize = 12.sp)
                }
                details?.status?.takeIf { it.isNotBlank() }?.let { status ->
                    Text("·", color = TextMuted)
                    Text(status, color = TextMuted, fontSize = 12.sp)
                }
            }
            if (!details?.plot.isNullOrBlank()) {
                Text(details!!.plot!!, color = Color(0xFFD2D9E5), fontSize = 13.sp, lineHeight = 20.sp)
            }
            val audioChips = buildList {
                details?.audio?.forEach { a ->
                    when (a.lowercase()) {
                        "dub" -> add("Dubbed")
                        "sub" -> add("Subbed")
                        else -> add(a.replaceFirstChar { it.uppercase() })
                    }
                }
            }
            if (audioChips.isNotEmpty()) {
                Text("Audio", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    audioChips.forEach { language ->
                        Box(Modifier.clip(RoundedCornerShape(50)).background(SurfaceElevated).padding(horizontal = 11.dp, vertical = 7.dp)) {
                            Text(language, color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
            if (!details?.genres.isNullOrEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    details!!.genres!!.take(4).forEach { genre ->
                        SmallTag(genre)
                    }
                }
            }
            Button(
                onClick = {
                    val d = details
                    val ep = firstEpisode
                    if (d != null && ep != null) onPlay(d, ep, sourceId)
                },
                enabled = details != null && firstEpisode != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text(if (episodes.isEmpty()) "Watch Movie" else "Watch Now", fontWeight = FontWeight.Bold)
            }

            // My List (v1.0.2): save this title to watch later.
            Button(
                onClick = { inMyList = WatchlistStore.toggle(context, item) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
            ) {
                Icon(
                    if (inMyList) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = null,
                )
                Spacer(Modifier.width(5.dp))
                Text(if (inMyList) "In My List" else "Add to My List", fontWeight = FontWeight.SemiBold)
            }

            // Resume prompt (v1.0.2): "you've watched this much" — tapping
            // Resume opens the player at EXACTLY that episode + timestamp
            // (the player seeks to the saved position on load).
            if (resumeEntry != null && resumeEpisode != null && details != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SurfaceNight)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Continue watching", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            (if (resumeEntry.episodeNumber > 0) "Episode ${resumeEntry.episodeNumber} · " else "") +
                                "Watched ${formatDuration(resumeEntry.positionMs)}" +
                                if (resumeEntry.durationMs > 0) " of ${formatDuration(resumeEntry.durationMs)}" else "",
                            color = TextMuted, fontSize = 11.sp,
                        )
                    }
                    Button(
                        onClick = { onPlay(details!!, resumeEpisode, sourceId) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                    ) {
                        Text("Resume", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Red)
                }
            } else if (loadFailed) {
                Text("Couldn't load details. Check your connection and try again.", color = TextMuted, fontSize = 12.sp)
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            details = runCatching { repository.details(sourceId, item.id) }.getOrNull()
                            loadFailed = details == null
                            loading = false
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                ) { Text("Retry") }
            } else if (episodes.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                SectionHeading("Episodes", if (seasons.size > 1) "Season ${currentSeason?.number ?: 1}" else null)
                if (seasons.size > 1) {
                    // Prominent season selector (v1.0.2): a clear "Seasons"
                    // header + a horizontally scrollable chip row. Each chip
                    // shows the episode COUNT and RANGE from the season's
                    // actual first/last episode numbers (e.g. "Season 5 •
                    // 24 episodes • Ep 89–112"). The old chips were small,
                    // in a non-scrollable row — 16 seasons clipped off-screen.
                    Text(
                        "Seasons", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        seasons.forEachIndexed { index, season ->
                            val eps = season.episodes.orEmpty()
                            val first = eps.minOfOrNull { it.number }
                            val last = eps.maxOfOrNull { it.number }
                            val range = if (first != null && last != null && first != last) {
                                "Ep $first–$last"
                            } else if (first != null) {
                                "Ep $first"
                            } else ""
                            val selected = index == selectedSeason
                            Box(
                                Modifier.clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) Red else SurfaceElevated)
                                    .clickable { selectedSeason = index }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        season.name ?: "Season ${season.number}",
                                        color = if (selected) Color.White else TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "${eps.size} episodes" + if (range.isNotBlank()) " · $range" else "",
                                        color = if (selected) Color.White.copy(alpha = 0.85f) else TextMuted,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (downloadMessage != null) {
                    Text(downloadMessage!!, color = TextPrimary, fontSize = 11.sp)
                }
                episodes.forEach { episode ->
                    val state = downloadStates[episode.number.toString()]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SurfaceNight)
                            .clickable {
                                val d = details
                                if (d != null) onPlay(d, episode, sourceId)
                            }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Wide landscape thumbnail (v1.0.2): the title
                        // poster, cropped 16:9 — no number box here. The
                        // "Episode N" label already carries the number.
                        // Dark charcoal fallback if the poster fails
                        // (never the old red).
                        Box(
                            Modifier.width(112.dp).height(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF26262B), Color(0xFF17171A), Color(0xFF0E0E10)),
                                    ),
                                ),
                        ) {
                            if (artUrl != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(artUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Episode ${episode.number}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Episode ${episode.number}",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            if (!episode.title.isNullOrBlank() && episode.title != "Episode ${episode.number}") {
                                Text(episode.title!!, color = TextMuted, fontSize = 10.sp)
                            }
                            Text(
                                when (state) {
                                    Download.STATE_COMPLETED -> "Downloaded"
                                    Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_RESTARTING -> "Downloading…"
                                    Download.STATE_FAILED -> "Download failed — tap to retry"
                                    else -> "Tap to play"
                                },
                                color = TextMuted,
                                fontSize = 9.sp,
                            )
                        }
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceElevated)
                                .clickable { downloadEpisode(episode) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state == Download.STATE_COMPLETED) Icons.Default.Check else Icons.Default.Download,
                                contentDescription = "Download episode",
                                tint = if (state == Download.STATE_COMPLETED) Color(0xFF46D369) else TextPrimary,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play episode", tint = TextPrimary)
                    }
                }
            } else if (details != null) {
                // Movie with no episode list: offer download of the single title.
                if (downloadMessage != null) {
                    Text(downloadMessage!!, color = TextPrimary, fontSize = 11.sp)
                }
                Button(
                    onClick = { firstEpisode?.let { downloadEpisode(it) } },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Download Movie")
                }
            }
            Spacer(Modifier.height(15.dp))
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
