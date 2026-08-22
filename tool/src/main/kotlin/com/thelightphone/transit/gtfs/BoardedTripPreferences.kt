package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val boardedTripIdKey = stringPreferencesKey("BOARDED_TRIP_ID")
private val boardedAgencyKey = stringPreferencesKey("BOARDED_AGENCY")
private val boardedFromStopSequenceKey = intPreferencesKey("BOARDED_FROM_STOP_SEQUENCE")
private val boardedRouteLabelKey = stringPreferencesKey("BOARDED_ROUTE_LABEL")
private val boardedDirectionLabelKey = stringPreferencesKey("BOARDED_DIRECTION_LABEL")
private val boardedLineTypeKey = stringPreferencesKey("BOARDED_LINE_TYPE")
private val boardedAlightStopIdKey = stringPreferencesKey("BOARDED_ALIGHT_STOP_ID")
private val progressBarVisibleKey = booleanPreferencesKey("TRIP_PROGRESS_BAR_VISIBLE")

/** Everything needed to reopen TripDetailScreen for the trip the rider is currently on, from
 * anywhere in the app (see HomeScreen's "Current Trip" entry) -- not just the trip id, since
 * reopening that screen needs the exact same constructor arguments the original navigation used. */
data class BoardedTrip(
    val tripId: String,
    val agency: GtfsAgency,
    val fromStopSequence: Int,
    val routeLabel: String,
    val directionLabel: String,
    /** This trip's own vehicle mode (bus/subway/commuter rail) -- null only if the route's
     * route_type couldn't be resolved. Carried here (not re-looked-up) so HomeScreen can show the
     * matching vehicle icon next to the Current Trip entry without opening its own repository
     * connection just for this. */
    val lineType: LineType?,
    /** The stop the rider tapped to mark as where they're getting off -- null until they've
     * chosen one. See TripDetailScreen's tap-to-designate / tap-hold-for-connections gesture. */
    val alightStopId: String?,
)

/**
 * Tracks "the current trip" (Trip Detail's Board/Alight feature) -- persisted via the SDK's
 * Preferences DataStore, the same mechanism [AgencyPreferences] uses, so it survives navigating
 * away and even an app restart. Deliberately a saved reference back to Trip Detail rather than a
 * background tracker: real-time "have we reached the alight stop" detection only ever happens
 * while that screen is open (its existing live-position poll), matching how live tracking already
 * works everywhere else in this app -- there's no SDK mechanism for meaningfully-real-time
 * background work (WorkManager's periodic floor is 15 minutes, confirmed via LightWork.kt).
 */
class BoardedTripPreferences(private val dataStore: DataStore<Preferences>) {

    val boardedTripFlow: Flow<BoardedTrip?> = dataStore.data.map { prefs ->
        val tripId = prefs[boardedTripIdKey] ?: return@map null
        val agency = prefs[boardedAgencyKey]?.let { id -> GtfsAgency.entries.find { it.id == id } } ?: return@map null
        val fromStopSequence = prefs[boardedFromStopSequenceKey] ?: return@map null
        BoardedTrip(
            tripId = tripId,
            agency = agency,
            fromStopSequence = fromStopSequence,
            routeLabel = prefs[boardedRouteLabelKey] ?: "",
            directionLabel = prefs[boardedDirectionLabelKey] ?: "",
            lineType = prefs[boardedLineTypeKey]?.let { name -> LineType.entries.find { it.name == name } },
            alightStopId = prefs[boardedAlightStopIdKey],
        )
    }

    suspend fun board(
        tripId: String,
        agency: GtfsAgency,
        fromStopSequence: Int,
        routeLabel: String,
        directionLabel: String,
        lineType: LineType?,
    ) {
        dataStore.edit { prefs ->
            prefs[boardedTripIdKey] = tripId
            prefs[boardedAgencyKey] = agency.id
            prefs[boardedFromStopSequenceKey] = fromStopSequence
            prefs[boardedRouteLabelKey] = routeLabel
            prefs[boardedDirectionLabelKey] = directionLabel
            if (lineType == null) prefs.remove(boardedLineTypeKey) else prefs[boardedLineTypeKey] = lineType.name
            prefs.remove(boardedAlightStopIdKey)
        }
    }

    suspend fun alight() {
        dataStore.edit { prefs ->
            prefs.remove(boardedTripIdKey)
            prefs.remove(boardedAgencyKey)
            prefs.remove(boardedFromStopSequenceKey)
            prefs.remove(boardedRouteLabelKey)
            prefs.remove(boardedDirectionLabelKey)
            prefs.remove(boardedLineTypeKey)
            prefs.remove(boardedAlightStopIdKey)
        }
    }

    suspend fun setAlightStop(stopId: String?) {
        dataStore.edit { prefs ->
            if (stopId == null) prefs.remove(boardedAlightStopIdKey) else prefs[boardedAlightStopIdKey] = stopId
        }
    }

    /** On by default -- HomeScreen's board-to-alight progress bar (Settings screen toggle). */
    val progressBarVisibleFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[progressBarVisibleKey] ?: true }

    suspend fun setProgressBarVisible(visible: Boolean) {
        dataStore.edit { prefs -> prefs[progressBarVisibleKey] = visible }
    }
}
