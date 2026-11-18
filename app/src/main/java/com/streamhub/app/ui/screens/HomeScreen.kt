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
import coil.imageLoader
import coil.request.ImageRequest
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.HistoryStore
import com.streamhub.app.data.WatchlistStore
import com.streamhub.app.data.api.ApiItem
import com.streamhub.app.data.api.APP_CATEGORIES
import com.streamhub.app.data.api.HomeSection
import com.streamhub.app.data.extensions.ExtensionsCatalog
import com.streamhub.app.ui.components.BrandHeader
import com.streamhub.app.ui.components.CategoryChips
import com.streamhub.app.ui.components.PosterArtwork
import com.streamhub.app.ui.components.PosterCard
import com.streamhub.app.ui.components.SectionHeading
import com.streamhub.app.ui.components.SmallTag
import com.streamhub.app.ui.player.WatchProgressStore
import com.streamhub.app.ui.theme.*

/** Adult source ids — adult items never appear on Home (v1.0.3). */
private val ADULT_IDS: Set<String> = ExtensionsCatalog.adult.map { it.id }.toSet()

/**
 * Home: brand header, language filter, hero billboard, Continue Watching,
 * then one rail per [HomeSection] — only rails with real items render.
 * Lists start empty until the API returns; a failed load shows a retry.
 */
@Composable
fun HomeScreen(
    onItemClick: (ApiItem) -> Unit,
    onSearchClick: () -> Unit,
    onSeeAll: (HomeSection) -> Unit,
    onCategoryClick: (String) -> Unit,
    onBrowseClick: () -> Unit,
    // Jump straight into the player at the saved position (not Details).
    onResumeTitle: (sourceId: String, detailsId: String, episodeNumber: Int, title: String, poster: String?) -> Unit,
    onMyListClick: () -> Unit,
    onHistoryClick: () -> Unit,
    // Hoisted by the app root: the vertical scroll position survives leaving
    // for Details/Player and coming back (scroll is restored, not reset).
    scrollState: ScrollState,
) {
    val context = LocalContext.current
    val repository = StreamHubApplication.repository
    var langFilter by remember { mutableStateOf("All") }
    var sections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    // Starts false: on revisit the cached rails arrive before any spinner
    // could show; loading only turns on for a true cold start (empty cache).
    var loading by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    val lang = when (langFilter) {
        "Hindi Dub" -> "hindi"
        "English" -> "english"
        else -> "all"
    }

    // CACHED-FIRST (v1.0.2): show the cached rails instantly (no spinner),
    // then refresh silently in the background. Only a cold start with an
    // empty cache shows the loading indicator.
    LaunchedEffect(lang, refreshTick) {
        loadFailed = false
        val cached = runCatching { repository.cachedHomeSections(lang) }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) {
            sections = cached
            loading = false
            val fresh = runCatching { repository.refreshHomeSections(lang) }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) sections = fresh
        } else {
            loading = true
            val result = runCatching { repository.refreshHomeSections(lang) }.getOrDefault(emptyList())
            sections = result
            loadFailed = result.isEmpty()
            loading = false
        }
    }

    // Prefetch the first posters into Coil's disk cache so cards render
    // instantly on revisit (the image loader already disk-caches; this
    // warms it ahead of the scroll).
    LaunchedEffect(sections) {
        if (sections.isEmpty()) return@LaunchedEffect
        val loader = context.imageLoader
        sections.flatMap { it.items }.take(24).forEach { item ->
            val url = StreamHubApplication.apiClient.posterUrl(item.poster) ?: return@forEach
            loader.enqueue(
                ImageRequest.Builder(context).data(url).build(),
            )
        }
    }

    val continueWatching = remember(sections) {
        // Adult titles never surface on Home (v1.0.3) — Continue Watching
        // included: an adult episode's progress must not leak here.
        WatchProgressStore.loadAll(context).filter { it.sourceId !in ADULT_IDS }
    }
    // My List + History (v1.0.2): local stores, re-read every time Home
    // re-enters composition (after Details/Player), so a title saved or
    // watched just now shows up immediately. Adult items are excluded
    // from both (v1.0.3) — adult history lives ONLY in the Lock page.
    val myList = remember { WatchlistStore.loadAll(context).filter { it.source !in ADULT_IDS } }
    val history = remember { HistoryStore.loadAll(context, includeAdult = false) }
    val featured = sections.firstOrNull()?.items?.firstOrNull()

    Column(
        modifier = Modifier.fillMaxSize().background(Night)
            .statusBarsPadding()
            .verticalScroll(scrollState)
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
            // Slimmer hero (300dp, was 340dp): the top of Home must not eat
            // half the screen before the first rail.
            val featuredPoster = remember(featured.poster) {
                StreamHubApplication.apiClient.posterUrl(featured.poster)
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)
                    .height(300.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF26262B), Color(0xFF17171A), Night))),
            ) {
                if (featuredPoster != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(featuredPoster)
                            .crossfade(true)
                            .build(),
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
            Box(Modifier.fillMaxWidth().height(300.dp).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Red)
            }
        }

        // Categories (v1.0.2): real category browsing — every pill opens a
        // grid that actually shows content from the API. The leading
        // "Browse" pill opens the Type + Country + Year discover screen.
        // There is NO adult pill here: adult content lives only in the
        // hidden page (Settings → Lock → password), never on Home.
        Column(Modifier.padding(top = 22.dp)) {
            Row(Modifier.padding(horizontal = 20.dp)) {
                SectionHeading("Categories")
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Red)
                        .clickable(onClick = onBrowseClick)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("Browse", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                APP_CATEGORIES.forEach { category ->
                    CategoryPill(category.title) { onCategoryClick(category.id) }
                }
            }
        }

        // My List (v1.0.2): titles saved from Details. Tap opens Details;
        // "See all" opens the full My List grid.
        if (myList.isNotEmpty()) {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    SectionHeading("My List", "See all") { onMyListClick() }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    myList.forEach { item ->
                        PosterCard(item, { onItemClick(item) }, Modifier.width(138.dp))
                    }
                }
            }
        }

        // History (v1.0.2): everything watched, newest first. Tap resumes
        // exactly where the user left off (same path as Continue Watching).
        if (history.isNotEmpty()) {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    SectionHeading("History", "See all") { onHistoryClick() }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    history.take(10).forEach { entry ->
                        HistoryRailCard(entry) {
                            onResumeTitle(
                                entry.sourceId, entry.detailsId, entry.episodeNumber,
                                entry.title, entry.poster,
                            )
                        }
                    }
                }
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
                        // Tapping a card jumps STRAIGHT into the player at
                        // the saved position (v1.0.2) — it used to open
                        // Details only, which is why resume felt broken.
                        ContinueWatchingCard(entry) {
                            onResumeTitle(
                                entry.sourceId, entry.detailsId, entry.episodeNumber,
                                entry.title, entry.poster,
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
                    SectionHeading(section.title, "See all") { onSeeAll(section) }
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
private fun CategoryPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HistoryRailCard(
    entry: HistoryStore.HistoryEntry,
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
