package com.thelightphone.transit

import android.graphics.Bitmap
import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.ArrivalStatus
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRealtimeClient
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.GtfsRtVehicleStatus
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.MapPreferences
import com.thelightphone.transit.gtfs.MapTiles
import com.thelightphone.transit.gtfs.NominatimGeocoder
import com.thelightphone.transit.gtfs.MapTileClient
import com.thelightphone.transit.gtfs.ScheduledArrival
import com.thelightphone.transit.gtfs.StopLocation
import com.thelightphone.transit.gtfs.computeArrivalEta
import com.thelightphone.transit.gtfs.currentGtfsTimeOfDay
import com.thelightphone.transit.gtfs.fitBoundsZoom
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.transit.gtfs.metersPerPixel
import com.thelightphone.transit.gtfs.projectRelativeToCenter
import com.thelightphone.transit.gtfs.todayForGtfs
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.sqrt

// Tunable. Faster than agencies typically refresh their own feed (~15-20s), so some polls just
// re-fetch the same data -- a deliberate trade of a few extra requests for lower latency once a
// feed does update, not a continuous/unbounded poll.
private const val LIVE_VEHICLE_POLL_INTERVAL_MS = 10_000L
// Per active stop, not a shared total -- turning on "Nearby Vehicles" and selecting more stops
// should show more buses, not compete with the primary stop for the same fixed slots.
private const val MAX_DISPLAYED_BUSES_PER_STOP = 4
// How many of the closest stops (no radius cap) drive the zoom-fit calculation -- see
// fitBoundsZoom. A fixed radius cap here would mean the "farthest of the nearby stops" is almost
// always near that cap in any reasonably dense area, collapsing the fit to look like a fixed zoom
// regardless of true local density (verified empirically against real MBTA stop data).
private const val ZOOM_FIT_NEAREST_STOP_COUNT = 8

// The LP3's screen is 1080x1240px at 3x density (see the @Preview(widthDp = 1080 / 3, heightDp =
// 1240 / 3) calls elsewhere in the SDK) — used as the "available half-extent" nearby stops are fit
// within (see fitBoundsZoom) before the Canvas itself is laid out. If the real canvas ends up a
// different size than assumed here, markers and the map background still line up exactly with each
// other (they share one projection); the only effect is more or less margin around the edge than
// intended (tiles are fetched with their own margin on top of this for exactly that reason — see
// MapTileClient).
private const val MAP_TARGET_RADIUS_PIXELS = 420f
// Floor: even a sparse/isolated area never zooms out past this, so nearby stops don't visually
// collapse into an unreadable cluster. Ceiling: never zooms in tighter than this either, so there's
// always at least a block or two of surrounding streets left for orientation. Fallback: used only
// when there's nothing to fit bounds to at all (no other stops exist anywhere nearby) -- rarely
// reached in practice since MIN_BOUNDING_BOX_MILES below already keeps the box away from zero-size.
private const val MIN_ZOOM = 17
private const val MAX_ZOOM = 20
private const val FALLBACK_ZOOM = 20
// Treat the nearby-stop cluster's bounding box as at least this wide in each dimension before
// computing zoom, so a real-world-tiny cluster (e.g. two platforms 30ft apart) doesn't zoom in
// absurdly tight just because the points themselves happen to be that close together.
private const val MIN_BOUNDING_BOX_MILES = 0.15
private const val STOP_HIT_RADIUS_PX = 44f
private const val LABEL_LINE_HEIGHT_PX = 22f
// Gap between a tapped stop's marker and its expanded name label, which renders to the marker's
// right (anchored at the marker's own base) rather than centered under it, so the label text never
// overlaps the icon itself.
private const val LABEL_GAP_PX = 12f

// Vector icon sizes (real pixels, matching this Canvas's own coordinate space -- not dp/grid units).
// Nearby-stop markers must stay visibly smaller than the center marker despite sharing the same
// icon shape (see LightIcons.DIRECTIONS_ARRIVAL usage below), since size is the only thing distinguishing them.
private const val CENTER_MARKER_ICON_PX = 64
private const val NEARBY_MARKER_ICON_PX = 40
// Close to NEARBY_MARKER_ICON_PX rather than matching CENTER_MARKER_ICON_PX -- vehicles are
// transient/moving, not a fixed place to orient by, so they shouldn't visually compete with the
// selected stop's own pin, but still read as slightly more prominent than a plain nearby stop.
private const val VEHICLE_MARKER_ICON_PX = 46
// Matches the on-canvas size of the "No live vehicles..."-style status message (LightTextVariant
// .Detail, rendered outside the canvas by Content()) -- measured against a real render of that text
// so the toggle reads at the same size as the status line right above it, rather than notably smaller.
private const val TOGGLE_ICON_PX = 60
private const val TOGGLE_TEXT_SIZE_PX = 40f
private const val TOGGLE_TEXT_GAP_PX = 12f
private const val TOGGLE_TEXT = "Track Tapped Stops"

// Attribution renders as an overlay bar on top of the map itself (matching how the compass letters
// already render directly on the map), rather than in a separate section above it. Solid, not
// translucent, and flush with the top of the screen -- the compass's "N" label is pushed below it
// (see COMPASS_SCRIM_MARGIN_PX) so the two never overlap. The real LightTopBar (back button) sits
// above this in normal layout flow, and any live-feed status message renders above the canvas too
// (see Content()), so this scrim only needs to cover the attribution line itself.
private const val SCRIM_HEIGHT_PX = 40f
private const val OVERLAY_INSET_X = 28f
private const val ATTRIBUTION_Y = 26f
private const val COMPASS_SCRIM_MARGIN_PX = 24f
private const val TOGGLE_BOTTOM_MARGIN_PX = 24f

