package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val darkMapKey = booleanPreferencesKey("DARK_MAP_ENABLED")
private val tapHoldArrivalsKey = booleanPreferencesKey("MAP_TAP_HOLD_ARRIVALS_ENABLED")

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

    /** Off by default -- tap-and-hold on a stop marker is an extra gesture on top of the Map
     * screen's existing tap-to-expand behavior, so it's opt-in rather than silently changing what
     * a long press on the map already does for existing users. */
    val tapHoldArrivalsEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[tapHoldArrivalsKey] ?: false }

    suspend fun setTapHoldArrivalsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[tapHoldArrivalsKey] = enabled }
    }
}
