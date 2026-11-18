package com.streamhub.app.ui.screens

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhub.app.data.HistoryStore
import com.streamhub.app.data.api.ApiItem
import com.streamhub.app.ui.components.PosterArtwork
import com.streamhub.app.ui.theme.*

/**
 * History (v1.0.2): everything watched, newest first — poster, episode,
 * when watched ("2h ago"), progress %. Tapping an entry resumes exactly
 * where the user left off (same path as Continue Watching). "Clear
 * History" wipes the list (two-tap confirm).
 */
@Composable
fun HistoryScreen(
    listState: LazyListState,
    onResume: (HistoryStore.HistoryEntry) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Adult history NEVER shows here (v1.0.3) — it lives only in the Lock page.
    var entries by remember { mutableStateOf(HistoryStore.loadAll(context, includeAdult = false)) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Night).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                "History", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                Text(
                    if (confirmClear) "Tap again to clear" else "Clear History",
                    color = if (confirmClear) Red else TextMuted,
                    fontSize = 12.sp,
                    fontWeight = if (confirmClear) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable {
                        if (confirmClear) {
                            HistoryStore.clear(context)
                            entries = emptyList()
                            confirmClear = false
                        } else {
                            confirmClear = true
                        }
                    }.padding(10.dp),
                )
            }
        }
        if (entries.isEmpty()) {
            Text(
                "Nothing watched yet. Titles you play will show up here.",
                color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(22.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { it.sourceId + "|" + it.detailsId + "|" + it.episodeNumber }) { entry ->
                    val progress = if (entry.durationMs > 0) {
                        (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
                    } else 0f
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceNight)
                            .clickable { onResume(entry) }
                            .padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PosterArtwork(
                            ApiItem(
                                id = entry.detailsId, title = entry.title,
                                poster = entry.poster, source = entry.sourceId,
                            ),
                            Modifier.width(64.dp).height(88.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(entry.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text(
                                (if (entry.episodeNumber > 0) "Episode ${entry.episodeNumber}" else "Movie") +
                                    " · " + HistoryStore.formatAgo(entry.watchedAtMs),
                                color = TextMuted, fontSize = 10.sp,
                            )
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(DividerColor)) {
                                Box(Modifier.fillMaxWidth(progress.coerceAtLeast(0.05f)).height(4.dp).clip(RoundedCornerShape(50)).background(Red))
                            }
                            Text("${(progress * 100).toInt()}% watched", color = TextMuted, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}
