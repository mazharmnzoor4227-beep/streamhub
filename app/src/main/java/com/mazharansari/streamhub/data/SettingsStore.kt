package com.mazharansari.streamhub.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mazharansari.streamhub.data.api.ApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streamhub_settings",
)

/**
 * App settings persisted in DataStore Preferences.
 * The API base URL lives here so it can be changed from Settings without a
 * rebuild; [ApiClient] reads it fresh on every call.
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    companion object {
        val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")
        val KEY_DEFAULT_AUDIO = stringPreferencesKey("default_audio")
        val KEY_SKIP_INTRO = booleanPreferencesKey("skip_intro")
        val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next")
        val KEY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val KEY_ADULT_UNLOCKED = booleanPreferencesKey("adult_unlocked")
    }

    /** Default tunnel URL (BUILD-CONTRACT.md); overridable in Settings. */
    val apiBaseUrl: Flow<String> = dataStore.data.map { prefs ->
        (prefs[KEY_API_BASE_URL] ?: ApiClient.DEFAULT_BASE_URL).trimEnd('/')
    }

    val defaultAudio: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_AUDIO] ?: "hindi"
    }

    val skipIntro: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SKIP_INTRO] ?: true
    }

    val autoNext: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_NEXT] ?: true
    }

    val playbackSpeed: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_PLAYBACK_SPEED] ?: 1f
    }

    val adultUnlocked: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ADULT_UNLOCKED] ?: false
    }

    /** Snapshot of the current base URL (normalised, no trailing slash). */
    suspend fun currentBaseUrl(): String = apiBaseUrl.first()

    suspend fun setApiBaseUrl(url: String) {
        val normalised = url.trim().trimEnd('/')
        dataStore.edit { prefs -> prefs[KEY_API_BASE_URL] = normalised }
    }

    suspend fun setDefaultAudio(audio: String) {
        dataStore.edit { prefs -> prefs[KEY_DEFAULT_AUDIO] = audio }
    }

    suspend fun setSkipIntro(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_SKIP_INTRO] = enabled }
    }

    suspend fun setAutoNext(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_AUTO_NEXT] = enabled }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { prefs -> prefs[KEY_PLAYBACK_SPEED] = speed }
    }

    suspend fun setAdultUnlocked(unlocked: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ADULT_UNLOCKED] = unlocked }
    }
}
