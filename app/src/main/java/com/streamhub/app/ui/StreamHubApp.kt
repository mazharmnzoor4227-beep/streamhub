package com.streamhub.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.api.ApiDetails
import com.streamhub.app.data.api.ApiEpisode
import com.streamhub.app.data.api.ApiItem
import com.streamhub.app.data.api.APP_CATEGORIES
import com.streamhub.app.data.api.HomeSection
import com.streamhub.app.download.DownloadHelper
import com.streamhub.app.ui.player.PlayerScreen
import com.streamhub.app.ui.screens.AdultScreen
import com.streamhub.app.ui.screens.CategoryScreen
import com.streamhub.app.ui.screens.DetailsScreen
import com.streamhub.app.ui.screens.DownloadsScreen
import com.streamhub.app.ui.screens.ExtensionsScreen
import com.streamhub.app.ui.screens.HomeScreen
import com.streamhub.app.ui.screens.SearchScreen
import com.streamhub.app.ui.screens.SeeAllScreen
import com.streamhub.app.ui.screens.SettingsScreen
import com.streamhub.app.ui.theme.*
import kotlinx.coroutines.launch

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SEARCH("Search", Icons.Default.Search),
    EXTENSIONS("Extensions", Icons.Default.Extension),
    DOWNLOADS("Downloads", Icons.Default.Download),
    ADULT("Adult", Icons.Default.Lock),
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
    var seeAllSection by remember { mutableStateOf<HomeSection?>(null) }
    var categoryId by remember { mutableStateOf<String?>(null) }

    // Key gate: the Adult tab exists ONLY when unlocked. Locked = the tab,
    // the section and the category are completely invisible.
    val adultUnlocked by StreamHubApplication.settings.adultUnlocked.collectAsState(initial = false)

    // ---- Hoisted scroll / list state (v1.0.2, scroll restore) -------------
    // These live at the app root, so they survive leaving a list for
    // Details/Player and coming back: the list is at the SAME position,
    // not reset to the top. Screens below must use the state passed in
    // here and never create their own.
    val homeScrollState = rememberScrollState()
    val detailsScrollState = rememberScrollState()
    val searchGridState = rememberLazyGridState()
    val seeAllGridState = rememberLazyGridState()
    val adultGridState = rememberLazyGridState()
    val categoryGridState = rememberLazyGridState()

    // Search query + filters are hoisted too: without them, returning to
    // Search would reset to the Popular list and the restored scroll
    // position would point at a different list.
    var searchQuery by remember { mutableStateOf("") }
    var searchLangFilter by remember { mutableStateOf("All") }
    var searchTypeFilter by remember { mutableStateOf("All") }

    val scope = rememberCoroutineScope()

    val playing = playTarget
    val inPlayer = playing != null
    val inDetails = selectedItem != null && playing == null
    val inOverlay = showAdult || seeAllSection != null || categoryId != null

    BackHandler(enabled = inPlayer || selectedItem != null || inOverlay) {
        when {
            inPlayer -> playTarget = null
            selectedItem != null -> selectedItem = null
            showAdult -> showAdult = false
            seeAllSection != null -> seeAllSection = null
            categoryId != null -> categoryId = null
        }
    }

    // Next/Previous episode for the online player, from the current details.
    val online = playing as? PlayTarget.Online
    val allEpisodes = online?.details?.seasons.orEmpty().flatMap { it.episodes.orEmpty() }
    val currentIndex = allEpisodes.indexOfFirst { it.number == online?.episode?.number }
    val nextEpisode = allEpisodes.getOrNull(currentIndex + 1).takeIf { currentIndex >= 0 }
    val prevEpisode = allEpisodes.getOrNull(currentIndex - 1).takeIf { currentIndex > 0 }

    fun openItem(item: ApiItem) {
        val newPair = item to (item.source ?: "")
        // A different title starts at the top; returning from the player
        // to the SAME title keeps its scroll position (selectedItem is
        // unchanged on that path, so no reset happens).
        if (selectedItem != newPair) {
            scope.launch { detailsScrollState.scrollTo(0) }
        }
        selectedItem = newPair
    }

    fun openCategory(id: String) {
        if (id == "adult") {
            showAdult = true
        } else {
            // A different category starts at the top; the same category
            // keeps its position (e.g. back from Details).
            if (categoryId != id) {
                scope.launch { categoryGridState.scrollToItem(0) }
            }
            categoryId = id
        }
    }

    // If the Adult tab is showing while locked (cannot happen through the
    // UI — the tab is hidden when locked), fall back to Home.
    val effectiveTab = if (activeTab == MainTab.ADULT && !adultUnlocked) MainTab.HOME else activeTab

    Scaffold(
        containerColor = Night,
        bottomBar = {
            if (!inPlayer && selectedItem == null && !inOverlay) {
                BottomNavigationBar(
                    activeTab = effectiveTab,
                    showAdultTab = adultUnlocked,
                    onTabSelected = { activeTab = it },
                )
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
                // Details first: an item picked inside the adult section, a
                // category or See All must open Details (back from Details
                // returns to the list it came from, same scroll position).
                selectedItem != null -> {
                    val (item, sourceId) = selectedItem!!
                    DetailsScreen(
                        item = item,
                        sourceId = sourceId,
                        onBack = { selectedItem = null },
                        onPlay = { details, episode, src ->
                            playTarget = PlayTarget.Online(details, episode, src)
                        },
                        scrollState = detailsScrollState,
                    )
                }
                showAdult -> {
                    AdultScreen(
                        onItemClick = { openItem(it) },
                        onBack = { showAdult = false },
                        onOpenExtensions = {
                            showAdult = false
                            activeTab = MainTab.EXTENSIONS
                        },
                        gridState = adultGridState,
                    )
                }
                seeAllSection != null -> {
                    val section = seeAllSection!!
                    SeeAllScreen(
                        title = section.title,
                        items = section.items,
                        gridState = seeAllGridState,
                        onItemClick = { openItem(it) },
                        onBack = { seeAllSection = null },
                    )
                }
                categoryId != null -> {
                    val id = categoryId!!
                    val title = APP_CATEGORIES.find { it.id == id }?.title ?: id
                    CategoryScreen(
                        categoryId = id,
                        title = title,
                        gridState = categoryGridState,
                        onItemClick = { openItem(it) },
                        onBack = { categoryId = null },
                    )
                }
                else -> when (effectiveTab) {
                    MainTab.HOME -> HomeScreen(
                        onItemClick = { openItem(it) },
                        onSearchClick = { activeTab = MainTab.SEARCH },
                        onSeeAll = { section ->
                            // A different rail's See All starts at the top;
                            // the same rail keeps its position on back.
                            if (seeAllSection?.title != section.title) {
                                scope.launch { seeAllGridState.scrollToItem(0) }
                            }
                            seeAllSection = section
                        },
                        onCategoryClick = { openCategory(it) },
                        adultUnlocked = adultUnlocked,
                        scrollState = homeScrollState,
                    )
                    MainTab.SEARCH -> SearchScreen(
                        onItemClick = { openItem(it) },
                        gridState = searchGridState,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        langFilter = searchLangFilter,
                        onLangFilterChange = { searchLangFilter = it },
                        typeFilter = searchTypeFilter,
                        onTypeFilterChange = { searchTypeFilter = it },
                    )
                    MainTab.EXTENSIONS -> ExtensionsScreen(
                        onBrowseAdult = { showAdult = true },
                        onOpenSettings = { activeTab = MainTab.SETTINGS },
                    )
                    MainTab.DOWNLOADS -> DownloadsScreen(
                        onPlayOffline = { item -> playTarget = PlayTarget.Offline(item) },
                    )
                    MainTab.ADULT -> AdultScreen(
                        onItemClick = { openItem(it) },
                        onBack = { activeTab = MainTab.HOME },
                        onOpenExtensions = { activeTab = MainTab.EXTENSIONS },
                        gridState = adultGridState,
                    )
                    MainTab.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun BottomNavigationBar(
    activeTab: MainTab,
    showAdultTab: Boolean,
    onTabSelected: (MainTab) -> Unit,
) {
    // The Adult tab is rendered ONLY when unlocked; locked users never see it.
    val tabs = MainTab.entries.filter { it != MainTab.ADULT || showAdultTab }
    Row(
        modifier = Modifier.fillMaxWidth().background(SurfaceNight).navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
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