data class BusMarker(
    val tripId: String,
    /** Which stop this vehicle is inbound to -- the primary stop, or (when "Nearby Vehicles" is on)
     * one of the additionally-selected nearby stops. Only the primary stop's arrivals get the
     * special "arrived" row above the center pin; everything else always draws at its true
     * position, since that row visually means "arrived at the center pin" specifically. */
    val targetStopId: String,
    /** Short route identifier only (e.g. "R"), not the full "shortName - longName" combo -- the
     * marker icon already conveys the mode (bus/subway/rail), so the label just needs enough to
     * tell same-mode routes apart. See [tripDescription]. */
    val routeLabel: String,
    val directionLabel: String,
    /** Mode-specific: subway/light rail, commuter rail, bus (also the fallback for any unmapped
     * route_type) -- see [vehicleIconFor], the single source of truth for this mapping. */
    val vehicleIcon: LightIconConfiguration,
    val etaEpochSeconds: Long,
    /** Null just means "no delay/prediction info yet" — a marker only ever exists for a trip with a
     * live VehiclePosition match, so this is never a stand-in for stale/non-live data. */
    val status: ArrivalStatus?,
    val isArrived: Boolean,
    val lat: Double,
    val lon: Double,
)

/** Maps a route's [LineType] to the SDK icon for its vehicle marker -- BUS is also the fallback
 * for a null (unmapped route_type) LineType, same as the emoji fallback this replaced. */
fun LineType?.toVehicleIcon(): LightIconConfiguration = when (this) {
    LineType.SUBWAY -> LightIcons.DIRECTIONS_SUBWAY
    LineType.COMMUTER_RAIL -> LightIcons.DIRECTIONS_TRAIN
    LineType.BUS, null -> LightIcons.DIRECTIONS_BUS
}

/** e.g. "R · Toward Pawtucket-Central Falls Transit Center" -- no mode prefix (the icon already
 * shows bus/subway/rail) and no long route name, just enough to tell same-mode routes apart. */
fun BusMarker.tripDescription(): String = "$routeLabel · $directionLabel"

fun BusMarker.etaDisplay(): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(etaEpochSeconds), ZoneId.systemDefault())
    return "ETA: " + formatGtfsTime("%02d:%02d:00".format(time.hour, time.minute))
}

fun BusMarker.statusLabel(): String? = when (val s = status) {
    null -> null
    ArrivalStatus.OnTime -> "On time"
    is ArrivalStatus.Late -> "Late ${(s.seconds / 60).coerceAtLeast(1)}m"
    is ArrivalStatus.Early -> "Early ${(s.seconds / 60).coerceAtLeast(1)}m"
}

data class NearbyStopMarker(
    val stopId: String,
    val stopName: String?,
    val lat: Double,
    val lon: Double,
)

/** Distinguishes *why* no live buses are shown, so the empty state is never ambiguous. */
enum class LiveFeedStatus {
    /** This agency has no VehiclePositions feed configured at all — a permanent condition. */
    NOT_SUPPORTED,
    /** The feed exists but this poll's fetch failed. */
    UNAVAILABLE,
    /** The feed fetched fine; zero matching buses just means none are out right now. */
    OK,
}

sealed class MapState {
    object Loading : MapState()
    data class Loaded(
        val streetContext: String?,
        val centerLat: Double,
        val centerLon: Double,
        val zoom: Int,
        val mapTiles: MapTiles?,
        val buses: List<BusMarker>,
        val nearbyStops: List<NearbyStopMarker>,
        val liveFeedStatus: LiveFeedStatus,
        /** Settings screen toggle (off by default) -- see MapPreferences.tapHoldArrivalsEnabledFlow. */
        val tapHoldArrivalsEnabled: Boolean,
        /** Settings screen's Map style toggle -- see MapPreferences.darkMapEnabledFlow. */
        val darkMapEnabled: Boolean,
    ) : MapState()
    data class Error(val message: String) : MapState()
}

/** Everything computed once at screen-open that [MapViewModel] needs again to serve an
 * out-of-cycle refresh (e.g. the user flipping the Nearby Vehicles toggle mid-poll-interval). */
private data class LoadedMapContext(
    val stop: StopLocation,
    val streetContext: String?,
    val zoom: Int,
    val mapTiles: MapTiles?,
    val nearbyStops: List<NearbyStopMarker>,
    val tapHoldArrivalsEnabled: Boolean,
    val darkMapEnabled: Boolean,
)

