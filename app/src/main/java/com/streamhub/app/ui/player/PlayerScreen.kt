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
 * Tap on the video surface SHOWS the controls only (never toggles pause);
 * pause/resume happens only via the play/pause button. Double-tap left/right
 * seeks -10s/+10s with an animated feedback overlay.
 *
 * Portrait layout (390px test width): top bar, 16:9 video, then the seek bar
 * on its own full-width row, a transport row and an icon-only settings row —
 * nothing clipped or pushed off-screen. In fullscreen the same controls
 * overlay the video and auto-hide after 3s of inactivity.
 *
 * Offline mode: pass [offlineItem] (details/episode null) to play a completed
 * download from the shared cache with no network.
 */
@OptIn(UnstableApi::class, ExperimentalAnimationApi::class)
@Composable
fun PlayerScreen(
    details: ApiDetails?,
    episode: ApiEpisode?,
    sourceId: String,
    offlineItem: DownloadHelper.DownloadItem? = null,
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

    LaunchedEffect(details?.id, episode?.data, sourceId, offlineItem?.id) {
        if (offlineItem != null) {
            viewModel.playOffline(context, offlineItem)
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
    val audioLabel = if (audioLang == "hindi") "Hindi" else "English"
    val subtitleLabel = when {
        !captionsEnabled -> "Off"
        preferredSubtitleLang != null -> preferredSubtitleLang!!
        subtitles.isNotEmpty() -> subtitles.first().label
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
                        // Tap = SHOW controls only. Never toggles pause.
                        onTap = { pokeControls() },
                        onDoubleTap = { offset ->
                            doSkip(offset.x >= videoWidthPx / 2f)
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
                modifier = Modifier.fillMaxSize(),
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
                Column(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error!!, color = PlayerText, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(PlayerRed)
                            .clickable {
                                if (offlineItem != null) {
                                    viewModel.playOffline(context, offlineItem)
                                } else if (details != null && episode != null) {
                                    viewModel.play(context, details, episode, sourceId)
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text("Retry", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
            // Transport row: prev / -10 / play-pause / +10 / next.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
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
                IconButton(onClick = { doSkip(false) }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Replay10, contentDescription = "Back 10 seconds",
                        tint = PlayerText, modifier = Modifier.size(24.dp),
                    )
                }
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(PlayerRed)
                        .clickable { viewModel.togglePlay(); pokeControls() },
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            (scaleIn(tween(180)) + fadeIn(tween(180))) togetherWith
                                (scaleOut(tween(180)) + fadeOut(tween(180)))
                        },
                        label = "play-pause-morph-bar",
                    ) { playing ->
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = Color.White, modifier = Modifier.size(30.dp),
                        )
                    }
                }
                IconButton(onClick = { doSkip(true) }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Forward10, contentDescription = "Forward 10 seconds",
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
            // Settings row: icon-only, 24dp icons, evenly spaced — fits 390px.
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsIconButton(Icons.Default.VolumeUp, "Volume") { openMenu = "Volume" }
                SettingsIconButton(Icons.Default.Speed, "Speed: ${formatSpeed(speed)}") { openMenu = "Speed" }
                SettingsIconButton(Icons.Default.ClosedCaption, "Subtitles: $subtitleLabel") { openMenu = "Subtitles" }
                SettingsIconButton(Icons.Default.HighQuality, "Quality: $qualityLabel") { openMenu = "Quality" }
                SettingsIconButton(Icons.Default.Language, "Audio: $audioLabel") { openMenu = "Audio" }
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

    // ---- Custom menus (Compose DropdownMenus only — no native popups) --------
    PlayerMenu(
        expanded = openMenu == "Speed",
        onDismiss = { openMenu = null },
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
    PlayerMenu(
        expanded = openMenu == "Quality",
        onDismiss = { openMenu = null },
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
    PlayerMenu(
        expanded = openMenu == "Audio",
        onDismiss = { openMenu = null },
    ) {
        // Hindi dub is the default preferred audio.
        MenuOption(label = "Hindi", selected = audioLang == "hindi") {
            viewModel.switchAudio("hindi")
            openMenu = null
        }
        MenuOption(label = "English", selected = audioLang == "english") {
            viewModel.switchAudio("english")
            openMenu = null
        }
    }
    PlayerMenu(
        expanded = openMenu == "Subtitles",
        onDismiss = { openMenu = null },
    ) {
        MenuOption(label = "Off", selected = !captionsEnabled) {
            viewModel.setCaptionsEnabled(false)
            openMenu = null
        }
        subtitles.forEach { sub ->
            MenuOption(
                label = sub.label,
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
    PlayerMenu(
        expanded = openMenu == "Volume",
        onDismiss = { openMenu = null },
    ) {
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
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

@Composable
private fun SettingsIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = PlayerText, modifier = Modifier.size(24.dp))
    }
}

/** Custom Compose dropdown — dark surface, red selection mark, no native popup. */
@Composable
private fun PlayerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Anchored full-screen so the menu opens above the bottom edge;
    // DropdownMenu is a Compose popup, not a native Android dialog.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.background(PlayerSurface),
        ) {
            content()
        }
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
