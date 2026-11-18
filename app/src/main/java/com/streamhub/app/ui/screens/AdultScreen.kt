package com.streamhub.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.HistoryStore
import com.streamhub.app.data.api.ApiItem
import com.streamhub.app.data.extensions.ExtensionInfo
import com.streamhub.app.data.extensions.ExtensionsCatalog
import com.streamhub.app.ui.components.PosterCard
import com.streamhub.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The hidden folder (18+) — a separate page reachable ONLY from
 * Settings → Lock → password, every time. It is NOT a tab, NOT in the
 * main nav, NOT on Home, and nothing in the app names or explains it.
 *
 * v1.0.4: the page is a self-contained MINI-APP of its own, with three
 * full-screen sections behind a tab row — Browse, History, Extensions.
 * v1.0.3 stacked everything (extensions + history + chips) above the
 * grid in one scrolling column, so the grid was squeezed into less than
 * half the screen. Now each section owns the whole screen when its tab
 * is open: Browse is a full-screen grid + search + source chips,
 * History is the FULL deduped adult history (not 10 cards), and
 * Extensions is the adult install/manage list. Nothing here ever leaks
 * into Home, Search, trending or recommendations.
 *
 * The adult backends are SEARCH-ONLY (their `home()` returns nothing —
 * there is no latest/trending endpoint), so the browse list is a broad
 * search across ALL installed adult extensions: query "a" returns
 * titles with posters per source in under a second (verified live
 * against the API). The search box stays on the Browse tab for
 * narrowing down; titles open Details and play like any other title
 * (direct HLS via /api/proxy, no embeds). The list is cached, so the
 * page opens instantly after the first load. Opening the page
 * auto-enables every adult extension (see SettingsScreen), so content
 * shows the moment the page opens.
 */
