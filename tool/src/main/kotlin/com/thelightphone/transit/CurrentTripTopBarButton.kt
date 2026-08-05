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
 * Play button shown in [com.thelightphone.sdk.ui.LightTopBar]'s rightButton slot, with different
 * behavior on Trip Detail vs. everywhere else:
 *
 * - On Trip Detail, this IS the board/alight toggle: Play icon when not boarded (tap to board this
 *   trip), Stop icon when boarded (tap to alight). If a DIFFERENT trip is already boarded when this
 *   screen opens, a delete icon appears alongside Play to warn that boarding here will end tracking
 *   on that other trip and switch to this one instead.
 * - On every other screen, this button only appears while some trip is boarded, and only ever shows
 *   Play — tapping it just navigates back to that trip's Detail screen. It never boards or alights
 *   anything from outside Trip Detail.
 *
 * Implementation note: this can't be written as a shared helper that any screen calls from outside.
 * It has to be called from inside each screen's own Content() function, for two reasons:
 * 1. It needs [dataStore] and [filesDir], which only exist on a screen's own `lightContext` and
 *    aren't accessible from outside that screen.
 * 2. Navigating to Trip Detail requires [com.thelightphone.sdk.SimpleLightScreen.navigateTo],
 *    which also only works from inside a screen class.
 *
 * Because of #2, this function can't navigate anywhere on its own. Instead, each screen that uses
 * it passes in [onOpenTripDetail] — that screen's own `navigateTo { TripDetailScreen(...) }` call —
 * and this function just invokes whatever was passed in when the button is tapped.
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
