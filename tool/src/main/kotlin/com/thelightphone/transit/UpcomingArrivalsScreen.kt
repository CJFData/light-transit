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
import com.thelightphone.transit.gtfs.computeArrivalEta
import com.thelightphone.transit.gtfs.currentGtfsTimeOfDay
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
)

fun ArrivalRow.etaDisplay(): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(etaEpochSeconds), ZoneId.systemDefault())
    return formatGtfsTime("%02d:%02d:00".format(time.hour, time.minute))
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
     * [isOffline] is true when there's no live feed connection at all this session — either the
     * agency has no [GtfsAgency.realtimeTripUpdatesUrl] (RIPTA) or the fetch itself failed — as
     * opposed to a feed that connected fine but simply has no update for one particular trip,
     * which is normal and shown per-row with no badge rather than as a screen-wide state.
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
    private val stopId: String,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<UpcomingArrivalsState>(UpcomingArrivalsState.Loading)
    val state: StateFlow<UpcomingArrivalsState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                val today = todayForGtfs()
                val scheduled = repository.getScheduledArrivals(stopId, currentGtfsTimeOfDay(), today)

                val feed = agency.realtimeTripUpdatesUrl?.let { url ->
                    try {
                        GtfsRealtimeClient.fetchFeed(url)
                    } catch (e: Exception) {
                        Log.e("UpcomingArrivalsScreen", "Realtime fetch failed for ${agency.displayName}", e)
                        null
                    }
                }

                val rows = scheduled.mapNotNull { arrival ->
                    val rtStopUpdate = feed?.tripUpdatesByTripId?.get(arrival.tripId)
                        ?.updateFor(stopId, arrival.stopSequence)
                    val eta = computeArrivalEta(arrival.departureTime, today, rtStopUpdate) ?: return@mapNotNull null
                    ArrivalRow(
                        tripId = arrival.tripId,
                        stopSequence = arrival.stopSequence,
                        routeLabel = arrival.route.displayName,
                        directionLabel = arrival.direction.displayLabel(),
                        lineType = LineType.forGtfsRouteType(arrival.route.routeType),
                        etaEpochSeconds = eta.etaEpochSeconds,
                        isLive = eta.isLive,
                        status = eta.status,
                    )
                }.sortedBy { it.etaEpochSeconds }

                val stale = feed?.header?.isStale(System.currentTimeMillis() / 1000) ?: false
                // Offline means "no live prediction at all" — a feed that fetched fine but simply
                // has zero matches among currently-scheduled trips still counts as offline, while
                // even one live match means we're genuinely getting live data.
                val isOffline = feed == null || (rows.isNotEmpty() && rows.none { it.isLive })
                UpcomingArrivalsState.Loaded(rows, isOffline = isOffline, realtimeStale = stale)
            } catch (e: Exception) {
                Log.e("UpcomingArrivalsScreen", "Failed to load arrivals for stop $stopId", e)
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
    private val stopId: String,
    private val stopLabel: String,
) : LightScreen<Unit, UpcomingArrivalsViewModel>(sealedActivity) {

    override val viewModelClass: Class<UpcomingArrivalsViewModel>
        get() = UpcomingArrivalsViewModel::class.java

    override fun createViewModel(): UpcomingArrivalsViewModel =
        UpcomingArrivalsViewModel(dbFile, agency, stopId)

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
                    center = LightTopBarCenter.Text("Upcoming Arrivals"),
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
                                MapScreen(activity, dbFile, agency, stopId, stopLabel)
                            })
                        }
                        .padding(bottom = 16.dp),
                ) {
                    LightIcon(icon = LightIcons.MAP, size = 1.4f, modifier = Modifier.padding(end = 8.dp))
                    LightText(text = stopLabel, variant = LightTextVariant.Copy)
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
                                            LightText(
                                                text = "${arrival.routeLabel} - ${arrival.directionLabel}",
                                                variant = LightTextVariant.Copy,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 16.dp),
                                            )
                                            LightText(
                                                text = arrival.etaDisplay(),
                                                variant = LightTextVariant.Copy,
                                                lighten = true,
                                            )
                                        }
                                        arrival.statusLabel()?.let {
                                            LightText(
                                                text = it,
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
}
