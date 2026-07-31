package com.thelightphone.transit

import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
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
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
// Matches LightTopBar's own (private) TOPBAR_HEIGHT_UNITS -- the overlaid back button lives at this
// height, so Loading/Error text below it needs the same clearance to avoid sitting behind the icon.
private const val TOPBAR_CLEARANCE_UNITS = 3f

// The LP3's screen is 1080x1240px at 3x density (see the @Preview(widthDp = 1080 / 3, heightDp =
// 1240 / 3) calls elsewhere in the SDK) — used as the "available half-extent" nearby stops are fit
// within (see fitBoundsZoom) before the Canvas itself is laid out. If the real canvas ends up a
// different size than assumed here, markers and the map background still line up exactly with each
// other (they share one projection); the only effect is more or less margin around the edge than
// intended (tiles are fetched with their own margin on top of this for exactly that reason — see
// MapTileClient).
private const val MAP_TARGET_RADIUS_PIXELS = 420f
// Floor: even an isolated stop with no nearby neighbors stays reasonably close-in, never "a tiny dot
// in a huge area". Ceiling: matches what CARTO/standard XYZ tile pyramids reliably serve -- higher
// risks missing tiles in less-mapped areas. Fallback: used only when there's nothing to fit bounds
// to at all (no other stops exist anywhere nearby).
private const val MIN_ZOOM = 14
private const val MAX_ZOOM = 19
private const val FALLBACK_ZOOM = 17
private const val STOP_HIT_RADIUS_PX = 44f
private const val LABEL_LINE_HEIGHT_PX = 22f
private const val LABEL_OFFSET_PX = 24f

// Title/legend render as an overlay bar on top of the map itself (matching how the compass letters
// already render directly on the map), rather than in a separate section above it. Solid, not
// translucent, and flush with the top of the screen -- the compass's "N" label is pushed below it
// (see COMPASS_SCRIM_MARGIN_PX) so the two never overlap.
private const val SCRIM_HEIGHT_PX = 180f
private const val OVERLAY_INSET_X = 28f
private const val TITLE_Y = 60f
private const val LEGEND_Y = 104f
private const val ATTRIBUTION_Y = 134f
private const val COMPASS_SCRIM_MARGIN_PX = 24f
private const val TOGGLE_BOTTOM_MARGIN_PX = 24f

