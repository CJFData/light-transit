package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val darkMapKey = booleanPreferencesKey("DARK_MAP_ENABLED")
private val tapHoldArrivalsKey = booleanPreferencesKey("MAP_TAP_HOLD_ARRIVALS_ENABLED")
private val doubleTapStationKey = booleanPreferencesKey("MAP_DOUBLE_TAP_STATION_ENABLED")
private val trackTappedStopsKey = booleanPreferencesKey("MAP_TRACK_TAPPED_STOPS_ENABLED")
private val seeEverythingKey = booleanPreferencesKey("MAP_SEE_EVERYTHING_ENABLED")
private val filterByStopKey = booleanPreferencesKey("MAP_FILTER_BY_STOP_ENABLED")
private val seeEverythingShowBusKey = booleanPreferencesKey("MAP_SEE_EVERYTHING_SHOW_BUS")
private val seeEverythingShowSubwayKey = booleanPreferencesKey("MAP_SEE_EVERYTHING_SHOW_SUBWAY")
private val seeEverythingShowCommuterRailKey = booleanPreferencesKey("MAP_SEE_EVERYTHING_SHOW_COMMUTER_RAIL")

/**
 * Map settings. [darkTilesEnabledFlow] picks CARTO's Dark Matter tiles (on by default) over
 * Voyager.
 */
class MapPreferences(private val dataStore: DataStore<Preferences>) {

    val darkMapEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[darkMapKey] ?: true }

    suspend fun setDarkMapEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[darkMapKey] = enabled }
    }

    /** On by default, like the other tap-and-hold arrivals toggles. */
    val tapHoldArrivalsEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[tapHoldArrivalsKey] ?: true }

    suspend fun setTapHoldArrivalsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[tapHoldArrivalsKey] = enabled }
    }

    /** On by default. */
    val doubleTapStationEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[doubleTapStationKey] ?: true }

    suspend fun setDoubleTapStationEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[doubleTapStationKey] = enabled }
    }

    /** Off by default. Also tracks vehicles for nearby stops the rider taps open. */
    val trackTappedStopsEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[trackTappedStopsKey] ?: false }

    suspend fun setTrackTappedStopsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[trackTappedStopsKey] = enabled }
    }

    /**
     * On by default. The map shows every live vehicle in view, labeled with its route until tapped.
     * Off, it shows only vehicles scheduled at a stop on screen.
     */
    val seeEverythingEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[seeEverythingKey] ?: true }

    suspend fun setSeeEverythingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[seeEverythingKey] = enabled }
    }

    /**
     * Off by default; only applies with "See everything" on. Keeps vehicles whose trip visits a
     * selected stop, labeled TO/FROM/AT.
     */
    val filterByStopEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[filterByStopKey] ?: false }

    suspend fun setFilterByStopEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[filterByStopKey] = enabled }
    }

    /** On by default. Per-mode toggles for "See everything". */
    val seeEverythingShowBusFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[seeEverythingShowBusKey] ?: true }

    suspend fun setSeeEverythingShowBus(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[seeEverythingShowBusKey] = enabled }
    }

    val seeEverythingShowSubwayFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[seeEverythingShowSubwayKey] ?: true }

    suspend fun setSeeEverythingShowSubway(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[seeEverythingShowSubwayKey] = enabled }
    }

    val seeEverythingShowCommuterRailFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[seeEverythingShowCommuterRailKey] ?: true }

    suspend fun setSeeEverythingShowCommuterRail(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[seeEverythingShowCommuterRailKey] = enabled }
    }
}