class MapViewModel(
    dbFile: File,
    private val agency: GtfsAgency,
    private val stopId: String,
    private val mapPreferences: MapPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val geocoder = NominatimGeocoder()
    private val tileClient = MapTileClient()

    private val _state = MutableStateFlow<MapState>(MapState.Loading)
    val state: StateFlow<MapState> = _state

    val nearbyVehiclesEnabled = MutableStateFlow(false)
    /** Nearby stops the user has tapped open -- always reveals that stop's name label; when
     * [nearbyVehiclesEnabled] is also on, an expanded stop additionally contributes its own inbound
     * vehicles to the map. */
    val expandedStopIds = MutableStateFlow<Set<String>>(emptySet())

    private var pollJob: Job? = null
    private var loadedContext: LoadedMapContext? = null
    /** Lazily populated as stops become active -- the primary stop's schedule is seeded up front,
     * a nearby stop's is fetched the first time it's ever toggled on. */
    private val scheduledArrivalsByStopId = mutableMapOf<String, List<ScheduledArrival>>()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val stop = repository.getStopLocation(stopId)
                if (stop == null) {
                    _state.value = MapState.Error("Stop location not found.")
                    return@launch
                }
                // Read once at screen-open, same as HomeScreen's default-agency read -- Settings
                // isn't shown at the same time as this screen, so there's no need to react live.
                val darkMode = mapPreferences.darkMapEnabledFlow.first()
                val tapHoldArrivalsEnabled = mapPreferences.tapHoldArrivalsEnabledFlow.first()
                // Snapshot once: which trips are scheduled for this stop from now on. Live polling
                // below only checks these same trips for a live position match — a trip that wasn't
                // scheduled at screen-open time won't appear mid-session.
                scheduledArrivalsByStopId[stopId] = repository.getScheduledArrivals(stopId, currentGtfsTimeOfDay(), todayForGtfs())
                val streetContext = try {
                    geocoder.reverseGeocode(stop.lat, stop.lon)
                } catch (e: Exception) {
                    Log.e("MapScreen", "Reverse geocoding failed for stop $stopId", e)
                    null
                }

                // Zoom fits the nearest ZOOM_FIT_NEAREST_STOP_COUNT stops (no radius cap) around the
                // selected stop (which always stays at dead center) -- a dense cluster naturally
                // zooms in more, an isolated stop naturally zooms out, with no per-agency
                // special-casing needed.
                val nearestForZoom = repository.rankStopsByDistance(stop.lat, stop.lon, ZOOM_FIT_NEAREST_STOP_COUNT, excludeStopId = stopId)
                val zoom = fitBoundsZoom(
                    centerLat = stop.lat,
                    centerLon = stop.lon,
                    points = nearestForZoom.map { it.lat to it.lon },
                    availableHalfExtentPx = MAP_TARGET_RADIUS_PIXELS,
                    minZoom = MIN_ZOOM,
                    maxZoom = MAX_ZOOM,
                    fallbackZoom = FALLBACK_ZOOM,
                    minBoundingBoxMiles = MIN_BOUNDING_BOX_MILES,
                )

                // The map background is a set of still tiles, fetched once — it doesn't need to
                // refresh on the same cadence as bus positions. Fetch radius is derived from the
                // zoom just chosen (how much ground MAP_TARGET_RADIUS_PIXELS actually covers at that
                // zoom) so a dense-hub view (zoomed in tight) doesn't fetch far more tiles than it
                // can ever show, and a sparse-area view (zoomed out to the MIN_ZOOM floor) doesn't
                // leave the edges of the canvas blank. The nearby-stops query below reuses the same
                // radius for exactly the same reason -- what's plotted should match what's visible.
                val fetchRadiusMeters = MAP_TARGET_RADIUS_PIXELS * metersPerPixel(stop.lat, zoom)
                val mapTiles = try {
                    tileClient.fetchTilesAround(stop.lat, stop.lon, zoom, fetchRadiusMeters, darkMode)
                } catch (e: Exception) {
                    Log.e("MapScreen", "Map tile fetch failed for stop $stopId", e)
                    null
                }
                val nearbyStops = repository.getStopsWithinRadius(
                    stop.lat, stop.lon, fetchRadiusMeters, excludeStopId = stopId,
                ).map { nearby -> NearbyStopMarker(nearby.stopId, nearby.stopName, nearby.lat, nearby.lon) }

                loadedContext = LoadedMapContext(stop, streetContext, zoom, mapTiles, nearbyStops, tapHoldArrivalsEnabled, darkMode)

                while (isActive) {
                    refresh()
                    delay(LIVE_VEHICLE_POLL_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e("MapScreen", "Failed to load map for stop $stopId", e)
                _state.value = MapState.Error("Unable to load bus positions.")
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        pollJob?.cancel()
        pollJob = null
    }

    /** Same tap either way: reveals/hides that stop's name label. Only additionally affects vehicle
     * data when [nearbyVehiclesEnabled] is on -- refreshed immediately rather than waiting for the
     * next scheduled poll, so the extra vehicles (or their removal) show up right away. */
    fun toggleStopExpanded(stopId: String) {
        expandedStopIds.value = expandedStopIds.value.let { if (stopId in it) it - stopId else it + stopId }
        if (nearbyVehiclesEnabled.value) refreshNow()
    }

    fun toggleNearbyVehicles() {
        nearbyVehiclesEnabled.value = !nearbyVehiclesEnabled.value
        refreshNow()
    }

    private fun refreshNow() {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    private suspend fun refresh() {
        val context = loadedContext ?: return
        val today = todayForGtfs()

        // Active stops: always the primary one; plus any expanded nearby stops, but only while the
        // toggle is on. Filtered against the known nearby-stop set as a safety net against stale ids.
        val activeStopIds = buildSet {
            add(stopId)
            if (nearbyVehiclesEnabled.value) {
                val nearbyIds = context.nearbyStops.mapTo(mutableSetOf()) { it.stopId }
                addAll(expandedStopIds.value.filter { it in nearbyIds })
            }
        }
        for (id in activeStopIds) {
            if (id !in scheduledArrivalsByStopId) {
                scheduledArrivalsByStopId[id] = repository.getScheduledArrivals(id, currentGtfsTimeOfDay(), today)
            }
        }

        if (agency.realtimeVehiclePositionsUrl == null) {
            _state.value = MapState.Loaded(
                context.streetContext, context.stop.lat, context.stop.lon, context.zoom, context.mapTiles,
                emptyList(), context.nearbyStops, LiveFeedStatus.NOT_SUPPORTED,
                context.tapHoldArrivalsEnabled, context.darkMapEnabled,
            )
            return
        }

        val vehiclePositionsFeed = try {
            GtfsRealtimeClient.fetchFeed(agency.realtimeVehiclePositionsUrl)
        } catch (e: Exception) {
            Log.e("MapScreen", "VehiclePositions fetch failed for ${agency.displayName}", e)
            null
        }
        if (vehiclePositionsFeed == null) {
            _state.value = MapState.Loaded(
                context.streetContext, context.stop.lat, context.stop.lon, context.zoom, context.mapTiles,
                emptyList(), context.nearbyStops, LiveFeedStatus.UNAVAILABLE,
                context.tapHoldArrivalsEnabled, context.darkMapEnabled,
            )
            return
        }

        val tripUpdatesFeed = agency.realtimeTripUpdatesUrl?.let { url ->
            try {
                GtfsRealtimeClient.fetchFeed(url)
            } catch (e: Exception) {
                Log.e("MapScreen", "TripUpdates fetch failed for ${agency.displayName}", e)
                null
            }
        }

        // Only trips that are BOTH scheduled to arrive at an active stop AND currently reporting a
        // live vehicle position ever become a marker — no scheduled-only approximation. Each active
        // stop gets its own MAX_DISPLAYED_BUSES_PER_STOP allotment rather than sharing one total, so
        // selecting more stops shows more buses instead of competing for the same fixed slots.
        val buses = activeStopIds.flatMap { activeStopId ->
            val scheduledArrivals = scheduledArrivalsByStopId[activeStopId] ?: return@flatMap emptyList()
            scheduledArrivals.mapNotNull { arrival ->
                val vehicle = vehiclePositionsFeed.vehiclePositionsByTripId[arrival.tripId] ?: return@mapNotNull null
                val position = vehicle.position ?: return@mapNotNull null

                // Already past this stop on this trip -> disappear rather than show it moving away.
                val currentSeq = vehicle.currentStopSequence
                if (currentSeq != null && currentSeq > arrival.stopSequence) return@mapNotNull null

                // No real stop_id is ever present on live vehicle data (see GtfsRtVehiclePosition's
                // doc) -- current_stop_sequence matching the target stop is the only signal available.
                val isArrived = vehicle.currentStatus == GtfsRtVehicleStatus.STOPPED_AT &&
                    currentSeq == arrival.stopSequence

                val rtStopUpdate = tripUpdatesFeed?.tripUpdatesByTripId?.get(arrival.tripId)
                    ?.updateFor(activeStopId, arrival.stopSequence)
                val eta = computeArrivalEta(arrival.departureTime, today, rtStopUpdate) ?: return@mapNotNull null

                val lineType = LineType.forGtfsRouteType(arrival.route.routeType)
                BusMarker(
                    tripId = arrival.tripId,
                    targetStopId = activeStopId,
                    routeLabel = arrival.route.shortName?.takeIf { it.isNotBlank() } ?: arrival.route.displayName,
                    directionLabel = arrival.direction.displayLabel(),
                    vehicleIcon = lineType.toVehicleIcon(),
                    etaEpochSeconds = eta.etaEpochSeconds,
                    status = eta.status,
                    isArrived = isArrived,
                    lat = position.latitude.toDouble(),
                    lon = position.longitude.toDouble(),
                )
            }.sortedBy { it.etaEpochSeconds }.take(MAX_DISPLAYED_BUSES_PER_STOP)
        }
        // The same live vehicle could be inbound to two selected stops close together on one route
        // -- keep only its earliest-ETA entry rather than showing it twice.
        val dedupedBuses = buses.groupBy { it.tripId }.values.map { group -> group.minBy { it.etaEpochSeconds } }

        _state.value = MapState.Loaded(
            context.streetContext, context.stop.lat, context.stop.lon, context.zoom, context.mapTiles,
            dedupedBuses, context.nearbyStops, LiveFeedStatus.OK,
            context.tapHoldArrivalsEnabled, context.darkMapEnabled,
        )
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
        geocoder.close()
        tileClient.close()
    }
}

class MapScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val agency: GtfsAgency,
    private val stopId: String,
    private val stopLabel: String,
) : LightScreen<Unit, MapViewModel>(sealedActivity) {

    override val viewModelClass: Class<MapViewModel>
        get() = MapViewModel::class.java

    override fun createViewModel(): MapViewModel =
        MapViewModel(dbFile, agency, stopId, MapPreferences(lightContext.dataStore))

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val nearbyVehiclesEnabled by viewModel.nearbyVehiclesEnabled.collectAsState()
        val expandedStopIds by viewModel.expandedStopIds.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                // A real LightTopBar in normal layout flow -- same pattern LightQrCodeScanner uses
                // for a back button over full-bleed content, rather than absolutely overlaying one
                // on top of the Canvas. That approach only ever colliding-by-luck depended on the
                // Canvas's own title text staying clear of wherever the icon happened to sit; this
                // way there's nothing else placed in the icon's row for it to collide with, by
                // construction. No title text here -- the Map screen doesn't need to say "Map".
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                )
                Column(modifier = Modifier.weight(1f)) {
                when (val s = state) {
                    is MapState.Loading -> LightText(
                        text = "Loading...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(16.dp),
                    )

                    is MapState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(16.dp),
                    )

                    is MapState.Loaded -> {
                        // Only a transient, occasionally-appearing banner -- title and legend render
                        // as an overlay on the map itself (see MapCanvas), same as the compass.
                        val liveMessage = when {
                            s.liveFeedStatus == LiveFeedStatus.NOT_SUPPORTED ->
                                "This agency doesn't provide live vehicle tracking."
                            s.liveFeedStatus == LiveFeedStatus.UNAVAILABLE ->
                                "Live positions unavailable right now."
                            s.liveFeedStatus == LiveFeedStatus.OK && s.buses.isEmpty() ->
                                "No live vehicles currently tracked for this stop."
                            else -> null
                        }
                        liveMessage?.let {
                            LightText(
                                text = it,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        MapCanvas(
                            stopId = stopId,
                            stopLabel = stopLabel,
                            streetContext = s.streetContext,
                            centerLat = s.centerLat,
                            centerLon = s.centerLon,
                            zoom = s.zoom,
                            mapTiles = s.mapTiles,
                            buses = s.buses,
                            nearbyStops = s.nearbyStops,
                            expandedStopIds = expandedStopIds,
                            nearbyVehiclesEnabled = nearbyVehiclesEnabled,
                            tapHoldArrivalsEnabled = s.tapHoldArrivalsEnabled,
                            darkMapEnabled = s.darkMapEnabled,
                            onToggleStop = viewModel::toggleStopExpanded,
                            onToggleNearbyVehicles = viewModel::toggleNearbyVehicles,
                            onStopLongPressed = { longPressStopId, longPressStopLabel ->
                                navigateTo(screenFactory = { activity ->
                                    UpcomingArrivalsScreen(activity, dbFile, agency, longPressStopId, longPressStopLabel)
                                })
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * Rasterizes an SDK vector icon to a plain [Bitmap] once (cached for this icon+size+tint for as
 * long as this composable stays alive), for drawing directly via the native Canvas -- which draws
 * bitmaps, not Compose vector assets. Built entirely from Compose's own resource/drawing APIs
 * ([painterResource], [CanvasDrawScope]) rather than a raw [android.content.Context] lookup, since
 * tool code can't hold one directly (see the SDK's own build-time source restrictions). Every icon
 * in [LightIcons] ships solid white in its own resource (see the "_white" drawable naming) -- [tint]
 * recolors it via [ColorFilter.tint] since there's no separate dark-mode drawable to fall back on,
 * needed so these solid (non-outlined) glyphs stay legible against Light map tiles the same way the
 * white-fill/black-outline label text already is against either tile style.
 */
@Composable
private fun rememberIconBitmap(icon: LightIconConfiguration, sizePx: Int, tint: Color): Bitmap {
    val painter = painterResource(icon.drawableResource)
    val colorFilter = remember(tint) { ColorFilter.tint(tint) }
    return remember(painter, sizePx, tint) {
        val imageBitmap = ImageBitmap(sizePx, sizePx)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = ComposeCanvas(imageBitmap),
            size = Size(sizePx.toFloat(), sizePx.toFloat()),
        ) {
            with(painter) { draw(size = size, colorFilter = colorFilter) }
        }
        imageBitmap.asAndroidBitmap()
    }
}

/**
 * Draws the whole map entirely on a single Canvas, including text — emoji glyphs and labels are
 * drawn via the underlying native android.graphics.Canvas/Paint (Compose's own text APIs are built
 * for styled text layout, not single positioned glyphs). Every marker is placed at its true
 * projected position (clipped to the visible radius only as a fallback for rare far-outliers), with
 * no de-overlap/clustering logic for markers themselves — only tapped-open stop labels get basic
 * collision handling (see resolveLabelPositions), since those are the only thing users reveal
 * on demand and might stack illegibly.
 *
 * Touch handling is a hand-written gesture loop rather than [androidx.compose.foundation.gestures
 * .detectTapGestures] deliberately: that helper unconditionally consumes the down (and up) event for
 * *every* touch anywhere in its bounds, even ones that don't land on anything -- confirmed by reading
 * its actual implementation. Since this Canvas fills the whole screen, that was swallowing edge
 * touches that back-navigation swiping needs to see. Here, nothing is consumed unless a touch
 * actually lands on a real marker or the toggle.
 */
@Composable
private fun MapCanvas(
    stopId: String,
    stopLabel: String,
    streetContext: String?,
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    mapTiles: MapTiles?,
    buses: List<BusMarker>,
    nearbyStops: List<NearbyStopMarker>,
    expandedStopIds: Set<String>,
    nearbyVehiclesEnabled: Boolean,
    tapHoldArrivalsEnabled: Boolean,
    /** Settings screen's Map style toggle -- Light tiles are bright, so solid icon glyphs need to
     * render dark (black) to stay legible over them; Dark tiles keep the original white glyphs. */
    darkMapEnabled: Boolean,
    onToggleStop: (String) -> Unit,
    onToggleNearbyVehicles: () -> Unit,
    /** Fires for a long press on a stop marker (the center stop or any nearby one) when
     * [tapHoldArrivalsEnabled] is on -- see the Settings screen's "Tap and hold a stop" toggle. */
    onStopLongPressed: (stopId: String, stopLabel: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Loaded once per icon+size+tint (remember only works at composition time, not inside the
    // Canvas draw-phase lambda below), then just referenced as a plain Bitmap during drawing.
    val iconTint = if (darkMapEnabled) Color.White else Color.Black
    val centerMarkerBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_ARRIVAL, CENTER_MARKER_ICON_PX, iconTint)
    val nearbyMarkerBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_ARRIVAL, NEARBY_MARKER_ICON_PX, iconTint)
    val busIconBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_BUS, VEHICLE_MARKER_ICON_PX, iconTint)
    val subwayIconBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_SUBWAY, VEHICLE_MARKER_ICON_PX, iconTint)
    val trainIconBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_TRAIN, VEHICLE_MARKER_ICON_PX, iconTint)
    val toggleOffBitmap = rememberIconBitmap(LightIcons.TOGGLE_STATE_OFF, TOGGLE_ICON_PX, iconTint)
    val toggleOnBitmap = rememberIconBitmap(LightIcons.TOGGLE_STATE_ON, TOGGLE_ICON_PX, iconTint)

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(nearbyStops, centerLat, centerLon, zoom, nearbyVehiclesEnabled, tapHoldArrivalsEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = minOf(size.width, size.height) / 2f * 0.8f

                    val hitStop = nearbyStops.firstOrNull { stop ->
                        val rel = projectRelativeToCenter(centerLat, centerLon, stop.lat, stop.lon, zoom)
                        val point = clipToRadius(Offset(center.x + rel.x, center.y + rel.y), center, maxRadius)
                        val dx = down.position.x - point.x
                        val dy = down.position.y - point.y
                        sqrt(dx * dx + dy * dy) < STOP_HIT_RADIUS_PX
                    }
                    val hitCenter = run {
                        val dx = down.position.x - center.x
                        val dy = down.position.y - center.y
                        sqrt(dx * dx + dy * dy) < STOP_HIT_RADIUS_PX
                    }
                    val toggleBounds = toggleHitRect(size.height.toFloat())
                    val hitToggle = toggleBounds.contains(down.position)

                    if (hitStop == null && !hitCenter && !hitToggle) {
                        // Not on anything we handle -- leave the event completely untouched so
                        // ancestor/system gestures (like edge-swipe back) still see it normally.
                        return@awaitEachGesture
                    }
                    down.consume()

                    // Tap-and-hold on a stop marker (the center stop or any nearby one) jumps to its
                    // upcoming arrivals -- opt-in via Settings ("Tap and hold a stop"), since it's an
                    // extra gesture layered on top of the tap-to-toggle behavior below. Fires as soon
                    // as the hold threshold is reached rather than waiting for release, matching how
                    // a long press reads everywhere else on Android.
                    if (tapHoldArrivalsEnabled && (hitStop != null || hitCenter)) {
                        val shortTapUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                        if (shortTapUp == null) {
                            if (hitStop != null) {
                                onStopLongPressed(hitStop.stopId, hitStop.stopName ?: "Stop ${hitStop.stopId}")
                            } else {
                                onStopLongPressed(stopId, stopLabel)
                            }
                        } else {
                            shortTapUp.consume()
                            hitStop?.let { onToggleStop(it.stopId) }
                        }
                        return@awaitEachGesture
                    }

                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        up.consume()
                        if (hitToggle) onToggleNearbyVehicles() else hitStop?.let { onToggleStop(it.stopId) }
                    }
                }
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = minOf(size.width, size.height) / 2f * 0.8f
        val nativeCanvas = drawContext.canvas.nativeCanvas

        // Map background: each fetched tile drawn independently at its own screen offset, so the
        // selected stop's projected pixel sits exactly at the canvas center (the same projection
        // markers use below) and a handful of missing tiles just leave that patch blank.
        mapTiles?.tiles?.forEach { tile ->
            val offset = mapTiles.screenOffset(tile.tileX, tile.tileY)
            nativeCanvas.drawBitmap(tile.bitmap, center.x + offset.x, center.y + offset.y, null)
        }

        // Always white fill + black outline, independent of theme -- Voyager's imagery ranges from
        // near-white streets to colored parks/water, so any single fixed color would go illegible
        // over some patch of the map. Same treatment nearbyStopLabelPaint below already used.
        val labelPaint = Paint().apply {
            textSize = 26f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            color = android.graphics.Color.WHITE
        }
        val labelOutlinePaint = Paint(labelPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = android.graphics.Color.BLACK
        }
        val smallLabelPaint = Paint(labelPaint).apply {
            textSize = 22f
            alpha = 180
        }
        val smallLabelOutlinePaint = Paint(labelOutlinePaint).apply {
            textSize = 22f
            strokeWidth = 4f
        }
        // Left-aligned copies for vehicle marker text, which -- like tapped stop labels -- renders
        // to the right of its icon rather than centered under it (see drawBusMarker).
        val vehicleLabelPaint = Paint(labelPaint).apply { textAlign = Paint.Align.LEFT }
        val vehicleLabelOutlinePaint = Paint(labelOutlinePaint).apply { textAlign = Paint.Align.LEFT }
        val vehicleSmallLabelPaint = Paint(smallLabelPaint).apply { textAlign = Paint.Align.LEFT }
        val vehicleSmallLabelOutlinePaint = Paint(smallLabelOutlinePaint).apply { textAlign = Paint.Align.LEFT }
        // Always-white fill + black outline, independent of theme -- legible against both dark and
        // light patches of map, rather than the theme-dependent (and previously too-small) styling.
        // Left-aligned (rather than centered) since expanded stop labels now render to the right of
        // their marker -- see the expandedLabels block below.
        val nearbyStopLabelPaint = Paint().apply {
            textSize = 26f
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
            color = android.graphics.Color.WHITE
        }
        val nearbyStopLabelOutlinePaint = Paint(nearbyStopLabelPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = android.graphics.Color.BLACK
        }

        // Attribution renders as an overlay directly on the map -- a solid (not translucent) black
        // bar flush with the top of the screen, so the text stays legible regardless of what's
        // underneath, same idea as the compass letters already drawing straight onto the map. Text
        // here is always light, independent of the app's light/dark theme, since the bar itself is
        // always solid black.
        val scrimPaint = Paint().apply {
            color = android.graphics.Color.BLACK
        }
        nativeCanvas.drawRect(0f, 0f, size.width, SCRIM_HEIGHT_PX, scrimPaint)
        val overlayAttributionPaint = Paint().apply {
            textSize = 16f
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            alpha = 160
        }
        nativeCanvas.drawText("© OpenStreetMap contributors © CARTO", OVERLAY_INSET_X, ATTRIBUTION_Y, overlayAttributionPaint)

        // Fixed north-up compass letters, each anchored a matching margin from its own screen edge
        // (top/bottom/right/left) rather than a shared circle around center -- accurate since the
        // map background is itself a north-up Mercator projection. N's margin is measured from the
        // bottom of the solid top bar so it never sits under it; S/E/W use that same margin value
        // from their own edge for consistent spacing on all four sides.
        val compassEdgeMarginPx = SCRIM_HEIGHT_PX + COMPASS_SCRIM_MARGIN_PX
        listOf(
            "N" to Offset(center.x, compassEdgeMarginPx),
            "S" to Offset(center.x, size.height - compassEdgeMarginPx),
            "E" to Offset(size.width - compassEdgeMarginPx, center.y),
            "W" to Offset(compassEdgeMarginPx, center.y),
        ).forEach { (letter, point) ->
            nativeCanvas.drawText(letter, point.x, point.y, labelOutlinePaint)
            nativeCanvas.drawText(letter, point.x, point.y, labelPaint)
        }

        // "Track Tapped Stops" toggle -- bottom-left corner, clear of the mid-edge compass letters.
        // Icon shows the on/off state; the label alongside it stays since the icon alone (a thin
        // slider-knob glyph) is too subtle at this size to read unambiguously on its own. Sized to
        // match the on-canvas "No live vehicles..."-style status message rather than the much
        // smaller size this used previously.
        val toggleY = size.height - TOGGLE_BOTTOM_MARGIN_PX
        val toggleOutlinePaint = Paint(nearbyStopLabelOutlinePaint).apply {
            textSize = TOGGLE_TEXT_SIZE_PX
        }
        val togglePaint = Paint(nearbyStopLabelPaint).apply {
            textSize = TOGGLE_TEXT_SIZE_PX
        }
        val toggleIconBitmap = if (nearbyVehiclesEnabled) toggleOnBitmap else toggleOffBitmap
        val toggleTextX = OVERLAY_INSET_X + TOGGLE_ICON_PX + TOGGLE_TEXT_GAP_PX
        nativeCanvas.drawBitmap(toggleIconBitmap, OVERLAY_INSET_X, toggleY - TOGGLE_ICON_PX / 2f - 6f, null)
        nativeCanvas.drawText(TOGGLE_TEXT, toggleTextX, toggleY, toggleOutlinePaint)
        nativeCanvas.drawText(TOGGLE_TEXT, toggleTextX, toggleY, togglePaint)

        // Nearby stops: icon-only by default, drawn as a background layer so bus markers stay
        // legible on top; a tap on a stop toggles its own name label on/off, independent per stop
        // (and, when the toggle above is on, also toggles whether it contributes vehicles).
        // Markers themselves are never grouped/clustered -- only the expanded labels below get
        // basic collision handling, since those are the only thing that can visually stack.
        val stopPoints = nearbyStops.map { stop ->
            val rel = projectRelativeToCenter(centerLat, centerLon, stop.lat, stop.lon, zoom)
            val point = clipToRadius(Offset(center.x + rel.x, center.y + rel.y), center, maxRadius)
            stop to point
        }
        stopPoints.forEach { (_, point) ->
            nativeCanvas.drawBitmap(
                nearbyMarkerBitmap,
                point.x - NEARBY_MARKER_ICON_PX / 2f,
                point.y - NEARBY_MARKER_ICON_PX / 2f,
                null,
            )
        }

        // Anchored to the right of the marker's own base (its bottom edge, where the pin visually
        // touches the map) rather than centered underneath it, so the label text never overlaps the
        // icon itself -- flips to the marker's left instead when the label would otherwise clip off
        // the canvas's right edge (see resolveLabelSide).
        val expandedLabels = stopPoints
            .filter { (stop, _) -> stop.stopId in expandedStopIds }
            .map { (stop, point) ->
                val text = stop.stopName ?: "Stop ${stop.stopId}"
                val width = nearbyStopLabelPaint.measureText(text)
                val side = resolveLabelSide(point.x, NEARBY_MARKER_ICON_PX.toFloat(), width, size.width)
                LabelBox(
                    key = stop.stopId,
                    text = text,
                    anchorX = side.anchorX(point.x, NEARBY_MARKER_ICON_PX.toFloat()),
                    align = side.paintAlign(),
                    initialY = point.y + NEARBY_MARKER_ICON_PX / 2f,
                    width = width,
                )
            }
        resolveLabelPositions(expandedLabels).forEach { (key, y) ->
            val box = expandedLabels.first { it.key == key }
            nearbyStopLabelOutlinePaint.textAlign = box.align
            nearbyStopLabelPaint.textAlign = box.align
            nativeCanvas.drawText(box.text, box.anchorX, y, nearbyStopLabelOutlinePaint)
            nativeCanvas.drawText(box.text, box.anchorX, y, nearbyStopLabelPaint)
        }

        // Center stop pin, name, and street context — always pinned dead center.
        nativeCanvas.drawBitmap(
            centerMarkerBitmap,
            center.x - CENTER_MARKER_ICON_PX / 2f,
            center.y - CENTER_MARKER_ICON_PX / 2f,
            null,
        )
        nativeCanvas.drawText(stopLabel, center.x, center.y + 64f, labelOutlinePaint)
        nativeCanvas.drawText(stopLabel, center.x, center.y + 64f, labelPaint)
        streetContext?.let {
            nativeCanvas.drawText(it, center.x, center.y + 92f, smallLabelOutlinePaint)
            nativeCanvas.drawText(it, center.x, center.y + 92f, smallLabelPaint)
        }

        // Only the PRIMARY stop's arrived buses get the special row above the center pin -- that
        // row visually means "arrived here", which would be misleading for a secondary stop's bus.
        val arrivedAtPrimary = buses.filter { it.isArrived && it.targetStopId == stopId }
        val everythingElse = buses.filterNot { it.isArrived && it.targetStopId == stopId }

        fun vehicleIconBitmapFor(bus: BusMarker): Bitmap = when (bus.vehicleIcon) {
            LightIcons.DIRECTIONS_SUBWAY -> subwayIconBitmap
            LightIcons.DIRECTIONS_TRAIN -> trainIconBitmap
            else -> busIconBitmap
        }

        arrivedAtPrimary.forEachIndexed { index, bus ->
            val xOffset = (index - (arrivedAtPrimary.size - 1) / 2f) * 110f
            val x = center.x + xOffset
            val y = center.y - maxRadius * 0.45f
            drawBusMarker(nativeCanvas, vehicleIconBitmapFor(bus), vehicleLabelPaint, vehicleLabelOutlinePaint, vehicleSmallLabelPaint, vehicleSmallLabelOutlinePaint, bus, x, y, size.width)
        }

        // Every other bus drawn individually at its true projected position — clipped to the
        // visible radius (preserving true direction) only as a fallback for rare far-outliers, with
        // no grouping/de-overlap logic.
        everythingElse.forEach { bus ->
            val rel = projectRelativeToCenter(centerLat, centerLon, bus.lat, bus.lon, zoom)
            val clipped = clipToRadius(Offset(center.x + rel.x, center.y + rel.y), center, maxRadius)
            drawBusMarker(nativeCanvas, vehicleIconBitmapFor(bus), vehicleLabelPaint, vehicleLabelOutlinePaint, vehicleSmallLabelPaint, vehicleSmallLabelOutlinePaint, bus, clipped.x, clipped.y, size.width)
        }
    }
}

