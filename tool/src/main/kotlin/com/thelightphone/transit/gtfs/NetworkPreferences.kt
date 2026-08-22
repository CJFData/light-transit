package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val wifiOnlyDownloadsKey = booleanPreferencesKey("WIFI_ONLY_DOWNLOADS_ENABLED")

/**
 * Settings toggle that gates the [GtfsIngestor]'s network access -- on by default. When on, it
 * skips both the update check and the schedule download whenever the device isn't on Wi-Fi; a
 * cached schedule keeps working while the gate is active.
 */
class NetworkPreferences(private val dataStore: DataStore<Preferences>) {

    val wifiOnlyDownloadsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[wifiOnlyDownloadsKey] ?: true }

    suspend fun setWifiOnlyDownloadsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[wifiOnlyDownloadsKey] = enabled }
    }
}