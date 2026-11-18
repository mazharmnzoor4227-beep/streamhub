package com.streamhub.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streamhub_settings",
)

/**
 * App settings persisted in DataStore Preferences.
 * The API base URL is NOT here: the app uses only the build-time constant
 * (ApiClient.DEFAULT_BASE_URL) — no in-app setting, no override. The adult
 * gate is NOT here either: the hidden page asks for the password every time
 * it is opened (see SettingsScreen / AdultGate), nothing persists unlocked.
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    companion object {
        val KEY_DEFAULT_AUDIO = stringPreferencesKey("default_audio")
        val KEY_SKIP_INTRO = booleanPreferencesKey("skip_intro")
        val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next")
        val KEY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
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
}
