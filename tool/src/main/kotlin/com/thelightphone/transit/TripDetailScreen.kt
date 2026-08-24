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
import com.thelightphone.transit.gtfs.GtfsRtStopTimeEvent
import com.thelightphone.transit.gtfs.GtfsRtStopTimeUpdate
import com.thelightphone.transit.gtfs.fetchTripUpdate
import com.thelightphone.transit.gtfs.fetchVehiclePosition
import com.thelightphone.transit.gtfs.matchCurrentStopByProximity
import com.thelightphone.transit.gtfs.FuzzyRunTrips
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.LiveVehicleSource
import com.thelightphone.transit.gtfs.BoardedFuzzyRun
import com.thelightphone.transit.gtfs.BoardedFuzzyRunPreferences
import com.thelightphone.transit.gtfs.FuzzyRunOption
import com.thelightphone.transit.gtfs.liveRunOptionsForTrip
import com.thelightphone.transit.gtfs.RunSelectionPreferences
import com.thelightphone.transit.gtfs.StopPredictionSource
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
import kotlinx.coroutines.CancellationException
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
        /** True only when [liveAtStopSequence]/[liveStatus] came from a [FuzzyRunTrips] source (CTA
         * 'L' trains, MBTA Green Line) -- an approximate rank-matched pairing, never a certain match
         * (see [FuzzyRunTrips]'s own doc). Content() must render this distinctly ("Closest match",
         * not "Live") so a rider never mistakes an approximation for a confirmed prediction. */
        val isClosestMatch: Boolean = false,
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
    private val boardedFuzzyRunPreferences: BoardedFuzzyRunPreferences,
    private val runSelectionPreferences: RunSelectionPreferences,
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

    /** This trip's own real route_id, set only once it's confirmed [FuzzyRunTrips]-eligible (see
     * onScreenShow's own `scopedFuzzyRunTrips` resolution) -- Content() uses non-null as the whole
     * gate for showing the Select Run row at all, and as the value passed to [SelectRunScreen].
     * Null for the vast majority of trips (any agency/route without a FuzzyRunTrips component). */
    val fuzzyRouteId = MutableStateFlow<String?>(null)

    /** The rider's own explicit Select Run pick, collected for this ViewModel's whole lifetime (not
     * just while visible) so both the poll loop and Content()'s "Select Run"/"Run {id} - Change" row
     * label reflect a change made on [SelectRunScreen] immediately upon returning here. Only ever
     * meaningful when it matches this screen's own [tripId] -- see [BoardedFuzzyRun]'s own doc. */
    val boardedFuzzyRun = MutableStateFlow<BoardedFuzzyRun?>(null)

    /** Settings gates for the run-selection row -- see [RunSelectionPreferences]'s own doc for why
     * [runStepperEnabled] is a second, nested toggle (off by default) rather than folded into
     * [runSelectionEnabled] (on by default): the label alone is the default experience, the
     * flanking Next/Previous icons are opt-in on top of it. */
    val runSelectionEnabled = MutableStateFlow(true)
    val runStepperEnabled = MutableStateFlow(false)

    /** Whether Content()'s footer has somewhere to step to/from -- null while not boarded on a
     * [FuzzyRunTrips] route at all (Content() must gate on this being non-null, not just check
     * hasNext/hasPrevious, so the icons don't flash briefly true before the first poll resolves). */
    data class RunStepperState(val hasNext: Boolean, val hasPrevious: Boolean)
    val runStepperState = MutableStateFlow<RunStepperState?>(null)

    /** The poll loop's own last-fetched, direction-filtered, time-sorted run list -- [nextRun]/
     * [previousRun] step through this snapshot directly rather than re-fetching on tap, the same
     * "at most one poll interval stale" tradeoff every other poll-driven control on this screen
     * already accepts. */
    private var lastRunOptions: List<FuzzyRunOption> = emptyList()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch { boardedTripPreferences.boardedTripFlow.collect { boardedTrip.value = it } }
        viewModelScope.launch { boardedFuzzyRunPreferences.boardedFuzzyRunFlow.collect { boardedFuzzyRun.value = it } }
        viewModelScope.launch { runSelectionPreferences.runSelectionEnabledFlow.collect { runSelectionEnabled.value = it } }
        viewModelScope.launch { runSelectionPreferences.runStepperEnabledFlow.collect { runStepperEnabled.value = it } }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val stops = repository.getTripStops(tripId, fromStopSequence)
                val tripLineType = repository.getRouteTypeForTrip(tripId)?.let { LineType.forGtfsRouteType(it) }
                lineType.value = tripLineType
                val stationStopIds = repository.getMultiPlatformStationStopIds()
                val vehiclePositionsUrl = agency?.realtimeVehiclePositionsUrl
                // A richer live source (e.g. CTA Bus Tracker's RunAssociatedTripSource) can locate this
                // trip's vehicle even when the agency has no standard GTFS-RT feed at all -- the same
                // architecture gap Upcoming Arrivals and the Map screen had before LiveVehicleSource/
                // StopPredictionSource were wired into them. Checked here, before the early-return below,
                // so CTA (both realtimeVehiclePositionsUrl and realtimeTripUpdatesUrl null) isn't forced
                // to a permanently-null live state just because the standard feed doesn't exist. Scoped
                // to this trip's own LineType, same coveredLineTypes rule MapScreen/Arrivals already use.
                val liveVehicleSource = agency?.component<LiveVehicleSource>()
                    ?.takeIf { source -> tripLineType != null && tripLineType in source.coveredLineTypes }
                // Same architecture-gap reasoning as liveVehicleSource above, for CTA 'L' trains/MBTA
                // Green Line vehicles that have no real trip to resolve to at all (see FuzzyRunTrips's
                // own doc) -- checked here too so this trip isn't forced to a permanently-null live
                // state just because it's a fuzzy-matched trip_id rather than a certain one.
                val fuzzyRunTrips = agency?.component<FuzzyRunTrips>()
                if (stops.isEmpty() || (vehiclePositionsUrl == null && liveVehicleSource == null && fuzzyRunTrips == null)) {
                    _state.value = TripDetailState.Loaded(
                        stops, liveAtStopSequence = null, liveStatus = null, stationStopIds = stationStopIds,
                    )
                    return@launch
                }
                // Real predicted-time source (e.g. CTA Bus Tracker's getpredictions) for a genuine
                // delay status once a stop is matched below -- see its own resolution order note further
                // down for why this can only be queried after the current stop is known, unlike
                // liveVehicleSource which is route-scoped and available up front.
                val stopPredictionSource = agency.component<StopPredictionSource>()
                // Only resolved when a LiveVehicleSource or FuzzyRunTrips component actually exists to
                // use it -- getRoutesForTrips is a heavier query (joins routes + a last-stop subquery)
                // than the plain getRouteTypeForTrip above, so a standard GTFS agency (neither
                // component, the vast majority) never pays for it; only CTA/MBTA-like agencies need
                // routeId at all.
                val routeId = (liveVehicleSource != null || fuzzyRunTrips != null)
                    .takeIf { it }
                    ?.let { repository.getRoutesForTrips(setOf(tripId))[tripId]?.route?.routeId }
                // fuzzyRunTrips is scoped to specific route_ids (e.g. CTA's 'L' lines only, not its
                // buses) -- this trip's own routeId has to actually be in that set for the component to
                // be usable for it, the same routeId-scoping every matchedTripUpdates call requires.
                val scopedFuzzyRunTrips = fuzzyRunTrips?.takeIf { source -> routeId != null && routeId in source.routeIds }
                fuzzyRouteId.value = scopedFuzzyRunTrips?.let { routeId }
                // Fetched once (not per-poll) since a trip's own stop locations never change --
                // feeds the GPS-proximity fallback below (see matchCurrentStopByProximity's own doc).
                val stopLocations = repository.getTripStopLocations(tripId, fromStopSequence)
                var lastMatchedStopSequence: Int? = null

                while (isActive) {
                    // Recomputed every poll, not hoisted above the loop -- confirmed live
                    // 2026-08-24: a screen left open across midnight kept comparing real live
                    // times against yesterday's date, producing a nonsense "Early by 1400+ minutes"
                    // (the ~24h gap between the two). This poll loop can run for as long as the
                    // rider keeps this screen open, unlike a one-shot load, so the service day
                    // itself has to be treated as something that can change mid-session.
                    val today = todayForGtfs(agency.zoneId)
                    val vehiclePosition = agency.fetchVehiclePosition(tripId)
                    // Fetched unconditionally now (not just once a matched stop is already in hand)
                    // since it also feeds the current-stop fallback below, not just the ETA lookup.
                    val tripUpdate = agency.fetchTripUpdate(tripId)
                    // liveVehicleSource is route-scoped (see its own doc), so this trip's routeId must
                    // resolve for it to be usable at all -- a trip missing its route (shouldn't happen in
                    // practice) just falls through to the standard vehiclePosition chain below.
                    val liveVehicleInfo = liveVehicleSource
                        ?.takeIf { routeId != null }
                        ?.let { source ->
                            try {
                                source.vehiclesByRoute(setOf(routeId!!), repository)[tripId]
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("TripDetailScreen", "Live vehicle fetch failed", e)
                                null
                            }
                        }

                    // A source that can be queried directly by its own vehicle id (e.g. CTA Bus
                    // Tracker's getpredictions?vid=) gives an authoritative next stop + real predicted
                    // time in one call, immune to the geometric ambiguity GPS-proximity matching has on
                    // looping/backtracking routes -- see StopPredictionSource.nextStopForVehicle's own
                    // doc (confirmed live 2026-08-23: a CTA route that loops back through downtown made
                    // a later stop briefly closer in straight-line distance than the true current one,
                    // causing an incorrect forward jump). Tried first; falls through to the
                    // position-based chain below only when unsupported or this vehicle currently has no
                    // predictions (e.g. near a short-turn).
                    val vehicleNextStop = stopPredictionSource?.let { source ->
                        liveVehicleInfo?.vehicleId?.let { vehicleId ->
                            try {
                                source.nextStopForVehicle(vehicleId, repository, agency.zoneId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("TripDetailScreen", "Vehicle-scoped prediction fetch failed", e)
                                null
                            }
                        }
                    }
                    val matchedStopFromVehicle = vehicleNextStop?.let { next -> stops.find { it.stopId == next.stopId } }

                    // Least certain of every source checked here (see FuzzyRunTrips's own doc), so it's
                    // only ever consulted once liveVehicleInfo/vehiclePosition/tripUpdate have all come
                    // up empty below -- an approximate rank-matched pairing should never override a
                    // certain match. A rider's own explicit Select Run pick (see BoardedFuzzyRun's own
                    // doc) is a genuinely different, higher-authority lookup -- no ranking at all, just
                    // that one pinned run's current live data -- checked first so it always wins over
                    // the automatic (if sticky) match once a rider has actually chosen.
                    val pinnedRun = boardedFuzzyRun.value?.takeIf { it.tripId == tripId }
                    val fuzzyTripUpdate = scopedFuzzyRunTrips?.let { source ->
                        try {
                            if (pinnedRun != null) {
                                source.tripUpdateForRun(pinnedRun.runId, tripId, routeId!!, repository, agency, agency.zoneId)
                            } else {
                                source.matchedTripUpdates(setOf(routeId!!), repository, agency, agency.zoneId)[tripId]
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("TripDetailScreen", "Fuzzy run match fetch failed", e)
                            null
                        }
                    }
                    // The soonest remaining stop in the matched live run's own ordered stop list (see
                    // CtaTrainTrackerSource/MbtaGreenLineFuzzyRunSource's own docs -- both build this list
                    // in stop order already), the same "first entry is the next stop" precedent
                    // vehicleNextStop/matchedStopFromVehicle above already establishes for StopPredictionSource.
                    val matchedStopFromFuzzy = fuzzyTripUpdate?.stopTimeUpdate?.firstOrNull()?.stopId
                        ?.let { stopId -> stops.find { it.stopId == stopId } }

                    // Feeds the footer's own Next/Previous stepper -- independent of pinnedRun/
                    // fuzzyTripUpdate above (which only fetch this ONE trip's own matched data); this
                    // is every same-direction live run on the route, so a rider can step through them.
                    if (scopedFuzzyRunTrips != null) {
                        val currentAlightStopId = boardedTrip.value?.takeIf { it.tripId == tripId }?.alightStopId
                        val runOptions = try {
                            scopedFuzzyRunTrips.liveRunOptionsForTrip(
                                tripId, fromStopSequence, routeId!!, repository, agency, agency.zoneId, currentAlightStopId,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("TripDetailScreen", "Run options fetch failed", e)
                            emptyList()
                        }
                        lastRunOptions = runOptions
                        val currentIndex = pinnedRun?.let { pr -> runOptions.indexOfFirst { it.runId == pr.runId } } ?: -1
                        runStepperState.value = RunStepperState(
                            hasNext = if (currentIndex == -1) runOptions.isNotEmpty() else currentIndex < runOptions.lastIndex,
                            hasPrevious = currentIndex > 0,
                        )
                    } else {
                        lastRunOptions = emptyList()
                        runStepperState.value = null
                    }
                    // Computed once, reused both in the fallback chain below and in isClosestMatch's own
                    // check, so the two can never drift out of sync with each other.
                    val tripUpdateInferredSequence = tripUpdate?.inferCurrentStopSequence()

                    // liveVehicleInfo (a richer source, e.g. CTA Bus Tracker) is preferred over the
                    // standard VehiclePositions match when present, same precedence Upcoming
                    // Arrivals/MapScreen already give it. Its own current_stop_sequence is checked first,
                    // then GPS-proximity against its own position -- both ahead of the standard feed's
                    // equivalent fallback chain, which only runs at all when liveVehicleInfo is absent.
                    // VehiclePositions' own current_stop_sequence is preferred when present; falls
                    // back to GPS-proximity matching against the vehicle's raw position (see
                    // matchCurrentStopByProximity), and only as a last resort to inferring it from
                    // TripUpdates' own remaining stops (see GtfsRtTripUpdate.inferCurrentStopSequence)
                    // -- RIPTA's feed needs one of these two fallbacks, since it never populates
                    // current_stop_sequence itself.
                    val liveStopSequence = matchedStopFromVehicle?.stopSequence
                        ?: liveVehicleInfo?.currentStopSequence
                        ?: liveVehicleInfo?.let { info ->
                            matchCurrentStopByProximity(
                                stops, stopLocations, info.latitude, info.longitude, lastMatchedStopSequence,
                            )
                        }?.stopSequence
                        ?: vehiclePosition?.currentStopSequence
                        ?: vehiclePosition?.position?.let { pos ->
                            matchCurrentStopByProximity(
                                stops, stopLocations, pos.latitude.toDouble(), pos.longitude.toDouble(), lastMatchedStopSequence,
                            )
                        }?.stopSequence
                        ?: tripUpdateInferredSequence
                        ?: matchedStopFromFuzzy?.stopSequence
                    lastMatchedStopSequence = liveStopSequence ?: lastMatchedStopSequence

                    val matchedStop = matchedStopFromVehicle
                        ?: liveStopSequence?.let { seq -> stops.find { it.stopSequence == seq } }
                    // True only when nothing above this point resolved a stop -- matchedStopFromFuzzy is
                    // exactly what filled liveStopSequence's last fallback slot, so this stays in sync
                    // with the priority chain above by construction rather than re-deriving it. A
                    // rider's own pinned run is never "closest match" -- they confirmed it themselves.
                    val isClosestMatch = pinnedRun == null && matchedStopFromVehicle == null &&
                        liveVehicleInfo == null && vehiclePosition == null &&
                        tripUpdateInferredSequence == null && matchedStopFromFuzzy != null
                    val liveStatus = matchedStop?.let { stop ->
                        val scheduledTime = stop.departureTime ?: stop.arrivalTime ?: return@let null
                        val rtStopUpdate = tripUpdate?.updateFor(stop.stopId, stop.stopSequence)
                        // vehicleNextStop's own predicted time is reused directly when it's the one that
                        // resolved this exact stop (the common case for CTA -- no extra network call
                        // needed). Otherwise falls back to a stop-scoped prediction lookup, same as
                        // before -- StopPredictionSource is stop-scoped (see its own doc), so there's no
                        // stop_id to ask about until the current stop is known some other way. A real
                        // prediction here is preferred over a standard TripUpdates match, same "real
                        // predicted time beats trust-the-schedule" priority Upcoming Arrivals already
                        // gives StopPredictionSource. fuzzyTripUpdate's own per-stop time is the last
                        // resort, same priority as matchedStopFromFuzzy above.
                        val predictedTime = vehicleNextStop?.takeIf { it.stopId == stop.stopId }?.predictedEpochSeconds
                            ?: stopPredictionSource?.let { source ->
                                try {
                                    source.predictionsByStop(setOf(stop.stopId), repository, agency.zoneId)[tripId]
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e("TripDetailScreen", "Stop prediction fetch failed", e)
                                    null
                                }
                            }
                        val effectiveRtUpdate = predictedTime?.let {
                            GtfsRtStopTimeUpdate(departure = GtfsRtStopTimeEvent(time = it))
                        } ?: rtStopUpdate ?: fuzzyTripUpdate?.updateFor(stop.stopId, stop.stopSequence)
                        computeArrivalEta(scheduledTime, today, effectiveRtUpdate, agency.zoneId)?.status
                    }

                    _state.value = TripDetailState.Loaded(
                        stops,
                        liveAtStopSequence = liveStopSequence,
                        liveStatus = liveStatus,
                        stationStopIds = stationStopIds,
                        isClosestMatch = isClosestMatch,
                    )

                    checkReachedAlightStop(stops, liveStopSequence)

                    delay(LIVE_VEHICLE_POLL_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
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
        viewModelScope.launch {
            boardedTripPreferences.alight()
            // Meaningless without a boarded trip to attach it to -- see BoardedFuzzyRun's own doc.
            boardedFuzzyRunPreferences.clear()
        }
    }

    /** Steps the pinned run one position forward/backward through [lastRunOptions] -- see
     * [RunStepperState]'s own doc. Unpinned (still on the automatic match) always lands on index 0
     * regardless of direction, since Content() only ever shows the Previous icon once a pin already
     * exists (hasPrevious requires currentIndex > 0), so a Previous tap from unpinned can't happen
     * in practice; Next tapped from unpinned establishing a baseline at the soonest option is the
     * only real case. */
    fun nextRun() = shiftRun(1)
    fun previousRun() = shiftRun(-1)

    private fun shiftRun(delta: Int) {
        if (lastRunOptions.isEmpty()) return
        val pinned = boardedFuzzyRun.value?.takeIf { it.tripId == tripId }
        val currentIndex = pinned?.let { pr -> lastRunOptions.indexOfFirst { it.runId == pr.runId } } ?: -1
        val targetIndex = if (currentIndex == -1) 0 else (currentIndex + delta).coerceIn(0, lastRunOptions.lastIndex)
        val target = lastRunOptions.getOrNull(targetIndex) ?: return
        viewModelScope.launch { boardedFuzzyRunPreferences.selectRun(tripId, target.runId) }
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
        dbFile, tripId, fromStopSequence, routeLabel, directionLabel,
        BoardedTripPreferences(lightContext.dataStore), BoardedFuzzyRunPreferences(lightContext.dataStore),
        RunSelectionPreferences(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val lineType by viewModel.lineType.collectAsState()
        val boardedTrip by viewModel.boardedTrip.collectAsState()
        val fuzzyRouteId by viewModel.fuzzyRouteId.collectAsState()
        val boardedFuzzyRun by viewModel.boardedFuzzyRun.collectAsState()
        val runSelectionEnabled by viewModel.runSelectionEnabled.collectAsState()
        val runStepperEnabled by viewModel.runStepperEnabled.collectAsState()
        val runStepperState by viewModel.runStepperState.collectAsState()
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
                // Select Run + the Next/Previous stepper, combined into one centered row right
                // under the header -- ties the whole concept to the boarding/progress-tracking use
                // case (see BoardedFuzzyRun's own doc), not general Trip Detail browsing, and only
                // for a route FuzzyRunTrips actually covers -- fuzzyRouteId is null for every
                // standard-GTFS trip, the vast majority, so this row never renders for them at all.
                // A Row with Arrangement.Center, not a Box with edge alignments -- keeps the icons
                // sitting close to the label they flank instead of pinned out at the screen's own
                // edges. runSelectionEnabled gates the whole row; runStepperEnabled (nested under
                // it in Settings -- see RunSelectionPreferences' own doc) additionally gates just
                // the icons, so the tap-to-open-list label alone is the default experience.
                if (isBoardedHere && fuzzyRouteId != null && agency != null && runSelectionEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (runStepperEnabled && runStepperState?.hasPrevious == true) {
                            LightIcon(
                                icon = LightIcons.REWIND,
                                size = 1.6f,
                                contentDescription = "Previous run",
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .lightClickable { viewModel.previousRun() },
                            )
                        }
                        LightText(
                            // Not the run's own id -- some sources hand back genuinely ugly ones
                            // (e.g. MBTA's "ADDED-1584870162"), not fit for a rider-facing label.
                            text = boardedFuzzyRun?.takeIf { it.tripId == tripId }
                                ?.let { "Run Selected - tap to change" }
                                ?: "Select Run",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.lightClickable {
                                navigateTo(screenFactory = { activity ->
                                    SelectRunScreen(activity, dbFile, agency, fuzzyRouteId!!, routeLabel, tripId, fromStopSequence, alightStopId)
                                })
                            },
                        )
                        if (runStepperEnabled && runStepperState?.hasNext == true) {
                            LightIcon(
                                icon = LightIcons.FAST_FORWARD,
                                size = 1.6f,
                                contentDescription = "Next run",
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .lightClickable { viewModel.nextRun() },
                            )
                        }
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
                                        // See TripDetailState.Loaded.isClosestMatch's own doc -- an
                                        // approximate FuzzyRunTrips pairing must never read as a
                                        // confirmed "Live" prediction, so it keeps its own prefix even
                                        // when a status label is present (unlike the plain "Live" case,
                                        // which shows the bare status label with no prefix).
                                        val text = when {
                                            s.isClosestMatch -> s.liveStatus?.label()?.let { "Closest match - $it" } ?: "Closest match"
                                            else -> s.liveStatus?.label() ?: "Live"
                                        }
                                        LightText(
                                            text = text,
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