/** Hit-test bounds for the "Track Tapped Stops" toggle, matching where it's drawn -- measured
 * against the real label text and current text size rather than a guessed fixed width, so this
 * can't quietly drift out of sync with the toggle's own size again. */
private fun toggleHitRect(canvasHeight: Float): androidx.compose.ui.geometry.Rect {
    val toggleY = canvasHeight - TOGGLE_BOTTOM_MARGIN_PX
    val textWidth = Paint().apply { textSize = TOGGLE_TEXT_SIZE_PX }.measureText(TOGGLE_TEXT)
    val textStartX = OVERLAY_INSET_X + TOGGLE_ICON_PX + TOGGLE_TEXT_GAP_PX
    return androidx.compose.ui.geometry.Rect(
        left = OVERLAY_INSET_X - 12f,
        top = toggleY - TOGGLE_TEXT_SIZE_PX,
        right = textStartX + textWidth + 12f,
        bottom = toggleY + 16f,
    )
}

/** Clamps [point] to within [maxRadius] of [center], preserving true direction — the fallback used
 * for markers whose real projected position falls outside the map's legible radius. */
private fun clipToRadius(point: Offset, center: Offset, maxRadius: Float): Offset {
    val dx = point.x - center.x
    val dy = point.y - center.y
    val dist = sqrt(dx * dx + dy * dy)
    if (dist <= maxRadius || dist == 0f) return point
    val scale = maxRadius / dist
    return Offset(center.x + dx * scale, center.y + dy * scale)
}

