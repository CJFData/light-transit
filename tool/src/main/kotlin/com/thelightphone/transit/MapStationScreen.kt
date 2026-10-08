package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.MapPreferences
import com.thelightphone.transit.gtfs.MapTileClient
import com.thelightphone.transit.gtfs.MapTiles
import com.thelightphone.transit.gtfs.fetchMergedTripUpdates
import com.thelightphone.transit.gtfs.fetchMergedVehiclePositions
import com.thelightphone.transit.gtfs.fitBoundsZoom
import com.thelightphone.transit.gtfs.metersPerPixel
import com.thelightphone.transit.gtfs.platformLabelFromStopDesc
import com.thelightphone.transit.gtfs.todayForGtfs
import com.thelightphone.transit.gtfs.TapHoldPreferences
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

// Much smaller than the main map's, since a station's platforms can share the same coordinates.
private const val STATION_MIN_BOUNDING_BOX_MILES = 0.02
// Larger than the main map's, since there's no center pin or unrelated stops to leave room for.
// Leaves room for the compass letters.
private const val STATION_ZOOM_TARGET_RADIUS_PIXELS = 500f
private const val STATION_MIN_ZOOM = 17
// Same zoom limit as the main map.
private const val STATION_MAX_ZOOM = 20
private const val STATION_FALLBACK_ZOOM = 20
// Same polling interval as the main map; only used with "See everything" on.
private const val STATION_LIVE_VEHICLE_POLL_INTERVAL_MS = 10_000L

sealed class MapStationState {
    object Loading : MapStationState()
    data class Loaded(
        val centerLat: Double,
        val centerLon: Double,
        val zoom: Int,
        val mapTiles: MapTiles?,
        val platforms: List<NearbyStopMarker>,
        val tapHoldArrivalsEnabled: Boolean,
        val darkMapEnabled: Boolean,
        /** Empty unless "See everything" is on. */
        val buses: List<BusMarker>,
        val seeEverythingEnabled: Boolean,
        /** Whether double-tapping the station name returns to the main map. */
        val doubleTapStationEnabled: Boolean,
        /** Whether a long press on a vehicle opens its trip. */
        val tapHoldVehicleEnabled: Boolean,
    ) : MapStationState()
    data class Error(val message: String) : MapStationState()
}

/** Values worked out when the screen opens, reused for out-of-cycle refreshes. */
private data class LoadedStationContext(
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Int,
    val mapTiles: MapTiles?,
    val platforms: List<NearbyStopMarker>,
    val tapHoldArrivalsEnabled: Boolean,
    val darkMapEnabled: Boolean,
    val seeEverythingEnabled: Boolean,
    val filterByStopEnabled: Boolean,
    val seeEverythingShowBus: Boolean,
    val seeEverythingShowSubway: Boolean,
    val seeEverythingShowCommuterRail: Boolean,
    val doubleTapStationEnabled: Boolean,
    val tapHoldVehicleEnabled: Boolean,
)

