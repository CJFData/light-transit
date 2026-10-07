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

// Same polling interval as the map.
private const val ARRIVALS_POLL_INTERVAL_MS = 10_000L

data class ArrivalRow(
    val tripId: String,
    val stopSequence: Int,
    val routeLabel: String,
    val directionLabel: String,
    /** The mode icon; see [toVehicleIcon]. */
    val lineType: LineType?,
    val etaEpochSeconds: Long,
    val isLive: Boolean,
    /**
     * True when the live data came from a closest match rather than a confirmed one; shown as
     * "Closest match", not "Live".
     */
    val isClosestMatch: Boolean = false,
    val status: ArrivalStatus?,
    /**
     * The arrival's platform at a multi-platform station (e.g. "Track 1"); null for a plain stop.
     */
    val platformLabel: String?,
    /** The agency's time zone, for showing the ETA. */
    val zoneId: ZoneId,
)

fun ArrivalRow.etaDisplay(): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(etaEpochSeconds), zoneId)
    return formatGtfsTime("%02d:%02d:00".format(time.hour, time.minute))
}

/**
 * e.g. "Commuter Rail - Toward Providence - Track 1". The platform is only added at a
 * multi-platform station.
 */
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
     * [isOffline] is true when no live data matched at all, as opposed to one trip without an
     * update.
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
    /**
     * The selected stop's platform stop_ids. More than one means a station; arrivals from every
     * platform are combined and labeled.
     */
    private val stopIds: List<String>,
    private val alertPreferences: AlertPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<UpcomingArrivalsState>(UpcomingArrivalsState.Loading)
    val state: StateFlow<UpcomingArrivalsState> = _state

    /**
     * Whether the selected stop is a multi-platform station. Checked from the first id, since a
     * caller may pass one id for a station. Separate from [state] so the icon shows before arrivals
     * load.
     */
    val isStation = MutableStateFlow(false)

    /** Alerts naming this stop, when alerts are shown in menus. */
    val stopAlerts = MutableStateFlow<Pair<List<Alert>, ScreenAlerts>?>(null)

    /** Tracked so a load is cancelled on hide and re-show and can't outlive [onCleared]. */
    private var loadJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        loadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { stopAlerts.value = loadStopAlerts(dbFile, repository, alertPreferences, stopIds) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            // Caught here so a screen closed mid-query doesn't crash when the repository closes.
            // The same goes for every query below.
            try {
                isStation.value = repository.getStationContaining(stopIds.first()) != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("UpcomingArrivalsScreen", "getStationContaining failed for stops $stopIds", e)
            }
            try {
                // The schedule is read once per service day; only the live data is polled.
                var today = todayForGtfs(agency.zoneId)
                var scheduled = repository.getScheduledArrivals(stopIds, currentGtfsTimeOfDay(agency.zoneId), today)

                while (isActive) {
                    // Re-read when the service day rolls over, since different days can run
                    // different trips.
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
                // Includes any extra feeds' trip updates. [feed.primary] is the agency's own feed,
                // for staleness and offline.
                val feed = agency.fetchMergedTripUpdates(repository, "UpcomingArrivalsScreen")

                // Agencies without GTFS-RT supply live data through a [StopPredictionSource]
                // (predicted times, preferred) or a [LiveVehicleSource] (positions only). Both are
                // fetched at once.
                val stopPredictionSource = agency.component<StopPredictionSource>()
                val liveVehicleSource = agency.component<LiveVehicleSource>()
                val fuzzyRunTrips = agency.component<FuzzyRunTrips>()
                val liveSourceRouteIds = liveVehicleSource?.let { source ->
                    scheduled.filterTo(mutableSetOf()) { LineType.forGtfsRouteType(it.route.routeType) in source.coveredLineTypes }
                        .mapTo(mutableSetOf()) { it.route.routeId }
                }.orEmpty()
                // Only routes scheduled at this stop, to limit requests.
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
                    // Matched against the arrival's own platform, since a station's predictions are
                    // per platform.
                    val rtStopUpdate = feed.byTripId[arrival.tripId]
                        ?.updateFor(arrival.stopId, arrival.stopSequence)
                    // computeArrivalEta always returns a time, falling back to the schedule, so
                    // other sources are checked first. In order of preference: a GTFS-RT match, a
                    // predicted time, a live vehicle confirming the trip is running, a closest
                    // match, then the schedule.
                    val fuzzyStopUpdate = fuzzyMatchesByTripId[arrival.tripId]?.updateFor(arrival.stopId, arrival.stopSequence)
                    var isClosestMatch = false
                    val eta = when {
                        rtStopUpdate != null -> computeArrivalEta(arrival.departureTime, today, rtStopUpdate, agency.zoneId) ?: return@mapNotNull null
                        arrival.tripId in stopPredictions ->
                            // Compared against the scheduled time at this stop, like a trip update.
                            computeArrivalEta(
                                arrival.departureTime, today,
                                GtfsRtStopTimeUpdate(departure = GtfsRtStopTimeEvent(time = stopPredictions.getValue(arrival.tripId))),
                                agency.zoneId,
                            ) ?: return@mapNotNull null
                        arrival.tripId in liveVehiclesByTripId ->
                            // A live vehicle is on this trip but there's no predicted time, so the
                            // schedule is shown, marked live.
                            gtfsTimeToEpochSeconds(arrival.departureTime, today, agency.zoneId)
                                ?.let { ArrivalEta(etaEpochSeconds = it, isLive = true, status = null) }
                                ?: return@mapNotNull null
                        fuzzyStopUpdate != null -> {
                            // A real predicted time from the matched run, so Late/Early/On Time
                            // still applies.
                            val fuzzyEta = computeArrivalEta(arrival.departureTime, today, fuzzyStopUpdate, agency.zoneId)
                                ?: return@mapNotNull null
                            // A live result with no status didn't plausibly fit this trip's
                            // schedule. Showing the schedule instead keeps this row and Trip Detail
                            // agreeing.
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
                // Offline means no live match at all, even when the feed fetched fine. An agency
                // with only its own live source isn't offline just for having no feed.
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
                    // Weighted so a long stop name wraps instead of pushing out the icon.
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
                                            // In the text's column, so the status lines up under
                                            // the route rather than the icon.
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