/** Which side of its icon a label renders on. */
private enum class LabelSide { RIGHT, LEFT }

/**
 * Right-of-icon is the default anchor for every label on this map (offset from the icon's own
 * right edge by LABEL_GAP_PX) -- but flips to the icon's left when the label would otherwise run
 * past the canvas's right edge (e.g. a vehicle marker near the map's right side). Shared by both
 * tapped-stop labels and vehicle labels so this edge-avoidance logic isn't duplicated (and can't
 * drift out of sync) between the two call sites.
 */
private fun resolveLabelSide(iconCenterX: Float, iconSizePx: Float, textWidth: Float, canvasWidth: Float): LabelSide {
    val rightAnchorX = iconCenterX + iconSizePx / 2f + LABEL_GAP_PX
    val wouldClipRight = rightAnchorX + textWidth > canvasWidth - OVERLAY_INSET_X
    return if (wouldClipRight) LabelSide.LEFT else LabelSide.RIGHT
}

/** The x to pass to [android.graphics.Canvas.drawText], paired with the [Paint.Align] it must be
 * drawn with -- RIGHT-side labels are left-aligned starting at the icon's right edge; LEFT-side
 * (flipped) labels are right-aligned ending at the icon's left edge. */
private fun LabelSide.anchorX(iconCenterX: Float, iconSizePx: Float): Float = when (this) {
    LabelSide.RIGHT -> iconCenterX + iconSizePx / 2f + LABEL_GAP_PX
    LabelSide.LEFT -> iconCenterX - iconSizePx / 2f - LABEL_GAP_PX
}

