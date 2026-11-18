package com.streamhub.app.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.streamhub.app.StreamHubApplication
import com.streamhub.app.data.HistoryStore
import com.streamhub.app.data.SettingsStore
import com.streamhub.app.data.api.ApiDetails
import com.streamhub.app.data.extensions.ExtensionsCatalog
import com.streamhub.app.download.DownloadCache
import com.streamhub.app.download.DownloadHelper
import com.streamhub.app.player.PlayerHttp
import com.streamhub.app.player.SkipIntro
import com.streamhub.app.data.api.ApiEpisode
import com.streamhub.app.data.api.ApiStream
import com.streamhub.app.data.api.ApiSubtitle
import com.streamhub.app.data.api.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Player state, backed by Media3 ExoPlayer. Adapted from the CINEMORA
 * reference player: same rebuild-per-source + header behaviour, but wired to
 * the StreamHub Flask API ([Repository]) instead of built-in providers.
 *
 * ASSUMED Agent A types (see report if any of these differ):
 * - ApiDetails: id: String, title: String, poster: String?
 * - ApiEpisode: number: Int, title: String?, data: String? (opaque, passed verbatim to streams())
 * - ApiStream: name: String, url: String, quality: Int?, type: String ("m3u8"|"video"|"embed"),
 *   headers: Map<String, String>?, proxy: Boolean
 * - ApiSubtitle: label: String, url: String
 * - ApiStreamsResponse: streams: List<ApiStream>, subtitles: List<ApiSubtitle>?
 * - Repository.streams(source: String, data: String): ApiStreamsResponse
 * - Repository.findSameEpisode(title: String, episodeNumber: Int, lang: String):
 *   SameEpisode?  // (source, data, title?) — the same episode in another language
 * - StreamHubApplication.repository is of type data.api.Repository.
 *
 * Proxy rule: Repository already wraps proxy:true streams through
 * /api/proxy — the URLs are played exactly as returned, never re-wrapped here.
 * Embed rule: type:"embed" streams are filtered out entirely, never offered.
 */
/** Frame / aspect mode (v1.0.2, MovieBox-style): Fit = the video's real
 * aspect ratio (letterboxed); Fill = cropped to fill the screen; Zoom =
 * Fill + ~1.3x enlargement (the screen scales the SurfaceView). */
enum class AspectMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    ZOOM("Zoom"),
}

