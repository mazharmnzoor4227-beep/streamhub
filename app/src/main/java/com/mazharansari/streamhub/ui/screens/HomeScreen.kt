package com.mazharansari.streamhub.ui.screens

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mazharansari.streamhub.StreamHubApplication
import com.mazharansari.streamhub.data.api.ApiItem
import com.mazharansari.streamhub.data.api.HomeSection
import com.mazharansari.streamhub.ui.components.BrandHeader
import com.mazharansari.streamhub.ui.components.CategoryChips
import com.mazharansari.streamhub.ui.components.PosterArtwork
import com.mazharansari.streamhub.ui.components.PosterCard
import com.mazharansari.streamhub.ui.components.SectionHeading
import com.mazharansari.streamhub.ui.components.SmallTag
import com.mazharansari.streamhub.ui.player.WatchProgressStore
import com.mazharansari.streamhub.ui.theme.*

/**
 * Home: brand header, language filter, hero billboard, Continue Watching,
 * then one rail per [HomeSection] — only rails with real items render.
 * Lists start empty until the API returns; a failed load shows a retry.
 */
@Composable
fun HomeScreen(
    onItemClick: (ApiItem) -> Unit,
    onSearchClick: () -> Unit,
    onSeeAll: () -> Unit,
) {
    val context = LocalContext.current
    val repository = StreamHubApplication.repository
    var langFilter by remember { mutableStateOf("All") }
    var sections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    val lang = when (langFilter) {
        "Hindi Dub" -> "hindi"
        "English" -> "english"
        else -> "all"
    }

    LaunchedEffect(lang, refreshTick) {
        loading = true
        loadFailed = false
        val result = runCatching { repository.homeSections(lang) }.getOrDefault(emptyList())
        sections = result
        loadFailed = result.isEmpty()
        loading = false
    }

    val continueWatching = remember(sections) { WatchProgressStore.loadAll(context) }
    val featured = sections.firstOrNull()?.items?.firstOrNull()

    Column(
        modifier = Modifier.fillMaxSize().background(Night)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 22.dp),
    ) {
        BrandHeader(onSearchClick = onSearchClick)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryChips(
                items = listOf("All", "Hindi Dub", "English"),
                selected = langFilter,
                onSelected = { langFilter = it },
            )
        }

        if (featured != null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)
                    .height(340.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFE50914), Color(0xFF7A0B12), Night))),
            ) {
                if (!featured.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = featured.poster,
                        contentDescription = featured.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))))
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    SmallTag(featured.type?.uppercase()?.ifBlank { "STREAMHUB" } ?: "STREAMHUB")
                    Text(
                        featured.title.uppercase(), color = Color.White, fontSize = 32.sp,
                        lineHeight = 34.sp, fontWeight = FontWeight.Black, maxLines = 3,
                    )
                    val meta = listOfNotNull(
                        featured.year?.toString(),
                        featured.type?.replaceFirstChar { it.uppercase() },
                    ).joinToString("   ·   ")
                    if (meta.isNotBlank()) {
                        Text(meta, color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp, letterSpacing = 0.7.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onItemClick(featured) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Night),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text("Play Now", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onItemClick(featured) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Details", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else if (loading) {
            Box(Modifier.fillMaxWidth().height(340.dp).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Red)
            }
        }

        if (continueWatching.isNotEmpty()) {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    SectionHeading("Continue Watching")
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    continueWatching.forEach { entry ->
                        ContinueWatchingCard(entry) {
                            onItemClick(
                                ApiItem(
                                    id = entry.detailsId,
                                    title = entry.title,
                                    poster = entry.poster,
                                    source = entry.sourceId,
                                ),
                            )
                        }
                    }
                }
            }
        }

        if (!loading && sections.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Couldn't load content.", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Check your connection and try again.", color = TextMuted, fontSize = 12.sp)
                Button(
                    onClick = { refreshTick++ },
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Retry", fontWeight = FontWeight.Bold) }
            }
        }

        sections.forEach { section ->
            Column(Modifier.padding(top = 25.dp)) {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    SectionHeading(section.title, "See all", onSeeAll)
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    section.items.forEach { item ->
                        PosterCard(item, { onItemClick(item) }, Modifier.width(138.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ContinueWatchingCard(
    entry: WatchProgressStore.ContinueEntry,
    onClick: () -> Unit,
) {
    val progress = if (entry.durationMs > 0) {
        (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
    } else 0f
    Row(
        modifier = Modifier.width(250.dp).clip(RoundedCornerShape(14.dp)).background(SurfaceNight)
            .clickable(onClick = onClick).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterArtwork(
            ApiItem(id = entry.detailsId, title = entry.title, poster = entry.poster, source = entry.sourceId),
            Modifier.width(64.dp).height(88.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(
                if (entry.episodeNumber > 0) "Episode ${entry.episodeNumber}" else "Movie",
                color = TextMuted, fontSize = 10.sp,
            )
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(DividerColor)) {
                Box(Modifier.fillMaxWidth(progress.coerceAtLeast(0.05f)).height(4.dp).clip(RoundedCornerShape(50)).background(Red))
            }
            Text("${(progress * 100).toInt()}% watched", color = TextMuted, fontSize = 9.sp)
        }
    }
}
