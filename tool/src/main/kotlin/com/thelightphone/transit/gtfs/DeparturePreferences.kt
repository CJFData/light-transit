package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val includeLongerTripsKey = booleanPreferencesKey("DEPARTURES_INCLUDE_LONGER_TRIPS_ENABLED")

/**
 * "Include longer trips", on by default: departures for a destination also include trips that go
 * past it (see [GtfsRepository.getDeparturesForVariant]). Off, only exact headsign matches (see
 * [GtfsRepository.getDeparturesForExactVariant]).
 */
class DeparturePreferences(private val dataStore: DataStore<Preferences>) {

    val includeLongerTripsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[includeLongerTripsKey] ?: true }

    suspend fun setIncludeLongerTripsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[includeLongerTripsKey] = enabled }
    }
}