private fun LabelSide.paintAlign(): Paint.Align = when (this) {
    LabelSide.RIGHT -> Paint.Align.LEFT
    LabelSide.LEFT -> Paint.Align.RIGHT
}

/** A tapped-open stop label's intended (pre-collision) position and measured size. [anchorX]/[align]
 * together fully describe where the text draws -- see [LabelSide.anchorX]/[LabelSide.paintAlign]. */
private data class LabelBox(val key: String, val text: String, val anchorX: Float, val align: Paint.Align, val initialY: Float, val width: Float)

/**
 * Basic collision handling for the (usually few) simultaneously-expanded stop labels: placed
 * top-to-bottom, each label that would overlap an already-placed one is pushed straight down below
 * it. Only ever moves label text, never the markers themselves. Returns each label's key mapped to
 * its resolved baseline Y.
 */
private fun resolveLabelPositions(labels: List<LabelBox>): Map<String, Float> {
    data class PlacedBox(val left: Float, val right: Float, val top: Float, val bottom: Float)

    val result = mutableMapOf<String, Float>()
    val placed = mutableListOf<PlacedBox>()
    for (label in labels.sortedBy { it.initialY }) {
        var y = label.initialY
        val left = if (label.align == Paint.Align.LEFT) label.anchorX else label.anchorX - label.width
        val right = if (label.align == Paint.Align.LEFT) label.anchorX + label.width else label.anchorX
        var moved = true
        while (moved) {
            moved = false
            val top = y - LABEL_LINE_HEIGHT_PX
            val bottom = y
            for (box in placed) {
                val overlapsX = left < box.right && right > box.left
                val overlapsY = top < box.bottom && bottom > box.top
                if (overlapsX && overlapsY) {
                    y = box.bottom + LABEL_LINE_HEIGHT_PX
                    moved = true
                }
            }
        }
        placed += PlacedBox(left, right, y - LABEL_LINE_HEIGHT_PX, y)
        result[label.key] = y
    }
    return result
}

