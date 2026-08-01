package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.ArrivalStatus
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRealtimeClient
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.TripStopRow
import com.thelightphone.transit.gtfs.computeArrivalEta
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.transit.gtfs.todayForGtfs
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

// Matches MapScreen's own polling cadence -- see its own comment on this same constant.
private const val LIVE_VEHICLE_POLL_INTERVAL_MS = 10_000L

sealed class TripDetailState {
    object Loading : TripDetailState()

    /**
     * [liveEmoji]/[liveAtStopSequence] are null together whenever there's no live position to show
     * (no realtime feed for this agency, this trip isn't currently reporting one, or the vehicle's
     * current_stop_sequence doesn't match any row still in [stops]). Per the GTFS-realtime spec,
     * current_stop_sequence means "at, arriving at, or en route to" that stop regardless of
     * current_status -- so matching it against a row's stopSequence alone is enough to place the
     * emoji at the stop the vehicle is closest to, including while it's between stops.
     *
     * [liveStatus] is the same On Time/Late/Early comparison the other live screens show, computed
     * against the matched stop's own scheduled time -- null whenever there's no TripUpdates
     * prediction for it yet (a live position with no matching prediction is a real, normal case,
     * not an error).
     */
    data class Loaded(
        val stops: List<TripStopRow>,
        val liveEmoji: String?,
        val liveAtStopSequence: Int?,
        val liveStatus: ArrivalStatus?,
    ) : TripDetailState()

    data class Error(val message: String) : TripDetailState()
}

fun ArrivalStatus.label(): String = when (this) {
    ArrivalStatus.OnTime -> "On time"
    is ArrivalStatus.Late -> "Late by ${(seconds / 60).coerceAtLeast(1)}m"
    is ArrivalStatus.Early -> "Early by ${(seconds / 60).coerceAtLeast(1)}m"
}

class TripDetailViewModel(
    dbFile: File,
    private val tripId: String,
    private val fromStopSequence: Int,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    // See GtfsAgency.forDbFile -- recovered from the db path rather than threaded through every
    // screen between here and wherever the agency was originally selected.
    private val agency = GtfsAgency.forDbFile(dbFile)

    private val _state = MutableStateFlow<TripDetailState>(TripDetailState.Loading)
    val state: StateFlow<TripDetailState> = _state

    private var pollJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val stops = repository.getTripStops(tripId, fromStopSequence)
                val emoji = repository.getRouteTypeForTrip(tripId)?.let { LineType.forGtfsRouteType(it)?.emoji } ?: "🚌"
                val vehiclePositionsUrl = agency?.realtimeVehiclePositionsUrl
                if (stops.isEmpty() || vehiclePositionsUrl == null) {
                    _state.value = TripDetailState.Loaded(stops, liveEmoji = null, liveAtStopSequence = null, liveStatus = null)
                    return@launch
                }
                val tripUpdatesUrl = agency.realtimeTripUpdatesUrl
                val today = todayForGtfs()

                while (isActive) {
                    val liveStopSequence = try {
                        GtfsRealtimeClient.fetchFeed(vehiclePositionsUrl)
                            .vehiclePositionsByTripId[tripId]?.currentStopSequence
                    } catch (e: Exception) {
                        Log.e("TripDetailScreen", "VehiclePositions fetch failed for trip $tripId", e)
                        null
                    }

                    // Only worth fetching TripUpdates at all once we actually have a stop to check
                    // a prediction against.
                    val matchedStop = liveStopSequence?.let { seq -> stops.find { it.stopSequence == seq } }
                    val liveStatus = matchedStop?.let { stop ->
                        val scheduledTime = stop.departureTime ?: stop.arrivalTime ?: return@let null
                        val rtStopUpdate = tripUpdatesUrl?.let { url ->
                            try {
                                GtfsRealtimeClient.fetchFeed(url).tripUpdatesByTripId[tripId]
                                    ?.updateFor(stop.stopId, stop.stopSequence)
                            } catch (e: Exception) {
                                Log.e("TripDetailScreen", "TripUpdates fetch failed for trip $tripId", e)
                                null
                            }
                        }
                        computeArrivalEta(scheduledTime, today, rtStopUpdate)?.status
                    }

                    _state.value = TripDetailState.Loaded(
                        stops,
                        liveEmoji = emoji,
                        liveAtStopSequence = liveStopSequence,
                        liveStatus = liveStatus,
                    )
                    delay(LIVE_VEHICLE_POLL_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e("TripDetailScreen", "Failed to load stop times for trip $tripId", e)
                _state.value = TripDetailState.Error("Unable to load trip detail.")
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class TripDetailScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val tripId: String,
    private val fromStopSequence: Int,
    private val routeLabel: String,
    private val directionLabel: String,
) : LightScreen<Unit, TripDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<TripDetailViewModel>
        get() = TripDetailViewModel::class.java

    override fun createViewModel(): TripDetailViewModel = TripDetailViewModel(dbFile, tripId, fromStopSequence)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Trip Detail"),
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                LightText(
                    text = "$routeLabel ($directionLabel)",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                when (val s = state) {
                    is TripDetailState.Loading -> LightText(
                        text = "Loading trip...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is TripDetailState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is TripDetailState.Loaded -> if (s.stops.isEmpty()) {
                        LightText(
                            text = "No stops found for this trip.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.stops) { stop ->
                                val isLive = s.liveEmoji != null && stop.stopSequence == s.liveAtStopSequence
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            val afterTime = stop.arrivalTime ?: stop.departureTime
                                            if (afterTime != null) {
                                                navigateTo(screenFactory = { activity ->
                                                    StopConnectionsScreen(
                                                        activity,
                                                        dbFile,
                                                        stop.stopId,
                                                        stop.stopName ?: "Stop ${stop.stopId}",
                                                        afterTime,
                                                        tripId,
                                                    )
                                                })
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        if (isLive) {
                                            LightText(
                                                text = s.liveEmoji,
                                                variant = LightTextVariant.Copy,
                                                modifier = Modifier.padding(end = 8.dp),
                                            )
                                        }
                                        LightText(
                                            text = stop.stopName ?: "Unknown stop",
                                            variant = LightTextVariant.Copy,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 16.dp),
                                        )
                                        LightText(
                                            text = formatGtfsTime(stop.arrivalTime ?: stop.departureTime),
                                            variant = LightTextVariant.Copy,
                                            lighten = true,
                                        )
                                    }
                                    if (isLive) {
                                        LightText(
                                            text = s.liveStatus?.label() ?: "Live",
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
