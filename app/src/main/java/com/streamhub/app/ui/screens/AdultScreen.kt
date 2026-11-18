package com.streamhub.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.api.ApiItem
import com.streamhub.app.data.extensions.ExtensionInfo
import com.streamhub.app.data.extensions.ExtensionsCatalog
import com.streamhub.app.ui.components.PosterCard
import com.streamhub.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The hidden folder (18+) — a separate page reachable ONLY from
 * Settings → Lock → password, every time. It is NOT a tab, NOT in
 * Extensions, NOT on Home, and nothing in the app names or explains it.
 * Everything adult lives ONLY here: the browse/discover list (across ALL
 * installed adult extensions) + search + the adult extensions themselves
 * (install/manage). Nothing here ever leaks into Home, Search, trending
 * or recommendations.
 *
 * The adult backends are SEARCH-ONLY (their `home()` returns nothing —
 * there is no latest/trending endpoint), so the browse list is a broad
 * search across ALL installed adult extensions: query "a" returns ~30
 * titles with posters per source in under a second (verified live against
 * the API). The search box stays on top for narrowing down; titles open
 * Details and play like any other title (direct HLS via /api/proxy,
 * no embeds). The list is cached, so the page opens instantly after
 * the first load. Opening the page auto-enables every adult extension
 * (see SettingsScreen), so content shows the moment the page opens.
 */
@Composable
fun AdultScreen(
    onItemClick: (ApiItem) -> Unit,
    onBack: () -> Unit,
    // Hoisted by the app root: grid position survives leaving for
    // Details/Player and coming back.
    gridState: LazyGridState,
) {
    val repository = StreamHubApplication.repository
    val scope = rememberCoroutineScope()
    val enabledIds by StreamHubApplication.extensions.enabledIds.collectAsState(initial = emptySet())
    val adultIds = remember { ExtensionsCatalog.adult.map { it.id }.toSet() }
    val enabledAdult = enabledIds.intersect(adultIds)
    var query by remember { mutableStateOf("") }
    var browse by remember { mutableStateOf<List<ApiItem>>(emptyList()) }
    var browseLoading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<ApiItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var liveIds by remember { mutableStateOf<Set<String>?>(null) }

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
    val shown = if (showingSearch) results else browse
    val loading = if (showingSearch) searching else browseLoading

    Column(Modifier.fillMaxSize().background(Night).statusBarsPadding().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                "Hidden Folder", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Adult anime · English subtitles · 18+ only",
            color = TextMuted, fontSize = 12.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
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
        // Extensions live on this page too: install/manage the adult
        // extensions here — nowhere else in the app shows them.
        Text(
            "Extensions", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        ExtensionsCatalog.adult.forEach { info ->
            val live = liveIds?.contains(info.id) ?: true
            val installed = info.id in enabledAdult
            AdultExtensionRow(
                info = info,
                installed = installed,
                live = live,
                onToggle = {
                    scope.launch {
                        StreamHubApplication.extensions.setEnabled(info.id, !installed)
                    }
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Red, modifier = Modifier.size(28.dp))
            }
        } else if (enabledAdult.isEmpty()) {
            Text(
                "No adult extension is installed yet — install one above to see titles here.",
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
            }
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
