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
import com.thelightphone.transit.gtfs.ArrivalEta
import com.thelightphone.transit.gtfs.ArrivalStatus
import com.thelightphone.transit.gtfs.Alert
import com.thelightphone.transit.gtfs.AlertPreferences
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.GtfsRtStopTimeEvent
import com.thelightphone.transit.gtfs.GtfsRtStopTimeUpdate
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.LiveVehicleSource
import com.thelightphone.transit.gtfs.computeArrivalEta
import com.thelightphone.transit.gtfs.currentGtfsTimeOfDay
import com.thelightphone.transit.gtfs.fetchMergedTripUpdates
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.transit.gtfs.FuzzyRunTrips
import com.thelightphone.transit.gtfs.gtfsTimeToEpochSeconds
import com.thelightphone.transit.gtfs.isStale
import com.thelightphone.transit.gtfs.ScheduledArrival
import com.thelightphone.transit.gtfs.StopPredictionSource
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// Matches MapScreen's own LIVE_VEHICLE_POLL_INTERVAL_MS -- fast enough that a live source's own
// refresh cadence (CTA Bus Tracker updates roughly every 30s per its own docs) is never the
// bottleneck, without polling so often it wastes requests on data that hasn't changed yet.
private const val ARRIVALS_POLL_INTERVAL_MS = 10_000L

data class ArrivalRow(
    val tripId: String,
    val stopSequence: Int,
    val routeLabel: String,
    val directionLabel: String,
    /** Mode icon shown in place of the old "Bus -"/"Subway -"/"Commuter Rail -" text prefix -- see
     * [toVehicleIcon] (MapScreen.kt), the single source of truth for this mapping. */
    val lineType: LineType?,
    val etaEpochSeconds: Long,
    val isLive: Boolean,
    /** True only when this row's live data came from a [FuzzyRunTrips] source (CTA 'L' trains, MBTA
     * subway) -- an approximate pairing (soonest live run <-> soonest scheduled trip by rank,
     * see [FuzzyRunTrips]'s own doc), never a certain match the way every other live source is.
     * [statusLabel] must surface this distinctly ("Closest match", not "Live") so a rider never
     * reads an approximation as a confirmed prediction. */
    val isClosestMatch: Boolean = false,
    val status: ArrivalStatus?,
    /** This arrival's own platform within the station, only populated when the selected stop is an
     * actual multi-platform grouped station (e.g. "Track 1", "Ashmont/Braintree") -- see
     * GtfsRepository.getScheduledArrivals(stopIds: List<String>, ...). Null for a plain stop. */
    val platformLabel: String?,
    /** The agency's timezone, not the device's: [etaDisplay] renders [etaEpochSeconds] as a clock
     * time in the agency's zone, like all GTFS time math in this codebase. */
    val zoneId: ZoneId,
)

fun ArrivalRow.etaDisplay(): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(etaEpochSeconds), zoneId)
    return formatGtfsTime("%02d:%02d:00".format(time.hour, time.minute))
}

/** e.g. "Red Line - Toward Alewife - Alewife" or "Commuter Rail - Toward Providence - Track 1" --
 * the platform is only appended when this arrival actually came from a grouped multi-platform
 * station ([ArrivalRow.platformLabel] non-null); a plain stop's line is unchanged. */
fun ArrivalRow.routeAndDirectionLabel(): String {
    val base = "$routeLabel - $directionLabel"
    return platformLabel?.let { "$base - $it" } ?: base
}

fun ArrivalRow.statusLabel(): String? {
    val prefix = if (isClosestMatch) "Closest match" else "Live"
    return when (val s = status) {
        null -> if (isClosestMatch) prefix else null
        ArrivalStatus.OnTime -> "$prefix - On time"
        is ArrivalStatus.Late -> "$prefix - Late by ${(s.seconds / 60).coerceAtLeast(1)}m"
        is ArrivalStatus.Early -> "$prefix - Early by ${(s.seconds / 60).coerceAtLeast(1)}m"
    }
}

