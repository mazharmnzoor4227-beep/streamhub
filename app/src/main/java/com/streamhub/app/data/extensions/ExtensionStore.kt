package com.streamhub.app.data.extensions

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.extensionsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streamhub_extensions",
)

/**
 * Persists which extensions (API sources) are enabled.
 * "Install" on the Extensions screen = enable here; only enabled sources
 * are queried for home/search/details (enforced in Repository).
 *
 * First run: no value is stored yet, so [enabledIds] emits the full verified
 * set ([ExtensionsCatalog.verifiedIds]). Once the user toggles anything, the
 * stored set wins — including an explicitly empty set (all disabled).
 */
class ExtensionStore(context: Context) {

    private val dataStore = context.applicationContext.extensionsDataStore

    companion object {
        val KEY_ENABLED_IDS = stringSetPreferencesKey("enabled_extension_ids")
    }

    /** Effective enabled ids (stored set, or all verified sources on first run). */
    val enabledIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_ENABLED_IDS] ?: ExtensionsCatalog.verifiedIds
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val current = enabledIds.first().toMutableSet()
        if (enabled) current.add(id) else current.remove(id)
        dataStore.edit { prefs -> prefs[KEY_ENABLED_IDS] = current }
    }

    suspend fun isEnabled(id: String): Boolean = id in enabledIds.first()

    /** Re-enable every verified source (e.g. "enable all" action). */
    suspend fun enableAllVerified() {
        dataStore.edit { prefs -> prefs[KEY_ENABLED_IDS] = ExtensionsCatalog.verifiedIds }
    }
}
