package com.streamhub.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * Adult (18+) browsing — reachable ONLY when unlocked with the key
 * (Settings → Adult Content (18+)): as its own bottom-nav "Adult" tab, or
 * from Extensions → Browse Adult. Locked = the tab does not exist at all.
 * Nothing here ever leaks into Home, Search, trending or recommendations.
 *
 * The adult backends are SEARCH-ONLY (their `home()` returns nothing —
 * there is no latest/trending endpoint), so the browse list is a broad
 * search across ALL installed adult extensions: query "a" returns ~30
 * titles with posters per source in under a second (verified live against
 * the API). The search box stays on top for narrowing down; titles open
 * Details and play like any other title (direct HLS via /api/proxy,
 * no embeds). The list is cached, so the section opens instantly after
 * the first load. Unlocking auto-enables every adult extension (see
 * SettingsScreen), so content shows the moment the section is opened.
 */
@Composable
fun AdultScreen(
    onItemClick: (ApiItem) -> Unit,
    onBack: () -> Unit,
    onOpenExtensions: () -> Unit,
    // Hoisted by the app root: grid position survives leaving for
    // Details/Player and coming back.
    gridState: LazyGridState,
) {
    val repository = StreamHubApplication.repository
    val enabledIds by StreamHubApplication.extensions.enabledIds.collectAsState(initial = emptySet())
    val adultIds = remember { ExtensionsCatalog.adult.map { it.id }.toSet() }
    val enabledAdult = enabledIds.intersect(adultIds)
    var query by remember { mutableStateOf("") }
    var browse by remember { mutableStateOf<List<ApiItem>>(emptyList()) }
    var browseLoading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<ApiItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

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
                "Adult (18+)", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
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
        if (loading) {
            Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Red, modifier = Modifier.size(28.dp))
            }
        } else if (enabledAdult.isEmpty()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "No adult extension is installed yet.",
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "Install HentaiHaven in Extensions to browse adult titles here.",
                    color = TextMuted, fontSize = 13.sp,
                )
                Button(
                    onClick = onOpenExtensions,
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Open Extensions", fontWeight = FontWeight.Bold) }
            }
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