@UnstableApi
class PlayerViewModel @JvmOverloads constructor(
    private val repository: Repository = StreamHubApplication.repository,
    private val settings: SettingsStore = StreamHubApplication.settings,
) : ViewModel() {

    /** Skip Intro / Auto Next toggles, from Settings (both default ON). */
    val autoNext: StateFlow<Boolean> =
        settings.autoNext.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val skipIntro: StateFlow<Boolean> =
        settings.skipIntro.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private var exoPlayer: ExoPlayer? = null

    /** Current player instance; the screen attaches the SurfaceView/SubtitleView to it. */
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _sources = MutableStateFlow<List<ApiStream>>(emptyList())
    val sources: StateFlow<List<ApiStream>> = _sources.asStateFlow()

    private val _currentSourceIndex = MutableStateFlow(0)
    val currentSourceIndex: StateFlow<Int> = _currentSourceIndex.asStateFlow()

    private val _subtitles = MutableStateFlow<List<ApiSubtitle>>(emptyList())
    val subtitles: StateFlow<List<ApiSubtitle>> = _subtitles.asStateFlow()

    private val _captionsEnabled = MutableStateFlow(true)
    val captionsEnabled: StateFlow<Boolean> = _captionsEnabled.asStateFlow()

    /** Current subtitle cues, fed to the standalone SubtitleView. */
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    private val cueListener = object : Player.Listener {
        override fun onCues(cues: List<Cue>) {
            _cues.value = cues
        }
    }

    /**
     * Playback-state listener (v1.0.2, failover v1.0.3): without this, a
     * stream that fails to load left the player on a silent black 00:00/00:00
     * screen forever — `_isLoading` was cleared right after `prepare()` and
     * no error was ever surfaced. Now STATE_READY clears loading, BUFFERING
     * shows the spinner again, and any fatal error AUTO-FAILOVERS to the
     * next mirror (CloudStream pattern) — the error screen appears ONLY
     * after ALL mirrors are exhausted (see [autoFailover]).
     */
    private val playbackListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    sawFirstFrame = true
                    _isLoading.value = false
                }
                Player.STATE_BUFFERING -> {
                    if (_error.value == null) _isLoading.value = true
                }
                Player.STATE_ENDED -> {
                    _playbackEnded.value = true
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _isLoading.value = false
            autoFailover()
        }
    }

    /**
     * Multi-server AUTO-failover (v1.0.3, CloudStream pattern): on any
     * fatal PlaybackException — before the first frame, or an IO error
     * mid-play — mark the current mirror failed and auto-load the next
     * unfailed mirror at the same position, with a toast
     * ("Server 1 failed, trying Server 2…"). The error screen is set ONLY
     * when every mirror has failed. Returns true when a failover started.
     */
    private val failedSourceIndices = mutableSetOf<Int>()
    private var sawFirstFrame = false

    private fun autoFailover(): Boolean {
        val sources = _sources.value
        if (sources.size < 2) {
            setAllMirrorsFailedError()
            return false
        }
        failedSourceIndices.add(_currentSourceIndex.value)
        val next = nextUnfailedIndex() ?: run {
            setAllMirrorsFailedError()
            return false
        }
        val from = _currentSourceIndex.value + 1
        val to = next + 1
        val pos = exoPlayer?.currentPosition ?: 0L
        _currentSourceIndex.value = next
        _error.value = null
        _isLoading.value = true
        _message.value = "Server $from failed, trying Server $to…"
        buildAndPlay(pos)
        armLoadWatchdog()
        return true
    }

    private fun setAllMirrorsFailedError() {
        _error.value = "This video could not be played. " +
            "Check your connection or try another server."
    }

    /** Next mirror index that has not failed yet (cyclic from current), or null when all failed. */
    private fun nextUnfailedIndex(): Int? {
        val size = _sources.value.size
        if (size == 0) return null
        for (step in 1..size) {
            val candidate = (_currentSourceIndex.value + step) % size
            if (candidate !in failedSourceIndices) return candidate
        }
        return null
    }

    /**
     * Load watchdog (v1.0.3: 9s, was 25s): if the player still has not
     * reached READY ~9s after a load starts (e.g. the upstream host 502s
     * and ExoPlayer keeps retrying silently), FAIL OVER to the next mirror
     * instead of sitting on a black screen — the error screen appears
     * only when no mirror is left. Each load bumps [loadGeneration]; the
     * watchdog only fires for the load that is still current.
     */
    private var loadGeneration = 0

    private fun armLoadWatchdog() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            delay(9_000)
            if (generation == loadGeneration && _isLoading.value && _error.value == null) {
                // Still no first frame: treat the current mirror as dead
                // and fail over (or show the error when none is left).
                if (!autoFailover()) {
                    _isLoading.value = false
                }
            }
        }
    }

    /** Label of the selected subtitle track, or null. Resolved to a language internally. */
    private val _preferredSubtitleLang = MutableStateFlow<String?>(null)
    val preferredSubtitleLang: StateFlow<String?> = _preferredSubtitleLang.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Current audio language code (e.g. "hindi"); see [availableAudioLanguages]. */
    private val _audioLang = MutableStateFlow("hindi")
    val audioLang: StateFlow<String> = _audioLang.asStateFlow()

    /**
     * REAL audio languages for the current title (v1.0.3) — language codes
     * CONFIRMED by the API (see [probeAudioLanguages]): the current
     * language, plus every other language for which the same episode
     * actually exists. The player menu shows ONLY these — never a
     * hardcoded Hindi/English list. One language → one menu row.
     */
    private val _availableAudioLanguages = MutableStateFlow<List<String>>(listOf("hindi"))
    val availableAudioLanguages: StateFlow<List<String>> = _availableAudioLanguages.asStateFlow()

    /** Frame / aspect mode (v1.0.2). */
    private val _aspectMode = MutableStateFlow(AspectMode.FIT)
    val aspectMode: StateFlow<AspectMode> = _aspectMode.asStateFlow()

    /** True while playing a downloaded file (offline audio switch uses variants). */
    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    /** Downloaded language variants of the current offline episode. */
    private val _offlineVariants = MutableStateFlow<List<DownloadHelper.DownloadItem>>(emptyList())
    val offlineVariants: StateFlow<List<DownloadHelper.DownloadItem>> = _offlineVariants.asStateFlow()

    /** Language of the currently playing offline variant. */
    private val _offlineLang = MutableStateFlow("hindi")
    val offlineLang: StateFlow<String> = _offlineLang.asStateFlow()

    /** True once the player reaches STATE_ENDED (drives Auto Next). */
    private val _playbackEnded = MutableStateFlow(false)
    val playbackEnded: StateFlow<Boolean> = _playbackEnded.asStateFlow()

    /**
     * Skip-intro range. Always null for StreamHub: the API details dict has no
     * MAL id, so there is nothing to query AniSkip with — the player falls back
     * to the manual +85s skip (see [skipIntro]). Kept as a StateFlow so the
     * screen can use an exact range if a MAL id ever appears in ApiDetails.
     */
    private val _skipIntroRange = MutableStateFlow<SkipIntro.OpRange?>(null)
    val skipIntroRange: StateFlow<SkipIntro.OpRange?> = _skipIntroRange.asStateFlow()

    private var appContext: Context? = null
    private var currentSourceId: String? = null
    private var currentDetails: ApiDetails? = null
    private var currentEpisode: ApiEpisode? = null
    private var positionJob: Job? = null

    fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Start playing this episode. Resumes saved progress when there is any. */
    fun play(context: Context, details: ApiDetails, episode: ApiEpisode, sourceId: String) {
        appContext = context.applicationContext
        currentDetails = details
        currentEpisode = episode
        currentSourceId = sourceId
        _audioLang.value = "hindi"
        _availableAudioLanguages.value = listOf("hindi")
        failedSourceIndices.clear()
        sawFirstFrame = false
        _isOffline.value = false
        _offlineVariants.value = emptyList()
        _playbackEnded.value = false
        if (positionJob?.isActive != true) startPositionPolling()
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _skipIntroRange.value = null
            _positionMs.value = 0L
            _durationMs.value = 0L
            val data = episode.data
            if (data.isNullOrBlank()) {
                _error.value = "No stream data for this episode."
                _isLoading.value = false
                return@launch
            }
            // v1.0.3: FRESH aggregated streams — /api/streams_all?fresh=1
            // across all sources, falling back to /api/streams?fresh=1.
            // Signed URLs expire in ~1 min, so a cached/stale URL is
            // usually dead by play time; resolving fresh at play time is
            // what makes titles play at all.
            val response = try {
                repository.playStreams(sourceId, data, details.title, details.type)
            } catch (e: Exception) {
                _error.value = "Could not load streams. Check your connection and try again."
                _isLoading.value = false
                return@launch
            }
            // EMBED RULE: embed streams cannot play in ExoPlayer — drop them.
            val playable = response.streams
                .filter { it.type != "embed" }
                .sortedByDescending { it.quality ?: 0 }
            if (playable.isEmpty()) {
                _error.value = "No playable stream found for this episode."
                _isLoading.value = false
                return@launch
            }
            _sources.value = playable
            _subtitles.value = response.subtitles.orEmpty()
            // Direct MP4s start at the LOWEST quality (fast first frame);
            // HLS/DASH starts at index 0 and adapts up from the lowest
            // variant (see the low initial bandwidth estimate in
            // [buildAndPlay]).
            _currentSourceIndex.value = initialSourceIndex(playable)
            val resumeMs = WatchProgressStore.loadPosition(
                context, sourceId, details.id, episode.number,
            )
            buildAndPlay(positionMs = resumeMs)
            // History: the title is watched from the moment playback starts.
            runCatching {
                HistoryStore.record(
                    context, sourceId, details, episode, 0L, 0L,
                    isAdult = sourceId in ADULT_SOURCE_IDS,
                )
            }
            // Loading clears on STATE_READY (playbackListener) or via the
            // 9s watchdog — never here: clearing it right after prepare()
            // is what used to leave a silent black 00:00 screen when the
            // stream itself failed to load.
            armLoadWatchdog()
            // Real audio languages (background): which languages does this
            // exact episode exist in? The menu updates when the probe
            // answers — never a hardcoded list.
            probeAudioLanguages(details, episode)
        }
    }

    /**
     * Probe which audio languages the current episode really exists in.
     * Candidates are the languages the API knows ([AUDIO_CANDIDATES]);
     * a language is listed ONLY when [Repository.findSameEpisode] finds
     * the same episode in it. Runs in the background after playback
     * starts, so it never delays the first frame.
     */
    private fun probeAudioLanguages(details: ApiDetails, episode: ApiEpisode) {
        viewModelScope.launch {
            val found = mutableListOf(_audioLang.value)
            for (lang in AUDIO_CANDIDATES) {
                if (lang == _audioLang.value) continue
                val match = runCatching {
                    repository.findSameEpisode(details.title, episode.number, lang)
                }.getOrNull()
                if (match != null && lang !in found) found.add(lang)
            }
            _availableAudioLanguages.value = found
        }
    }

    private fun buildAndPlay(positionMs: Long) {
        val context = appContext ?: return
        val source = _sources.value.getOrNull(_currentSourceIndex.value) ?: return
        // v1.0.3: OkHttpDataSource (connection pooling, HTTP/2, keep-alive,
        // 10s timeouts, DNS-over-HTTPS) over a 300MB LRU SimpleCache —
        // NOT DefaultHttpDataSource. Direct (proxy:false) streams get their
        // CDN headers as request headers; proxy:true streams already carry
        // Referer/UA inside the /api/proxy URL (see ApiClient.proxyUrl).
        // The adaptive track selector starts from a LOW bandwidth estimate
        // (500 kbps), so HLS/DASH begins on the lowest variant and steps
        // up as real bandwidth is measured. Media3 DEFAULT buffers are
        // kept (first frame needs only ~2.5s buffered).
        val upstreamFactory = OkHttpDataSource.Factory(PlayerHttp.client())
            .setDefaultRequestProperties(source.headers.orEmpty())
        val cacheFactory = CacheDataSource.Factory()
            .setCache(DownloadCache.getPlayerCache(context))
            .setUpstreamDataSourceFactory(upstreamFactory)
        // setMediaSourceFactory exists only on ExoPlayer.Builder, not on the
        // player instance — and the factory carries this source's headers, so
        // rebuild the player here (quality / audio switches re-enter here).
        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setBandwidthMeter(
                DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(500_000)
                    .build(),
            )
            .build()
            .also { _player.value = it }
        val player = exoPlayer ?: return

        // Standalone SubtitleView has no setPlayer: cues arrive via listener.
        player.addListener(cueListener)
        player.addListener(playbackListener)
        val subtitleConfigs = _subtitles.value.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                .setMimeType(
                    if (sub.url.contains(".vtt", ignoreCase = true)) MimeTypes.TEXT_VTT
                    else MimeTypes.APPLICATION_SUBRIP,
                )
                .setLanguage(subtitleLabelToLang(sub.label))
                .setLabel(sub.label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(source.url) // proxy:true URLs arrive already proxy-wrapped; play as given.
            .setMimeType(if (source.type == "m3u8") MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()
        applyCaptionSettings()
        applyAspectMode()
        player.setMediaItem(item)
        player.prepare()
        if (positionMs > 10_000) player.seekTo(positionMs)
        player.playWhenReady = true
        player.playbackParameters = PlaybackParameters(_speed.value)
        _playbackEnded.value = false
    }

    /** Cycle Fit → Fill → Zoom (v1.0.2). Applies to the current player immediately. */
    fun cycleAspectMode() {
        _aspectMode.value = when (_aspectMode.value) {
            AspectMode.FIT -> AspectMode.FILL
            AspectMode.FILL -> AspectMode.ZOOM
            AspectMode.ZOOM -> AspectMode.FIT
        }
        applyAspectMode()
    }

    /** Fit = letterboxed; Fill/Zoom = cropped to fill (Zoom adds a 1.3x surface scale in the screen). */
    private fun applyAspectMode() {
        val player = exoPlayer ?: return
        player.videoScalingMode = when (_aspectMode.value) {
            AspectMode.FIT -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            AspectMode.FILL, AspectMode.ZOOM -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
    }

    /** Switch to another source (quality) keeping the current position. */
    fun selectSource(index: Int) {
        if (index == _currentSourceIndex.value) return
        if (index !in _sources.value.indices) return
        val pos = exoPlayer?.currentPosition ?: 0L
        _currentSourceIndex.value = index
        failedSourceIndices.remove(index)
        _error.value = null
        _isLoading.value = true
        buildAndPlay(pos)
        armLoadWatchdog()
    }

    /**
     * "Try another server" (v1.0.2, failover-aware v1.0.3): when the
     * current stream fails (e.g. its host 502s — MovieBox's CDN did
     * exactly this for "Perverted Teacher"), switch to the next
     * NOT-YET-FAILED source/quality at the same position. Returns false
     * when there is nothing else to try.
     */
    fun tryNextSource(): Boolean {
        if (_sources.value.size < 2) return false
        failedSourceIndices.add(_currentSourceIndex.value)
        val next = nextUnfailedIndex() ?: return false
        val pos = exoPlayer?.currentPosition ?: 0L
        _currentSourceIndex.value = next
        _error.value = null
        _isLoading.value = true
        buildAndPlay(pos)
        armLoadWatchdog()
        return true
    }

    /**
     * Switch audio language (v1.0.3: REAL languages only). There is no
     * in-stream multi-audio: the same title is re-searched in the target
     * language and the same episode number is matched (see contract
     * LANGUAGE rule). [targetLang] is a language code from
     * [availableAudioLanguages] — a language the probe already confirmed
     * exists for this episode — never a hardcoded list.
     * Offline: switch between the DOWNLOADED variants instead (no network).
     * Resumes at the same position.
     */
    fun switchAudio(targetLang: String) {
        val lang = targetLang.lowercase()
        if (_isOffline.value) {
            val variant = _offlineVariants.value.firstOrNull { it.lang == lang }
            if (variant != null) {
                switchOfflineVariant(variant)
            } else {
                _message.value = "${languageDisplayName(lang)} download is not available for this episode."
            }
            return
        }
        if (lang == _audioLang.value) return
        val details = currentDetails ?: return
        val episode = currentEpisode ?: return
        viewModelScope.launch {
            _isLoading.value = true
            // SameEpisode carries the matching episode in the target language:
            // same title, same episode number, different source/data.
            val found = try {
                repository.findSameEpisode(details.title, episode.number, lang)
            } catch (_: Exception) {
                null
            }
            if (found == null) {
                _message.value = "${languageDisplayName(lang)} audio is not available for this episode."
                _isLoading.value = false
                return@launch
            }
            currentSourceId = found.source
            _audioLang.value = lang
            val response = try {
                // Fresh streams in the target language (server lang= param).
                repository.playStreams(found.source, found.data, details.title, details.type)
            } catch (_: Exception) {
                _message.value = "Could not load streams in this language."
                _isLoading.value = false
                return@launch
            }
            val playable = response.streams
                .filter { it.type != "embed" }
                .sortedByDescending { it.quality ?: 0 }
            if (playable.isEmpty()) {
                _message.value = "No playable stream in this language."
                _isLoading.value = false
                return@launch
            }
            val pos = exoPlayer?.currentPosition ?: 0L
            _sources.value = playable
            _subtitles.value = response.subtitles.orEmpty()
            _currentSourceIndex.value = initialSourceIndex(playable)
            failedSourceIndices.clear()
            sawFirstFrame = false
            if (lang !in _availableAudioLanguages.value) {
                _availableAudioLanguages.value = _availableAudioLanguages.value + lang
            }
            buildAndPlay(pos)
            armLoadWatchdog()
        }
    }

    /** Play a completed download from the shared offline cache — no network. */
    fun playOffline(
        context: Context,
        item: DownloadHelper.DownloadItem,
        variants: List<DownloadHelper.DownloadItem> = emptyList(),
    ) {
        appContext = context.applicationContext
        currentSourceId = item.sourceId
        // Offline playback now carries details + episode (v1.0.2): progress
        // saves and History work exactly like online playback, and the
        // player resumes the saved position for downloads too.
        currentDetails = ApiDetails(
            id = item.contentId, title = item.title, poster = item.posterUrl,
        )
        currentEpisode = ApiEpisode(
            number = item.episodeNumber, title = item.title, data = item.id,
        )
        _audioLang.value = item.lang
        _isOffline.value = true
        _offlineVariants.value = variants.ifEmpty { listOf(item) }
        _offlineLang.value = item.lang
        _playbackEnded.value = false
        if (positionJob?.isActive != true) startPositionPolling()
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val download = runCatching {
                DownloadCache.getDownloadManager(context).downloadIndex.getDownload(item.id)
            }.getOrNull()
            if (download == null) {
                _error.value = "Download not found."
                _isLoading.value = false
                return@launch
            }
            val resumeMs = WatchProgressStore.loadPosition(
                context, item.sourceId, item.contentId, item.episodeNumber,
            )
            val mime = download.request.mimeType ?: MimeTypes.VIDEO_MP4
            buildOfflinePlayer(download.request.uri, mime, resumeMs)
            val details = currentDetails
            val episode = currentEpisode
            if (details != null && episode != null) {
                runCatching {
                    HistoryStore.record(
                        context, item.sourceId, details, episode, 0L, 0L,
                        isAdult = item.sourceId in ADULT_SOURCE_IDS,
                    )
                }
            }
            armLoadWatchdog()
        }
    }

    /** Switch to another downloaded language variant of the same episode. */
    fun switchOfflineVariant(item: DownloadHelper.DownloadItem) {
        if (!_isOffline.value) return
        val context = appContext ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            currentSourceId = item.sourceId
            currentEpisode = ApiEpisode(
                number = item.episodeNumber, title = item.title, data = item.id,
            )
            _offlineLang.value = item.lang
            _audioLang.value = item.lang
            val download = runCatching {
                DownloadCache.getDownloadManager(context).downloadIndex.getDownload(item.id)
            }.getOrNull()
            if (download == null) {
                _error.value = "Download not found."
                _isLoading.value = false
                return@launch
            }
            val pos = exoPlayer?.currentPosition ?: 0L
            val mime = download.request.mimeType ?: MimeTypes.VIDEO_MP4
            buildOfflinePlayer(download.request.uri, mime, pos)
            armLoadWatchdog()
        }
    }

    /** Build the offline player for one downloaded file (cache-backed source). */
    private fun buildOfflinePlayer(uri: android.net.Uri, mime: String, positionMs: Long) {
        val context = appContext ?: return
        _sources.value = listOf(
            ApiStream(
                name = "Downloaded",
                url = uri.toString(),
                quality = null,
                type = if (mime == MimeTypes.APPLICATION_M3U8) "m3u8" else "video",
                headers = null,
                proxy = false,
            ),
        )
        _subtitles.value = emptyList()
        _currentSourceIndex.value = 0
        // Cache-backed source: the download's segments are served from the
        // shared SimpleCache (cache key = the download URL, as enqueued).
        // Same OkHttp upstream as online playback (pooling/HTTP/2/DoH).
        val cacheFactory = CacheDataSource.Factory()
            .setCache(DownloadCache.get(context))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(PlayerHttp.client()))
        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .build()
            .also { _player.value = it }
        val player = exoPlayer ?: return
        player.addListener(cueListener)
        player.addListener(playbackListener)
        applyAspectMode()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mime)
            .setCustomCacheKey(uri.toString())
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        if (positionMs > 10_000) player.seekTo(positionMs)
        player.playWhenReady = true
        player.playbackParameters = PlaybackParameters(_speed.value)
        _playbackEnded.value = false
    }

    fun setCaptionsEnabled(enabled: Boolean) {
        _captionsEnabled.value = enabled
        applyCaptionSettings()
    }

    /** [language] is a subtitle *label* (or null to clear); resolved to a language code internally. */
    fun setPreferredSubtitleLang(language: String?) {
        _preferredSubtitleLang.value = language
        applyCaptionSettings()
    }

    private fun applyCaptionSettings() {
        val player = exoPlayer ?: return
        val lang = _preferredSubtitleLang.value?.let { subtitleLabelToLang(it) }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !_captionsEnabled.value)
            .apply {
                if (lang != null) setPreferredTextLanguage(lang)
            }
            .build()
    }

    fun setSpeed(speed: Float) {
        _speed.value = speed
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun togglePlay() {
        val player = exoPlayer ?: return
        player.playWhenReady = !player.playWhenReady
        _isPlaying.value = player.playWhenReady
    }

    fun seekTo(ms: Long) {
        val player = exoPlayer ?: return
        player.seekTo(ms.coerceIn(0L, player.duration.coerceAtLeast(0L)))
        _playbackEnded.value = false
    }

    fun seekBy(deltaMs: Long) {
        val player = exoPlayer ?: return
        player.seekTo(
            (player.currentPosition + deltaMs)
                .coerceIn(0L, player.duration.coerceAtLeast(0L)),
        )
        _playbackEnded.value = false
    }

    /** Skip the opening: exact range when known, else +85s from the current position. */
    fun skipIntro() {
        val player = exoPlayer ?: return
        val range = _skipIntroRange.value
        if (range != null) {
            player.seekTo(range.endMs)
        } else {
            player.seekTo(
                (player.currentPosition + 85_000)
                    .coerceAtMost(player.duration.coerceAtLeast(0L)),
            )
        }
    }

    /** Mark the current episode watched (drops it from Continue Watching). */
    fun markWatched() {
        val context = appContext ?: return
        val details = currentDetails ?: return
        val episode = currentEpisode ?: return
        val sourceId = currentSourceId ?: return
        WatchProgressStore.save(
            context, sourceId, details, episode,
            positionMs = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L,
            durationMs = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L,
        )
    }

    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            var tick = 0
            while (true) {
                delay(500)
                // Continue (not break) on a null player: buildAndPlay briefly
                // releases the old player before assigning the rebuilt one.
                val player = exoPlayer ?: continue
                _positionMs.value = player.currentPosition
                _durationMs.value = player.duration.coerceAtLeast(0L)
                _isPlaying.value = player.isPlaying
                if (player.playbackState == Player.STATE_ENDED) {
                    _playbackEnded.value = true
                }
                // Periodic save (v1.0.2): every ~10s while playing, so the
                // position survives a crash/kill — the old code only saved
                // on exit, and a killed app lost everything watched.
                tick++
                if (tick >= 20) {
                    tick = 0
                    saveProgressNow()
                }
            }
        }
    }

    /** Persist progress (Continue Watching) + History, without releasing. */
    private fun saveProgressNow() {
        val context = appContext
        val details = currentDetails
        val episode = currentEpisode
        val sourceId = currentSourceId
        val player = exoPlayer
        if (context != null && details != null && episode != null && sourceId != null && player != null) {
            val pos = player.currentPosition
            val dur = player.duration.coerceAtLeast(0L)
            if (pos > 0 && dur > 0) {
                WatchProgressStore.save(context, sourceId, details, episode, pos, dur)
                HistoryStore.record(
                    context, sourceId, details, episode, pos, dur,
                    isAdult = sourceId in ADULT_SOURCE_IDS,
                )
            }
        }
    }

    /** Persist progress (Continue Watching) and release the player. */
    fun releaseAndSave() {
        saveProgressNow()
        positionJob?.cancel()
        exoPlayer?.removeListener(cueListener)
        exoPlayer?.removeListener(playbackListener)
        exoPlayer?.release()
        exoPlayer = null
        _player.value = null
        _cues.value = emptyList()
    }

    override fun onCleared() {
        releaseAndSave()
        super.onCleared()
    }
}

