package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.ArrivalStatus
import com.thelightphone.transit.gtfs.BoardedTrip
import com.thelightphone.transit.gtfs.BoardedTripPreferences
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.fetchTripUpdate
import com.thelightphone.transit.gtfs.fetchVehiclePosition
import com.thelightphone.transit.gtfs.matchCurrentStopByProximity
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
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Matches MapScreen's own polling cadence -- see its own comment on this same constant.
private const val LIVE_VEHICLE_POLL_INTERVAL_MS = 10_000L

sealed class TripDetailState {
    object Loading : TripDetailState()

    /**
     * [liveAtStopSequence] is non-null whenever a live position is available, from GTFS-RT's
     * current_stop_sequence, GPS-proximity matching, or inference from TripUpdates. It is not itself
     * filtered against [stops]: if the live sequence falls outside that trimmed range, it stays
     * non-null even though no row in [stops] will ever match it, so the live indicator just never
     * renders. Per the GTFS-realtime spec, current_stop_sequence means "at, arriving at, or en route
     * to" that stop regardless of current_status, so matching it against a row's stopSequence is
     * enough to place the indicator even between stops. This trip's vehicle type (for that
     * indicator's icon) comes from [TripDetailViewModel.lineType] separately, since it's known as
     * soon as the trip loads.
     *
     * [liveStatus] is the same On Time/Late/Early comparison the other live screens show, computed
     * against the matched stop's scheduled time. It's null whenever there's no TripUpdates
     * prediction yet, which is a normal case, not an error.
     */
    data class Loaded(
        val stops: List<TripStopRow>,
        val liveAtStopSequence: Int?,
        val liveStatus: ArrivalStatus?,
        /** Every stop_id along this trip that's part of a real, qualifying multi-platform station
         * (see GtfsRepository.getMultiPlatformStationStopIds) -- shows the transfer icon next to
         * that row, always, independent of the Map screen's double-tap-to-station setting. */
        val stationStopIds: Set<String>,
    ) : TripDetailState()

    data class Error(val message: String) : TripDetailState()
}

fun ArrivalStatus.label(): String = when (this) {
    ArrivalStatus.OnTime -> "On time"
    is ArrivalStatus.Late -> "Late by ${(seconds / 60).coerceAtLeast(1)}m"
    is ArrivalStatus.Early -> "Early by ${(seconds / 60).coerceAtLeast(1)}m"
}

