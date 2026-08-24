package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val boardedFuzzyRunTripIdKey = stringPreferencesKey("BOARDED_FUZZY_RUN_TRIP_ID")
private val boardedFuzzyRunIdKey = stringPreferencesKey("BOARDED_FUZZY_RUN_ID")

/** A rider's own explicit "Select Run" pick for the currently boarded trip -- see
 * [FuzzyRunTrips.liveRunOptions]/[FuzzyRunTrips.tripUpdateForRun]'s own docs for why this exists
 * separately from the automatic (if sticky) closest-match every other [FuzzyRunTrips] consumer
 * uses. [tripId] lets a caller tell whether this selection is even for the trip currently being
 * viewed/boarded -- a stale selection for a since-alighted trip is just ignored, never cleared out
 * from anywhere but [BoardedFuzzyRunPreferences.clear] itself. */
data class BoardedFuzzyRun(val tripId: String, val runId: String)

/**
 * Tracks the rider's own explicit run selection, persisted via the same Preferences DataStore
 * mechanism [BoardedTripPreferences] uses and with the same single-current-slot shape (one
 * selection at a time, matching "only one trip can be boarded at once") -- deliberately a genuinely
 * separate class/file, not a field on [BoardedTrip] itself, so an agency with no [FuzzyRunTrips]
 * component never has any reason to touch this store at all. Cleared whenever the rider alights
 * (see [BoardedTripPreferences.alight]'s own call sites) since a boarded fuzzy run is meaningless
 * without a boarded trip to attach it to.
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