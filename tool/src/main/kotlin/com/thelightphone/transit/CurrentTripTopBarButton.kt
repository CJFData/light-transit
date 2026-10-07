package com.thelightphone.transit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.thelightphone.transit.gtfs.BoardedTripPreferences
import com.thelightphone.transit.gtfs.gtfsDbFile
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import java.io.File

/**
 * The Current Trip button for a screen's [LightTopBar], shown while a trip is boarded.
 * [onOpenTripDetail] is the caller's `navigateTo { TripDetailScreen(...) }`, since navigateTo is
 * only callable from the screen.
 */
@Composable
fun currentTripTopBarButton(
    dataStore: DataStore<Preferences>,
    filesDir: File,
    onOpenTripDetail: (dbFile: File, tripId: String, fromStopSequence: Int, routeLabel: String, directionLabel: String) -> Unit,
): LightBarButton.LightIcon? {
    val boardedTrip by remember(dataStore) { BoardedTripPreferences(dataStore).boardedTripFlow }.collectAsState(initial = null)
    return boardedTrip?.let { trip ->
        LightBarButton.LightIcon(
            icon = LightIcons.PLAY,
            contentDescription = "Current Trip",
            onClick = {
                onOpenTripDetail(
                    gtfsDbFile(filesDir, trip.agency), trip.tripId, trip.fromStopSequence, trip.routeLabel, trip.directionLabel,
                )
            },
        )
    }
}
