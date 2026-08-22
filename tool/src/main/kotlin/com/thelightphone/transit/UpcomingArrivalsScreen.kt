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
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.computeArrivalEta
import com.thelightphone.transit.gtfs.currentGtfsTimeOfDay
import com.thelightphone.transit.gtfs.fetchMergedTripUpdates
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.transit.gtfs.isStale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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
    val status: ArrivalStatus?,
    /** This arrival's own platform within the station, only populated when the selected stop is an
     * actual multi-platform grouped station (e.g. "Track 1", "Ashmont/Braintree") -- see
     * GtfsRepository.getScheduledArrivals(stopIds: List<String>, ...). Null for a plain stop. */
    val platformLabel: String?,
)

fun ArrivalRow.etaDisplay(): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(etaEpochSeconds), ZoneId.systemDefault())
    return formatGtfsTime("%02d:%02d:00".format(time.hour, time.minute))
}

/** e.g. "Red Line - Toward Alewife - Alewife" or "Commuter Rail - Toward Providence - Track 1" --
 * the platform is only appended when this arrival actually came from a grouped multi-platform
 * station ([ArrivalRow.platformLabel] non-null); a plain stop's line is unchanged. */
fun ArrivalRow.routeAndDirectionLabel(): String {
    val base = "$routeLabel - $directionLabel"
    return platformLabel?.let { "$base - $it" } ?: base
}

fun ArrivalRow.statusLabel(): String? = when (val s = status) {
    null -> null
    ArrivalStatus.OnTime -> "Live - On time"
    is ArrivalStatus.Late -> "Live - Late by ${(s.seconds / 60).coerceAtLeast(1)}m"
    is ArrivalStatus.Early -> "Live - Early by ${(s.seconds / 60).coerceAtLeast(1)}m"
}

sealed class UpcomingArrivalsState {
    object Loading : UpcomingArrivalsState()

    /**
     * [isOffline] is true when there's no live feed connection at all this session -- either the
     * agency has no [GtfsAgency.realtimeTripUpdatesUrl] (RIPTA) or the fetch itself failed -- as
     * opposed to a feed that connected fine but simply has no update for one particular trip,
     * which is shown per-row with no badge rather than as a screen-wide state.
     */
    data class Loaded(
        val arrivals: List<ArrivalRow>,
        val isOffline: Boolean,
        val realtimeStale: Boolean,
    ) : UpcomingArrivalsState()

    data class Error(val message: String) : UpcomingArrivalsState()
}

class UpcomingArrivalsViewModel(
    dbFile: File,
    private val agency: GtfsAgency,
    /** Every child platform stop_id belonging to the selected stop -- more than one entry means this
     * is a real multi-platform grouped station (see GtfsRepository.groupStationsByParent), in
     * which case arrivals across every platform are unioned and each is labeled with its own
     * platform. A plain stop is just its own single-element list. */
    private val stopIds: List<String>,
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

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            // Own try/catch (not folded into the state one below) so a screen popped mid-query -- e.g.
            // several rapid-fire goBack() calls in a row, like BackToHomeFooter's "jump to Home" loop --
            // can't crash the app just because this repository got closed out from under an in-flight
            // query on the way out. Same reasoning applies to every DB call in this coroutine, not just
            // this one.
            try {
                isStation.value = repository.getStationContaining(stopIds.first()) != null
            } catch (e: Exception) {
                Log.e("UpcomingArrivalsScreen", "getStationContaining failed for stops $stopIds", e)
            }
            _state.value = try {
                val today = todayForGtfs(agency.zoneId)
                val scheduled = repository.getScheduledArrivals(stopIds, currentGtfsTimeOfDay(agency.zoneId), today)

                // Merged with any SecondaryGtfsFeed component's own realtime feed (e.g. Bustang under RTD
                // Denver) -- see MergedRealtimeFeed's own doc. [feed.primary] (used below for
                // staleness/offline) stays keyed off the agency's own primary feed only.
                val feed = agency.fetchMergedTripUpdates("UpcomingArrivalsScreen")

                val rows = scheduled.mapNotNull { arrival ->
                    // Matched against this specific arrival's own platform (arrival.stopId), not
                    // just whichever platform the screen was originally opened for -- a grouped
                    // station's live predictions are per-platform, same as its static schedule.
                    val rtStopUpdate = feed.byTripId[arrival.tripId]
                        ?.updateFor(arrival.stopId, arrival.stopSequence)
                    val eta = computeArrivalEta(arrival.departureTime, today, rtStopUpdate, agency.zoneId) ?: return@mapNotNull null
                    ArrivalRow(
                        tripId = arrival.tripId,
                        stopSequence = arrival.stopSequence,
                        routeLabel = arrival.route.displayName,
                        directionLabel = arrival.direction.displayLabel(),
                        lineType = LineType.forGtfsRouteType(arrival.route.routeType),
                        etaEpochSeconds = eta.etaEpochSeconds,
                        isLive = eta.isLive,
                        status = eta.status,
                        platformLabel = arrival.platformLabel,
                    )
                }.sortedBy { it.etaEpochSeconds }

                val stale = feed.primary?.header?.isStale(System.currentTimeMillis() / 1000) ?: false
                // Offline means "no live prediction at all" — a feed that fetched fine but simply
                // has zero matches among currently-scheduled trips still counts as offline, while
                // even one live match means we're genuinely getting live data.
                val isOffline = feed.primary == null || (rows.isNotEmpty() && rows.none { it.isLive })
                UpcomingArrivalsState.Loaded(rows, isOffline = isOffline, realtimeStale = stale)
            } catch (e: Exception) {
                Log.e("UpcomingArrivalsScreen", "Failed to load arrivals for stops $stopIds", e)
                UpcomingArrivalsState.Error("Unable to load arrivals.")
            }
        }
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
        UpcomingArrivalsViewModel(dbFile, agency, stopIds)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val isStation by viewModel.isStation.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Upcoming Arrivals"),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
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
