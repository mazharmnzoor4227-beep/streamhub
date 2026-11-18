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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.extensions.ExtensionInfo
import com.streamhub.app.data.extensions.ExtensionsCatalog
import com.streamhub.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Extensions — the core screen, in the main menu.
 * One extension = one API source. "Install" enables that source locally;
 * only enabled sources are queried for home/search. Only verified-working
 * sources appear. The 18+ section is separate, at the bottom, locked by
 * default — it opens only after the key is entered in Settings.
 */
@Composable
fun ExtensionsScreen(
    onBrowseAdult: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val extensions = StreamHubApplication.extensions
    val repository = StreamHubApplication.repository
    val settings = StreamHubApplication.settings
    val scope = rememberCoroutineScope()
    val enabledIds by extensions.enabledIds.collectAsState(initial = emptySet())
    val adultUnlocked by settings.adultUnlocked.collectAsState(initial = false)
    var liveIds by remember { mutableStateOf<Set<String>?>(null) }

    // Sources list — cached first (instant), silent background refresh.
    // The spinner only shows on a cold start with an empty cache.
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

    Column(
        Modifier.fillMaxSize().background(Night).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(bottom = 22.dp),
    ) {
        Text(
            "Extensions", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
        Text(
            "Install an extension to add its catalogue. Installed extensions feed Home, Search and Details.",
            color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp),
        )

        if (liveIds == null) {
            Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Red, modifier = Modifier.size(28.dp))
            }
        }

        ExtensionsCatalog.standard.forEach { info ->
            val live = liveIds?.contains(info.id) ?: true
            val installed = info.id in enabledIds
            ExtensionRow(
                info = info,
                installed = installed,
                live = live,
                onToggle = {
                    scope.launch { extensions.setEnabled(info.id, !installed) }
                },
            )
        }

        // ---- 18+ section: separate, locked by default ----
        Text(
            "Adult (18+)", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 4.dp),
        )
        Text(
            "Kept separate so kids can't use it. Unlock it with the key in Settings.",
            color = TextMuted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
        )
        if (!adultUnlocked) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(14.dp)).background(SurfaceNight).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = TextMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Locked", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Enter the key in Settings to unlock.", color = TextMuted, fontSize = 12.sp)
                }
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        } else {
            ExtensionsCatalog.adult.forEach { info ->
                val live = liveIds?.contains(info.id) ?: true
                val installed = info.id in enabledIds
                ExtensionRow(
                    info = info,
                    installed = installed,
                    live = live,
                    onToggle = {
                        scope.launch { extensions.setEnabled(info.id, !installed) }
                    },
                )
            }
            Button(
                onClick = onBrowseAdult,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red),
            ) { Text("Browse Adult", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ExtensionRow(
    info: ExtensionInfo,
    installed: Boolean,
    live: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
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
