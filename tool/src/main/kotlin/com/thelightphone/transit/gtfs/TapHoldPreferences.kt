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
 * Tap-and-hold settings outside the map (see MapPreferences.tapHoldArrivalsEnabledFlow). Schedule's
 * stop list and the Stations list each have one, on by default; tap keeps its usual action.
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

    /** On by default: tap and hold a vehicle on a map to open its trip. */
    val tapHoldVehicleEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[tapHoldVehicleKey] ?: true }

    suspend fun setTapHoldVehicleEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[tapHoldVehicleKey] = enabled }
    }

    /**
     * On by default, Stations list only: tap opens a station's arrivals and tap and hold opens its
     * platform map. Off, the reverse, with [tapHoldStationArrivalsEnabledFlow] controlling tap and
     * hold.
     */
    val stationTapArrivalsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[stationTapArrivalsKey] ?: true }

    suspend fun setStationTapArrivalsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[stationTapArrivalsKey] = enabled }
    }
}
