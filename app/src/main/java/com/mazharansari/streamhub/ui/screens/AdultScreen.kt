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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.streamhub.StreamHubApplication
import com.mazharansari.streamhub.data.api.ApiItem
import com.mazharansari.streamhub.ui.components.PosterCard
import com.mazharansari.streamhub.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Adult (18+) browsing — reachable ONLY from the unlocked adult section of
 * the Extensions screen (menu path: Extensions → Adult (18+) → Browse Adult,
 * after unlocking with the key in Settings → Adult Content (18+)).
 * Searches the HentaiHaven source directly; nothing here ever leaks into
 * Home, Search, trending or recommendations.
 */
@Composable
fun AdultScreen(onItemClick: (ApiItem) -> Unit, onBack: () -> Unit) {
    val apiClient = StreamHubApplication.apiClient
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ApiItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(500)
        searching = true
        results = runCatching {
            apiClient.search(q = query.trim(), lang = "all", source = "hentaihaven")
        }.getOrNull()?.results.orEmpty()
        searching = false
    }

    Column(Modifier.fillMaxSize().background(Night).statusBarsPadding().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                "Adult (18+)", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "HentaiHaven · English subtitles · 18+ only",
            color = TextMuted, fontSize = 12.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 16.dp),
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
        Spacer(Modifier.height(18.dp))
        if (searching) {
            Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Red, modifier = Modifier.size(28.dp))
            }
        } else if (query.isNotBlank() && results.isEmpty()) {
            Text("No titles found. Try another search.", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(22.dp))
        } else if (query.isBlank()) {
            Text(
                "Search above to browse adult titles. Nothing here appears on Home or in Search.",
                color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(22.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(results, key = { it.id }) { item ->
                    PosterCard(item, { onItemClick(item) }, modifier = Modifier.fillMaxWidth(), artHeight = 168.dp)
                }
            }
        }
    }
}
