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
 * The "Current Trip" entry point every screen's own [LightTopBar] shows in its rightButton slot --
 * present whenever there's a boarded trip (see [BoardedTripPreferences]), gone otherwise, so it's
 * always one tap away from wherever the rider happens to be, not just HomeScreen's own dedicated
 * (vehicle-icon + Play) entry. Called from within a screen's own Content() (needs
 * [dataStore]/[filesDir] from its own `lightContext`) rather than as a receiver/extension, since
 * [SimpleLightScreen.navigateTo] can't be called from outside that class either --
 * [onOpenTripDetail] is the caller's own `navigateTo { TripDetailScreen(...) }` wrapper.
 */
@Composable
fun currentTripTopBarButton(
    dataStore: DataStore<Preferences>,
    filesDir: File,
    onOpenTripDetail: (dbFile: File, tripId: String, fromStopSequence: Int, routeLabel: String, directionLabel: String) -> Unit,
): LightBarButton? {
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