class TripDetailViewModel(
    private val dbFile: File,
    private val tripId: String,
    private val fromStopSequence: Int,
    private val routeLabel: String,
    private val directionLabel: String,
    private val boardedTripPreferences: BoardedTripPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    // See GtfsAgency.forDbFile -- recovered from the db path rather than threaded through every
    // screen between here and wherever the agency was originally selected.
    private val agency = GtfsAgency.forDbFile(dbFile)

    private val _state = MutableStateFlow<TripDetailState>(TripDetailState.Loading)
    val state: StateFlow<TripDetailState> = _state

    /** This trip's own vehicle mode -- set once as soon as the trip's route loads, independent of
     * live-position availability (see [TripDetailState.Loaded]'s own doc). Used for both the
     * live-row icon and the Board/Alight row's vehicle-type icon, and threaded into [BoardedTrip]
     * so HomeScreen can show it too without its own repository lookup. */
    val lineType = MutableStateFlow<LineType?>(null)

    /** Collected for this ViewModel's whole lifetime (not just while visible) so Board/Alight/
     * alight-stop taps reflect immediately, and so the poll loop below can check it every cycle. */
    val boardedTrip = MutableStateFlow<BoardedTrip?>(null)

    /** One-shot signal: non-null exactly when the rider has just dismissed the "you've arrived"
     * modal for this trip's designated alight stop -- Content() observes this to navigate to that
     * stop's Upcoming Arrivals, then calls [clearReachedAlightStop] to consume it. */
    val reachedAlightStop = MutableStateFlow<TripStopRow?>(null)

    private var pollJob: Job? = null

    init {
        viewModelScope.launch { boardedTripPreferences.boardedTripFlow.collect { boardedTrip.value = it } }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val stops = repository.getTripStops(tripId, fromStopSequence)
                lineType.value = repository.getRouteTypeForTrip(tripId)?.let { LineType.forGtfsRouteType(it) }
                val stationStopIds = repository.getMultiPlatformStationStopIds()
                val vehiclePositionsUrl = agency?.realtimeVehiclePositionsUrl
                if (stops.isEmpty() || vehiclePositionsUrl == null) {
                    _state.value = TripDetailState.Loaded(
                        stops, liveAtStopSequence = null, liveStatus = null, stationStopIds = stationStopIds,
                    )
                    return@launch
                }
                // Fetched once (not per-poll) since a trip's own stop locations never change --
                // feeds the GPS-proximity fallback below (see matchCurrentStopByProximity's own doc).
                val stopLocations = repository.getTripStopLocations(tripId, fromStopSequence)
                var lastMatchedStopSequence: Int? = null
                val today = todayForGtfs(agency.zoneId)

                while (isActive) {
                    val vehiclePosition = agency.fetchVehiclePosition(tripId)
                    // Fetched unconditionally now (not just once a matched stop is already in hand)
                    // since it also feeds the current-stop fallback below, not just the ETA lookup.
                    val tripUpdate = agency.fetchTripUpdate(tripId)
                    // VehiclePositions' own current_stop_sequence is preferred when present; falls
                    // back to GPS-proximity matching against the vehicle's raw position (see
                    // matchCurrentStopByProximity), and only as a last resort to inferring it from
                    // TripUpdates' own remaining stops (see GtfsRtTripUpdate.inferCurrentStopSequence)
                    // -- RIPTA's feed needs one of these two fallbacks, since it never populates
                    // current_stop_sequence itself.
                    val liveStopSequence = vehiclePosition?.currentStopSequence
                        ?: vehiclePosition?.position?.let { pos ->
                            matchCurrentStopByProximity(
                                stops, stopLocations, pos.latitude.toDouble(), pos.longitude.toDouble(), lastMatchedStopSequence,
                            )
                        }?.stopSequence
                        ?: tripUpdate?.inferCurrentStopSequence()
                    lastMatchedStopSequence = liveStopSequence ?: lastMatchedStopSequence

                    val matchedStop = liveStopSequence?.let { seq -> stops.find { it.stopSequence == seq } }
                    val liveStatus = matchedStop?.let { stop ->
                        val scheduledTime = stop.departureTime ?: stop.arrivalTime ?: return@let null
                        val rtStopUpdate = tripUpdate?.updateFor(stop.stopId, stop.stopSequence)
                        computeArrivalEta(scheduledTime, today, rtStopUpdate, agency.zoneId)?.status
                    }

                    _state.value = TripDetailState.Loaded(
                        stops,
                        liveAtStopSequence = liveStopSequence,
                        liveStatus = liveStatus,
                        stationStopIds = stationStopIds,
                    )

                    checkReachedAlightStop(stops, liveStopSequence)

                    delay(LIVE_VEHICLE_POLL_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e("TripDetailScreen", "Failed to load stop times for trip $tripId", e)
                _state.value = TripDetailState.Error("Unable to load trip detail.")
            }
        }
    }

    /** Only checks while THIS trip is the boarded one -- see the shared [checkReachedAlightStop]
     * (used identically by HomeScreenViewModel) for the actual reached/alight/celebration logic. */
    private suspend fun checkReachedAlightStop(stops: List<TripStopRow>, liveStopSequence: Int?) {
        val boarded = boardedTrip.value ?: return
        if (boarded.tripId != tripId) return
        checkReachedAlightStop(boarded, stops, liveStopSequence, boardedTripPreferences) { reachedAlightStop.value = it }
    }

    fun clearReachedAlightStop() {
        reachedAlightStop.value = null
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        pollJob?.cancel()
        pollJob = null
    }

    fun board() {
        val currentAgency = agency ?: return
        viewModelScope.launch {
            boardedTripPreferences.board(tripId, currentAgency, fromStopSequence, routeLabel, directionLabel, lineType.value)
        }
    }

    fun alight() {
        viewModelScope.launch { boardedTripPreferences.alight() }
    }

    /** Only meaningful while this screen's own trip is the boarded one. Tapping the already-
     * designated stop clears it, same toggle convention as Settings' default-agency row. */
    fun toggleAlightStop(stopId: String) {
        val boarded = boardedTrip.value ?: return
        if (boarded.tripId != tripId) return
        viewModelScope.launch {
            boardedTripPreferences.setAlightStop(if (boarded.alightStopId == stopId) null else stopId)
        }
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

    override fun createViewModel(): TripDetailViewModel = TripDetailViewModel(
        dbFile, tripId, fromStopSequence, routeLabel, directionLabel, BoardedTripPreferences(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val lineType by viewModel.lineType.collectAsState()
        val boardedTrip by viewModel.boardedTrip.collectAsState()
        val reachedAlightStop by viewModel.reachedAlightStop.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        // Fires once the "you've arrived" modal has been dismissed (manually or by timeout) --
        // see ReachedStopModal. Navigates to that stop's own Upcoming Arrivals, matching "the stop
        // they just alighted at" from the feature spec.
        LaunchedEffect(reachedAlightStop) {
            val stop = reachedAlightStop ?: return@LaunchedEffect
            val agency = GtfsAgency.forDbFile(dbFile)
            if (agency != null) {
                navigateTo(screenFactory = { activity ->
                    UpcomingArrivalsScreen(activity, dbFile, agency, listOf(stop.stopId), stop.stopName ?: "Stop ${stop.stopId}")
                })
            }
            viewModel.clearReachedAlightStop()
        }

        val isBoardedHere = boardedTrip?.tripId == tripId
        val alightStopId = boardedTrip?.takeIf { it.tripId == tripId }?.alightStopId
        val vehicleIcon = lineType.toVehicleIcon()
        val agency = GtfsAgency.forDbFile(dbFile)

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                Box {
                    // This screen's own Board/Alight toggle -- Play when this trip isn't the
                    // boarded one (tap to board it), Stop when it is (tap to alight). Distinct from
                    // every other screen's currentTripTopBarButton "return to trip" button (which
                    // never changes state, just navigates) -- Trip Detail always shows its own
                    // toggle, even while a different trip is boarded elsewhere. No rightButton here
                    // (LightTopBar only takes one) since a trip switch also needs its own warning
                    // icon alongside the toggle, so a plain Row is stacked on top instead, matching
                    // HomeScreen's own two-icon corner.
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("Trip Detail"),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3f.gridUnitsAsDp())
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A different trip is already boarded -- tapping Play here would end that
                        // one and start tracking this one instead, so it's flagged before the tap
                        // rather than silently swapping.
                        if (boardedTrip != null && !isBoardedHere) {
                            LightIcon(
                                icon = LightIcons.DELETE,
                                size = 1.2f,
                                contentDescription = "Boarding this trip will end tracking of your current trip",
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        LightIcon(
                            icon = if (isBoardedHere) LightIcons.STOP else LightIcons.PLAY,
                            size = 1.2f,
                            contentDescription = if (isBoardedHere) "Alight" else "Board",
                            modifier = Modifier.lightClickable {
                                if (isBoardedHere) viewModel.alight() else viewModel.board()
                            },
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                LightText(
                    text = "$routeLabel ($directionLabel)",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                // Once an alight stop is actually picked, the underlined stop below already shows
                // it -- this instruction has done its job and would just be stale clutter from
                // here on (tap-and-hold still works for connections, just no longer called out).
                if (isBoardedHere && alightStopId == null) {
                    LightText(
                        text = "Tap a stop below to mark where you're getting off. Tap and hold a " +
                            "stop to see its connections.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }

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
                                val isLive = s.liveAtStopSequence != null && stop.stopSequence == s.liveAtStopSequence

                                fun openConnections() {
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

                                fun openArrivals() {
                                    val stopAgency = agency ?: return
                                    navigateTo(screenFactory = { activity ->
                                        UpcomingArrivalsScreen(activity, dbFile, stopAgency, listOf(stop.stopId), stop.stopName ?: "Stop ${stop.stopId}")
                                    })
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .let { base ->
                                            if (isBoardedHere) {
                                                // While boarded, a short tap designates/clears this stop as the alight stop
                                                // instead -- tap-and-hold still reaches its connections, same as the
                                                // not-boarded case below. Uses detectTapGestures specifically, not a
                                                // hand-rolled awaitEachGesture loop: an unconditional
                                                // awaitFirstDown().consume() claims every touch starting on a row, including
                                                // the start of a swipe, before LazyColumn's own scroll gesture gets a chance
                                                // to recognize the drag. detectTapGestures respects touch slop, so a real
                                                // swipe still falls through to the list's scrolling.
                                                base.pointerInput(stop.stopId) {
                                                    detectTapGestures(
                                                        onTap = { viewModel.toggleAlightStop(stop.stopId) },
                                                        onLongPress = { openConnections() },
                                                    )
                                                }
                                            } else {
                                                // Not boarded: a short tap keeps opening this stop's connections, same as
                                                // always; tap-and-hold newly opens its live upcoming arrivals instead -- same
                                                // gesture split as the boarded branch above, just with arrivals in the
                                                // long-press slot since connections already has the short tap here.
                                                base.pointerInput(stop.stopId) {
                                                    detectTapGestures(
                                                        onTap = { openConnections() },
                                                        onLongPress = { openArrivals() },
                                                    )
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        if (isLive) {
                                            LightIcon(
                                                icon = vehicleIcon,
                                                size = 1.2f,
                                                contentDescription = "Live vehicle",
                                                modifier = Modifier.padding(end = 8.dp),
                                            )
                                        }
                                        // Weighted so a long stop name wraps within its own share of the row instead
                                        // of first greedily measuring against the row's full width and only then
                                        // discovering there's no room for the trailing time -- see NearbyStopsScreen's
                                        // identical fix for the same Compose behavior.
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            LightText(
                                                text = stop.stopName ?: "Unknown stop",
                                                variant = LightTextVariant.Copy,
                                                underline = stop.stopId == alightStopId,
                                            )
                                            if (stop.stopId in s.stationStopIds) {
                                                LightIcon(
                                                    icon = LightIcons.DIRECTIONS_MIDDLE_FORK,
                                                    size = 1.2f,
                                                    contentDescription = "Transfer station",
                                                    modifier = Modifier.padding(start = 8.dp),
                                                )
                                            }
                                        }
                                        LightText(
                                            text = formatGtfsTime(stop.arrivalTime ?: stop.departureTime),
                                            variant = LightTextVariant.Copy,
                                            lighten = true,
                                            modifier = Modifier.padding(start = 16.dp),
                                        )
                                    }
                                    if (isLive) {
                                        LightText(
                                            text = s.liveStatus?.label() ?: "Live",
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                            // Indented to align under the stop name, not the vehicle icon before it
                                            // (1.2f size + the icon's own 8dp end padding) -- a sibling of that Row, not
                                            // a child of it, so it needs its own matching start padding.
                                            modifier = Modifier.padding(start = 1.2f.gridUnitsAsDp() + 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
                BackToHomeFooter(onGoBackOnce = { goBack() })
            }
        }
    }
}
