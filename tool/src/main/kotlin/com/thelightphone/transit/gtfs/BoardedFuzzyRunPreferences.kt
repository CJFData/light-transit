package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val boardedFuzzyRunTripIdKey = stringPreferencesKey("BOARDED_FUZZY_RUN_TRIP_ID")
private val boardedFuzzyRunIdKey = stringPreferencesKey("BOARDED_FUZZY_RUN_ID")

/**
 * The rider's Select Run pick for the boarded trip. [tripId] tells callers whether it applies to
 * the trip they're showing; a pick for another trip is ignored.
 */
data class BoardedFuzzyRun(val tripId: String, val runId: String)

/**
 * The rider's run pick, saved in DataStore like [BoardedTripPreferences]. Separate from
 * [BoardedTrip] so agencies without closest-match runs never touch it. Cleared on alighting.
 */
class BoardedFuzzyRunPreferences(private val dataStore: DataStore<Preferences>) {

    val boardedFuzzyRunFlow: Flow<BoardedFuzzyRun?> = dataStore.data.map { prefs ->
        val tripId = prefs[boardedFuzzyRunTripIdKey] ?: return@map null
        val runId = prefs[boardedFuzzyRunIdKey] ?: return@map null
        BoardedFuzzyRun(tripId, runId)
    }

    suspend fun selectRun(tripId: String, runId: String) {
        dataStore.edit { prefs ->
            prefs[boardedFuzzyRunTripIdKey] = tripId
            prefs[boardedFuzzyRunIdKey] = runId
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(boardedFuzzyRunTripIdKey)
            prefs.remove(boardedFuzzyRunIdKey)
        }
    }
}