class MapStationViewModel(
    dbFile: File,
    private val agency: GtfsAgency,
    private val memberStopIds: List<String>,
    private val mapPreferences: MapPreferences,
    private val tapHoldPreferences: TapHoldPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val tileClient = MapTileClient()

    private val _state = MutableStateFlow<MapStationState>(MapStationState.Loading)
    val state: StateFlow<MapStationState> = _state

    /** Platforms tapped open to show their names. Also the stops "Track tapped stops" filters by. */
    val expandedStopIds = MutableStateFlow<Set<String>>(emptySet())

    /** Vehicles tapped open in "See everything" mode to show their full label. */
    val expandedVehicleTripIds = MutableStateFlow<Set<String>>(emptySet())

    private var loadJob: Job? = null
    private var loadedContext: LoadedStationContext? = null

    /** Wakes the poll loop early when the tapped-stop selection changes. */
    private val refreshTrigger = Channel<Unit>(Channel.CONFLATED)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val platforms = memberStopIds.mapNotNull { repository.getStopLocation(it) }
                if (platforms.isEmpty()) {
                    _state.value = MapStationState.Error("Station platforms not found.")
                    return@launch
                }
                val centerLat = platforms.map { it.lat }.average()
                val centerLon = platforms.map { it.lon }.average()
                val darkMode = mapPreferences.darkMapEnabledFlow.first()
                val tapHoldArrivalsEnabled = mapPreferences.tapHoldArrivalsEnabledFlow.first()
                val seeEverythingEnabled = mapPreferences.seeEverythingEnabledFlow.first()
                // With "See everything", tracking tapped stops filters to their vehicles.
                val filterByStopEnabled = mapPreferences.trackTappedStopsEnabledFlow.first()
                val seeEverythingShowBus = mapPreferences.seeEverythingShowBusFlow.first()
                val seeEverythingShowSubway = mapPreferences.seeEverythingShowSubwayFlow.first()
                val seeEverythingShowCommuterRail = mapPreferences.seeEverythingShowCommuterRailFlow.first()
                val doubleTapStationEnabled = mapPreferences.doubleTapStationEnabledFlow.first()
                val tapHoldVehicleEnabled = tapHoldPreferences.tapHoldVehicleEnabledFlow.first()

                val zoom = fitBoundsZoom(
                    centerLat = centerLat,
                    centerLon = centerLon,
                    points = platforms.map { it.lat to it.lon },
                    availableHalfExtentPx = STATION_ZOOM_TARGET_RADIUS_PIXELS,
                    minZoom = STATION_MIN_ZOOM,
                    maxZoom = STATION_MAX_ZOOM,
                    fallbackZoom = STATION_FALLBACK_ZOOM,
                    minBoundingBoxMiles = STATION_MIN_BOUNDING_BOX_MILES,
                )
                val fetchRadiusMeters = STATION_ZOOM_TARGET_RADIUS_PIXELS * metersPerPixel(centerLat, zoom)
                val mapTiles = try {
                    tileClient.fetchTilesAround(centerLat, centerLon, zoom, fetchRadiusMeters, darkMode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("MapStationScreen", "Map tile fetch failed for platforms $memberStopIds", e)
                    null
                }

                // Each platform's own label (e.g. "Track 1"), falling back to its stop_name.
                val descriptions = repository.getStopDescriptions(memberStopIds)
                val platformMarkers = platforms.map { platform ->
                    val label = platformLabelFromStopDesc(descriptions[platform.stopId]) ?: platform.stopName
                    NearbyStopMarker(
                        stopId = platform.stopId,
                        stopName = label,
                        lat = platform.lat,
                        lon = platform.lon,
                        isStation = false,
                        memberStopIds = listOf(platform.stopId),
                    )
                }

                loadedContext = LoadedStationContext(
                    centerLat, centerLon, zoom, mapTiles, platformMarkers,
                    tapHoldArrivalsEnabled, darkMode, seeEverythingEnabled, filterByStopEnabled,
                    seeEverythingShowBus, seeEverythingShowSubway, seeEverythingShowCommuterRail,
                    doubleTapStationEnabled, tapHoldVehicleEnabled,
                )

                // Without "See everything" there's nothing live to poll.
                if (seeEverythingEnabled) {
                    while (isActive) {
                        refresh()
                        withTimeoutOrNull(STATION_LIVE_VEHICLE_POLL_INTERVAL_MS) { refreshTrigger.receive() }
                    }
                } else {
                    _state.value = MapStationState.Loaded(
                        centerLat, centerLon, zoom, mapTiles, platformMarkers,
                        tapHoldArrivalsEnabled, darkMode, emptyList(), false, doubleTapStationEnabled,
                        tapHoldVehicleEnabled,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MapStationScreen", "Failed to load station map for platforms $memberStopIds", e)
                _state.value = MapStationState.Error("Unable to load station map.")
            }
        }
    }

    /** Fetches live vehicles and rebuilds the state, using the same code as the main map. */
    private suspend fun refresh() {
        val context = loadedContext ?: return
        if (agency.realtimeVehiclePositionsUrl == null) {
            _state.value = stationLoaded(context, emptyList())
            return
        }
        val vehiclePositions = agency.fetchMergedVehiclePositions(repository, "MapStationScreen")
        if (vehiclePositions.primary == null) {
            _state.value = stationLoaded(context, emptyList())
            return
        }
        val tripUpdatesByTripId = agency.fetchMergedTripUpdates(repository, "MapStationScreen").byTripId
        val buses = buildSeeEverythingBuses(
            repository, context.centerLat, context.centerLon, context.zoom,
            vehiclePositions.byTripId, tripUpdatesByTripId, todayForGtfs(agency.zoneId), agency.zoneId, System.currentTimeMillis() / 1000,
            expandedStopIds.value.toList(), context.filterByStopEnabled, memberStopIds.first(),
            context.seeEverythingShowBus, context.seeEverythingShowSubway, context.seeEverythingShowCommuterRail,
        )
        _state.value = stationLoaded(context, buses)
    }

    private fun stationLoaded(context: LoadedStationContext, buses: List<BusMarker>) = MapStationState.Loaded(
        context.centerLat, context.centerLon, context.zoom, context.mapTiles, context.platforms,
        context.tapHoldArrivalsEnabled, context.darkMapEnabled, buses, context.seeEverythingEnabled,
        context.doubleTapStationEnabled, context.tapHoldVehicleEnabled,
    )

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        loadJob?.cancel()
        loadJob = null
    }

    fun toggleStopExpanded(stopId: String) {
        expandedStopIds.value = expandedStopIds.value.let { if (stopId in it) it - stopId else it + stopId }
        if (loadedContext?.seeEverythingEnabled == true && loadedContext?.filterByStopEnabled == true) {
            refreshTrigger.trySend(Unit)
        }
    }

    /** Toggles a vehicle's full label in "See everything" mode. */
    fun toggleVehicleExpanded(tripId: String) {
        expandedVehicleTripIds.value = expandedVehicleTripIds.value.let { if (tripId in it) it - tripId else it + tripId }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
        tileClient.close()
    }
}

