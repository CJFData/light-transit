package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val darkMapKey = booleanPreferencesKey("DARK_MAP_ENABLED")

/**
 * The user's Map screen tile style (Settings screen), persisted the same way [AgencyPreferences]
 * persists the default agency. When enabled, the Map screen fetches CARTO's Dark Matter tiles
 * instead of its default (Voyager, brighter/more legible) tiles -- see [MapTileClient].
 */
class MapPreferences(private val dataStore: DataStore<Preferences>) {

    val darkMapEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[darkMapKey] ?: false }

    suspend fun setDarkMapEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[darkMapKey] = enabled }
    }
}
