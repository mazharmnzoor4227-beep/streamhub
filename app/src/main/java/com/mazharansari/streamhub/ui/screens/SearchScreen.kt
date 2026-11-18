package com.mazharansari.streamhub.ui.screens

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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.streamhub.StreamHubApplication
import com.mazharansari.streamhub.data.api.ApiItem
import com.mazharansari.streamhub.data.extensions.ExtensionsCatalog
import com.mazharansari.streamhub.ui.components.CategoryChips
import com.mazharansari.streamhub.ui.components.PosterCard
import com.mazharansari.streamhub.ui.components.SectionHeading
import com.mazharansari.streamhub.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Search: debounced live search across the enabled extensions.
 * Blank query shows what's popular right now (real API items, never demo).
 * Adult sources are never searched here (Repository filters them out).
 */
@Composable
fun SearchScreen(onItemClick: (ApiItem) -> Unit) {
    val repository = StreamHubApplication.repository
    val apiClient = StreamHubApplication.apiClient
    // Enabled extensions + adult ids: the blank-query Popular rail comes
    // straight from ApiClient, so filter it here (Repository search results
    // are already filtered). Adult sources never show in Search.
    val enabledIds by StreamHubApplication.extensions.enabledIds.collectAsState(
        initial = ExtensionsCatalog.verifiedIds,
    )
    val adultIds = remember { ExtensionsCatalog.adult.map { it.id }.toSet() }
    var query by remember { mutableStateOf("") }
    var langFilter by remember { mutableStateOf("All") }
    var typeFilter by remember { mutableStateOf("All") }
    var results by remember { mutableStateOf<List<ApiItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var popular by remember { mutableStateOf<List<ApiItem>>(emptyList()) }

    val lang = when (langFilter) {
        "Hindi Dub" -> "hindi"
        "English" -> "english"
        else -> "all"
    }

    // Popular rail for the blank-query state.
    LaunchedEffect(Unit) {
        popular = runCatching { apiClient.trending(lang = "all") }
            .getOrNull()?.results.orEmpty()
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

    Column(
        Modifier.fillMaxSize().background(Night).statusBarsPadding().padding(top = 8.dp),
    ) {
        Text(
            "Search", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "Find movies, anime and dramas", color = TextMuted, fontSize = 12.sp,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 18.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
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
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(horizontal = 18.dp)) {
            CategoryChips(
                items = listOf("All", "Hindi Dub", "English"),
                selected = langFilter,
                onSelected = { langFilter = it },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 18.dp)) {
            CategoryChips(
                items = listOf("All", "Movies", "Anime"),
                selected = typeFilter,
                onSelected = { typeFilter = it },
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            SectionHeading(if (query.isBlank()) "Popular on StreamHub" else "Search results")
        }
        if (searching) {
            Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Red, modifier = Modifier.size(28.dp))
            }
        } else if (filtered.isEmpty()) {
            Text(
                if (query.isBlank()) "Popular titles will appear here once loaded."
                else "No titles found. Try another search.",
                color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(22.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(filtered, key = { (it.source ?: "") + "|" + it.id }) { item ->
                    PosterCard(item, { onItemClick(item) }, modifier = Modifier.fillMaxWidth(), artHeight = 168.dp)
                }
            }
        }
    }
}