/** Best-effort ISO code from a subtitle label (the API returns label + url only). */
internal fun subtitleLabelToLang(label: String): String? {
    val lower = label.lowercase()
    return when {
        "hindi" in lower -> "hi"
        "english" in lower -> "en"
        "spanish" in lower -> "es"
        "french" in lower -> "fr"
        "german" in lower -> "de"
        "portuguese" in lower -> "pt"
        "arabic" in lower -> "ar"
        "urdu" in lower -> "ur"
        else -> null
    }
}

/** Adult source ids (hidden-folder extensions) — for History adult-flagging. */
internal val ADULT_SOURCE_IDS: Set<String> =
    ExtensionsCatalog.adult.map { it.id }.toSet()

/**
 * Languages the audio probe checks (v1.0.3). These are CANDIDATES, not a
 * menu: a language appears in the player menu ONLY when
 * [Repository.findSameEpisode] confirms the same episode exists in it.
 * "hindi" is the default audio, so it is always probed first (it is the
 * language already playing).
 */
internal val AUDIO_CANDIDATES: List<String> = listOf("hindi", "english")

/**
 * First-play source index (v1.0.3). HLS/DASH: index 0 — the adaptive
 * selector starts on the lowest variant (low initial bandwidth estimate)
 * and steps up. Direct MP4s: the LOWEST quality index, so the first frame
 * arrives fast on a slow connection; the user can still pick higher
 * quality from the menu.
 */
