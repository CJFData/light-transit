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

// A station's real platforms can be literally co-located (verified against real MBTA South Station
// data -- every platform shares identical lat/lon), so this floor is far smaller than the main Map
// screen's MIN_BOUNDING_BOX_MILES; otherwise every station would render at the same fixed zoom
// regardless of how spread out its platforms actually are.
private const val STATION_MIN_BOUNDING_BOX_MILES = 0.02
// Deliberately larger than the main Map screen's own MAP_TARGET_RADIUS_PIXELS (420f): that value
// reserves margin for a center pin/label/street-context block this screen never draws (see
// showCenterPin = false), and the main map also leaves room around unrelated nearby stops -- here
// every point being fit is a platform the rider actually cares about, so filling more of the frame
// is fine (per product feedback, even preferable). Pushed close to a real device's own half-width
// (see MapScreen's comment on the LP3's 1080x1240px canvas) while leaving room for the compass
// letters.
private const val STATION_ZOOM_TARGET_RADIUS_PIXELS = 500f
private const val STATION_MIN_ZOOM = 17
// Same ceiling as the main Map screen's own MAX_ZOOM -- the CARTO tile server this app fetches
// from (see MapTileClient) isn't verified to serve anything past this, so it stays the hard cap
// even though the fit-to-bounds formula above now usually reaches it for a real station's
// platform cluster.
private const val STATION_MAX_ZOOM = 20
private const val STATION_FALLBACK_ZOOM = 20
// Same cadence as the main Map screen's own LIVE_VEHICLE_POLL_INTERVAL_MS -- only ever used at all
// when "See Everything" is on (see MapStationViewModel.onScreenShow); this screen otherwise never
// polls live data, same as before that mode existed.
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
        /** Always empty unless "See Everything" (Settings toggle) is on -- see
         * MapStationViewModel.onScreenShow, which skips live polling entirely otherwise, same as
         * this screen behaved before that mode existed. */
        val buses: List<BusMarker>,
        val seeEverythingEnabled: Boolean,
        /** Settings screen toggle (off by default) -- see MapPreferences.doubleTapStationEnabledFlow.
         * Governs double-tapping the scrim title here (zoom OUT to the main map) the same way it
         * already governs double-tapping a station marker on the main Map screen (zoom IN). */
        val doubleTapStationEnabled: Boolean,
        /** Settings toggle (on by default) -- see TapHoldPreferences.tapHoldVehicleEnabledFlow. Tap
         * and hold a vehicle marker here to open its own Trip Detail, same as on the main Map screen. */
        val tapHoldVehicleEnabled: Boolean,
    ) : MapStationState()
    data class Error(val message: String) : MapStationState()
}

/** Everything computed once at screen-open that [MapStationViewModel] needs again to serve an
 * out-of-cycle refresh (e.g. a "Filter by stop" selection changing mid poll-interval) -- mirrors
 * MapScreen's own LoadedMapContext. */
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

    /** Mirrors MapViewModel's own expanded-label state -- a tap on a platform reveals/hides its own
     * name label, same as a nearby-stop marker on the main Map screen. Also doubles as "See
     * Everything" + "Filter by stop"'s own stop selection, same as on the main Map screen. */
    val expandedStopIds = MutableStateFlow<Set<String>>(emptySet())

    /** "See Everything" mode's own tap-to-expand state for vehicle markers -- see MapViewModel's
     * identical field for the full explanation. */
    val expandedVehicleTripIds = MutableStateFlow<Set<String>>(emptySet())

    private var loadJob: Job? = null
    private var loadedContext: LoadedStationContext? = null

    /** Wakes the poll loop early on a "Filter by stop" selection change -- see MapViewModel's
     * identical field. Only ever actually consumed while "See Everything" is on (see
     * [onScreenShow]); harmless if sent otherwise, just never received. */
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
                val filterByStopEnabled = mapPreferences.filterByStopEnabledFlow.first()
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
                } catch (e: Exception) {
                    Log.e("MapStationScreen", "Map tile fetch failed for platforms $memberStopIds", e)
                    null
                }

                // Each platform's own label (e.g. "Track 1"), not prefixed with the station name -- same
                // South-Station-verified technique Upcoming Arrivals already uses for platform
                // disambiguation (see GtfsRepository.platformLabelFromStopDesc). Falls back to the
                // platform's own stop_name when stop_desc has nothing more specific.
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

                // Only "See Everything" ever needs live vehicle data here -- with it off, this
                // screen behaves exactly as it always has (one static load, no polling at all).
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
            } catch (e: Exception) {
                Log.e("MapStationScreen", "Failed to load station map for platforms $memberStopIds", e)
                _state.value = MapStationState.Error("Unable to load station map.")
            }
        }
    }

    /** Only reachable while "See Everything" is on (see [onScreenShow]) -- fetches live vehicle
     * data and rebuilds [MapStationState.Loaded] with it, reusing MapScreen's own
     * buildSeeEverythingBuses so both screens share one implementation. */
    private suspend fun refresh() {
        val context = loadedContext ?: return
        if (agency.realtimeVehiclePositionsUrl == null) {
            _state.value = stationLoaded(context, emptyList())
            return
        }
        val vehiclePositions = agency.fetchMergedVehiclePositions("MapStationScreen")
        if (vehiclePositions.primary == null) {
            _state.value = stationLoaded(context, emptyList())
            return
        }
        val tripUpdatesByTripId = agency.fetchMergedTripUpdates("MapStationScreen").byTripId
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

    /** "See Everything" mode's own tap-to-expand for a vehicle marker -- see MapViewModel's
     * identical function. */
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
 * A zoomed-in sub-map of just one multi-platform station's own platforms ("Map-Station mode"),
 * reached by double-tapping a station marker on the main Map screen (see MapScreen's
 * doubleTapStationEnabled/onOpenStation). Reuses the same [MapCanvas] the main Map screen draws
 * with -- same tiles/pin style/tap-to-reveal-label behavior -- just with no privileged center pin
 * (every platform is an equal small pin) and the station's name in the scrim instead. Shows live
 * vehicles of its own only when "See Everything" (Settings toggle) is on -- see
 * MapStationViewModel's own doc.
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
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
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
                        // Inert placeholders: hitCenter can never fire (see MapCanvas's own gating on
                        // showCenterPin), so these are never actually read for a hit-test.
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
                        // Tap-and-hold the scrim title for the whole station's own upcoming arrivals -- consistent with
                        // tap-and-hold meaning "show actual arrivals" everywhere else in the app (a single
                        // platform pin here, a stop on the main Map screen, Schedule's stop list, the
                        // Stations list).
                        onScrimTitleLongPressed = {
                            navigateTo(screenFactory = { activity ->
                                UpcomingArrivalsScreen(activity, dbFile, agency, memberStopIds, stationLabel)
                            })
                        },
                        // Double-tap the scrim title to zoom back out to the main Map screen, centered on this same
                        // station -- symmetric with double-tapping a station marker on the main map to zoom
                        // in here (see MapScreen's own onOpenStation). Any one of this station's member
                        // platform ids resolves back to the full station (via MapViewModel's own
                        // getStationContaining lookup, the same one every other station entry point goes
                        // through), so which specific platform id gets passed doesn't matter.
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
