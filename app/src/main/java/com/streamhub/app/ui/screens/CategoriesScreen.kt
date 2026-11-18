package com.streamhub.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhub.app.data.api.APP_CATEGORIES
import com.streamhub.app.ui.theme.*

/**
 * Categories tab (v1.0.3) — replaces the Extensions tab. Customers must
 * NEVER see an Extensions option: extensions are pre-installed by Mazhar
 * himself. This tab is the Browse/Categories experience: every tile opens
 * a grid that returns ALL of that category's content from the API
 * (see Repository.categoryItems / categoryRequest — every mapping
 * verified live). The "Browse" tile opens the Type + Country + Year
 * discover screen.
 */
@Composable
fun CategoriesScreen(
    onCategoryClick: (String) -> Unit,
    onBrowseClick: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Night).statusBarsPadding()) {
        Text(
            "Categories", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CategoryTile(
                    title = "Browse",
                    subtitle = "Type · Country · Year",
                    onClick = onBrowseClick,
                )
            }
            items(APP_CATEGORIES) { category ->
                CategoryTile(
                    title = category.title,
                    subtitle = categorySubtitle(category.id),
                    onClick = { onCategoryClick(category.id) },
                )
            }
        }
    }
}

private fun categorySubtitle(id: String): String = when (id) {
    "anime" -> "Anime series & movies"
    "movies" -> "All movies"
    "series" -> "TV series & dramas"
    "hollywood" -> "Hollywood movies"
    "bollywood" -> "Bollywood movies"
    "kdrama" -> "Korean dramas"
    "cdrama" -> "Chinese dramas"
    "hindi" -> "Hindi-dubbed titles"
    else -> "Titles"
}

@Composable
private fun CategoryTile(title: String, subtitle: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(96.dp)
            .clip(RoundedCornerShape(16.dp)).background(SurfaceNight)
            .clickable(onClick = onClick).padding(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        if (title == "Browse") {
            Icon(
                Icons.Default.Tune, contentDescription = null, tint = TextMuted,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}
