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

/** Everything needed to reopen Trip Detail for the boarded trip from anywhere in the app. */
data class BoardedTrip(
    val tripId: String,
    val agency: GtfsAgency,
    val fromStopSequence: Int,
    val routeLabel: String,
    val directionLabel: String,
    /** The trip's vehicle type, saved so Home can show its icon without a lookup. */
    val lineType: LineType?,
    /** The stop the rider picked to get off at; null until chosen. */
    val alightStopId: String?,
)

/**
 * The boarded trip, saved in DataStore so it survives navigation and restarts. Arrival at the
 * alight stop is only checked while Home or Trip Detail is open and polling.
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

    /** On by default: Home's progress bar for the boarded trip. */
    val progressBarVisibleFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[progressBarVisibleKey] ?: true }

    suspend fun setProgressBarVisible(visible: Boolean) {
        dataStore.edit { prefs -> prefs[progressBarVisibleKey] = visible }
    }
}