private fun drawBusMarker(
    canvas: android.graphics.Canvas,
    vehicleIconBitmap: Bitmap,
    labelPaint: Paint,
    labelOutlinePaint: Paint,
    smallLabelPaint: Paint,
    smallLabelOutlinePaint: Paint,
    bus: BusMarker,
    x: Float,
    y: Float,
    canvasWidth: Float,
) {
    canvas.drawBitmap(vehicleIconBitmap, x - VEHICLE_MARKER_ICON_PX / 2f, y - VEHICLE_MARKER_ICON_PX / 2f, null)
    // Anchored to the right of the icon's own base, the same offset-from-icon approach used for
    // tapped stop labels (see the expandedLabels block above) -- flips to the icon's left instead
    // when the label would otherwise clip off the canvas's right edge (see resolveLabelSide). All
    // three lines share one side decision (based on the widest of them) so they stay aligned with
    // each other rather than each flipping independently.
    val tripText = bus.tripDescription()
    val etaText = bus.etaDisplay()
    val statusText = bus.statusLabel()
    val widestTextPx = maxOf(
        smallLabelPaint.measureText(tripText),
        labelPaint.measureText(etaText),
        statusText?.let { smallLabelPaint.measureText(it) } ?: 0f,
    )
    val side = resolveLabelSide(x, VEHICLE_MARKER_ICON_PX.toFloat(), widestTextPx, canvasWidth)
    val anchorX = side.anchorX(x, VEHICLE_MARKER_ICON_PX.toFloat())
    val align = side.paintAlign()
    labelPaint.textAlign = align
    labelOutlinePaint.textAlign = align
    smallLabelPaint.textAlign = align
    smallLabelOutlinePaint.textAlign = align
    val baseY = y + VEHICLE_MARKER_ICON_PX / 2f
    canvas.drawText(tripText, anchorX, baseY, smallLabelOutlinePaint)
    canvas.drawText(tripText, anchorX, baseY, smallLabelPaint)
    canvas.drawText(etaText, anchorX, baseY + 24f, labelOutlinePaint)
    canvas.drawText(etaText, anchorX, baseY + 24f, labelPaint)
    statusText?.let {
        canvas.drawText(it, anchorX, baseY + 48f, smallLabelOutlinePaint)
        canvas.drawText(it, anchorX, baseY + 48f, smallLabelPaint)
    }
}
