package com.mazharansari.streamhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.mazharansari.streamhub.StreamHubApplication
import com.mazharansari.streamhub.data.api.ApiDetails
import com.mazharansari.streamhub.data.api.ApiEpisode
import com.mazharansari.streamhub.data.api.ApiItem
import com.mazharansari.streamhub.download.DownloadHelper
import com.mazharansari.streamhub.ui.components.SectionHeading
import com.mazharansari.streamhub.ui.components.SmallTag
import com.mazharansari.streamhub.ui.theme.*
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

    fun downloadEpisode(ep: ApiEpisode) {
        scope.launch {
            val state = downloadStates[ep.number.toString()]
            if (state == Download.STATE_COMPLETED) {
                downloadMessage = "Already downloaded — find it in Downloads."
                return@launch
            }
            val data = ep.data
            if (data.isNullOrBlank()) {
                downloadMessage = "No stream available to download."
                return@launch
            }
            downloadMessage = "Finding best stream for download…"
            val response = runCatching { repository.streams(sourceId, data) }.getOrNull()
            val best = response?.streams?.maxByOrNull { it.quality ?: -1 }
            if (best == null) {
                downloadMessage = "No stream available to download."
                return@launch
            }
            DownloadHelper.enqueue(
                context = context,
                title = item.title,
                sourceId = sourceId,
                contentId = item.id,
                episodeNumber = ep.number,
                playbackUrl = best.url, // already proxy-wrapped by Repository when needed
                mimeType = if (best.type == "m3u8") MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4,
                posterUrl = item.poster,
            )
            downloadStates[ep.number.toString()] = Download.STATE_QUEUED
            downloadMessage = "Download started — see Downloads tab."
        }
    }

    Column(
        Modifier.fillMaxSize().background(Night).statusBarsPadding()
            .verticalScroll(rememberScrollState()),
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

        val artUrl = details?.banner ?: details?.poster ?: item.poster
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(255.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFE50914), Color(0xFF7A0B12), Night))),
        ) {
            if (!artUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artUrl,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        seasons.forEachIndexed { index, season ->
                            Box(
                                Modifier.clip(RoundedCornerShape(50))
                                    .background(if (index == selectedSeason) Red else SurfaceElevated)
                                    .clickable { selectedSeason = index }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    season.name ?: "Season ${season.number}",
                                    color = if (index == selectedSeason) Color.White else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
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
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceElevated),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${episode.number}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