data class BusMarker(
    val tripId: String,
    /** Which stop this vehicle is inbound to -- the primary stop, or (when "Nearby Vehicles" is on)
     * one of the additionally-selected nearby stops. Only the primary stop's arrivals get the
     * special "arrived" row above the center pin; everything else always draws at its true
     * position, since that row visually means "arrived at the center pin" specifically. */
    val targetStopId: String,
    val routeLabel: String,
    val directionLabel: String,
    val lineLabel: String?,
    /** Mode-specific: 🚇 subway/light rail, 🚆 commuter rail, 🚌 bus (also the fallback for any
     * unmapped route_type) -- see LineType.emoji, the single source of truth for this mapping. */
    val emoji: String,
    val etaEpochSeconds: Long,
    /** Null just means "no delay/prediction info yet" — a marker only ever exists for a trip with a
     * live VehiclePosition match, so this is never a stand-in for stale/non-live data. */
    val status: ArrivalStatus?,
    val isArrived: Boolean,
    val lat: Double,
    val lon: Double,
)

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

                loadedContext = LoadedMapContext(stop, streetContext, zoom, mapTiles, nearbyStops)

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
                    routeLabel = arrival.route.displayName,
                    directionLabel = arrival.direction.displayLabel(),
                    lineLabel = lineType?.label,
                    emoji = lineType?.emoji ?: "🚌",
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                // Loading/Error render below the overlaid back button's row (see the LightTopBar
                // further down, drawn on top of this Column) rather than at the very top edge,
                // so the text never sits behind the icon.
                val aboveOverlayPadding = Modifier.padding(top = TOPBAR_CLEARANCE_UNITS.gridUnitsAsDp() + 16.dp, start = 16.dp, end = 16.dp)
                when (val s = state) {
                    is MapState.Loading -> LightText(
                        text = "Loading...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = aboveOverlayPadding,
                    )

                    is MapState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = aboveOverlayPadding,
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
                            onToggleStop = viewModel::toggleStopExpanded,
                            onToggleNearbyVehicles = viewModel::toggleNearbyVehicles,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                }

                // Always white, independent of the app's light/dark theme -- forced into the Dark
                // palette so the icon reads against the canvas's own always-solid-black top scrim
                // (see SCRIM_HEIGHT_PX), the same theme-independent treatment as the rest of that
                // overlay (title, legend, compass letters).
                LightTheme(colors = LightThemeColors.Dark) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }
        }
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
    onToggleStop: (String) -> Unit,
    onToggleNearbyVehicles: () -> Unit,
    modifier: Modifier = Modifier,
) {

    Canvas(
        modifier = modifier
            .pointerInput(nearbyStops, centerLat, centerLon, zoom, nearbyVehiclesEnabled) {
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
                    val toggleBounds = toggleHitRect(size.height.toFloat())
                    val hitToggle = toggleBounds.contains(down.position)

                    if (hitStop == null && !hitToggle) {
                        // Not on anything we handle -- leave the event completely untouched so
                        // ancestor/system gestures (like edge-swipe back) still see it normally.
                        return@awaitEachGesture
                    }
                    down.consume()
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

        val emojiPaint = Paint().apply {
            textSize = 56f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
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
        val nearbyStopEmojiPaint = Paint().apply {
            textSize = 34f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        // Always-white fill + black outline, independent of theme -- legible against both dark and
        // light patches of map, rather than the theme-dependent (and previously too-small) styling.
        val nearbyStopLabelPaint = Paint().apply {
            textSize = 26f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            color = android.graphics.Color.WHITE
        }
        val nearbyStopLabelOutlinePaint = Paint(nearbyStopLabelPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = android.graphics.Color.BLACK
        }

        // Title/legend/attribution render as an overlay directly on the map -- a solid (not
        // translucent) black bar flush with the top of the screen, so the text stays legible
        // regardless of what's underneath, same idea as the compass letters already drawing
        // straight onto the map. Text here is always light, independent of the app's light/dark
        // theme, since the bar itself is always solid black.
        val scrimPaint = Paint().apply {
            color = android.graphics.Color.BLACK
        }
        nativeCanvas.drawRect(0f, 0f, size.width, SCRIM_HEIGHT_PX, scrimPaint)
        val overlayTitlePaint = Paint().apply {
            textSize = 34f
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
            color = android.graphics.Color.WHITE
        }
        val overlayLegendPaint = Paint(overlayTitlePaint).apply {
            textSize = 22f
            alpha = 220
        }
        val overlayAttributionPaint = Paint(overlayTitlePaint).apply {
            textSize = 16f
            alpha = 160
        }
        nativeCanvas.drawText("🗺️ Map", OVERLAY_INSET_X, TITLE_Y, overlayTitlePaint)
        val legendText = "📍 Selected stop · 🚏 Nearby stop · 🚌 Bus · 🚇 Subway/Light Rail · 🚆 Commuter Rail"
        val maxLegendWidthPx = size.width - 2 * OVERLAY_INSET_X
        val legendWidthPx = overlayLegendPaint.measureText(legendText)
        // Five items is a lot to fit on one line at a fixed size -- shrink just enough to fit
        // rather than risk it running off the edge of the bar.
        if (legendWidthPx > maxLegendWidthPx) {
            overlayLegendPaint.textSize *= maxLegendWidthPx / legendWidthPx
        }
        nativeCanvas.drawText(legendText, OVERLAY_INSET_X, LEGEND_Y, overlayLegendPaint)
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

        // Nearby Vehicles toggle -- bottom-left corner, clear of the mid-edge compass letters.
        val toggleText = if (nearbyVehiclesEnabled) "🚌 Nearby Vehicles: On" else "🚌 Nearby Vehicles: Off"
        val toggleY = size.height - TOGGLE_BOTTOM_MARGIN_PX
        val toggleOutlinePaint = Paint(nearbyStopLabelOutlinePaint).apply {
            textSize = 22f
            textAlign = Paint.Align.LEFT
        }
        val togglePaint = Paint(nearbyStopLabelPaint).apply {
            textSize = 22f
            textAlign = Paint.Align.LEFT
        }
        nativeCanvas.drawText(toggleText, OVERLAY_INSET_X, toggleY, toggleOutlinePaint)
        nativeCanvas.drawText(toggleText, OVERLAY_INSET_X, toggleY, togglePaint)

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
        stopPoints.forEach { (_, point) -> nativeCanvas.drawText("🚏", point.x, point.y, nearbyStopEmojiPaint) }

        val expandedLabels = stopPoints
            .filter { (stop, _) -> stop.stopId in expandedStopIds }
            .map { (stop, point) ->
                val text = stop.stopName ?: "Stop ${stop.stopId}"
                LabelBox(
                    key = stop.stopId,
                    text = text,
                    centerX = point.x,
                    initialY = point.y + LABEL_OFFSET_PX,
                    width = nearbyStopLabelPaint.measureText(text),
                )
            }
        resolveLabelPositions(expandedLabels).forEach { (key, y) ->
            val box = expandedLabels.first { it.key == key }
            nativeCanvas.drawText(box.text, box.centerX, y, nearbyStopLabelOutlinePaint)
            nativeCanvas.drawText(box.text, box.centerX, y, nearbyStopLabelPaint)
        }

        // Center stop pin, name, and street context — always pinned dead center.
        nativeCanvas.drawText("📍", center.x, center.y, emojiPaint)
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

        arrivedAtPrimary.forEachIndexed { index, bus ->
            val xOffset = (index - (arrivedAtPrimary.size - 1) / 2f) * 110f
            val x = center.x + xOffset
            val y = center.y - maxRadius * 0.45f
            drawBusMarker(nativeCanvas, emojiPaint, labelPaint, labelOutlinePaint, smallLabelPaint, smallLabelOutlinePaint, bus, x, y)
        }

        // Every other bus drawn individually at its true projected position — clipped to the
        // visible radius (preserving true direction) only as a fallback for rare far-outliers, with
        // no grouping/de-overlap logic.
        everythingElse.forEach { bus ->
            val rel = projectRelativeToCenter(centerLat, centerLon, bus.lat, bus.lon, zoom)
            val clipped = clipToRadius(Offset(center.x + rel.x, center.y + rel.y), center, maxRadius)
            drawBusMarker(nativeCanvas, emojiPaint, labelPaint, labelOutlinePaint, smallLabelPaint, smallLabelOutlinePaint, bus, clipped.x, clipped.y)
        }
    }
}

/** Hit-test bounds for the Nearby Vehicles toggle text, matching where it's drawn. Rough but
 * generous -- a fixed-width box wide enough for either "On"/"Off" state of the label. */
private fun toggleHitRect(canvasHeight: Float): androidx.compose.ui.geometry.Rect {
    val toggleY = canvasHeight - TOGGLE_BOTTOM_MARGIN_PX
    return androidx.compose.ui.geometry.Rect(
        left = OVERLAY_INSET_X - 12f,
        top = toggleY - 32f,
        right = OVERLAY_INSET_X + 320f,
        bottom = toggleY + 12f,
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

/** A tapped-open stop label's intended (pre-collision) position and measured size. */
private data class LabelBox(val key: String, val text: String, val centerX: Float, val initialY: Float, val width: Float)

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
        val left = label.centerX - label.width / 2f
        val right = label.centerX + label.width / 2f
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
    emojiPaint: Paint,
    labelPaint: Paint,
    labelOutlinePaint: Paint,
    smallLabelPaint: Paint,
    smallLabelOutlinePaint: Paint,
    bus: BusMarker,
    x: Float,
    y: Float,
) {
    canvas.drawText(bus.emoji, x, y, emojiPaint)
    val routeText = bus.lineLabel?.let { "$it - ${bus.routeLabel}" } ?: bus.routeLabel
    canvas.drawText(routeText, x, y + 30f, smallLabelOutlinePaint)
    canvas.drawText(routeText, x, y + 30f, smallLabelPaint)
    canvas.drawText(bus.directionLabel, x, y + 54f, smallLabelOutlinePaint)
    canvas.drawText(bus.directionLabel, x, y + 54f, smallLabelPaint)
    canvas.drawText(bus.etaDisplay(), x, y + 78f, labelOutlinePaint)
    canvas.drawText(bus.etaDisplay(), x, y + 78f, labelPaint)
    bus.statusLabel()?.let {
        canvas.drawText(it, x, y + 102f, smallLabelOutlinePaint)
        canvas.drawText(it, x, y + 102f, smallLabelPaint)
    }
}