@Composable
fun AdultScreen(
    onItemClick: (ApiItem) -> Unit,
    onBack: () -> Unit,
    // Hoisted by the app root: grid position survives leaving for
    // Details/Player and coming back.
    gridState: LazyGridState,
    // Resume an adult history entry (same path as Home's History rail).
    onResumeHistory: (HistoryStore.HistoryEntry) -> Unit = {},
) {
    val context = LocalContext.current
    val repository = StreamHubApplication.repository
    val scope = rememberCoroutineScope()
    val enabledIds by StreamHubApplication.extensions.enabledIds.collectAsState(initial = emptySet())
    val adultIds = remember { ExtensionsCatalog.adult.map { it.id }.toSet() }
    val enabledAdult = enabledIds.intersect(adultIds)
    // v1.0.4: mini-app tab. Browse is the default (what the user opens
    // the folder for); History and Extensions are full sections of
    // their own, not strips stacked above the grid.
    var tab by remember { mutableStateOf(AdultTab.BROWSE) }
    var query by remember { mutableStateOf("") }
    var browse by remember { mutableStateOf<List<ApiItem>>(emptyList()) }
    var browseLoading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<ApiItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var liveIds by remember { mutableStateOf<Set<String>?>(null) }
    // v1.0.3: per-source filter (the API has no adult genre/category
    // endpoint — each installed adult source IS the category) + "Load
    // more" pagination over the broad-search pages.
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var browsePage by remember { mutableStateOf(1) }
    var loadingMore by remember { mutableStateOf(false) }
    var browseExhausted by remember { mutableStateOf(false) }
    // Adult history ONLY here (v1.0.3) — never on Home. Reloaded every
    // time the History tab opens, so a just-watched title is there.
    val adultHistory = remember(tab, enabledAdult) {
        HistoryStore.loadAll(context, includeAdult = true).filter { it.isAdult }
    }

    // Live status for the adult extension rows — cached first (instant).
    LaunchedEffect(Unit) {
        val cached = repository.cachedSources()
        if (cached.isNotEmpty()) {
            liveIds = cached.map { it.id }.toSet()
            val fresh = runCatching { repository.refreshSources() }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) liveIds = fresh.map { it.id }.toSet()
        } else {
            liveIds = runCatching { repository.refreshSources() }
                .getOrNull()?.map { it.id }?.toSet()
        }
    }

    // Browse list on open: broad search across every installed adult
    // extension (backends have no latest/trending list). Cached first —
    // instant on revisit — then a silent background refresh.
    LaunchedEffect(enabledAdult) {
        if (enabledAdult.isEmpty()) {
            browse = emptyList()
            browseLoading = false
            return@LaunchedEffect
        }
        browseLoading = true
        browsePage = 1
        browseExhausted = false
        val cached = runCatching { repository.cachedAdultBrowse(enabledAdult) }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) {
            browse = cached
            browseLoading = false
            val fresh = runCatching { repository.refreshAdultBrowse(enabledAdult) }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) browse = fresh
        } else {
            browse = runCatching { repository.refreshAdultBrowse(enabledAdult) }.getOrDefault(emptyList())
            browseLoading = false
        }
    }

    LaunchedEffect(query, enabledAdult) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(500)
        searching = true
        results = runCatching {
            repository.adultSearch(query.trim(), enabledAdult)
        }.getOrDefault(emptyList())
        searching = false
    }

    val showingSearch = query.isNotBlank()
    // Per-source filter applies to browse AND search results.
    val shownUnfiltered = if (showingSearch) results else browse
    val shown = if (sourceFilter == null) shownUnfiltered
    else shownUnfiltered.filter { it.source == sourceFilter }
    val loading = if (showingSearch) searching else browseLoading

    fun loadMore() {
        if (loadingMore || browseExhausted || showingSearch) return
        loadingMore = true
        scope.launch {
            val nextPage = browsePage + 1
            val more = runCatching {
                repository.adultBrowsePage(enabledAdult, nextPage)
            }.getOrDefault(emptyList())
            if (more.isEmpty()) {
                browseExhausted = true
            } else {
                browsePage = nextPage
                val seen = browse.map { (it.source ?: "") + "|" + it.id }.toSet()
                browse = browse + more.filter { (it.source ?: "") + "|" + it.id !in seen }
            }
            loadingMore = false
        }
    }

    Column(Modifier.fillMaxSize().background(Night).statusBarsPadding().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                "Hidden Folder", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "18+ only · Private",
            color = TextMuted, fontSize = 12.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
        )
        // Mini-app tab row (v1.0.4): each section owns the full screen.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdultTabChip("Browse", tab == AdultTab.BROWSE) { tab = AdultTab.BROWSE }
            AdultTabChip(
                "History" + if (adultHistory.isNotEmpty()) " (${adultHistory.size})" else "",
                tab == AdultTab.HISTORY,
            ) { tab = AdultTab.HISTORY }
            AdultTabChip("Extensions", tab == AdultTab.EXTENSIONS) { tab = AdultTab.EXTENSIONS }
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            AdultTab.BROWSE -> AdultBrowseTab(
                query = query,
                onQueryChange = { query = it },
                enabledAdult = enabledAdult,
                sourceFilter = sourceFilter,
                onSourceFilterChange = { sourceFilter = it },
                loading = loading,
                showingSearch = showingSearch,
                shown = shown,
                browseExhausted = browseExhausted,
                loadingMore = loadingMore,
                onLoadMore = { loadMore() },
                gridState = gridState,
                onItemClick = onItemClick,
            )
            AdultTab.HISTORY -> AdultHistoryTab(
                history = adultHistory,
                onResumeHistory = onResumeHistory,
            )
            AdultTab.EXTENSIONS -> AdultExtensionsTab(
                liveIds = liveIds,
                enabledAdult = enabledAdult,
                onToggle = { info, installed ->
                    scope.launch {
                        StreamHubApplication.extensions.setEnabled(info.id, !installed)
                    }
                },
            )
        }
    }
}

/** The three sections of the hidden-folder mini-app. */
private enum class AdultTab { BROWSE, HISTORY, EXTENSIONS }

/** Tab pill — same look as the source filter chips. */
@Composable
private fun AdultTabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    AdultFilterChip(text, selected, onClick)
}

/**
 * Browse tab: search + per-source filter + the FULL-SCREEN grid.
 * Nothing is stacked above the grid except the search box and the
 * source chips — the grid owns the rest of the screen.
 */
