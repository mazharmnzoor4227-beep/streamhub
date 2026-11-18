package com.streamhub.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.download.DownloadHelper
import com.streamhub.app.download.DownloadHelper.DownloadState
import com.streamhub.app.download.HlsEstimate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DownloadsBg = Color(0xFF141414)
private val DownloadsSurface = Color(0xFF1F1F1F)
private val DownloadsAccent = Color(0xFFE50914)
private val DownloadsText = Color(0xFFFFFFFF)
private val DownloadsMuted = Color(0xFFB3B3B3)

/**
 * Downloads list (v1.0.2).
 *
 * - ONE card per episode/movie, with ALL its language variants inside
 *   (Hindi + English): each variant shows its own live % line and a
 *   language badge, so the user sees which languages a download includes.
 * - % is ALWAYS visible while downloading: the real total when known,
 *   else the HLS size estimate (stored at enqueue time, or computed live
 *   once for older downloads) — shown as "% · X / ~Y". Never a bare
 *   "Downloading…" with no %.
 * - Pause / Resume / Delete are BIG LABELED buttons on every row, in
 *   every state (Pause while downloading/queued, Resume when paused or
 *   failed, Delete always). Delete removes ALL language variants of the
 *   episode, including every partially downloaded segment.
 * - Play opens the completed variant (Hindi first) and hands the whole
 *   variant group to the player, so the offline Audio menu can switch
 *   languages while watching.
 */
