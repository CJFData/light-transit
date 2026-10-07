package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val wifiOnlyDownloadsKey = booleanPreferencesKey("WIFI_ONLY_DOWNLOADS_ENABLED")

/**
 * "Only download over Wi-Fi", on by default: [GtfsIngestor] skips the update check and download
 * when not on Wi-Fi. A downloaded schedule keeps working.
 */
class NetworkPreferences(private val dataStore: DataStore<Preferences>) {

    val wifiOnlyDownloadsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[wifiOnlyDownloadsKey] ?: true }

    suspend fun setWifiOnlyDownloadsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[wifiOnlyDownloadsKey] = enabled }
    }
}