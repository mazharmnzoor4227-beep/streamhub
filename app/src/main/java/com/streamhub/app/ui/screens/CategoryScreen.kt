package com.streamhub.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.api.ApiItem
import com.streamhub.app.ui.components.PosterCard
import com.streamhub.app.ui.theme.*

/**
 * Category grid (v1.0.2): one real category (Anime, Movies, Hollywood,
 * Bollywood, K-Drama, C-Drama, Hindi Dubbed) — items come from the API,
 * cached-first so the grid shows instantly on revisit and refreshes
 * silently in the background. Slim sticky header only (back + title +
 * count); the grid fills the rest of the screen.
 */
@Composable
fun CategoryScreen(
    categoryId: String,
    title: String,
    gridState: LazyGridState,
    onItemClick: (ApiItem) -> Unit,
    onBack: () -> Unit,
) {
    val repository = StreamHubApplication.repository
    var items by remember(categoryId) { mutableStateOf<List<ApiItem>>(emptyList()) }
    var loading by remember(categoryId) { mutableStateOf(true) }

    LaunchedEffect(categoryId) {
        loading = true
        val cached = runCatching { repository.cachedCategoryItems(categoryId) }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) {
            items = cached
            loading = false
            val fresh = runCatching { repository.refreshCategoryItems(categoryId) }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) items = fresh
        } else {
            items = runCatching { repository.refreshCategoryItems(categoryId) }.getOrDefault(emptyList())
            loading = false
        }
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
                title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            if (!loading) {
                Text(
                    "${items.size} titles", color = TextMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
        when {
            loading -> {
                Row(
                    Modifier.fillMaxWidth().padding(22.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = Red, modifier = Modifier.size(28.dp))
                }
            }
            items.isEmpty() -> {
                Text(
                    "Nothing in this category right now. Check your connection and try again.",
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
