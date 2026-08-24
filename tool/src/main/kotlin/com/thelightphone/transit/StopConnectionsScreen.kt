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
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.StopConnection
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 100 feet -- close enough to be "the same corner", not a separate trip to walk to. */
private const val NEARBY_STOP_RADIUS_METERS = 30.48
/** Enough to show real variety at each nearby stop without turning this into a second full
 * schedule dump for every one of them. */
private const val NEARBY_STOP_CONNECTIONS_LIMIT = 3
private const val FEET_PER_METER = 3.28084

sealed class StopConnectionsState {
    object Loading : StopConnectionsState()
    data class Loaded(
        val connections: List<StopConnection>,
        val nearbyStops: List<NearbyStopConnections>,
    ) : StopConnectionsState()
    data class Error(val message: String) : StopConnectionsState()
}

/** A nearby stop's next few departures, already filtered to exclude any route+direction the
 * current stop itself already offers -- these are meant to surface options you wouldn't otherwise
 * see from here, not duplicate what's already on screen. */
data class NearbyStopConnections(
    val stopName: String,
    val distanceMeters: Double,
    val connections: List<StopConnection>,
)

fun NearbyStopConnections.distanceFeetLabel(): String = "%.0f ft".format(distanceMeters * FEET_PER_METER)

fun StopConnection.displayLabel(): String {
    val lineLabel = LineType.forGtfsRouteType(route.routeType)?.label
    val routeAndDirection = "${route.displayName} - ${direction.displayLabel()}"
    val base = lineLabel?.let { "$it - $routeAndDirection" } ?: routeAndDirection
    return platformLabel?.let { "$base - $it" } ?: base
}

class StopConnectionsViewModel(
    dbFile: File,
    private val stopId: String,
    private val afterTime: String,
    private val excludeTripId: String,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val agency = GtfsAgency.forDbFile(dbFile)

    private val _state = MutableStateFlow<StopConnectionsState>(StopConnectionsState.Loading)
    val state: StateFlow<StopConnectionsState> = _state

    /** Whether [stopId] is itself a real, qualifying multi-platform station -- see
     * GtfsRepository.getStationContaining, the same single source of truth Upcoming Arrivals' own
     * transfer icon uses. */
    val isStation = MutableStateFlow(false)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            // Inside the same try/catch as the rest of this block (not a separate unguarded call before it)
            // so a screen popped mid-query -- e.g. several rapid-fire goBack() calls in a row, like
            // BackToHomeFooter's "jump to Home" loop -- can't crash the app just because this
            // repository got closed out from under an in-flight query on the way out.
            _state.value = try {
                // A trip only ever stops at one platform of a station, so stopId here is just that one platform
                // -- resolve its full station (if any) so connections are listed across every platform
                // actually serving it, not just the one this trip happened to use. Same pattern as
                // UpcomingArrivalsViewModel/GtfsRepository.getScheduledArrivals(stopIds).
                val station = repository.getStationContaining(stopId)
                isStation.value = station != null
                val stopIds = station?.memberStopIds ?: listOf(stopId)

                val today = todayForGtfs(agency?.zoneId ?: java.time.ZoneId.systemDefault())
                val connections = repository.getNextConnections(stopIds, afterTime, excludeTripId, today)
                // What's already offered right here -- a nearby stop repeating one of these isn't
                // telling you anything new, so it's left out of that stop's list entirely.
                val servedHere = connections.mapTo(mutableSetOf()) { it.route.routeId to it.direction.directionId }

                val here = repository.getStopLocation(stopId)
                val nearbyStops = if (here == null) {
                    emptyList()
                } else {
                    repository.getStopsWithinRadius(here.lat, here.lon, NEARBY_STOP_RADIUS_METERS, excludeStopId = stopId)
                        .mapNotNull { nearby ->
                            val nearbyConnections = repository.getNextConnections(nearby.memberStopIds, afterTime, excludeTripId, today)
                                .filter { (it.route.routeId to it.direction.directionId) !in servedHere }
                                .sortedBy { it.departureTime }
                                .take(NEARBY_STOP_CONNECTIONS_LIMIT)
                            if (nearbyConnections.isEmpty()) return@mapNotNull null
                            NearbyStopConnections(
                                stopName = nearby.stopName ?: "Stop ${nearby.stopId}",
                                distanceMeters = nearby.distanceMeters,
                                connections = nearbyConnections,
                            )
                        }
                }
                StopConnectionsState.Loaded(connections, nearbyStops)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("StopConnectionsScreen", "Failed to load connections for stop $stopId", e)
                StopConnectionsState.Error("Unable to load connections.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class StopConnectionsScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val stopId: String,
    private val stopLabel: String,
    private val afterTime: String,
    private val excludeTripId: String,
) : LightScreen<Unit, StopConnectionsViewModel>(sealedActivity) {

    override val viewModelClass: Class<StopConnectionsViewModel>
        get() = StopConnectionsViewModel::class.java

    override fun createViewModel(): StopConnectionsViewModel =
        StopConnectionsViewModel(dbFile, stopId, afterTime, excludeTripId)

    @Composable
    private fun ConnectionRow(connection: StopConnection) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable {
                    navigateTo(screenFactory = { activity ->
                        TripDetailScreen(
                            activity,
                            dbFile,
                            connection.tripId,
                            connection.stopSequence,
                            connection.route.displayName,
                            connection.direction.displayLabel(),
                        )
                    })
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LightText(
                text = connection.displayLabel(),
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            )
            LightText(
                text = formatGtfsTime(connection.departureTime),
                variant = LightTextVariant.Copy,
                lighten = true,
            )
        }
    }

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
                    center = LightTopBarCenter.Text("Connections"),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp),
                ) {
                    LightText(
                        text = "$stopLabel - After ${formatGtfsTime(afterTime)}",
                        variant = LightTextVariant.Detail,
                        lighten = true,
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
                    is StopConnectionsState.Loading -> LightText(
                        text = "Loading connections...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is StopConnectionsState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is StopConnectionsState.Loaded -> if (s.connections.isEmpty() && s.nearbyStops.isEmpty()) {
                        LightText(
                            text = "No more connections today.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.connections) { connection ->
                                ConnectionRow(connection)
                            }
                            s.nearbyStops.forEach { nearby ->
                                item {
                                    LightText(
                                        text = "${nearby.stopName} - ${nearby.distanceFeetLabel()}",
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                                    )
                                }
                                items(nearby.connections) { connection ->
                                    ConnectionRow(connection)
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
