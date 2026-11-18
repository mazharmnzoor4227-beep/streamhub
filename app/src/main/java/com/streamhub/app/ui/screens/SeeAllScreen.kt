package com.streamhub.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhub.app.data.api.ApiItem
import com.streamhub.app.ui.components.PosterCard
import com.streamhub.app.ui.theme.*

/**
 * "See all" grid for one home rail (v1.0.2 — this used to just open the
 * Search tab, whose tall header ate half the screen). Slim sticky header
 * only: back arrow + rail title + count. The grid fills the rest of the
 * screen. Grid state is hoisted by the app root, so leaving for
 * Details/Player and coming back restores the same scroll position.
 */
@Composable
fun SeeAllScreen(
    title: String,
    items: List<ApiItem>,
    gridState: LazyGridState,
    onItemClick: (ApiItem) -> Unit,
    onBack: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(Night).statusBarsPadding(),
    ) {
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
            Text(
                "${items.size} titles", color = TextMuted, fontSize = 12.sp,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
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