@Composable
fun DownloadsScreen(
    onPlayOffline: (DownloadHelper.DownloadItem, List<DownloadHelper.DownloadItem>) -> Unit,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<DownloadHelper.DownloadItem>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<List<DownloadHelper.DownloadItem>?>(null) }
    // Live HLS estimates for downloads enqueued before v1.0.2 (no stored
    // estimate): computed once per download id, from the playlist itself.
    val liveEstimates = remember { mutableStateMapOf<String, Long>() }
    val estimateAttempted = remember { mutableSetOf<String>() }
    val scope = rememberCoroutineScope()

    // Poll the offline index while visible: downloads progress outside this screen.
    LaunchedEffect(Unit) {
        while (true) {
            items = DownloadHelper.list(context)
            delay(1000)
        }
    }

    // Fill in missing estimates (one attempt per download, best-effort).
    LaunchedEffect(items) {
        items.filter {
            (it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED) &&
                it.effectiveTotalBytes <= 0 && it.id !in estimateAttempted
        }.forEach { item ->
            estimateAttempted.add(item.id)
            scope.launch {
                val estimate = runCatching { HlsEstimate.estimate(item.playbackUrl) }.getOrNull()
                if (estimate != null && estimate > 0) liveEstimates[item.id] = estimate
            }
        }
    }

    BackHandler(enabled = pendingDelete != null) { pendingDelete = null }

    // Group variants of one episode into one card (same source + content + episode).
    val groups = remember(items) {
        items.groupBy { Triple(it.sourceId, it.contentId, it.episodeNumber) }.values.toList()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(DownloadsBg)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = "Downloads",
                color = DownloadsText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )

            if (items.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        tint = DownloadsMuted,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No downloads yet",
                        color = DownloadsText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Episodes and movies you download will appear here for offline viewing.",
                        color = DownloadsMuted,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(groups, key = { group -> group.first().id }) { group ->
                        DownloadGroupRow(
                            group = group,
                            liveEstimates = liveEstimates,
                            onPause = {
                                group.forEach { DownloadHelper.pause(context, it.id) }
                                items = DownloadHelper.list(context)
                            },
                            onResume = {
                                group.forEach { DownloadHelper.resume(context, it.id) }
                                items = DownloadHelper.list(context)
                            },
                            onDelete = { pendingDelete = group },
                            onPlay = { item -> onPlayOffline(item, group) },
                        )
                    }
                }
            }
        }

        // Custom confirm modal (Compose scrim + card — not a native Android dialog).
        pendingDelete?.let { group ->
            val first = group.first()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { pendingDelete = null },
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .clickable(enabled = false) {},
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DownloadsSurface),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = "Delete download?",
                            color = DownloadsText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "This removes \"${first.title}\"" +
                                (if (group.size > 1) " in all ${group.size} languages" else "") +
                                " and deletes the downloaded file completely, including any partially downloaded part.",
                            color = DownloadsMuted,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { pendingDelete = null }) {
                                Text("Cancel", color = DownloadsText)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    DownloadHelper.removeGroup(context, group.map { it.id })
                                    pendingDelete = null
                                    items = DownloadHelper.list(context)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DownloadsAccent),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadGroupRow(
    group: List<DownloadHelper.DownloadItem>,
    liveEstimates: Map<String, Long>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onPlay: (DownloadHelper.DownloadItem) -> Unit,
) {
    val first = group.first()
    val hasActive = group.any {
        it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
    }
    val hasPausedOrFailed = group.any {
        it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED
    }
    // Play prefers the completed Hindi variant, else any completed one.
    val playable = group.firstOrNull {
        it.state == DownloadState.COMPLETED && it.lang == "hindi"
    } ?: group.firstOrNull { it.state == DownloadState.COMPLETED }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DownloadsSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Banner / poster thumb, with a dark title-card fallback.
                // Poster goes through the API proxy (see ApiClient.posterUrl):
                // direct hosts are slow/flaky from the phone.
                val thumbUrl = remember(first.posterUrl) {
                    StreamHubApplication.apiClient.posterUrl(first.posterUrl)
                }
                if (thumbUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(thumbUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = first.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 64.dp, height = 96.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 96.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF26262B), Color(0xFF17171A), Color(0xFF0E0E10)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = first.title.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = first.title,
                        color = DownloadsText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (first.episodeNumber > 0) {
                            "Episode ${first.episodeNumber} · ${first.sourceId}"
                        } else {
                            first.sourceId
                        },
                        color = DownloadsMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Language badges: which languages this download includes.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        group.forEach { variant ->
                            Box(
                                Modifier.clip(RoundedCornerShape(50))
                                    .background(
                                        if (variant.state == DownloadState.COMPLETED) Color(0xFF1E5C2E)
                                        else Color(0xFF3A3A3A),
                                    )
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = when (variant.lang.lowercase()) {
                                        "hindi" -> "Hindi"
                                        "english" -> "English"
                                        else -> variant.lang.replaceFirstChar { it.uppercase() }
                                    },
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // One progress line per language variant — % always visible.
            group.forEach { variant ->
                val total = if (variant.effectiveTotalBytes > 0) {
                    variant.effectiveTotalBytes
                } else {
                    liveEstimates[variant.id] ?: -1L
                }
                val percent = if (total > 0) {
                    (variant.bytesDownloaded.toFloat() / total * 100).toInt().coerceIn(0, 100)
                } else {
                    (variant.progress * 100).toInt().coerceIn(0, 100)
                }
                val langLabel = when (variant.lang.lowercase()) {
                    "hindi" -> "Hindi"
                    "english" -> "English"
                    else -> variant.lang.replaceFirstChar { it.uppercase() }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = langLabel,
                        color = DownloadsText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(64.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        LinearProgressIndicator(
                            progress = { (percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (variant.state == DownloadState.PAUSED) DownloadsMuted else DownloadsAccent,
                            trackColor = Color(0xFF3A3A3A),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when (variant.state) {
                                DownloadState.DOWNLOADING, DownloadState.QUEUED -> when {
                                    variant.totalBytes > 0 ->
                                        "$percent% · ${formatBytes(variant.bytesDownloaded)} / ${formatBytes(variant.totalBytes)}"
                                    total > 0 ->
                                        "$percent% · ${formatBytes(variant.bytesDownloaded)} / ~${formatBytes(total)}"
                                    else ->
                                        "Downloading… ${formatBytes(variant.bytesDownloaded)}"
                                }
                                DownloadState.PAUSED -> "Paused · $percent%"
                                DownloadState.COMPLETED -> if (variant.totalBytes > 0) {
                                    "Downloaded · ${formatBytes(variant.totalBytes)}"
                                } else {
                                    "Downloaded"
                                }
                                DownloadState.FAILED -> "Download failed"
                            },
                            color = if (variant.state == DownloadState.FAILED) DownloadsAccent else DownloadsMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // Big labeled buttons, always visible, in every state. Tapping
            // the row never hides them (there is no expand/collapse).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasActive) {
                    OutlinedButton(
                        onClick = onPause,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pause", fontSize = 12.sp)
                    }
                }
                if (hasPausedOrFailed) {
                    OutlinedButton(
                        onClick = onResume,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Resume", fontSize = 12.sp)
                    }
                }
                if (playable != null) {
                    Button(
                        onClick = { onPlay(playable) },
                        colors = ButtonDefaults.buttonColors(containerColor = DownloadsAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", fontSize = 12.sp)
                }
            }
            if (group.any { it.state == DownloadState.COMPLETED }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF46D369),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Ready to watch offline",
                        color = DownloadsMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "%.1f GB".format(bytes / gb)
        bytes >= mb -> "%.1f MB".format(bytes / mb)
        bytes >= kb -> "%.0f KB".format(bytes / kb)
        else -> "$bytes B"
    }
}
