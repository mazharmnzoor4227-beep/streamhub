package com.streamhub.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay

private val DownloadsBg = Color(0xFF141414)
private val DownloadsSurface = Color(0xFF1F1F1F)
private val DownloadsAccent = Color(0xFFE50914)
private val DownloadsText = Color(0xFFFFFFFF)
private val DownloadsMuted = Color(0xFFB3B3B3)

/**
 * Downloads list.
 *
 * @param onPlayOffline called with the completed [DownloadHelper.DownloadItem] when the
 *  user taps Play. Resolving the downloaded file back into the player (offline
 *  MediaSource from the shared download cache) is the main agent's wiring: this
 *  screen only hands over the item — its [DownloadHelper.DownloadItem.id] (the
 *  Media3 download id), [DownloadHelper.DownloadItem.sourceId],
 *  [DownloadHelper.DownloadItem.contentId] and
 *  [DownloadHelper.DownloadItem.episodeNumber] identify the downloaded content.
 */
@Composable
fun DownloadsScreen(onPlayOffline: (DownloadHelper.DownloadItem) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<DownloadHelper.DownloadItem>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<DownloadHelper.DownloadItem?>(null) }

    // Poll the offline index while visible: downloads progress outside this screen.
    LaunchedEffect(Unit) {
        while (true) {
            items = DownloadHelper.list(context)
            delay(1000)
        }
    }

    BackHandler(enabled = pendingDelete != null) { pendingDelete = null }

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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        DownloadRow(
                            item = item,
                            onPause = {
                                DownloadHelper.pause(context, item.id)
                                items = DownloadHelper.list(context)
                            },
                            onResume = {
                                DownloadHelper.resume(context, item.id)
                                items = DownloadHelper.list(context)
                            },
                            onDelete = { pendingDelete = item },
                            onPlay = { onPlayOffline(item) },
                        )
                    }
                }
            }
        }

        // Custom confirm modal (Compose scrim + card — not a native Android dialog).
        pendingDelete?.let { item ->
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
                            text = "This removes \"${item.title}\" and deletes the downloaded file completely, including any partially downloaded part.",
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
                                    DownloadHelper.remove(context, item.id)
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
private fun DownloadRow(
    item: DownloadHelper.DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DownloadsSurface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Banner / poster thumb, with a dark title-card fallback.
            // Poster goes through the API proxy (see ApiClient.posterUrl):
            // direct hosts are slow/flaky from the phone.
            val thumbUrl = remember(item.posterUrl) {
                StreamHubApplication.apiClient.posterUrl(item.posterUrl)
            }
            if (thumbUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumbUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
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
                        text = item.title.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = DownloadsText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (item.episodeNumber > 0) {
                        "Episode ${item.episodeNumber} · ${item.sourceId}"
                    } else {
                        item.sourceId
                    },
                    color = DownloadsMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))

                when (item.state) {
                    DownloadState.DOWNLOADING, DownloadState.QUEUED -> {
                        LinearProgressIndicator(
                            progress = { item.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = DownloadsAccent,
                            trackColor = Color(0xFF3A3A3A),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (item.totalBytes > 0) {
                                "${(item.progress * 100).toInt()}% · ${formatBytes(item.bytesDownloaded)} / ${formatBytes(item.totalBytes)}"
                            } else {
                                "Downloading… ${formatBytes(item.bytesDownloaded)}"
                            },
                            color = DownloadsMuted,
                            fontSize = 12.sp,
                        )
                    }
                    DownloadState.PAUSED -> {
                        LinearProgressIndicator(
                            progress = { item.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = DownloadsMuted,
                            trackColor = Color(0xFF3A3A3A),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Paused · ${(item.progress * 100).toInt()}%",
                            color = DownloadsMuted,
                            fontSize = 12.sp,
                        )
                    }
                    DownloadState.COMPLETED -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF46D369),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (item.totalBytes > 0) {
                                    "Downloaded · ${formatBytes(item.totalBytes)}"
                                } else {
                                    "Downloaded"
                                },
                                color = DownloadsMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    DownloadState.FAILED -> {
                        Text(
                            text = "Download failed",
                            color = DownloadsAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (item.state) {
                        DownloadState.DOWNLOADING, DownloadState.QUEUED -> {
                            OutlinedButton(
                                onClick = onPause,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 0.dp,
                                ),
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pause", fontSize = 12.sp)
                            }
                        }
                        DownloadState.PAUSED, DownloadState.FAILED -> {
                            OutlinedButton(
                                onClick = onResume,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 0.dp,
                                ),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Resume", fontSize = 12.sp)
                            }
                        }
                        DownloadState.COMPLETED -> {
                            Button(
                                onClick = onPlay,
                                colors = ButtonDefaults.buttonColors(containerColor = DownloadsAccent),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 12.dp,
                                    vertical = 0.dp,
                                ),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Play", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete download",
                            tint = DownloadsAccent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
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
