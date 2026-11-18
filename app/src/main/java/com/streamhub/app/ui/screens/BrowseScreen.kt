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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.api.ApiItem
import com.streamhub.app.data.api.BROWSE_COUNTRIES
import com.streamhub.app.data.api.BrowseFilter
import com.streamhub.app.ui.components.PosterCard
import com.streamhub.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Browse / Discover (v1.0.2): ONE screen for Type + Country + Year.
 * - Type: Anime → all anime, Movies → all movies, Series → all series
 *   (semantic ?type= on the API — source-based, not the unreliable
 *   per-item type: K-dramas arrive labelled "anime").
 * - Country: USA, Germany, Korea, Japan, China, India — every one verified
 *   live to return real content (Germany needs pages 1–3 merged: it
 *   returns 0 on pages 1–2 of latest — see Repository.browseFiltered).
 * - Year: from-year / to-year (e.g. 2002–2005) on the item year.
 * Only filter options that return real content are shown; an empty result
 * says so plainly instead of showing a blank grid. Cached-first (instant
 * on revisit, silent refresh); grid state is hoisted by the app root so
 * back from Details restores the same scroll position.
 */
@Composable
fun BrowseScreen(
    filter: BrowseFilter,
    onFilterChange: (BrowseFilter) -> Unit,
    gridState: LazyGridState,
    onItemClick: (ApiItem) -> Unit,
    onBack: () -> Unit,
) {
    val repository = StreamHubApplication.repository
    var items by remember(filter) { mutableStateOf<List<ApiItem>>(emptyList()) }
    var loading by remember(filter) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(filter) {
        loading = true
        val cached = runCatching { repository.cachedBrowseFiltered(filter) }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) {
            items = cached
            loading = false
            val fresh = runCatching { repository.refreshBrowseFiltered(filter) }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) items = fresh
        } else {
            items = runCatching { repository.refreshBrowseFiltered(filter) }.getOrDefault(emptyList())
            loading = false
        }
    }

    // A new filter starts at the top of the grid.
    LaunchedEffect(filter) {
        scope.launch { gridState.scrollToItem(0) }
    }

    var yearFromDraft by remember(filter) { mutableStateOf(filter.yearFrom?.toString() ?: "") }
    var yearToDraft by remember(filter) { mutableStateOf(filter.yearTo?.toString() ?: "") }

    fun applyYear() {
        val from = yearFromDraft.trim().toIntOrNull()
        val to = yearToDraft.trim().toIntOrNull()
        onFilterChange(filter.copy(yearFrom = from, yearTo = to))
    }

    Column(Modifier.fillMaxSize().background(Night).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                "Browse", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            if (!loading) {
                Text(
                    "${items.size} titles", color = TextMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterSection("Type") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip("All", filter.type == null) { onFilterChange(filter.copy(type = null)) }
                    FilterChip("Anime", filter.type == "anime") { onFilterChange(filter.copy(type = "anime")) }
                    FilterChip("Movies", filter.type == "movie") { onFilterChange(filter.copy(type = "movie")) }
                    FilterChip("Series", filter.type == "series") { onFilterChange(filter.copy(type = "series")) }
                }
            }
            FilterSection("Country") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip("All", filter.country == null) { onFilterChange(filter.copy(country = null)) }
                    BROWSE_COUNTRIES.forEach { country ->
                        FilterChip(country, filter.country == country) {
                            onFilterChange(filter.copy(country = country))
                        }
                    }
                }
            }
            FilterSection("Year") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = yearFromDraft,
                        onValueChange = { yearFromDraft = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("From", fontSize = 11.sp) },
                        placeholder = { Text("2002", fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Red,
                            unfocusedBorderColor = DividerColor,
                            focusedLabelColor = TextMuted,
                            unfocusedLabelColor = TextMuted,
                        ),
                        modifier = Modifier.width(110.dp),
                    )
                    Text("–", color = TextMuted, fontSize = 16.sp)
                    OutlinedTextField(
                        value = yearToDraft,
                        onValueChange = { yearToDraft = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("To", fontSize = 11.sp) },
                        placeholder = { Text("2005", fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Red,
                            unfocusedBorderColor = DividerColor,
                            focusedLabelColor = TextMuted,
                            unfocusedLabelColor = TextMuted,
                        ),
                        modifier = Modifier.width(110.dp),
                    )
                    Button(
                        onClick = { applyYear() },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                    ) { Text("Apply", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    if (filter.yearFrom != null || filter.yearTo != null) {
                        Text(
                            "Clear", color = TextMuted, fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                yearFromDraft = ""
                                yearToDraft = ""
                                onFilterChange(filter.copy(yearFrom = null, yearTo = null))
                            }.padding(6.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        when {
            loading && items.isEmpty() -> {
                Row(
                    Modifier.fillMaxWidth().padding(22.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = Red, modifier = Modifier.size(28.dp))
                }
            }
            items.isEmpty() -> {
                Text(
                    "No titles found for this filter. Try a different type, country or year.",
                    color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(22.dp),
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(items, key = { (it.source ?: "") + "|" + it.id }) { item ->
                        PosterCard(item, { onItemClick(item) }, modifier = Modifier.fillMaxWidth(), artHeight = 168.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Red else SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            color = if (selected) androidx.compose.ui.graphics.Color.White else TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
