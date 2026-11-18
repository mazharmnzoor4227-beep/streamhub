package com.streamhub.app.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import com.streamhub.app.data.api.ApiDetails
import com.streamhub.app.data.api.ApiEpisode
import com.streamhub.app.download.DownloadHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PlayerBg = Color(0xFF141414)
private val PlayerSurface = Color(0xFF1E1E1E)
private val PlayerRed = Color(0xFFE50914)
private val PlayerText = Color(0xFFF5F5F5)
private val PlayerMuted = Color(0xFFB3B3B3)

private const val CONTROLS_HIDE_MS = 3_000L

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * StreamHub player. Custom controls ONLY — no PlayerView, no native controls,
 * no native popups/dialogs: every menu is a Compose DropdownMenu.
 *
 * Tap on the video surface TOGGLES the controls (first tap shows, second
 * tap hides) — it never toggles pause; pause/resume happens only via the
 * play/pause button. Double-tap left/right seeks -10s/+10s with an animated
 * feedback overlay. HOLD on the video (400ms) plays at 2x while held, with
 * a "2x" indicator, and releases back to the previous speed.
 *
 * Aspect modes (v1.0.2, MovieBox-style): the chip in the top bar cycles
 * Fit (real aspect ratio) → Fill (cropped fullscreen) → Zoom (cropped +
 * enlarged). The mode is shown on the chip itself.
 *
 * Portrait layout (390px test width): top bar, 16:9 video, then the seek bar
 * on its own full-width row, a transport row and an icon-only settings row —
 * nothing clipped or pushed off-screen. In fullscreen the same controls
 * overlay the video and auto-hide after 3s of inactivity.
 *
 * Offline mode: pass [offlineItem] (details/episode null) to play a completed
 * download from the shared cache with no network. [offlineVariants] are the
 * other downloaded languages of the same episode — the Audio menu switches
 * between them offline.
 */
