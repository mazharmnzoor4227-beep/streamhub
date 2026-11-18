package com.mazharansari.streamhub

import android.app.Application
import com.mazharansari.streamhub.data.SettingsStore
import com.mazharansari.streamhub.data.api.ApiClient
import com.mazharansari.streamhub.data.api.Repository
import com.mazharansari.streamhub.data.extensions.ExtensionStore

/**
 * Holds the app-wide singletons. All content comes from the StreamHub Flask
 * API — there are no built-in providers and no demo data. Extensions are API
 * sources, enabled/disabled locally via [ExtensionStore].
 */
class StreamHubApplication : Application() {

    companion object {
        lateinit var settings: SettingsStore
            private set

        lateinit var extensions: ExtensionStore
            private set

        lateinit var apiClient: ApiClient
            private set

        lateinit var repository: Repository
            private set
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        extensions = ExtensionStore(this)
        apiClient = ApiClient(settings)
        repository = Repository(apiClient, extensions)
    }
}