internal fun initialSourceIndex(streams: List<ApiStream>): Int {
    if (streams.isEmpty()) return 0
    if (streams.first().type.equals("m3u8", ignoreCase = true)) return 0
    var lowestIndex = 0
    var lowestQuality = Int.MAX_VALUE
    streams.forEachIndexed { index, stream ->
        val quality = stream.quality ?: 0
        if (quality < lowestQuality) {
            lowestQuality = quality
            lowestIndex = index
        }
    }
    return lowestIndex
}

/** Display name for an audio language code (menu label + messages). */
internal fun languageDisplayName(lang: String): String = when (lang.lowercase()) {
    "hindi", "hi" -> "Hindi"
    "english", "en" -> "English"
    "urdu", "ur" -> "Urdu"
    "japanese", "ja" -> "Japanese"
    "korean", "ko" -> "Korean"
    "chinese", "zh", "cn" -> "Chinese"
    "spanish", "es" -> "Spanish"
    "french", "fr" -> "French"
    "german", "de" -> "German"
    "portuguese", "pt" -> "Portuguese"
    "arabic", "ar" -> "Arabic"
    "tamil", "ta" -> "Tamil"
    "telugu", "te" -> "Telugu"
    "bengali", "bn" -> "Bengali"
    "indonesian", "in", "id" -> "Indonesian"
    else -> lang.replaceFirstChar { it.uppercase() }
}

