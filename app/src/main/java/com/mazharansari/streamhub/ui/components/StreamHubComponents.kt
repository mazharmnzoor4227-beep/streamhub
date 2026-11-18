package com.mazharansari.streamhub.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mazharansari.streamhub.data.api.ApiItem
import com.mazharansari.streamhub.ui.theme.*

/** App brand header: red logo tile + STREAMHUB wordmark + search action. */
@Composable
fun BrandHeader(onSearchClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Red),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("STREAMHUB", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
            Text("MOVIES · ANIME · DRAMAS", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.1.sp)
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(SurfaceElevated).clickable(onClick = onSearchClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = TextPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun SectionHeading(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(
                action, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onAction?.invoke() },
            )
        }
    }
}

@Composable
fun CategoryChips(
    items: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val active = item == selected
            Box(
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (active) Red else SurfaceElevated)
                    .clickable { onSelected(item) }
                    .padding(horizontal = 15.dp, vertical = 9.dp),
            ) {
                Text(item, color = if (active) Color.White else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Poster card for one API item. Real poster when present, otherwise a branded
 * red-gradient title card — never a blank card.
 */
@Composable
fun PosterCard(item: ApiItem, onClick: () -> Unit, modifier: Modifier = Modifier, artHeight: androidx.compose.ui.unit.Dp = 190.dp) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        PosterArtwork(item = item, modifier = Modifier.fillMaxWidth().height(artHeight))
        Spacer(Modifier.height(8.dp))
        Text(item.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(
            item.year?.toString(),
            item.type?.replaceFirstChar { it.uppercase() },
        ).joinToString("  ·  ")
        if (sub.isNotBlank()) {
            Text(sub, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PosterArtwork(item: ApiItem, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFE50914), Color(0xFF7A0B12), Color(0xFF141414)))),
    ) {
        if (!item.poster.isNullOrBlank()) {
            AsyncImage(
                model = item.poster,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                item.title.uppercase(), color = Color.White, fontSize = 17.sp,
                lineHeight = 18.sp, fontWeight = FontWeight.Black, maxLines = 3,
            )
            item.type?.takeIf { it.isNotBlank() }?.let {
                Text(it.uppercase(), color = Color.White.copy(alpha = 0.75f), fontSize = 8.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
fun SmallTag(text: String, color: Color = Red) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(text, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    }
}
