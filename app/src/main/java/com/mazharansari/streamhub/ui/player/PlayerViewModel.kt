package com.mazharansari.streamhub.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mazharansari.streamhub.StreamHubApplication
import com.mazharansari.streamhub.data.SettingsStore
import com.mazharansari.streamhub.data.api.ApiDetails
import com.mazharansari.streamhub.download.DownloadCache
import com.mazharansari.streamhub.download.DownloadHelper
import com.mazharansari.streamhub.player.SkipIntro
import com.mazharansari.streamhub.data.api.ApiEpisode
import com.mazharansari.streamhub.data.api.ApiStream
import com.mazharansari.streamhub.data.api.ApiSubtitle
import com.mazharansari.streamhub.data.api.Repository
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

    /** Current audio language ("hindi" default, or "english"). */
    private val _audioLang = MutableStateFlow("hindi")
    val audioLang: StateFlow<String> = _audioLang.asStateFlow()

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
            val response = try {
                repository.streams(sourceId, data)
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
            _currentSourceIndex.value = 0
            val resumeMs = WatchProgressStore.loadPosition(
                context, sourceId, details.id, episode.number,
            )
            buildAndPlay(positionMs = resumeMs)
            _isLoading.value = false
        }
    }

    private fun buildAndPlay(positionMs: Long) {
        val context = appContext ?: return
        val source = _sources.value.getOrNull(_currentSourceIndex.value) ?: return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(source.headers.orEmpty())
        // setMediaSourceFactory exists only on ExoPlayer.Builder, not on the
        // player instance — and the factory carries this source's headers, so
        // rebuild the player here (quality / audio switches re-enter here).
        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .also { _player.value = it }
        val player = exoPlayer ?: return

        // Standalone SubtitleView has no setPlayer: cues arrive via listener.
        player.addListener(cueListener)
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
        player.setMediaItem(item)
        player.prepare()
        if (positionMs > 10_000) player.seekTo(positionMs)
        player.playWhenReady = true
        player.playbackParameters = PlaybackParameters(_speed.value)
        _playbackEnded.value = false
    }

    /** Switch to another source (quality) keeping the current position. */
    fun selectSource(index: Int) {
        if (index == _currentSourceIndex.value) return
        if (index !in _sources.value.indices) return
        val pos = exoPlayer?.currentPosition ?: 0L
        _currentSourceIndex.value = index
        buildAndPlay(pos)
    }

    /**
     * Switch audio language. There is no in-stream multi-audio: the same title
     * is re-searched in the target language and the same episode number is
     * matched (see contract LANGUAGE rule). [targetLang] is "hindi" or "english".
     */
    fun switchAudio(targetLang: String) {
        val lang = targetLang.lowercase()
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
                _message.value = if (lang == "hindi") {
                    "Hindi dub is not available for this episode."
                } else {
                    "English audio is not available for this episode."
                }
                _isLoading.value = false
                return@launch
            }
            currentSourceId = found.source
            _audioLang.value = lang
            val response = try {
                repository.streams(found.source, found.data)
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
            _currentSourceIndex.value = 0
            buildAndPlay(pos)
            _isLoading.value = false
        }
    }

    /** Play a completed download from the shared offline cache — no network. */
    fun playOffline(context: Context, item: DownloadHelper.DownloadItem) {
        appContext = context.applicationContext
        currentSourceId = item.sourceId
        currentDetails = null
        currentEpisode = null
        _audioLang.value = "hindi"
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
            val uri = download.request.uri
            val mime = download.request.mimeType ?: MimeTypes.VIDEO_MP4
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
            val cacheFactory = CacheDataSource.Factory()
                .setCache(DownloadCache.get(context))
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            exoPlayer?.release()
            exoPlayer = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
                .build()
                .also { _player.value = it }
            val player = exoPlayer ?: return@launch
            player.addListener(cueListener)
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMimeType(mime)
                .setCustomCacheKey(uri.toString())
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
            player.playbackParameters = PlaybackParameters(_speed.value)
            _playbackEnded.value = false
            _isLoading.value = false
        }
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
            }
        }
    }

    /** Persist progress (Continue Watching) and release the player. */
    fun releaseAndSave() {
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
            }
        }
        positionJob?.cancel()
        exoPlayer?.removeListener(cueListener)
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
