package com.thelightphone.transit

import androidx.compose.runtime.Composable
import com.thelightphone.transit.gtfs.BoardedTrip
import com.thelightphone.transit.gtfs.BoardedTripPreferences
import com.thelightphone.transit.gtfs.TripStopRow
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightModalManager
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred

// How long the "you've arrived" message stays up before closing on its own.
val REACHED_STOP_MODAL_DURATION = 15.seconds

/**
 * The "you've arrived" message, using the SDK's full-screen modal. Closing it and timing out both
 * call [onDismissed] exactly once.
 */
class ReachedStopModal(
    stopName: String,
    private val onDismissed: () -> Unit,
) : LightModal {
    private val message = "You've reached your stop! 🎉\n$stopName"
    private val dismissSignal = CompletableDeferred<Unit>()

    @Composable
    override fun Content() {
        LightFullscreenModal(message = message, onClose = { dismiss() })
    }

    override val onExpired: () -> Unit = { onDismissed() }

    override fun dismiss() {
        if (dismissSignal.complete(Unit)) onDismissed()
    }

    override suspend fun awaitDismiss() {
        dismissSignal.await()
    }
}

/**
 * When the vehicle reaches or passes the alight stop: ends the boarded trip (so it can't fire
 * again) and shows [ReachedStopModal] over the current screen. Used by Trip Detail for its own trip
 * and by Home.
 */
suspend fun checkReachedAlightStop(
    boardedTrip: BoardedTrip,
    stops: List<TripStopRow>,
    liveStopSequence: Int?,
    boardedTripPreferences: BoardedTripPreferences,
    onReached: (TripStopRow) -> Unit,
) {
    if (liveStopSequence == null) return
    val alightStopId = boardedTrip.alightStopId ?: return
    val alightStop = stops.find { it.stopId == alightStopId } ?: return
    if (liveStopSequence < alightStop.stopSequence) return

    boardedTripPreferences.alight()
    LightModalManager.show(
        modal = ReachedStopModal(
            stopName = alightStop.stopName ?: "your stop",
            onDismissed = { onReached(alightStop) },
        ),
        duration = REACHED_STOP_MODAL_DURATION,
    )
}
