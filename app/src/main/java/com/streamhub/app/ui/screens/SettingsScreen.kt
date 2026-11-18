package com.streamhub.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.AdultGate
import com.streamhub.app.data.StorageStats
import com.streamhub.app.data.extensions.ExtensionsCatalog
import com.streamhub.app.ui.components.CategoryChips
import com.streamhub.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Professional Settings. No native popups anywhere — every choice is an
 * in-app control.
 *
 * The API is hidden: there is no API/endpoint setting anywhere in the app;
 * the app uses only the build-time base URL (ApiClient.DEFAULT_BASE_URL).
 *
 * Hidden folder (18+): Settings shows ONE row, "Lock". Tapping it asks for
 * the password EVERY time; the correct password opens a separate hidden
 * page ([onOpenHiddenFolder]). Nothing about that page — its name, its
 * contents, how to reach it — is shown or explained anywhere else.
 */
@Composable
fun SettingsScreen(onOpenHiddenFolder: () -> Unit) {
    val context = LocalContext.current
    val settings = StreamHubApplication.settings
    val scope = rememberCoroutineScope()

    val defaultAudio by settings.defaultAudio.collectAsState(initial = "hindi")
    val skipIntro by settings.skipIntro.collectAsState(initial = true)
    val autoNext by settings.autoNext.collectAsState(initial = true)
    val playbackSpeed by settings.playbackSpeed.collectAsState(initial = 1f)

    var lockOpen by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var keyMessage by remember { mutableStateOf<String?>(null) }

    // Storage breakdown (v1.0.3): re-read after a cache clear.
    var storage by remember { mutableStateOf<StorageStats.Breakdown?>(null) }
    var storageTick by remember { mutableStateOf(0) }
    var cacheClearedMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(storageTick) {
        storage = withContext(Dispatchers.IO) { StorageStats.breakdown(context) }
    }

    Column(
        Modifier.fillMaxSize().background(Night).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(bottom = 22.dp),
    ) {
        Text(
            "Settings", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )

        SettingsSectionTitle("Playback")
        SettingsRow("Skip Intro", "Skip the opening automatically when available") {
            Switch(
                checked = skipIntro,
                onCheckedChange = { scope.launch { settings.setSkipIntro(it) } },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Red),
            )
        }
        SettingsRow("Auto Next", "Play the next episode automatically") {
            Switch(
                checked = autoNext,
                onCheckedChange = { scope.launch { settings.setAutoNext(it) } },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Red),
            )
        }
        Text("Playback speed", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.padding(horizontal = 20.dp)) {
            CategoryChips(
                items = listOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x"),
                selected = "${if (playbackSpeed == playbackSpeed.toInt().toFloat()) playbackSpeed.toInt() else playbackSpeed}x",
                onSelected = { label ->
                    scope.launch { settings.setPlaybackSpeed(label.removeSuffix("x").toFloatOrNull() ?: 1f) }
                },
            )
        }
        Text("Default audio", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.padding(horizontal = 20.dp)) {
            CategoryChips(
                items = listOf("Hindi", "English"),
                selected = if (defaultAudio == "hindi") "Hindi" else "English",
                onSelected = { scope.launch { settings.setDefaultAudio(it.lowercase()) } },
            )
        }

        SettingsSectionTitle("Lock")
        // The ONLY entry point to the hidden page: one row, "Lock", no
        // explanation of what is behind it. Tapping it asks for the
        // password every time; the correct password opens the separate
        // hidden page. Wrong password = nothing opens, no hint.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp)).background(SurfaceNight)
                .clickable { lockOpen = !lockOpen; keyMessage = null }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = TextMuted)
            Spacer(Modifier.width(12.dp))
            Text(
                "Lock", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
        }
        if (lockOpen) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it; keyMessage = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Enter password", color = TextMuted, fontSize = 12.sp) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp),
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
            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 20.dp)) {
                Button(
                    onClick = {
                        if (AdultGate.isValidKey(keyInput.trim())) {
                            scope.launch {
                                // Opening the hidden page turns its
                                // extensions ON, so the browse list is
                                // populated the moment the page opens.
                                ExtensionsCatalog.adult.forEach { info ->
                                    StreamHubApplication.extensions.setEnabled(info.id, true)
                                }
                                keyInput = ""
                                keyMessage = null
                                lockOpen = false
                                onOpenHiddenFolder()
                            }
                        } else {
                            keyMessage = "Incorrect password."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Open", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }
        if (keyMessage != null) {
            Text(keyMessage!!, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }

        SettingsSectionTitle("Storage")
        // v1.0.3: storage breakdown + Clear cache. Clearing wipes the
        // image/list/video caches only — downloads are NEVER deleted here.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp)).background(SurfaceNight).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val breakdown = storage
            if (breakdown == null) {
                Text("Measuring storage…", color = TextMuted, fontSize = 13.sp)
            } else {
                StorageRow("Images", StorageStats.format(breakdown.imageCacheBytes))
                StorageRow("Lists", StorageStats.format(breakdown.feedCacheBytes))
                StorageRow("Video cache", StorageStats.format(breakdown.playerCacheBytes))
                StorageRow("Downloads", StorageStats.format(breakdown.downloadsBytes))
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Total", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(StorageStats.format(breakdown.totalBytes), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { StorageStats.clearCaches(context) }
                        StreamHubApplication.feedCache.clearMemory()
                        cacheClearedMessage = "Cache cleared. Downloads were kept."
                        storageTick++
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Clear cache", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            if (cacheClearedMessage != null) {
                Text(cacheClearedMessage!!, color = TextMuted, fontSize = 12.sp)
            }
        }

        SettingsSectionTitle("About")
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp)).background(SurfaceNight).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("StreamHub 1.0.3", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Developed by Mazhar Ansari", color = TextMuted, fontSize = 13.sp)
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://github.com/mazharmnzoor4227-beep".toUri())
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                shape = RoundedCornerShape(10.dp),
            ) { Text("GitHub: mazharmnzoor4227-beep", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun StorageRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextMuted, fontSize = 13.sp)
        Text(value, color = TextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title.uppercase(), color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp)).background(SurfaceNight).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
        control()
    }
}
