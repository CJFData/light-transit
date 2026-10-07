package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val showStopsBeforeBoardingKey = booleanPreferencesKey("SHOW_STOPS_BEFORE_BOARDING_ENABLED")

/**
 * "Show earlier stops", off by default: Trip Detail's list starts at the trip's first stop instead
 * of the boarding stop, greyed out, so a vehicle still approaching the boarding stop has a row to
 * show on.
 */
class TripDetailPreferences(private val dataStore: DataStore<Preferences>) {

    val showStopsBeforeBoardingEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[showStopsBeforeBoardingKey] ?: false }

    suspend fun setShowStopsBeforeBoardingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[showStopsBeforeBoardingKey] = enabled }
    }
}