@Composable
private fun AdultBrowseTab(
    query: String,
    onQueryChange: (String) -> Unit,
    enabledAdult: Set<String>,
    sourceFilter: String?,
    onSourceFilterChange: (String?) -> Unit,
    loading: Boolean,
    showingSearch: Boolean,
    shown: List<ApiItem>,
    browseExhausted: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    gridState: LazyGridState,
    onItemClick: (ApiItem) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            placeholder = { Text("Search adult titles...", color = TextMuted, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Red,
                unfocusedBorderColor = DividerColor,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Red,
                focusedContainerColor = SurfaceNight,
                unfocusedContainerColor = SurfaceNight,
            ),
        )
        Spacer(Modifier.height(12.dp))
        // Per-source filter (v1.0.3): each installed adult source is its
        // own category — the API exposes no adult genre/category list.
        if (enabledAdult.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(start = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AdultFilterChip("All", sourceFilter == null) { onSourceFilterChange(null) }
                enabledAdult.sorted().forEach { sourceId ->
                    val name = ExtensionsCatalog.byId(sourceId)?.name ?: sourceId
                    AdultFilterChip(name, sourceFilter == sourceId) { onSourceFilterChange(sourceId) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (loading) {
            Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Red, modifier = Modifier.size(28.dp))
            }
        } else if (enabledAdult.isEmpty()) {
            Text(
                "No adult extension is installed yet — install one in the Extensions tab to see titles here.",
                color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(22.dp),
            )
        } else if (shown.isEmpty()) {
            Text(
                if (showingSearch) "No titles found. Try another search."
                else "Nothing here yet — check your connection and reopen this section.",
                color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(22.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(span = { GridItemSpan(3) }) {
                    Text(
                        if (showingSearch) "Search results" else "Browse all",
                        color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(shown, key = { it.id }) { item ->
                    PosterCard(item, { onItemClick(item) }, modifier = Modifier.fillMaxWidth(), artHeight = 168.dp)
                }
                // "Load more" pagination (v1.0.3): next broad-search page
                // across all installed adult sources. Hidden while
                // searching (search is one page) and once exhausted.
                if (!showingSearch && !browseExhausted && shown.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Button(
                                onClick = onLoadMore,
                                enabled = !loadingMore,
                                colors = ButtonDefaults.buttonColors(containerColor = Red),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    if (loadingMore) "Loading…" else "Load more",
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * History tab: the FULL deduped adult history as a full-screen list,
 * newest first. Tap resumes exactly where the user left off. Lives
 * ONLY here — never on Home (v1.0.3).
 */
@Composable
private fun AdultHistoryTab(
    history: List<HistoryStore.HistoryEntry>,
    onResumeHistory: (HistoryStore.HistoryEntry) -> Unit,
) {
    if (history.isEmpty()) {
        Text(
            "No adult history yet — watch something and it will show here.",
            color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(22.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(history, key = { it.sourceId + "|" + it.detailsId + "|" + it.episodeNumber }) { entry ->
            AdultHistoryCard(entry) { onResumeHistory(entry) }
        }
    }
}

/** Extensions tab: install/manage the adult extensions — nowhere else. */
@Composable
private fun AdultExtensionsTab(
    liveIds: Set<String>?,
    enabledAdult: Set<String>,
    onToggle: (ExtensionInfo, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(ExtensionsCatalog.adult, key = { it.id }) { info ->
            val live = liveIds?.contains(info.id) ?: true
            val installed = info.id in enabledAdult
            AdultExtensionRow(
                info = info,
                installed = installed,
                live = live,
                onToggle = { onToggle(info, installed) },
            )
        }
    }
}

@Composable
private fun AdultFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Red else SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            color = if (selected) Color.White else TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Full-width adult-history card (poster + title + episode + time ago). */
@Composable
private fun AdultHistoryCard(
    entry: HistoryStore.HistoryEntry,
    onClick: () -> Unit,
) {
    val posterUrl = entry.poster?.let { StreamHubApplication.apiClient.posterUrl(it) }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceNight)
            .clickable(onClick = onClick).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(64.dp).height(88.dp).clip(RoundedCornerShape(10.dp))
                .background(SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    entry.title.take(1).uppercase(), color = TextMuted,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(
                (if (entry.episodeNumber > 0) "Episode ${entry.episodeNumber}" else "Movie") +
                    " · " + HistoryStore.formatAgo(entry.watchedAtMs),
                color = TextMuted, fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun AdultExtensionRow(
    info: ExtensionInfo,
    installed: Boolean,
    live: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp)).background(SurfaceNight).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                info.name.take(1).uppercase(), color = TextPrimary,
                fontSize = 20.sp, fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (live) Color(0xFF46D369).copy(alpha = 0.18f) else TextMuted.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        if (live) "Working" else "Unavailable",
                        color = if (live) Color(0xFF46D369) else TextMuted,
                        fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text("${info.language.replaceFirstChar { it.uppercase() }} · ${info.category}", color = TextMuted, fontSize = 11.sp)
            Text(info.description, color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onToggle,
            enabled = live,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (installed) Color(0xFF46D369) else Red,
                contentColor = Color.White,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(
                if (installed) Icons.Default.Check else Icons.Default.Download,
                contentDescription = null, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (installed) "Installed" else "Install", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