/**
 * Human subtitle name for the menu (v1.0.2). Some sources return bare
 * language codes as the label — MovieBox returns "IN" — which showed up
 * verbatim next to "Off" and looked broken. Map known codes to proper
 * names ("IN"/"ID" = Indonesian — MovieBox's code for it); anything
 * unknown is shown as-is. The RAW label is still the selection key —
 * only the displayed text is mapped.
 */
internal fun subtitleDisplayLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.length > 3) return trimmed
    return when (trimmed.uppercase()) {
        "EN" -> "English"
        "HI" -> "Hindi"
        "IN", "ID" -> "Indonesian"
        "ES" -> "Spanish"
        "FR" -> "French"
        "DE" -> "German"
        "PT" -> "Portuguese"
        "AR" -> "Arabic"
        "UR" -> "Urdu"
        "JA" -> "Japanese"
        "KO" -> "Korean"
        "ZH", "CN" -> "Chinese"
        "RU" -> "Russian"
        "TR" -> "Turkish"
        "VI" -> "Vietnamese"
        "TH" -> "Thai"
        "MS" -> "Malay"
        "IT" -> "Italian"
        "NL" -> "Dutch"
        "PL" -> "Polish"
        "TA" -> "Tamil"
        "TE" -> "Telugu"
        "BN" -> "Bengali"
        else -> trimmed
    }
}

