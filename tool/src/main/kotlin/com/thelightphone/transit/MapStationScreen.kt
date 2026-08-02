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
import com.thelightphone.transit.gtfs.fitBoundsZoom
import com.thelightphone.transit.gtfs.metersPerPixel
import com.thelightphone.transit.gtfs.platformLabelFromStopDesc
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

// A station's real platforms can be literally co-located (verified against real MBTA South Station
// data -- every platform shares identical lat/lon), so this floor is far smaller than the main Map
// screen's MIN_BOUNDING_BOX_MILES; otherwise every station would render at the same fixed zoom
// regardless of how spread out (or not) its platforms actually are.
private const val STATION_MIN_BOUNDING_BOX_MILES = 0.02
// Deliberately larger than the main Map screen's own MAP_TARGET_RADIUS_PIXELS (420f): that value
// reserves margin for a center pin/label/street-context block this screen never draws (see
// showCenterPin = false), and the main map's fit also has to leave room to breathe around a whole
// neighborhood of *unrelated* nearby stops -- here every point being fit is itself a platform the
// rider actually cares about, so it's fine (in fact preferable, per explicit product feedback) to
// fill more of the frame with them. Pushed close to a real device's own half-width (see MapScreen's
// own comment on the LP3's 1080x1240px canvas) while still leaving room for the compass letters.
private const val STATION_ZOOM_TARGET_RADIUS_PIXELS = 500f
private const val STATION_MIN_ZOOM = 17
// Same ceiling as the main Map screen's own MAX_ZOOM -- the CARTO tile server this app fetches from
// (see MapTileClient) isn't verified to serve anything past this, so this stays the hard cap even
// though the fit-to-bounds formula above will now usually reach it for a real station's platform
// cluster (unlike before, where the main map's own smaller target radius rarely got close).
private const val STATION_MAX_ZOOM = 20
private const val STATION_FALLBACK_ZOOM = 20

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
    ) : MapStationState()
    data class Error(val message: String) : MapStationState()
}

class MapStationViewModel(
    dbFile: File,
    private val memberStopIds: List<String>,
    private val mapPreferences: MapPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val tileClient = MapTileClient()

    private val _state = MutableStateFlow<MapStationState>(MapStationState.Loading)
    val state: StateFlow<MapStationState> = _state

    /** Mirrors MapViewModel's own expanded-label state -- a tap on a platform reveals/hides its own
     * name label, same as a nearby-stop marker on the main Map screen. No vehicle-tracking side
     * effect here, since Map-Station mode never shows live vehicles of its own (this screen never
     * passes a `nearbyVehiclesEnabled = true` to MapCanvas). */
    val expandedStopIds = MutableStateFlow<Set<String>>(emptySet())

    private var loadJob: Job? = null

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

                // Each platform's own label (e.g. "Track 1"), not prefixed with the station name --
                // same real-South-Station-verified technique Upcoming Arrivals already uses for
                // platform disambiguation (see GtfsRepository.platformLabelFromStopDesc). Falls back
                // to the platform's own stop_name when stop_desc has nothing more specific.
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

                _state.value = MapStationState.Loaded(
                    centerLat, centerLon, zoom, mapTiles, platformMarkers, tapHoldArrivalsEnabled, darkMode,
                )
            } catch (e: Exception) {
                Log.e("MapStationScreen", "Failed to load station map for platforms $memberStopIds", e)
                _state.value = MapStationState.Error("Unable to load station map.")
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        loadJob?.cancel()
        loadJob = null
    }

    fun toggleStopExpanded(stopId: String) {
        expandedStopIds.value = expandedStopIds.value.let { if (stopId in it) it - stopId else it + stopId }
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
 * doubleTapStationEnabled/onOpenStation). Reuses the exact same [MapCanvas] the main Map screen
 * draws with -- same tiles/pin style/tap-to-reveal-label behavior -- just with no privileged center
 * pin (every platform is an equal small pin), the station's name in the scrim instead, and no live
 * vehicle tracking of its own.
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
        MapStationViewModel(dbFile, memberStopIds, MapPreferences(lightContext.dataStore))

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val expandedStopIds by viewModel.expandedStopIds.collectAsState()
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
                        buses = emptyList(),
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
                        doubleTapStationEnabled = false,
                        centerIsStation = false,
                        centerStationMemberIds = emptyList(),
                        onOpenStation = { _, _ -> },
                        showCenterPin = false,
                        scrimTitle = stationLabel,
                        onScrimTitleLongPressed = {
                            navigateTo(screenFactory = { activity ->
                                UpcomingArrivalsScreen(activity, dbFile, agency, memberStopIds, stationLabel)
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