sealed class UpcomingArrivalsState {
    object Loading : UpcomingArrivalsState()

    /**
     * [isOffline] is true when there's no live feed at all (no [GtfsAgency.realtimeTripUpdatesUrl],
     * or the fetch failed), as opposed to a feed with no update for one trip, which is shown per row
     * without a badge.
     */
    data class Loaded(
        val arrivals: List<ArrivalRow>,
        val isOffline: Boolean,
        val realtimeStale: Boolean,
    ) : UpcomingArrivalsState()

    data class Error(val message: String) : UpcomingArrivalsState()
}

class UpcomingArrivalsViewModel(
    private val dbFile: File,
    private val agency: GtfsAgency,
    /** Every child platform stop_id belonging to the selected stop -- more than one entry means this
     * is a real multi-platform grouped station (see GtfsRepository.groupStationsByParent), in
     * which case arrivals across every platform are unioned and each is labeled with its own
     * platform. A plain stop is just its own single-element list. */
    private val stopIds: List<String>,
    private val alertPreferences: AlertPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<UpcomingArrivalsState>(UpcomingArrivalsState.Loading)
    val state: StateFlow<UpcomingArrivalsState> = _state

    /** Whether the selected stop is itself a real, qualifying multi-platform station -- see
     * GtfsRepository.getStationContaining, the same source of truth every other screen's transfer
     * icon uses. Resolved via [stopIds]'s first entry rather than checking its length, since a
     * caller may pass just one representative id even for a station (e.g. the Map screen's
     * tap-and-hold shortcut). Kept separate from [state] so the icon can render as soon as this
     * quick lookup resolves, without waiting on the network-bound arrivals fetch below. */
    val isStation = MutableStateFlow(false)

    /** Alerts naming this stop, when alerts are shown in menus. */
    val stopAlerts = MutableStateFlow<Pair<List<Alert>, ScreenAlerts>?>(null)

    /** Tracked so a previous load job is cancelled on hide/re-show, like other screens' load jobs;
     * an untracked one could outlive [onCleared] and hit the closed [repository]. */
    private var loadJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        loadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { stopAlerts.value = loadStopAlerts(dbFile, repository, alertPreferences, stopIds) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            // Own try/catch (not folded into the state one below) so a screen popped mid-query -- e.g.
            // several rapid-fire goBack() calls in a row, like BackToHomeFooter's "jump to Home" loop --
            // can't crash the app just because this repository got closed out from under an in-flight
            // query on the way out. Same reasoning applies to every DB call in this coroutine, not just
            // this one.
            try {
                isStation.value = repository.getStationContaining(stopIds.first()) != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("UpcomingArrivalsScreen", "getStationContaining failed for stops $stopIds", e)
            }
            try {
                // Scheduled trips snapshotted once per service day, not re-queried every poll --
                // same reasoning as MapScreen's own scheduledArrivalsByStopId: only the live-data
                // fetches below need to repeat on a cadence, not the static schedule itself.
                // Previously this whole block ran exactly once per screen visit with no poll loop
                // at all, so live data (ETAs, delay status) never updated after the initial load no
                // matter how long the screen stayed open -- confirmed as the real cause of arrivals
                // "taking so long to update": it wasn't network/caching latency, the screen just
                // never asked again.
                var today = todayForGtfs(agency.zoneId)
                var scheduled = repository.getScheduledArrivals(stopIds, currentGtfsTimeOfDay(agency.zoneId), today)

                while (isActive) {
                    // Re-snapshotted when the service day rolls over while this screen is open. The schedule itself
                    // is re-fetched, not just re-dated, since calendar.txt can run different trips on different days.
                    val currentDay = todayForGtfs(agency.zoneId)
                    if (currentDay != today) {
                        today = currentDay
                        scheduled = repository.getScheduledArrivals(stopIds, currentGtfsTimeOfDay(agency.zoneId), today)
                    }
                    _state.value = loadArrivalsState(scheduled, today)
                    delay(ARRIVALS_POLL_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("UpcomingArrivalsScreen", "Failed to load arrivals for stops $stopIds", e)
                _state.value = UpcomingArrivalsState.Error("Unable to load arrivals.")
            }
        }
    }

    private suspend fun loadArrivalsState(
        scheduled: List<ScheduledArrival>,
        today: LocalDate,
    ): UpcomingArrivalsState = try {
                // Merged with any MultiGtfsFeed component's own realtime feed (e.g. Bustang under RTD
                // Denver) -- see MergedRealtimeFeed's own doc. [feed.primary] (used below for
                // staleness/offline) stays keyed off the agency's own primary feed only.
                val feed = agency.fetchMergedTripUpdates(repository, "UpcomingArrivalsScreen")

                // Agencies without GTFS-RT leave [feed] empty and supply live data through a [StopPredictionSource]
                // (predicted times, preferred) or a [LiveVehicleSource] (positions only). Both are fetched concurrently,
                // since neither depends on the other.
                val stopPredictionSource = agency.component<StopPredictionSource>()
                val liveVehicleSource = agency.component<LiveVehicleSource>()
                val fuzzyRunTrips = agency.component<FuzzyRunTrips>()
                val liveSourceRouteIds = liveVehicleSource?.let { source ->
                    scheduled.filterTo(mutableSetOf()) { LineType.forGtfsRouteType(it.route.routeType) in source.coveredLineTypes }
                        .mapTo(mutableSetOf()) { it.route.routeId }
                }.orEmpty()
                // Scoped to routes actually scheduled at this stop, not FuzzyRunTrips' own full
                // routeIds set -- same rate-limit discipline as liveSourceRouteIds above.
                val fuzzyRouteIds = scheduled.mapTo(mutableSetOf()) { it.route.routeId }
                val (stopPredictions, liveVehiclesByTripId, fuzzyMatchesByTripId) = coroutineScope {
                    val predictionsDeferred = async {
                        stopPredictionSource?.let { source ->
                            try {
                                source.predictionsByStop(stopIds.toSet(), repository, agency.zoneId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("UpcomingArrivalsScreen", "Stop prediction fetch failed", e)
                                emptyMap()
                            }
                        } ?: emptyMap()
                    }
                    val vehiclesDeferred = async {
                        liveVehicleSource
                            ?.takeIf { liveSourceRouteIds.isNotEmpty() }
                            ?.let { source ->
                                try {
                                    source.vehiclesByRoute(liveSourceRouteIds, repository)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e("UpcomingArrivalsScreen", "Live vehicle fetch failed", e)
                                    emptyMap()
                                }
                            } ?: emptyMap()
                    }
                    val fuzzyDeferred = async {
                        fuzzyRunTrips
                            ?.takeIf { fuzzyRouteIds.isNotEmpty() }
                            ?.let { source ->
                                try {
                                    source.matchedTripUpdates(fuzzyRouteIds, repository, agency, agency.zoneId)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e("UpcomingArrivalsScreen", "Fuzzy run match fetch failed", e)
                                    emptyMap()
                                }
                            } ?: emptyMap()
                    }
                    Triple(predictionsDeferred.await(), vehiclesDeferred.await(), fuzzyDeferred.await())
                }

                val rows = scheduled.mapNotNull { arrival ->
                    // Matched against this specific arrival's own platform (arrival.stopId), not
                    // just whichever platform the screen was originally opened for -- a grouped
                    // station's live predictions are per-platform, same as its static schedule.
                    val rtStopUpdate = feed.byTripId[arrival.tripId]
                        ?.updateFor(arrival.stopId, arrival.stopSequence)
                    // computeArrivalEta never returns null (it falls back to a schedule-only time), so the other
                    // live sources have to be checked before calling it, not after. In order of preference: a
                    // GTFS-RT match, a predicted time from a StopPredictionSource, a LiveVehicleSource confirming the
                    // trip is running (so we trust the schedule), a FuzzyRunTrips closest match, and finally the
                    // plain schedule.
                    val fuzzyStopUpdate = fuzzyMatchesByTripId[arrival.tripId]?.updateFor(arrival.stopId, arrival.stopSequence)
                    var isClosestMatch = false
                    val eta = when {
                        rtStopUpdate != null -> computeArrivalEta(arrival.departureTime, today, rtStopUpdate, agency.zoneId) ?: return@mapNotNull null
                        arrival.tripId in stopPredictions ->
                            // Diffed against arrival.departureTime, the scheduled time at this stop, via the same synthetic
                            // GtfsRtStopTimeUpdate pattern as a TripUpdates match. Never against the trip's origin time,
                            // which StopPredictionSource has no basis to diff against (see its doc).
                            computeArrivalEta(
                                arrival.departureTime, today,
                                GtfsRtStopTimeUpdate(departure = GtfsRtStopTimeEvent(time = stopPredictions.getValue(arrival.tripId))),
                                agency.zoneId,
                            ) ?: return@mapNotNull null
                        arrival.tripId in liveVehiclesByTripId ->
                            // No GTFS-RT or real prediction match, but a LiveVehicleSource confirms a real
                            // vehicle is out on this exact trip right now -- e.g. RunAssociatedTripSource's
                            // position-only bridge (see its own doc) has no arrival-time prediction of its
                            // own, so this trusts the scheduled time rather than guessing an offset, just
                            // marks it confirmed live instead of schedule-only.
                            gtfsTimeToEpochSeconds(arrival.departureTime, today, agency.zoneId)
                                ?.let { ArrivalEta(etaEpochSeconds = it, isLive = true, status = null) }
                                ?: return@mapNotNull null
                        fuzzyStopUpdate != null -> {
                            // Real predicted time from an actual live run -- just paired to this trip_id
                            // approximately (see fuzzyMatchesByTripId's own doc) -- so computeArrivalEta's
                            // diff-against-schedule status (Late/Early/OnTime) is still meaningful, unlike
                            // a fabricated one would be.
                            val fuzzyEta = computeArrivalEta(arrival.departureTime, today, fuzzyStopUpdate, agency.zoneId)
                                ?: return@mapNotNull null
                            // A live result with no status means the update didn't plausibly fit this trip's schedule (see
                            // ARRIVAL_STATUS_IMPLAUSIBLE_THRESHOLD_SECONDS). Showing the schedule instead keeps this row and
                            // Trip Detail agreeing on the trip's time.
                            if (fuzzyEta.isLive && fuzzyEta.status != null) {
                                isClosestMatch = true
                                fuzzyEta
                            } else {
                                computeArrivalEta(arrival.departureTime, today, null, agency.zoneId) ?: return@mapNotNull null
                            }
                        }
                        else -> computeArrivalEta(arrival.departureTime, today, null, agency.zoneId) ?: return@mapNotNull null
                    }
                    ArrivalRow(
                        tripId = arrival.tripId,
                        stopSequence = arrival.stopSequence,
                        routeLabel = arrival.route.displayName,
                        directionLabel = arrival.direction.displayLabel(),
                        lineType = LineType.forGtfsRouteType(arrival.route.routeType),
                        etaEpochSeconds = eta.etaEpochSeconds,
                        isLive = eta.isLive,
                        isClosestMatch = isClosestMatch,
                        status = eta.status,
                        platformLabel = arrival.platformLabel,
                        zoneId = agency.zoneId,
                    )
                }.sortedBy { it.etaEpochSeconds }

                val stale = feed.primary?.header?.isStale(System.currentTimeMillis() / 1000) ?: false
                // Offline means "no live prediction at all" — a feed that fetched fine but simply
                // has zero matches among currently-scheduled trips still counts as offline, while
                // even one live match means we're genuinely getting live data. An agency with a
                // StopPredictionSource/LiveVehicleSource but no standard feed (feed.primary always null)
                // isn't offline just because of that -- only the "zero live matches" check below applies.
                val hasNonStandardLiveSource = stopPredictionSource != null || liveVehicleSource != null || fuzzyRunTrips != null
                val isOffline = (feed.primary == null && !hasNonStandardLiveSource) || (rows.isNotEmpty() && rows.none { it.isLive })
                UpcomingArrivalsState.Loaded(rows, isOffline = isOffline, realtimeStale = stale)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("UpcomingArrivalsScreen", "Failed to load arrivals for stops $stopIds", e)
                UpcomingArrivalsState.Error("Unable to load arrivals.")
            }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        loadJob?.cancel()
        loadJob = null
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class UpcomingArrivalsScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val agency: GtfsAgency,
    private val stopIds: List<String>,
    private val stopLabel: String,
) : LightScreen<Unit, UpcomingArrivalsViewModel>(sealedActivity) {

    override val viewModelClass: Class<UpcomingArrivalsViewModel>
        get() = UpcomingArrivalsViewModel::class.java

    override fun createViewModel(): UpcomingArrivalsViewModel =
        UpcomingArrivalsViewModel(dbFile, agency, stopIds, AlertPreferences(lightContext.dataStore))

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val isStation by viewModel.isStation.collectAsState()
        val stopAlerts by viewModel.stopAlerts.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                AlertsTopBar(
                    title = "Upcoming Arrivals",
                    onBack = { goBack() },
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                    alerts = stopAlerts?.first.orEmpty(),
                    screenAlerts = stopAlerts?.second,
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                LightText(
                    text = "Selected stop",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .lightClickable {
                            navigateTo(screenFactory = { activity ->
                                MapScreen(activity, dbFile, agency, stopIds.first(), stopLabel)
                            })
                        }
                        .padding(bottom = 16.dp),
                ) {
                    LightIcon(icon = LightIcons.MAP, size = 1.4f, modifier = Modifier.padding(end = 8.dp))
                    // Weighted so a long stop name wraps within its own bounded share of the row,
                    // leaving guaranteed room for the trailing icon -- see NearbyStopsScreen's
                    // identical fix for the same underlying Compose behavior.
                    LightText(
                        text = stopLabel,
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isStation) {
                        LightIcon(
                            icon = LightIcons.DIRECTIONS_MIDDLE_FORK,
                            size = 1.2f,
                            contentDescription = "Transfer station",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                when (val s = state) {
                    is UpcomingArrivalsState.Loading -> LightText(
                        text = "Loading arrivals...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is UpcomingArrivalsState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is UpcomingArrivalsState.Loaded -> {
                        if (s.isOffline) {
                            LightText(
                                text = "Offline - showing scheduled times",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        } else if (s.realtimeStale) {
                            LightText(
                                text = "Live data may be outdated",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        }

                        if (s.arrivals.isEmpty()) {
                            LightText(
                                text = "No more arrivals today.",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                            )
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(s.arrivals) { arrival ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .lightClickable {
                                                navigateTo(screenFactory = { activity ->
                                                    TripDetailScreen(
                                                        activity,
                                                        dbFile,
                                                        arrival.tripId,
                                                        arrival.stopSequence,
                                                        arrival.routeLabel,
                                                        arrival.directionLabel,
                                                    )
                                                })
                                            }
                                            .padding(vertical = 12.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            LightIcon(
                                                icon = arrival.lineType.toVehicleIcon(),
                                                size = 1.2f,
                                                modifier = Modifier.padding(end = 8.dp),
                                            )
                                            // Status renders directly under the route/direction text it describes (same weighted column)
                                            // rather than as a sibling of the whole Row -- otherwise it lines up flush
                                            // with the mode icon's left edge instead of the text.
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 16.dp),
                                            ) {
                                                LightText(
                                                    text = arrival.routeAndDirectionLabel(),
                                                    variant = LightTextVariant.Copy,
                                                )
                                                arrival.statusLabel()?.let {
                                                    LightText(
                                                        text = it,
                                                        variant = LightTextVariant.Detail,
                                                        lighten = true,
                                                    )
                                                }
                                            }
                                            LightText(
                                                text = arrival.etaDisplay(),
                                                variant = LightTextVariant.Copy,
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
                BackToHomeFooter(onGoBackOnce = { goBack() })
            }
        }
    }
}