/**
 * Watch-progress store. Same idea (and same 7-field, unit-separator value
 * format) as CINEMORA's WatchProgress, under prefs "streamhub_progress":
 * key = "sourceId|detailsId|episodeNumber", value =
 * title ␟ poster ␟ sourceId ␟ detailsId ␟ episodeNumber ␟ positionMs ␟ durationMs.
 * Finished items (position within 10s of the end) are removed — that is the
 * "watched" state. Home's Continue Watching can read this with the same
 * loadAll parsing as the reference.
 */
internal object WatchProgressStore {
    private const val PREFS = "streamhub_progress"
    private const val SEP = "\u001F"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(sourceId: String, detailsId: String, episodeNumber: Int) =
        "$sourceId|$detailsId|$episodeNumber"

    fun save(
        context: Context,
        sourceId: String,
        details: ApiDetails,
        episode: ApiEpisode,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (durationMs <= 0 || positionMs <= 0) return
        val k = key(sourceId, details.id, episode.number)
        if (positionMs >= durationMs - 10_000) {
            prefs(context).edit().remove(k).apply()
            return
        }
        val value = listOf(
            details.title, details.poster ?: "", sourceId, details.id,
            episode.number.toString(), positionMs.toString(), durationMs.toString(),
        ).joinToString(SEP)
        prefs(context).edit().putString(k, value).apply()
    }

