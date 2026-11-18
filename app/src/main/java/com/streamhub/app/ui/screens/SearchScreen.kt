package com.streamhub.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.api.ApiItem
import com.streamhub.app.data.extensions.ExtensionsCatalog
import com.streamhub.app.ui.components.PosterCard
import com.streamhub.app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Search: debounced live search across the enabled extensions.
 * Blank query shows what's popular right now (real API items, never demo).
 * Adult sources are never searched here (Repository filters them out).
 *
 * SLIM FIXED HEADER (v1.0.2): the old header — big 27sp title + subtitle +
 * search box + TWO stacked chip rows + a full-width heading — ate a third
 * to a half of the portrait screen and never scrolled away. Now the header
 * is just a small title, the search box and ONE horizontally scrollable
 * chip row (~150dp incl. status bar), pinned above the grid; the section
 * heading scrolls away with the grid, so the content gets the screen.
 * (LazyGridScope has no stickyHeader — only LazyListScope does — so the
 * header lives in a Column above the LazyVerticalGrid instead.)
 *
 * Query + filters + grid state are hoisted by the app root, so leaving for
 * Details/Player and coming back restores the same query, results and
 * scroll position instead of resetting to the top.
 */
@Composable
fun SearchScreen(
    onItemClick: (ApiItem) -> Unit,
    gridState: LazyGridState,
    query: String,
    onQueryChange: (String) -> Unit,
    langFilter: String,
    onLangFilterChange: (String) -> Unit,
    typeFilter: String,
    onTypeFilterChange: (String) -> Unit,
) {
    val repository = StreamHubApplication.repository
    // Enabled extensions + adult ids: the blank-query Popular rail comes
    // straight from ApiClient, so filter it here (Repository search results
    // are already filtered). Adult sources never show in Search.
    val enabledIds by StreamHubApplication.extensions.enabledIds.collectAsState(
        initial = ExtensionsCatalog.verifiedIds,
    )
    val adultIds = remember { ExtensionsCatalog.adult.map { it.id }.toSet() }
    var results by remember { mutableStateOf<List<ApiItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var popular by remember { mutableStateOf<List<ApiItem>>(emptyList()) }

    val lang = when (langFilter) {
        "Hindi Dub" -> "hindi"
        "English" -> "english"
        else -> "all"
    }

    // Popular rail for the blank-query state — cached first (instant),
    // silent background refresh. No spinner when cached data exists.
    LaunchedEffect(Unit) {
        val cached = repository.cachedTrending()
        if (cached.isNotEmpty()) {
            popular = cached
            val fresh = runCatching { repository.refreshTrending() }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) popular = fresh
        } else {
            popular = runCatching { repository.refreshTrending() }.getOrDefault(emptyList())
        }
    }

    // Debounced search.
    LaunchedEffect(query, lang) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(500)
        searching = true
        results = runCatching { repository.search(query.trim(), lang) }.getOrDefault(emptyList())
        searching = false
    }

    val shown = (if (query.isBlank()) popular else results)
        .filter { item ->
            val src = item.source
            src == null || (src in enabledIds && src !in adultIds)
        }
    val filtered = shown.filter { item ->
        when (typeFilter) {
            "Movies" -> item.type.equals("movie", ignoreCase = true)
            "Anime" -> item.type.equals("series", ignoreCase = true) ||
                item.type.equals("anime", ignoreCase = true)
            else -> true
        }
    }

    Column(Modifier.fillMaxSize().background(Night)) {
        // Slim fixed header: pinned at the top while the grid scrolls
        // beneath it. ~150dp incl. status bar — content gets the screen.
        Column(
            Modifier.fillMaxWidth()
                .statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        ) {
                Text(
                    "Search", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search movies, anime, dramas...", color = TextMuted, fontSize = 12.sp) },
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
                Spacer(Modifier.height(8.dp))
                // ONE horizontally scrollable chip row (was two stacked rows):
                // language chips, divider, type chips.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("All", "Hindi Dub", "English").forEach { item ->
                        FilterChip(item, item == langFilter) { onLangFilterChange(item) }
                    }
                    Box(
                        Modifier.width(1.dp).height(20.dp).background(DividerColor),
                    )
                    listOf("All", "Movies", "Anime").forEach { item ->
                        FilterChip(item, item == typeFilter) { onTypeFilterChange(item) }
                    }
                }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        // Small section heading — scrolls away with the grid, not pinned.
        item(span = { GridItemSpan(3) }) {
            Text(
                if (query.isBlank()) "Popular on StreamHub" else "Search results",
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (searching) {
            item(span = { GridItemSpan(3) }) {
                Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Red, modifier = Modifier.size(28.dp))
                }
            }
        } else if (filtered.isEmpty()) {
            item(span = { GridItemSpan(3) }) {
                Text(
                    if (query.isBlank()) "Popular titles will appear here once loaded."
                    else "No titles found. Try another search.",
                    color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(22.dp),
                )
            }
        } else {
            items(filtered, key = { (it.source ?: "") + "|" + it.id }) { item ->
                PosterCard(item, { onItemClick(item) }, modifier = Modifier.fillMaxWidth(), artHeight = 168.dp)
            }
        }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Red else SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
    ) {
        Text(
            text, color = if (selected) TextPrimary else TextMuted,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}
