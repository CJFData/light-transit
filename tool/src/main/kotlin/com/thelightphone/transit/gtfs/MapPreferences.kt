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

    /** Off by default, same reasoning as [tapHoldArrivalsEnabledFlow] -- double-tapping a station
     * marker to open its Station sub-map is an extra gesture layered on top of the Map screen's
     * existing tap/tap-hold behavior, so it's opt-in. */
    val doubleTapStationEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[doubleTapStationKey] ?: false }

    suspend fun setDoubleTapStationEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[doubleTapStationKey] = enabled }
    }

    /** Off by default, same reasoning as [tapHoldArrivalsEnabledFlow] -- expanding a nearby stop
     * to also track ITS vehicles is an extra opt-in on top of the Map screen's default (just the
     * selected stop's own vehicles). Controlled from the Settings screen rather than a toggle
     * drawn directly on the Map canvas -- see [com.thelightphone.transit.MapViewModel.nearbyVehiclesEnabled],
     * which now just reads this persisted value instead of owning its own in-memory one. */
    val trackTappedStopsEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[trackTappedStopsKey] ?: false }

    suspend fun setTrackTappedStopsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[trackTappedStopsKey] = enabled }
    }

    /** Off by default -- when on, the Map screen and Station sub-map drop the usual schedule-
     * anchored vehicle matching entirely and instead plot EVERY live vehicle (any route, any
     * agency feed) whose position falls within the map's own rendered radius, labeled with just
     * its route short name until tapped (see BusMarker.shortLabel/tripDescription). A materially
     * different, much broader view than the default "vehicles actually relevant to this stop" one. */
    val seeEverythingEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[seeEverythingKey] ?: false }

    suspend fun setSeeEverythingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[seeEverythingKey] = enabled }
    }

    /** Off by default, and only meaningful while [seeEverythingEnabledFlow] is also on (Settings
     * only shows this row then) -- narrows the "see everything" vehicle set down to just vehicles
     * whose own trip visits one of the currently tap-selected stops (the same selection
     * [trackTappedStopsEnabledFlow] already uses), tagged TO/FROM/AT that stop instead of a bare
     * route label. */
    val filterByStopEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[filterByStopKey] ?: false }

    suspend fun setFilterByStopEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[filterByStopKey] = enabled }
    }

    /** On by default (all three of these) -- "See Everything" starts out showing every mode, same
     * as before these existed; each is its own per-mode opt-OUT, only meaningful while
     * [seeEverythingEnabledFlow] is also on (Settings only shows these rows then). */
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
