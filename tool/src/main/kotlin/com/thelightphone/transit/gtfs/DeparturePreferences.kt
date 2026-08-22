package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val includeLongerTripsKey = booleanPreferencesKey("DEPARTURES_INCLUDE_LONGER_TRIPS_ENABLED")

/**
 * Settings toggle for [GtfsRepository.getDeparturesForVariant]'s "reaches at least this far"
 * inclusion rule (see that function's own doc) -- on by default. When off, the departures list
 * falls back to an exact-headsign match only (see [GtfsRepository.getDeparturesForExactVariant]):
 * e.g. picking "Toward Readville" would show only Readville-headsign trips, never the longer
 * "Toward South Station" ones that also happen to reach Readville along the way. An escape hatch
 * for a rider who specifically doesn't want the broader trips mixed in, even though they're never
 * misleading in the other direction.
 */
class DeparturePreferences(private val dataStore: DataStore<Preferences>) {

    val includeLongerTripsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[includeLongerTripsKey] ?: true }

    suspend fun setIncludeLongerTripsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[includeLongerTripsKey] = enabled }
    }
}