/**
 * A zoomed-in map of one station's platforms, opened by double-tapping a station on the main map.
 * Uses the same [MapCanvas], with every platform as an equal pin and the station's name in the top
 * bar. Shows live vehicles only with "See everything" on.
 */
class MapStationScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val agency: GtfsAgency,
    private val memberStopIds: List<String>,
    private val stationLabel: String,
) : LightScreen<Unit, MapStationViewModel>(sealedActivity) {

    override val viewModelClass: Class<MapStationViewModel>
        get() = MapStationViewModel::class.java

    override fun createViewModel(): MapStationViewModel =
        MapStationViewModel(
            dbFile, agency, memberStopIds,
            MapPreferences(lightContext.dataStore), TapHoldPreferences(lightContext.dataStore),
        )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val expandedStopIds by viewModel.expandedStopIds.collectAsState()
        val expandedVehicleTripIds by viewModel.expandedVehicleTripIds.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                // The same gesture reminders as the main map.
                val loadedState = state as? MapStationState.Loaded
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = mapGestureHints(
                        doubleTapEnabled = loadedState?.doubleTapStationEnabled == true,
                        tapHoldEnabled = loadedState?.tapHoldArrivalsEnabled == true,
                    ),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                when (val s = state) {
                    is MapStationState.Loading -> LightText(
                        text = "Loading...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(16.dp),
                    )

                    is MapStationState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(16.dp),
                    )

                    is MapStationState.Loaded -> MapCanvas(
                        // Placeholders; there's no center pin here, so they're never used.
                        stopId = memberStopIds.firstOrNull() ?: "",
                        stopLabel = stationLabel,
                        streetContext = null,
                        centerLat = s.centerLat,
                        centerLon = s.centerLon,
                        zoom = s.zoom,
                        mapTiles = s.mapTiles,
                        buses = s.buses,
                        nearbyStops = s.platforms,
                        expandedStopIds = expandedStopIds,
                        nearbyVehiclesEnabled = false,
                        tapHoldArrivalsEnabled = s.tapHoldArrivalsEnabled,
                        darkMapEnabled = s.darkMapEnabled,
                        onToggleStop = viewModel::toggleStopExpanded,
                        onStopLongPressed = { platformStopId, platformLabel ->
                            navigateTo(screenFactory = { activity ->
                                UpcomingArrivalsScreen(activity, dbFile, agency, listOf(platformStopId), platformLabel)
                            })
                        },
                        doubleTapStationEnabled = s.doubleTapStationEnabled,
                        centerIsStation = false,
                        centerStationMemberIds = emptyList(),
                        onOpenStation = { _, _ -> },
                        showCenterPin = false,
                        scrimTitle = stationLabel,
                        // Long press on the station name: arrivals for the whole station.
                        onScrimTitleLongPressed = {
                            navigateTo(screenFactory = { activity ->
                                UpcomingArrivalsScreen(activity, dbFile, agency, memberStopIds, stationLabel)
                            })
                        },
                        // Double-tap on the station name: back to the main map, centered on this
                        // station. Any platform id resolves to the whole station.
                        onScrimTitleDoubleTapped = {
                            navigateTo(screenFactory = { activity ->
                                MapScreen(activity, dbFile, agency, memberStopIds.first(), stationLabel)
                            })
                        },
                        seeEverythingEnabled = s.seeEverythingEnabled,
                        expandedVehicleTripIds = expandedVehicleTripIds,
                        onToggleVehicle = viewModel::toggleVehicleExpanded,
                        tapHoldVehicleEnabled = s.tapHoldVehicleEnabled,
                        onVehicleLongPressed = { bus ->
                            navigateTo(screenFactory = { activity ->
                                TripDetailScreen(activity, dbFile, bus.tripId, 0, bus.routeLabel, bus.directionLabel)
                            })
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                }
                BackToHomeFooter(onGoBackOnce = { goBack() })
            }
        }
    }
}
