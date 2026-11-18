package com.mazharansari.streamhub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.mazharansari.streamhub.data.api.ApiDetails
import com.mazharansari.streamhub.data.api.ApiEpisode
import com.mazharansari.streamhub.data.api.ApiItem
import com.mazharansari.streamhub.download.DownloadHelper
import com.mazharansari.streamhub.ui.player.PlayerScreen
import com.mazharansari.streamhub.ui.screens.AdultScreen
import com.mazharansari.streamhub.ui.screens.DetailsScreen
import com.mazharansari.streamhub.ui.screens.DownloadsScreen
import com.mazharansari.streamhub.ui.screens.ExtensionsScreen
import com.mazharansari.streamhub.ui.screens.HomeScreen
import com.mazharansari.streamhub.ui.screens.SearchScreen
import com.mazharansari.streamhub.ui.screens.SettingsScreen
import com.mazharansari.streamhub.ui.theme.*

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SEARCH("Search", Icons.Default.Search),
    EXTENSIONS("Extensions", Icons.Default.Extension),
    DOWNLOADS("Downloads", Icons.Default.Download),
    SETTINGS("Settings", Icons.Default.Settings),
}

/** What the player is playing: a streamed episode, or a downloaded file. */
private sealed interface PlayTarget {
    data class Online(val details: ApiDetails, val episode: ApiEpisode, val sourceId: String) : PlayTarget
    data class Offline(val item: DownloadHelper.DownloadItem) : PlayTarget
}

@OptIn(UnstableApi::class)
@Composable
fun StreamHubApp() {
    var activeTab by remember { mutableStateOf(MainTab.HOME) }
    // Selected title (item + its source id) shown in Details.
    var selectedItem by remember { mutableStateOf<Pair<ApiItem, String>?>(null) }
    var playTarget by remember { mutableStateOf<PlayTarget?>(null) }
    var showAdult by remember { mutableStateOf(false) }

    val playing = playTarget
    val inPlayer = playing != null
    val inDetails = selectedItem != null && playing == null

    BackHandler(enabled = inPlayer || selectedItem != null || showAdult) {
        when {
            inPlayer -> playTarget = null
            selectedItem != null -> selectedItem = null
            showAdult -> showAdult = false
        }
    }

    // Next/Previous episode for the online player, from the current details.
    val online = playing as? PlayTarget.Online
    val allEpisodes = online?.details?.seasons.orEmpty().flatMap { it.episodes.orEmpty() }
    val currentIndex = allEpisodes.indexOfFirst { it.number == online?.episode?.number }
    val nextEpisode = allEpisodes.getOrNull(currentIndex + 1).takeIf { currentIndex >= 0 }
    val prevEpisode = allEpisodes.getOrNull(currentIndex - 1).takeIf { currentIndex > 0 }

    fun openItem(item: ApiItem) {
        selectedItem = item to (item.source ?: "")
    }

    Scaffold(
        containerColor = Night,
        bottomBar = {
            if (!inPlayer && selectedItem == null && !showAdult) {
                BottomNavigationBar(activeTab = activeTab, onTabSelected = { activeTab = it })
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).background(Night)) {
            when {
                playing is PlayTarget.Online -> {
                    val target = playing as PlayTarget.Online
                    PlayerScreen(
                        details = target.details,
                        episode = target.episode,
                        sourceId = target.sourceId,
                        onBack = { playTarget = null },
                        onNextEpisode = nextEpisode?.let { ep ->
                            { playTarget = PlayTarget.Online(target.details, ep, target.sourceId) }
                        },
                        onPrevEpisode = prevEpisode?.let { ep ->
                            { playTarget = PlayTarget.Online(target.details, ep, target.sourceId) }
                        },
                    )
                }
                playing is PlayTarget.Offline -> {
                    val target = playing as PlayTarget.Offline
                    PlayerScreen(
                        details = null,
                        episode = null,
                        sourceId = target.item.sourceId,
                        offlineItem = target.item,
                        onBack = { playTarget = null },
                    )
                }
                // Details first: an item picked inside the adult section must
                // open Details (back from Details returns to the adult list).
                selectedItem != null -> {
                    val (item, sourceId) = selectedItem!!
                    DetailsScreen(
                        item = item,
                        sourceId = sourceId,
                        onBack = { selectedItem = null },
                        onPlay = { details, episode, src ->
                            playTarget = PlayTarget.Online(details, episode, src)
                        },
                    )
                }
                showAdult -> {
                    AdultScreen(
                        onItemClick = { openItem(it) },
                        onBack = { showAdult = false },
                    )
                }
                else -> when (activeTab) {
                    MainTab.HOME -> HomeScreen(
                        onItemClick = { openItem(it) },
                        onSearchClick = { activeTab = MainTab.SEARCH },
                        onSeeAll = { activeTab = MainTab.SEARCH },
                    )
                    MainTab.SEARCH -> SearchScreen(onItemClick = { openItem(it) })
                    MainTab.EXTENSIONS -> ExtensionsScreen(
                        onBrowseAdult = { showAdult = true },
                        onOpenSettings = { activeTab = MainTab.SETTINGS },
                    )
                    MainTab.DOWNLOADS -> DownloadsScreen(
                        onPlayOffline = { item -> playTarget = PlayTarget.Offline(item) },
                    )
                    MainTab.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun BottomNavigationBar(activeTab: MainTab, onTabSelected: (MainTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(SurfaceNight).navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { tab ->
            val selected = tab == activeTab
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(13.dp))
                    .clickable { onTabSelected(tab) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    tab.icon, contentDescription = tab.label,
                    tint = if (selected) Red else TextMuted, modifier = Modifier.size(22.dp),
                )
                Text(
                    tab.label,
                    color = if (selected) TextPrimary else TextMuted, fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