    fun loadPosition(context: Context, sourceId: String, detailsId: String, episodeNumber: Int): Long {
        val raw = prefs(context).getString(key(sourceId, detailsId, episodeNumber), null)
            ?: return 0L
        val parts = raw.split(SEP)
        if (parts.size < 7) return 0L
        return parts[5].toLongOrNull() ?: 0L
    }

    /** One unfinished title for Home's Continue Watching rail. */
    data class ContinueEntry(
        val title: String,
        val poster: String?,
        val sourceId: String,
        val detailsId: String,
        val episodeNumber: Int,
        val positionMs: Long,
        val durationMs: Long,
    )

    /** All unfinished titles (finished ones were removed on save). */
    fun loadAll(context: Context): List<ContinueEntry> {
        val all = prefs(context).all
        val out = mutableListOf<ContinueEntry>()
        for ((_, value) in all) {
            val raw = value as? String ?: continue
            val parts = raw.split(SEP)
            if (parts.size < 7) continue
            out.add(
                ContinueEntry(
                    title = parts[0],
                    poster = parts[1].ifBlank { null },
                    sourceId = parts[2],
                    detailsId = parts[3],
                    episodeNumber = parts[4].toIntOrNull() ?: 0,
                    positionMs = parts[5].toLongOrNull() ?: 0L,
                    durationMs = parts[6].toLongOrNull() ?: 0L,
                ),
            )
        }
        return out
    }
}
