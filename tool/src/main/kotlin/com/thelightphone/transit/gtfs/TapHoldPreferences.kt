package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val tapHoldScheduleArrivalsKey = booleanPreferencesKey("TAP_HOLD_SCHEDULE_ARRIVALS_ENABLED")
private val tapHoldStationArrivalsKey = booleanPreferencesKey("TAP_HOLD_STATION_ARRIVALS_ENABLED")
private val tapHoldVehicleKey = booleanPreferencesKey("TAP_HOLD_VEHICLE_ENABLED")
private val stationTapArrivalsKey = booleanPreferencesKey("STATION_TAP_ARRIVALS_ENABLED")

/**
 * Settings toggles for "tap and hold a stop to see its actual arrivals" outside the Map screen,
 * which has its own toggle (see MapPreferences.tapHoldArrivalsEnabledFlow). Schedule mode's stop
 * selection list and the Stations browse list each get their own here, both on by default like
 * the Map screen's. All three are purely additive: the short tap keeps doing exactly what it
 * always did either way, and only tap-and-hold's new behavior is gated.
 */
class TapHoldPreferences(private val dataStore: DataStore<Preferences>) {

    val tapHoldScheduleArrivalsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[tapHoldScheduleArrivalsKey] ?: true }

    suspend fun setTapHoldScheduleArrivalsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[tapHoldScheduleArrivalsKey] = enabled }
    }

    val tapHoldStationArrivalsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[tapHoldStationArrivalsKey] ?: true }

    suspend fun setTapHoldStationArrivalsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[tapHoldStationArrivalsKey] = enabled }
    }

    /** On by default -- tap and hold a vehicle marker on the Map screen or Station map to open its
     * own Trip Detail, regardless of "See Everything" mode. Purely additive, same reasoning as the
     * two toggles above: a vehicle marker has no existing short-tap behavior of its own to preserve
     * outside "See Everything" (where a plain tap keeps toggling its short/long label either way). */
    val tapHoldVehicleEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[tapHoldVehicleKey] ?: true }

    suspend fun setTapHoldVehicleEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[tapHoldVehicleKey] = enabled }
    }

    /** On by default -- scoped to the Stations list screen only (StationListScreen), nowhere else.
     * Swaps that screen's two gestures: a plain tap opens a station's live upcoming arrivals
     * directly (its platform map is still one tap away from there, via Upcoming Arrivals' own
     * "Selected stop" row), and tap-and-hold opens the platform map instead. Off, the gestures
     * revert to their original assignment -- plain tap opens the platform map, and
     * [tapHoldStationArrivalsEnabledFlow] above resumes controlling whether tap-and-hold opens
     * arrivals. */
    val stationTapArrivalsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[stationTapArrivalsKey] ?: true }

    suspend fun setStationTapArrivalsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[stationTapArrivalsKey] = enabled }
    }
}
