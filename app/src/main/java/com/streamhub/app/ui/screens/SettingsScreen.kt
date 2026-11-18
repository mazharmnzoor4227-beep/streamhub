package com.streamhub.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.streamhub.app.data.api.ApiClient
import com.streamhub.app.data.extensions.ExtensionsCatalog
import com.streamhub.app.ui.components.CategoryChips
import com.streamhub.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Professional Settings. No native popups anywhere — every choice is an
 * in-app control. Adult key entry lives here:
 * Settings → Adult Content (18+) → enter key → Unlock.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = StreamHubApplication.settings
    val scope = rememberCoroutineScope()

    val apiBaseUrl by settings.apiBaseUrl.collectAsState(initial = ApiClient.DEFAULT_BASE_URL)
    val defaultAudio by settings.defaultAudio.collectAsState(initial = "hindi")
    val skipIntro by settings.skipIntro.collectAsState(initial = true)
    val autoNext by settings.autoNext.collectAsState(initial = true)
    val playbackSpeed by settings.playbackSpeed.collectAsState(initial = 1f)
    val adultUnlocked by settings.adultUnlocked.collectAsState(initial = false)

    var baseUrlDraft by remember(apiBaseUrl) { mutableStateOf(apiBaseUrl) }
    var baseUrlSaved by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var keyMessage by remember { mutableStateOf<String?>(null) }

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

        SettingsSectionTitle("Server")
        Text("API base URL", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        OutlinedTextField(
            value = baseUrlDraft,
            onValueChange = { baseUrlDraft = it; baseUrlSaved = false },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            singleLine = true,
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
                    scope.launch {
                        settings.setApiBaseUrl(baseUrlDraft)
                        baseUrlSaved = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Save", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            if (baseUrlSaved) {
                Spacer(Modifier.width(10.dp))
                Text("Saved.", color = Color(0xFF46D369), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }

        SettingsSectionTitle("Adult Content (18+)")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp)).background(SurfaceNight).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (adultUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                contentDescription = null, tint = TextMuted,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (adultUnlocked) "Unlocked" else "Locked",
                    color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
                Text(
                    if (adultUnlocked) "The adult section in Extensions is open on this device."
                    else "Enter the key to unlock the adult section in Extensions.",
                    color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!adultUnlocked) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it; keyMessage = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Enter key", color = TextMuted, fontSize = 12.sp) },
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
                                settings.setAdultUnlocked(true)
                                // Unlocking turns the adult section ON: enable
                                // every adult extension at once, so the adult
                                // browse list is populated the moment the
                                // section is opened (no empty section).
                                ExtensionsCatalog.adult.forEach { info ->
                                    StreamHubApplication.extensions.setEnabled(info.id, true)
                                }
                                keyInput = ""
                                keyMessage = "Unlocked. The adult section is now open in Extensions."
                            }
                        } else {
                            keyMessage = "Incorrect key. The adult section stays locked."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Unlock", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        } else {
            Row(Modifier.padding(horizontal = 20.dp)) {
                Button(
                    onClick = { scope.launch { settings.setAdultUnlocked(false); keyMessage = null } },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Lock again", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }
        if (keyMessage != null) {
            Text(keyMessage!!, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }

        SettingsSectionTitle("About")
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp)).background(SurfaceNight).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("StreamHub 1.0.2", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