@OptIn(UnstableApi::class, ExperimentalAnimationApi::class)
@Composable
fun PlayerScreen(
    details: ApiDetails?,
    episode: ApiEpisode?,
    sourceId: String,
    offlineItem: DownloadHelper.DownloadItem? = null,
    offlineVariants: List<DownloadHelper.DownloadItem> = emptyList(),
    onBack: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onPrevEpisode: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val viewModel: PlayerViewModel = viewModel()
    val sources by viewModel.sources.collectAsState()
    val currentSourceIndex by viewModel.currentSourceIndex.collectAsState()
    val subtitles by viewModel.subtitles.collectAsState()
    val captionsEnabled by viewModel.captionsEnabled.collectAsState()
    val preferredSubtitleLang by viewModel.preferredSubtitleLang.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val audioLang by viewModel.audioLang.collectAsState()
    // REAL audio languages (v1.0.3): only languages the API confirmed for
    // this exact episode — never a hardcoded Hindi/English list.
    val availableAudioLanguages by viewModel.availableAudioLanguages.collectAsState()
    val aspectMode by viewModel.aspectMode.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val offlineVariantsState by viewModel.offlineVariants.collectAsState()
    val offlineLang by viewModel.offlineLang.collectAsState()
    val skipIntroRange by viewModel.skipIntroRange.collectAsState()
    val playbackEnded by viewModel.playbackEnded.collectAsState()
    val player by viewModel.player.collectAsState()
    val cues by viewModel.cues.collectAsState()

    val autoNextEnabled by viewModel.autoNext.collectAsState()
    val skipIntroEnabled by viewModel.skipIntro.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var openMenu by remember { mutableStateOf<String?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var skipFeedback by remember { mutableStateOf<Pair<Boolean, Int>?>(null) } // (forward, counter)
    var feedbackCounter by remember { mutableIntStateOf(0) }
    var videoWidthPx by remember { mutableIntStateOf(0) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var subtitleView by remember { mutableStateOf<SubtitleView?>(null) }
    var autoNextFiredFor by remember { mutableStateOf<String?>(null) }
    // Hold-for-2x (v1.0.2): true while the video is held at 2x speed.
    var hold2x by remember { mutableStateOf(false) }
    var holdSpeedBefore by remember { mutableFloatStateOf(1f) }
    val gestureScope = rememberCoroutineScope()

    val displayTitle = offlineItem?.title ?: details?.title ?: ""
    val displayEpisodeLabel = when {
        offlineItem != null -> if (offlineItem.episodeNumber > 0) {
            "Episode ${offlineItem.episodeNumber} · Downloaded"
        } else {
            "Downloaded"
        }
        episode != null -> "Episode ${episode.number}" +
            (episode.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        else -> ""
    }

    fun pokeControls() {
        controlsVisible = true
        interactionTick++
    }

    /** Single tap TOGGLES the controls (v1.0.2): first tap shows, second tap hides. Never pauses. */
    fun toggleControls() {
        if (controlsVisible) {
            controlsVisible = false
        } else {
            pokeControls()
        }
    }

    LaunchedEffect(details?.id, episode?.data, sourceId, offlineItem?.id) {
        if (offlineItem != null) {
            viewModel.playOffline(context, offlineItem, offlineVariants)
        } else if (details != null && episode != null) {
            viewModel.play(context, details, episode, sourceId)
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.releaseAndSave() }
    }
    // Auto-hide the on-video controls after 3s of inactivity; any tap resets it.
    LaunchedEffect(controlsVisible, interactionTick) {
        if (controlsVisible) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2500)
            viewModel.clearMessage()
        }
    }
    // Attach the video surface whenever the player is rebuilt
    // (quality / audio switches rebuild the ExoPlayer instance).
    // Subtitles are fed separately: the ViewModel forwards onCues to the
    // SubtitleView via setCues (SubtitleView has no setPlayer in Media3).
    LaunchedEffect(player) {
        val p = player
        surfaceView?.let { if (p != null) p.setVideoSurfaceView(it) }
    }
    // Auto Next: once per episode, shortly after playback ends.
    LaunchedEffect(playbackEnded) {
        if (playbackEnded && autoNextEnabled && onNextEpisode != null &&
            autoNextFiredFor != episode?.data
        ) {
            autoNextFiredFor = episode?.data
            delay(1500)
            onNextEpisode()
        }
    }

    // Fullscreen: real orientation toggle + system-bar hiding.
    val activity = context as? Activity
    val view = LocalView.current
    fun setFullscreen(full: Boolean) {
        isFullscreen = full
        activity?.requestedOrientation = if (full) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            if (full) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    BackHandler {
        if (isFullscreen) setFullscreen(false) else onBack()
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val showSkipIntro = skipIntroEnabled && when {
        skipIntroRange != null ->
            positionMs in (skipIntroRange!!.startMs - 2000)..skipIntroRange!!.endMs
        durationMs > 0 -> positionMs < 90_000
        else -> false
    }
    val qualityLabel = sources.getOrNull(currentSourceIndex)?.let { src ->
        if ((src.quality ?: 0) > 0) "${src.quality}p" else src.name
    } ?: "Auto"
    // Offline: the audio label is the downloaded variant's language.
    // Online: the label of the language actually playing.
    val audioLabel = when {
        isOffline -> languageDisplayName(offlineLang)
        else -> languageDisplayName(audioLang)
    }
    val subtitleLabel = when {
        !captionsEnabled -> "Off"
        preferredSubtitleLang != null -> subtitleDisplayLabel(preferredSubtitleLang!!)
        subtitles.isNotEmpty() -> subtitleDisplayLabel(subtitles.first().label)
        else -> "Off"
    }

    fun doSkip(forward: Boolean) {
        viewModel.seekBy(if (forward) 10_000 else -10_000)
        feedbackCounter++
        skipFeedback = forward to feedbackCounter
        pokeControls()
    }

    // ---- Shared pieces -------------------------------------------------------
    val topBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (isFullscreen) setFullscreen(false) else onBack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = PlayerText)
            }
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(
                    displayTitle, color = PlayerText, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    displayEpisodeLabel,
                    color = PlayerMuted, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Aspect / frame chip (v1.0.2): shows the active mode, tap cycles
            // Fit → Fill → Zoom. Same chip in portrait and fullscreen.
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable { viewModel.cycleAspectMode(); pokeControls() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(aspectMode.label, color = PlayerText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    val centerControls: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { doSkip(false) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.Replay10, contentDescription = "Back 10 seconds",
                    tint = PlayerText, modifier = Modifier.size(40.dp),
                )
            }
            // Animated play/pause button: scale + fade morph.
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape)
                    .background(PlayerRed).clickable { viewModel.togglePlay(); pokeControls() },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (scaleIn(tween(180)) + fadeIn(tween(180))) togetherWith
                            (scaleOut(tween(180)) + fadeOut(tween(180)))
                    },
                    label = "play-pause-morph",
                ) { playing ->
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White, modifier = Modifier.size(34.dp),
                    )
                }
            }
            IconButton(
                onClick = { doSkip(true) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.Forward10, contentDescription = "Forward 10 seconds",
                    tint = PlayerText, modifier = Modifier.size(40.dp),
                )
            }
        }
    }

    val videoSurface: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier = modifier.background(Color.Black)
                .onSizeChanged { videoWidthPx = it.width }
                .pointerInput(Unit) {
                    detectTapGestures(
                        // Tap TOGGLES the controls (v1.0.2): show on first
                        // tap, hide on second. Never toggles pause.
                        onTap = { toggleControls() },
                        onDoubleTap = { offset ->
                            doSkip(offset.x >= videoWidthPx / 2f)
                        },
                        // Hold (400ms) = 2x while held, with a "2x"
                        // indicator; release restores the previous speed.
                        // Quick taps release before the delay fires, so
                        // tap / double-tap are unaffected. Works on the
                        // whole video area, including the left side.
                        onPress = {
                            val job = gestureScope.launch {
                                delay(400)
                                holdSpeedBefore = viewModel.speed.value
                                viewModel.setSpeed(2f)
                                hold2x = true
                            }
                            try {
                                awaitRelease()
                            } finally {
                                job.cancel()
                                if (hold2x) {
                                    viewModel.setSpeed(holdSpeedBefore)
                                    hold2x = false
                                }
                            }
                        },
                    )
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).also { surfaceView = it }
                },
                update = { sv ->
                    surfaceView = sv
                    player?.setVideoSurfaceView(sv)
                },
                // Zoom mode: the surface itself is enlarged ~1.3x on top of
                // the cropped scaling (see ViewModel.applyAspectMode).
                // Subtitles and controls are NOT scaled.
                modifier = Modifier.fillMaxSize().then(
                    if (aspectMode == AspectMode.ZOOM) Modifier.scale(1.3f) else Modifier,
                ),
            )
            AndroidView(
                factory = { ctx ->
                    SubtitleView(ctx).also { subtitleView = it }
                },
                update = { sv ->
                    subtitleView = sv
                    sv.setCues(cues)
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (isLoading && error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PlayerRed)
                }
            }
            if (error != null) {
                // Never a silent black screen: a clear error, Retry, and —
                // when another source exists — "Try another server".
                Column(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error!!, color = PlayerText, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(PlayerRed)
                                .clickable {
                                    if (offlineItem != null) {
                                        viewModel.playOffline(context, offlineItem, offlineVariants)
                                    } else if (details != null && episode != null) {
                                        viewModel.play(context, details, episode, sourceId)
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text("Retry", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        if (sources.size > 1) {
                            Box(
                                Modifier.clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.14f))
                                    .clickable { viewModel.tryNextSource() }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                            ) {
                                Text("Try another server", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            if (message != null) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(message!!, color = Color.White, fontSize = 12.sp)
                }
            }
            // Hold-for-2x indicator (v1.0.2): visible while held at 2x.
            if (hold2x) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("2x", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            // Double-tap seek feedback: circular arrow + "10", rotates/pulses then fades.
            skipFeedback?.let { (forward, count) ->
                key(count) {
                    SkipFeedbackOverlay(forward = forward) { skipFeedback = null }
                }
            }
            if (controlsVisible && !isLoading && error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    centerControls()
                }
                if (showSkipIntro) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp)
                            .clip(RoundedCornerShape(10.dp)).background(Color.White)
                            .clickable { viewModel.skipIntro(); pokeControls() }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(
                            "Skip Intro", color = Color.Black,
                            fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    val bottomControls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            // Seek bar on its own full-width row (portrait-fit rule).
            var dragging by remember { mutableStateOf(false) }
            var dragFraction by remember { mutableFloatStateOf(0f) }
            val fraction = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else 0f
            Slider(
                value = if (dragging) dragFraction else fraction,
                onValueChange = { dragging = true; dragFraction = it },
                onValueChangeFinished = {
                    viewModel.seekTo((dragFraction * durationMs).toLong())
                    dragging = false
                    pokeControls()
                },
                colors = SliderDefaults.colors(
                    thumbColor = PlayerRed,
                    activeTrackColor = PlayerRed,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatMs(positionMs), color = PlayerMuted, fontSize = 11.sp)
                Text(formatMs(durationMs), color = PlayerMuted, fontSize = 11.sp)
            }
            // Bottom row (v1.0.2): NO transport here — play/pause and
            // -10s/+10s live ONLY in the center overlay ("upar"). This row
            // is prev/next episode + the settings icons, each with its menu
            // anchored to the icon itself (see AnchoredMenuButton).
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onPrevEpisode?.invoke() },
                    enabled = onPrevEpisode != null,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.SkipPrevious, contentDescription = "Previous episode",
                        tint = if (onPrevEpisode != null) PlayerText else PlayerMuted,
                        modifier = Modifier.size(24.dp),
                    )
                }
                AnchoredMenuButton(
                    icon = Icons.Default.VolumeUp, label = "Volume",
                    expanded = openMenu == "Volume",
                    onOpen = { openMenu = "Volume" }, onDismiss = { openMenu = null },
                ) {
                    VolumeMenuContent()
                }
                AnchoredMenuButton(
                    icon = Icons.Default.Speed, label = "Speed: ${formatSpeed(speed)}",
                    expanded = openMenu == "Speed",
                    onOpen = { openMenu = "Speed" }, onDismiss = { openMenu = null },
                ) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                        MenuOption(
                            label = formatSpeed(value),
                            selected = speed == value,
                        ) {
                            viewModel.setSpeed(value)
                            openMenu = null
                        }
                    }
                }
                AnchoredMenuButton(
                    icon = Icons.Default.ClosedCaption, label = "Subtitles: $subtitleLabel",
                    expanded = openMenu == "Subtitles",
                    onOpen = { openMenu = "Subtitles" }, onDismiss = { openMenu = null },
                ) {
                    MenuOption(label = "Off", selected = !captionsEnabled) {
                        viewModel.setCaptionsEnabled(false)
                        openMenu = null
                    }
                    subtitles.forEach { sub ->
                        MenuOption(
                            label = subtitleDisplayLabel(sub.label),
                            selected = captionsEnabled && preferredSubtitleLang == sub.label,
                        ) {
                            viewModel.setCaptionsEnabled(true)
                            viewModel.setPreferredSubtitleLang(sub.label)
                            openMenu = null
                        }
                    }
                    if (subtitles.isEmpty()) {
                        MenuOption(label = "No subtitles available", selected = false) { openMenu = null }
                    }
                }
                AnchoredMenuButton(
                    icon = Icons.Default.HighQuality, label = "Quality: $qualityLabel",
                    expanded = openMenu == "Quality",
                    onOpen = { openMenu = "Quality" }, onDismiss = { openMenu = null },
                ) {
                    if (sources.isEmpty()) {
                        MenuOption(label = "No variants available", selected = false) { openMenu = null }
                    } else {
                        sources.forEachIndexed { index, src ->
                            val label = if ((src.quality ?: 0) > 0) "${src.quality}p · ${src.name}" else src.name
                            MenuOption(label = label, selected = index == currentSourceIndex) {
                                viewModel.selectSource(index)
                                openMenu = null
                            }
                        }
                    }
                }
                AnchoredMenuButton(
                    icon = Icons.Default.Language, label = "Audio: $audioLabel",
                    expanded = openMenu == "Audio",
                    onOpen = { openMenu = "Audio" }, onDismiss = { openMenu = null },
                ) {
                    if (isOffline) {
                        // Offline: switch between the DOWNLOADED language
                        // variants of this episode (v1.0.2) — no network.
                        val variants = offlineVariantsState
                        if (variants.isEmpty()) {
                            MenuOption(label = "No downloaded audio", selected = false) { openMenu = null }
                        } else {
                            variants.forEach { variant ->
                                MenuOption(
                                    label = languageDisplayName(variant.lang),
                                    selected = offlineLang == variant.lang,
                                ) {
                                    viewModel.switchOfflineVariant(variant)
                                    openMenu = null
                                }
                            }
                        }
                    } else {
                        // REAL languages only (v1.0.3): the menu lists exactly
                        // the languages the API confirmed for this episode
                        // (availableAudioLanguages). One language → one row;
                        // switching resumes at the same position.
                        if (availableAudioLanguages.isEmpty()) {
                            MenuOption(label = "No audio options", selected = false) { openMenu = null }
                        } else {
                            availableAudioLanguages.forEach { lang ->
                                MenuOption(
                                    label = languageDisplayName(lang),
                                    selected = audioLang == lang,
                                ) {
                                    viewModel.switchAudio(lang)
                                    openMenu = null
                                }
                            }
                        }
                    }
                }
                IconButton(
                    onClick = { setFullscreen(!isFullscreen) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = PlayerText, modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(
                    onClick = { onNextEpisode?.invoke() },
                    enabled = onNextEpisode != null,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.SkipNext, contentDescription = "Next episode",
                        tint = if (onNextEpisode != null) PlayerText else PlayerMuted,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }

    // ---- Layout --------------------------------------------------------------
    Box(Modifier.fillMaxSize().background(PlayerBg)) {
        if (isFullscreen) {
            // Fullscreen: video fills the screen, controls overlay it and auto-hide.
            videoSurface(Modifier.fillMaxSize())
            if (controlsVisible) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.45f)),
                    ) { topBar() }
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f)),
                    ) { bottomControls() }
                }
            }
        } else {
            // Portrait: top bar, 16:9 video, then the control bar below it.
            Column(Modifier.fillMaxSize()) {
                topBar()
                videoSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                bottomControls()
                episode?.title?.takeIf { it.isNotBlank() }?.let { epTitle ->
                    Text(
                        epTitle, color = PlayerMuted, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

/**
 * A settings icon whose menu opens ANCHORED TO THE ICON (v1.0.2).
 * The DropdownMenu lives inside this icon's own Box, so it opens directly
 * above/next to the tapped icon — never at a fixed bottom-left corner,
 * never overlapping the title. Material3's DropdownMenu flips the popup
 * above the anchor when there is no room below it.
 */
@Composable
private fun AnchoredMenuButton(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box {
        IconButton(
            onClick = {
                if (expanded) onDismiss() else onOpen()
            },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(icon, contentDescription = label, tint = PlayerText, modifier = Modifier.size(24.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.background(PlayerSurface),
        ) {
            content()
        }
    }
}

/** Volume slider content for the anchored volume menu. */
@Composable
private fun VolumeMenuContent() {
    val context = LocalContext.current
    val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val max = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
    val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    var volumeFraction by remember {
        mutableFloatStateOf(if (max > 0) current.toFloat() / max else 0f)
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Volume", color = PlayerText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Slider(
            value = volumeFraction,
            onValueChange = {
                volumeFraction = it
                audioManager?.setStreamVolume(
                    AudioManager.STREAM_MUSIC, (it * max).toInt(), 0,
                )
            },
            colors = SliderDefaults.colors(
                thumbColor = PlayerRed,
                activeTrackColor = PlayerRed,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.width(220.dp),
        )
    }
}

@Composable
private fun MenuOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                (if (selected) "✓  " else "") + label,
                color = if (selected) PlayerRed else PlayerText,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        onClick = onClick,
    )
}

/**
 * Double-tap seek feedback: a circular-arrow "10" icon that rotates and
 * pulses, then fades out. Sits on the half that was double-tapped.
 */
@Composable
private fun SkipFeedbackOverlay(forward: Boolean, onDone: () -> Unit) {
    var fading by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (fading) 1.25f else 1f,
        animationSpec = tween(650), label = "skip-feedback-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (fading) 0f else 1f,
        animationSpec = tween(650), label = "skip-feedback-alpha",
    )
    val rotation by animateFloatAsState(
        targetValue = if (fading) -25f else 0f,
        animationSpec = tween(650), label = "skip-feedback-rotation",
    )
    LaunchedEffect(Unit) {
        delay(90)
        fading = true
        delay(700)
        onDone()
    }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.padding(horizontal = 48.dp).size(72.dp).scale(scale).rotate(rotation)
                .alpha(alpha).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (forward) Icons.Default.Forward10 else Icons.Default.Replay10,
                contentDescription = if (forward) "Forward 10 seconds" else "Back 10 seconds",
                tint = Color.White, modifier = Modifier.size(52.dp),
            )
        }
    }
}
