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

// Long enough that a rider glancing at their phone has time to notice and read it, short enough
// that it doesn't sit forever if they don't tap Close -- either way, the caller navigates once
// it's gone (see TripDetailScreen/HomeScreen's own reachedAlightStop handling).
val REACHED_STOP_MODAL_DURATION = 15.seconds

/** The dismissable "you've arrived" celebration -- reuses the SDK's own full-screen modal template
 * rather than custom UI. Manual close (tap) and auto-expiry ([REACHED_STOP_MODAL_DURATION]) both
 * end up calling [onDismissed] exactly once -- LightModalManager only invokes [onExpired] when
 * [awaitDismiss] *didn't* already win the race, so there's no risk of double-firing the navigation
 * it triggers. */
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
 * Reached (or passed) [boardedTrip]'s designated alight stop -- ends boarded status immediately
 * (matches "the stop they just alighted at" from the feature spec, and self-guards against
 * re-triggering the modal on a later poll, since the boarded trip will no longer match once this
 * runs) and shows the celebration via [LightModalManager], which draws on top of whatever screen
 * is current. Shared between [com.thelightphone.transit.TripDetailViewModel] (only checks while
 * showing THIS trip's own detail screen) and [com.thelightphone.transit.HomeScreenViewModel]
 * (checks regardless of which trip's detail screen, if any, is open) -- either way, this only ever
 * runs while ONE of those two screens is actually visible and polling, matching
 * [BoardedTripPreferences]'s own doc comment on why there's no background equivalent.
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
