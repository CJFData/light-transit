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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
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
import com.thelightphone.transit.gtfs.GtfsRtFeedMessage
import com.thelightphone.transit.gtfs.GtfsRtVehicleStatus
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.MapPreferences
import com.thelightphone.transit.gtfs.TapHoldPreferences
import com.thelightphone.transit.gtfs.MapTiles
import com.thelightphone.transit.gtfs.NominatimGeocoder
import com.thelightphone.transit.gtfs.MapTileClient
import com.thelightphone.transit.gtfs.LiveVehicleSource
import com.thelightphone.transit.gtfs.ScheduledArrival
import com.thelightphone.transit.gtfs.platformLabelFromStopDesc
import com.thelightphone.transit.gtfs.StopLocation
import com.thelightphone.transit.gtfs.computeArrivalEta
import com.thelightphone.transit.gtfs.currentGtfsTimeOfDay
import com.thelightphone.transit.gtfs.fitBoundsZoom
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.transit.gtfs.haversineMeters
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
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
// A vehicle reporting STOPPED_AT (e.g. dwelling at a layover/terminal) with a predicted arrival
// farther out than this is excluded entirely rather than left to the sort+cap above -- otherwise a
// stop with a thin real candidate pool ends up padding its display with static, not-actually-
// arriving-soon vehicles just because a slot was open. Doesn't apply to a vehicle that's actually
// stopped AT the target stop itself (see isArrived in refresh()) or to any vehicle still moving
// (IN_TRANSIT_TO/INCOMING_AT), however far away -- those are still meaningful to show.
private const val DWELLING_FAR_ETA_THRESHOLD_SECONDS = 15 * 60L
// Widens the SQL prefilter behind scheduledArrivalsByStopId backward by this much (see
// GtfsRepository.getScheduledArrivals's graceSeconds param) -- without it, a trip whose scheduled
// departure ticks past while this screen is open drops out of the candidate list permanently, even
// if the live feed shows the vehicle still dwelling right at the stop. The live-position-based
// inclusion/exclusion logic in refresh() (not this window) makes the real call on whether a
// candidate this wide is still actually relevant.
private const val SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS = 10 * 60
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

// LightIcons.DIRECTIONS_ARRIVAL (see ic_directions_arrival_white.xml) is a map-pin shape whose
// actual "point" -- the spot that should sit exactly on the marker's real projected coordinate --
// sits near the BOTTOM of its square bounding box, not at its center. Computed directly from that
// vector drawable's own path data: the pin's tip is the cubic bezier endpoint at (13.7, 27.3)
// within its 27.6x27.6 viewport. Drawing this bitmap centered on a coordinate (as if it had no
// "point" the way a plain icon does) previously placed the tip about half the icon's own height
// below where it should be -- every draw call below offsets by this fraction instead of by half
// the icon size, so the tip (not the bounding box's center) is what lands on the coordinate.
// Vehicle icons (bus/subway/train) are roughly symmetric glyphs with no equivalent point, so their
// own bounding-box-center anchoring is already correct and untouched.
private const val PIN_TIP_FRACTION_X = 13.7f / 27.6f
private const val PIN_TIP_FRACTION_Y = 27.3f / 27.6f

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

// Map-Station mode's scrim is taller than the plain attribution bar (SCRIM_HEIGHT_PX above) since it
// also carries the station's own name -- tap-and-hold on that name (see MapCanvas's scrimTitle
// param) opens the whole station's Upcoming Arrivals, the same way tap-hold on a stop marker does.
private const val STATION_SCRIM_HEIGHT_PX = 76f
private const val STATION_TITLE_TEXT_SIZE_PX = 30f
private const val STATION_TITLE_Y = 44f
private const val STATION_ATTRIBUTION_Y = 68f

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
    /** Which platform/gate/track within a multi-platform station this vehicle is inbound to or
     * arrived at, e.g. "Track 1" -- see [platformLabelFromStopDesc]. Null for a single-platform stop,
     * where there's nothing more specific to name (same "only when actually grouped" rule
     * [StopConnection.platformLabel] and [ArrivalRow.platformLabel] already follow). */
    val platformLabel: String? = null,
    /** Only set for a "See Everything" + "Filter by stop" match (see MapPreferences.
     * filterByStopEnabledFlow) -- TO/FROM/AT relative to whichever tap-selected stop this vehicle's
     * trip was found to visit. Used as the short label's suffix instead of the route alone, e.g.
     * "SL1-TO" -- see [shortLabel]. */
    val stopRelation: StopRelation? = null,
    /** Live current_status text (e.g. "In transit", "Stopped") for a "See Everything" vehicle with
     * no specific stop to measure an ETA against -- shown in the expanded label in place of the
     * usual ETA/status badge (see [drawBusMarker]). Null for an ordinary schedule-matched vehicle,
     * or a "Filter by stop" match, both of which already have a real ETA/status. [etaEpochSeconds]
     * is an unused placeholder on a marker with this set, since it has no target stop to be an ETA
     * "to" at all. */
    val liveStatusText: String? = null,
)

/** A "See Everything" + "Filter by stop" vehicle's relationship to the selected stop it matched --
 * see [BusMarker.stopRelation]. */
enum class StopRelation { TO, AT, FROM }

/** Maps a route's [LineType] to the SDK icon for its vehicle marker -- BUS is also the fallback
 * for a null (unmapped route_type) LineType, same as the emoji fallback this replaced. */
fun LineType?.toVehicleIcon(): LightIconConfiguration = when (this) {
    LineType.SUBWAY -> LightIcons.DIRECTIONS_SUBWAY
    LineType.COMMUTER_RAIL -> LightIcons.DIRECTIONS_TRAIN
    LineType.BUS, null -> LightIcons.DIRECTIONS_BUS
}

/** e.g. "R · Toward Pawtucket-Central Falls Transit Center · Track 1" -- no mode prefix (the icon
 * already shows bus/subway/rail) and no long route name, just enough to tell same-mode routes apart,
 * plus which platform/gate/track when [BusMarker.platformLabel] names one. */
fun BusMarker.tripDescription(): String {
    val base = "$routeLabel · $directionLabel"
    return platformLabel?.let { "$base · $it" } ?: base
}

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

/** "See Everything" map mode's compact default label -- just the route, or route-TO/FROM/AT when
 * also narrowed down by "Filter by stop" (e.g. "SL1-TO"). Expanded to the full
 * [tripDescription]/[etaDisplay]/[statusLabel] stack on tap -- see [drawBusMarker] and MapCanvas's
 * own vehicle-tap handling. */
fun BusMarker.shortLabel(): String = stopRelation?.let { "$routeLabel-${it.name}" } ?: routeLabel

/** V3/GTFS-RT's current_status text, human-readable, for a "See Everything" vehicle's expanded
 * label in place of an ETA it has no target stop to measure against -- see
 * [BusMarker.liveStatusText]. */
fun currentStatusText(status: Int?): String? = when (status) {
    GtfsRtVehicleStatus.INCOMING_AT -> "Approaching"
    GtfsRtVehicleStatus.STOPPED_AT -> "Stopped"
    GtfsRtVehicleStatus.IN_TRANSIT_TO -> "In transit"
    else -> null
}

data class NearbyStopMarker(
    val stopId: String,
    val stopName: String?,
    val lat: Double,
    val lon: Double,
    /** See [StopLocation.isStation]. Gates whether double-tapping this marker opens its Station
     * sub-map (see MapPreferences.doubleTapStationEnabledFlow). */
    val isStation: Boolean = false,
    /** Every platform stop_id this marker's station represents -- see [StopLocation.memberStopIds].
     * Only meaningful when [isStation] is true; a plain stop is just its own id. */
    val memberStopIds: List<String> = emptyList(),
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
        /** Settings screen toggle (off by default) -- see MapPreferences.doubleTapStationEnabledFlow. */
        val doubleTapStationEnabled: Boolean,
        /** Non-null only when the centered/selected stop is itself a qualifying multi-platform
         * station (see [StopLocation.isStation]) -- gates double-tap-to-open-Station for the center
         * pin the same way [NearbyStopMarker.isStation] does for a nearby one. */
        val centerStation: StopLocation?,
        /** Settings screen toggle (off by default) -- see MapPreferences.seeEverythingEnabledFlow. */
        val seeEverythingEnabled: Boolean,
        /** Settings screen toggle (on by default) -- see TapHoldPreferences.tapHoldVehicleEnabledFlow. */
        val tapHoldVehicleEnabled: Boolean,
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
    val doubleTapStationEnabled: Boolean,
    val centerStation: StopLocation?,
    val seeEverythingEnabled: Boolean,
    val filterByStopEnabled: Boolean,
    val seeEverythingShowBus: Boolean,
    val seeEverythingShowSubway: Boolean,
    val seeEverythingShowCommuterRail: Boolean,
    val tapHoldVehicleEnabled: Boolean,
)

class MapViewModel(
    dbFile: File,
    private val agency: GtfsAgency,
    private val stopId: String,
    private val mapPreferences: MapPreferences,
    private val tapHoldPreferences: TapHoldPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val geocoder = NominatimGeocoder()
    private val tileClient = MapTileClient()

    private val _state = MutableStateFlow<MapState>(MapState.Loading)
    val state: StateFlow<MapState> = _state

    val nearbyVehiclesEnabled = MutableStateFlow(false)
    /** Nearby stops the user has tapped open -- always reveals that stop's name label; when
     * [nearbyVehiclesEnabled] is also on, an expanded stop additionally contributes its own inbound
     * vehicles to the map. Also doubles as "See Everything" + "Filter by stop"'s own stop selection
     * (see MapPreferences.filterByStopEnabledFlow) -- the same tap-a-stop gesture, reused rather
     * than a second, separate selection mechanism. */
    val expandedStopIds = MutableStateFlow<Set<String>>(emptySet())

    /** "See Everything" mode's own tap-to-expand state for vehicle markers (mirrors
     * [expandedStopIds]'s role for stops) -- a tripId in here shows that vehicle's full
     * [tripDescription]/[etaDisplay]/[statusLabel] label instead of [BusMarker.shortLabel]. */
    val expandedVehicleTripIds = MutableStateFlow<Set<String>>(emptySet())

    private var pollJob: Job? = null
    private var loadedContext: LoadedMapContext? = null
    /** Lazily populated as stops become active -- the primary stop's schedule is seeded up front,
     * a nearby stop's is fetched the first time it's ever toggled on. */
    private val scheduledArrivalsByStopId = mutableMapOf<String, List<ScheduledArrival>>()

    /** Wakes the single poll loop below early (see [refreshNow]) instead of spawning a second,
     * concurrent [refresh] call -- CONFLATED so a burst of toggles between poll ticks only wakes it
     * once, and so it's never possible for two refreshes to race and have a stale one clobber a
     * fresher one's state write. */
    private val refreshTrigger = Channel<Unit>(Channel.CONFLATED)

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
                val doubleTapStationEnabled = mapPreferences.doubleTapStationEnabledFlow.first()
                // "Track Tapped Stops" -- now controlled from the Settings screen (see
                // MapPreferences.trackTappedStopsEnabledFlow) rather than a toggle drawn on this
                // canvas, so it's just another one-shot Settings read like the three above it.
                nearbyVehiclesEnabled.value = mapPreferences.trackTappedStopsEnabledFlow.first()
                val seeEverythingEnabled = mapPreferences.seeEverythingEnabledFlow.first()
                val filterByStopEnabled = mapPreferences.filterByStopEnabledFlow.first()
                val seeEverythingShowBus = mapPreferences.seeEverythingShowBusFlow.first()
                val seeEverythingShowSubway = mapPreferences.seeEverythingShowSubwayFlow.first()
                val seeEverythingShowCommuterRail = mapPreferences.seeEverythingShowCommuterRailFlow.first()
                val tapHoldVehicleEnabled = tapHoldPreferences.tapHoldVehicleEnabledFlow.first()
                // Reuses the exact same station-detection query every other screen does (search
                // results, trip detail) -- non-null only if the selected stop is itself a real,
                // qualifying multi-platform station.
                val centerStation = repository.getStationContaining(stopId)
                // When the selected stop is itself a station, [stopId] is only ONE of its member
                // platforms (whichever one the caller happened to pass in) -- every platform has its
                // own distinct stop_id and its own scheduled trips (e.g. South Station's Commuter
                // Rail, Silver Line, and Red Line platforms are all different stop_ids), so vehicles
                // for the OTHER platforms/modes would never be considered at all if only [stopId]'s
                // own schedule were snapshotted here. Same union-across-member-platforms approach
                // already used by Upcoming Arrivals and Map-Station mode.
                val primaryStopIds = centerStation?.memberStopIds ?: listOf(stopId)
                // Snapshot once: which trips are scheduled for this stop from now on (plus a grace
                // window backward -- see SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS). Live polling
                // below only checks these same trips for a live position match — a trip that wasn't
                // scheduled at screen-open time won't appear mid-session.
                for (id in primaryStopIds) {
                    scheduledArrivalsByStopId[id] = repository.getScheduledArrivals(
                        id, currentGtfsTimeOfDay(), todayForGtfs(), SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS,
                    )
                }
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
                ).map { nearby ->
                    NearbyStopMarker(nearby.stopId, nearby.stopName, nearby.lat, nearby.lon, nearby.isStation, nearby.memberStopIds)
                }

                loadedContext = LoadedMapContext(
                    stop, streetContext, zoom, mapTiles, nearbyStops,
                    tapHoldArrivalsEnabled, darkMode, doubleTapStationEnabled, centerStation,
                    seeEverythingEnabled, filterByStopEnabled,
                    seeEverythingShowBus, seeEverythingShowSubway, seeEverythingShowCommuterRail,
                    tapHoldVehicleEnabled,
                )

                while (isActive) {
                    refresh()
                    // Waits out the full interval unless refreshTrigger fires first (see
                    // [refreshNow]) -- either way, this is the only place refresh() is ever called
                    // from, so there's exactly one live-position fetch in flight at a time.
                    withTimeoutOrNull(LIVE_VEHICLE_POLL_INTERVAL_MS) { refreshTrigger.receive() }
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
        if (nearbyVehiclesEnabled.value || loadedContext?.filterByStopEnabled == true) refreshNow()
    }

    /** "See Everything" mode's own tap-to-expand for a vehicle marker -- purely a display toggle
     * (unlike [toggleStopExpanded], nothing to refetch), so no [refreshNow] call needed. */
    fun toggleVehicleExpanded(tripId: String) {
        expandedVehicleTripIds.value = expandedVehicleTripIds.value.let { if (tripId in it) it - tripId else it + tripId }
    }

    /** Wakes the single poll loop in [onScreenShow] early rather than running its own separate
     * [refresh] -- see [refreshTrigger]. */
    private fun refreshNow() {
        refreshTrigger.trySend(Unit)
    }

    private suspend fun refresh() {
        val context = loadedContext ?: return
        val today = todayForGtfs()
        val nowEpochSeconds = System.currentTimeMillis() / 1000

        // Active stops: always the primary one -- every member platform when it's a station (see
        // primaryStopIds in onScreenShow, same reasoning), not just the single id the screen was
        // opened with -- plus any expanded nearby stops, but only while the toggle is on. Filtered
        // against the known nearby-stop set as a safety net against stale ids.
        val activeStopIds = buildSet {
            addAll(context.centerStation?.memberStopIds ?: listOf(stopId))
            if (nearbyVehiclesEnabled.value) {
                val nearbyIds = context.nearbyStops.mapTo(mutableSetOf()) { it.stopId }
                addAll(expandedStopIds.value.filter { it in nearbyIds })
            }
        }
        for (id in activeStopIds) {
            if (id !in scheduledArrivalsByStopId) {
                scheduledArrivalsByStopId[id] = repository.getScheduledArrivals(
                    id, currentGtfsTimeOfDay(), today, SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS,
                )
            }
        }

        if (agency.realtimeVehiclePositionsUrl == null) {
            _state.value = MapState.Loaded(
                context.streetContext, context.stop.lat, context.stop.lon, context.zoom, context.mapTiles,
                emptyList(), context.nearbyStops, LiveFeedStatus.NOT_SUPPORTED,
                context.tapHoldArrivalsEnabled, context.darkMapEnabled, context.doubleTapStationEnabled, context.centerStation,
                context.seeEverythingEnabled, context.tapHoldVehicleEnabled,
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
                context.tapHoldArrivalsEnabled, context.darkMapEnabled, context.doubleTapStationEnabled, context.centerStation,
                context.seeEverythingEnabled, context.tapHoldVehicleEnabled,
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
        // Real coordinates for each active stop, for the distance-tiebreaker sort below -- every
        // primary/member platform shares the center stop's own single lat/lon (StopLocation doesn't
        // carry per-platform coordinates for a grouped station), each nearby stop uses its own.
        val stopLocationsById = buildMap {
            (context.centerStation?.memberStopIds ?: listOf(stopId)).forEach { put(it, context.stop.lat to context.stop.lon) }
            context.nearbyStops.forEach { put(it.stopId, it.lat to it.lon) }
        }

        // Only the primary station's own member platforms ever get a platform label -- same "only
        // when actually grouped" rule getScheduledArrivals/getNextConnections already follow, so a
        // plain single-platform stop's vehicle never shows a misleading label pulled from unrelated
        // stop_desc content. Fetched once per refresh rather than per-candidate.
        val groupedStopIds = context.centerStation?.memberStopIds?.takeIf { it.size > 1 }?.toSet() ?: emptySet()
        val platformLabelByStopId = if (groupedStopIds.isEmpty()) {
            emptyMap()
        } else {
            repository.getStopDescriptions(groupedStopIds.toList()).mapValues { (_, desc) -> platformLabelFromStopDesc(desc) }
        }

        // Commuter rail's track assignment isn't in GTFS-RT at all, and isn't fixed like subway/
        // Silver Line platforms -- MBTA typically doesn't decide (or publish) it until roughly
        // 10-15 minutes before departure (see MbtaV3VehicleSource's own doc), so most of the time
        // there's simply no assignment yet. That's the normal case, not an error: a trip missing
        // from this map (fetch failure, or genuinely no live V3 report yet) just falls back to its
        // ordinary GTFS-RT match below, same as every other mode -- never blocked on this lookup
        // succeeding. Scoped by ROUTE (every commuter rail route already known to serve the primary
        // station, from whatever's in scheduledArrivalsByStopId so far) rather than by a fixed trip
        // list -- a station's routes are stable even when its exact trip snapshot isn't, and
        // filtering by route is also the only option V3 actually supports here (see
        // LiveVehicleSource's own doc).
        val primaryStopIds = context.centerStation?.memberStopIds ?: listOf(stopId)
        val commuterRailRouteIds = primaryStopIds.flatMapTo(mutableSetOf()) { id ->
            scheduledArrivalsByStopId[id].orEmpty()
                .filter { LineType.forGtfsRouteType(it.route.routeType) == LineType.COMMUTER_RAIL }
                .map { it.route.routeId }
        }
        val mbtaV3VehiclesByTripId = agency.component<LiveVehicleSource>()
            ?.takeIf { commuterRailRouteIds.isNotEmpty() }
            ?.let { source ->
                try {
                    source.vehiclesByRoute(commuterRailRouteIds)
                } catch (e: Exception) {
                    Log.e("MapScreen", "Live vehicle fetch failed", e)
                    emptyMap()
                }
            } ?: emptyMap()

        // A trip only ever becomes a candidate if it's in scheduledArrivalsByStopId, which is a
        // SNAPSHOT taken once per stop (see the activeStopIds loop above) -- a trip added since, or
        // one whose only scheduled time fell outside SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS's
        // window, would otherwise never show up even though it's genuinely live and heading to/at
        // this station right now. This backfills exactly that gap for the PRIMARY station's own
        // stops (not nearby-expanded ones, which keep their existing snapshot-only behavior): any
        // trip GTFS-RT or V3 already reports as live but that isn't in the snapshot yet gets looked
        // up directly by trip_id and merged into the very same cache candidatesFor reads from below,
        // so no other code path needs to know the difference.
        val knownPrimaryTripIds = primaryStopIds.flatMapTo(mutableSetOf()) { id ->
            scheduledArrivalsByStopId[id].orEmpty().map { it.tripId }
        }
        val missingLiveTripIds = (vehiclePositionsFeed.vehiclePositionsByTripId.keys + mbtaV3VehiclesByTripId.keys) - knownPrimaryTripIds
        if (missingLiveTripIds.isNotEmpty()) {
            repository.getScheduledArrivalsForTrips(missingLiveTripIds, primaryStopIds).forEach { arrival ->
                val existing = scheduledArrivalsByStopId[arrival.stopId].orEmpty()
                if (existing.none { it.tripId == arrival.tripId }) {
                    scheduledArrivalsByStopId[arrival.stopId] = existing + arrival
                }
            }
        }

        fun candidatesFor(activeStopId: String): List<BusMarker> {
            val scheduledArrivals = scheduledArrivalsByStopId[activeStopId] ?: return emptyList()
            return scheduledArrivals.mapNotNull { arrival ->
                val lineType = LineType.forGtfsRouteType(arrival.route.routeType)
                // Commuter rail prefers MBTA's V3 API for position AND status/sequence together --
                // never mixing sources for one vehicle, so "is it arrived" can't disagree with
                // itself across two feeds with different update cadences (see MbtaV3VehicleSource's
                // own doc). Falls back to the ordinary GTFS-RT match below when V3 has nothing for
                // this trip. Every other mode always uses GTFS-RT, unchanged.
                val v3Vehicle = if (lineType == LineType.COMMUTER_RAIL) mbtaV3VehiclesByTripId[arrival.tripId] else null

                val lat: Double
                val lon: Double
                val currentStatus: Int?
                val currentSeq: Int?
                if (v3Vehicle != null) {
                    lat = v3Vehicle.latitude
                    lon = v3Vehicle.longitude
                    currentStatus = v3Vehicle.currentStatus
                    currentSeq = v3Vehicle.currentStopSequence
                } else {
                    val vehicle = vehiclePositionsFeed.vehiclePositionsByTripId[arrival.tripId] ?: return@mapNotNull null
                    val position = vehicle.position ?: return@mapNotNull null
                    lat = position.latitude.toDouble()
                    lon = position.longitude.toDouble()
                    currentStatus = vehicle.currentStatus
                    currentSeq = vehicle.currentStopSequence
                }

                // No real stop_id is ever present on live vehicle data (see GtfsRtVehiclePosition's
                // doc) -- current_stop_sequence matching the target stop is the only signal
                // available. Takes priority over both departed checks below -- a vehicle can report
                // STOPPED_AT here even after its predicted departure time has technically ticked
                // past (real-world dwell time is naturally a bit fuzzy).
                val isArrived = currentStatus == GtfsRtVehicleStatus.STOPPED_AT && currentSeq == arrival.stopSequence

                val rtStopUpdate = tripUpdatesFeed?.tripUpdatesByTripId?.get(arrival.tripId)
                    ?.updateFor(activeStopId, arrival.stopSequence)
                val eta = computeArrivalEta(arrival.departureTime, today, rtStopUpdate) ?: return@mapNotNull null

                // Departed: either its own GPS-based progress has moved past this stop, or (a
                // fallback for when current_stop_sequence is stale/missing) its predicted/actual
                // departure time here has already passed. Either signal alone means it's no longer
                // relevant to someone waiting here -- unless it's still literally AT this stop
                // (isArrived above), which overrides both, since dwelling a little past a predicted
                // departure time doesn't mean it's actually gone.
                val hasDeparted = !isArrived && (
                    (currentSeq != null && currentSeq > arrival.stopSequence) || eta.etaEpochSeconds < nowEpochSeconds
                )
                if (hasDeparted) return@mapNotNull null

                // Dwelling somewhere other than this stop (e.g. a layover/terminal) with a distant
                // predicted arrival isn't meaningfully "coming soon" -- excluded so a stop with a
                // thin real candidate pool doesn't get padded with static vehicles just because a
                // display slot was open (see DWELLING_FAR_ETA_THRESHOLD_SECONDS). A vehicle that's
                // actually moving, however far away, is untouched by this check -- and one already
                // confirmed AT this stop above never reaches here regardless of its predicted time.
                val isDwellingFar = currentStatus == GtfsRtVehicleStatus.STOPPED_AT && !isArrived &&
                    eta.etaEpochSeconds - nowEpochSeconds > DWELLING_FAR_ETA_THRESHOLD_SECONDS
                if (isDwellingFar) return@mapNotNull null

                // Commuter rail only ever trusts a CONFIRMED V3 assignment for its platform label
                // -- its own scheduled stop_id is often a station's generic per-route placeholder
                // (e.g. South Station's "NEC-2287"), whose stop_desc would otherwise resolve to a
                // misleading label like "Commuter Rail" via platformLabelByStopId, not a real track
                // name. Subway/Silver Line's scheduled stop_id is always already the real, distinct
                // platform, so they keep using it exactly as before. Either way, with no resolved
                // platform this vehicle still renders fine -- just at its own true GPS position with
                // no platform label, the same catch-all every other unresolved vehicle already falls
                // back to.
                val assignedStopId = v3Vehicle?.assignedStopId
                val platformLabel = if (lineType == LineType.COMMUTER_RAIL) {
                    assignedStopId?.let { platformLabelByStopId[it] }
                } else {
                    platformLabelByStopId[activeStopId]
                }
                BusMarker(
                    tripId = arrival.tripId,
                    targetStopId = assignedStopId ?: activeStopId,
                    routeLabel = arrival.route.shortName?.takeIf { it.isNotBlank() } ?: arrival.route.displayName,
                    directionLabel = arrival.direction.displayLabel(),
                    vehicleIcon = lineType.toVehicleIcon(),
                    etaEpochSeconds = eta.etaEpochSeconds,
                    status = eta.status,
                    isArrived = isArrived,
                    lat = lat,
                    lon = lon,
                    platformLabel = platformLabel,
                )
            }
        }

        // Soonest predicted time first, rounded to the nearest minute so genuinely-close
        // predictions (or ones sharing a coarse schedule-only timestamp, i.e. stale/missing live
        // data) tie and fall through to the distance tiebreaker instead of an arbitrary few-second
        // gap always winning outright.
        val busComparator = compareBy<BusMarker> { Math.round(it.etaEpochSeconds / 60.0) }
            .thenBy { bus ->
                val (stopLat, stopLon) = stopLocationsById[bus.targetStopId] ?: return@thenBy Double.MAX_VALUE
                haversineMeters(stopLat, stopLon, bus.lat, bus.lon)
            }

        // The primary stop -- its own single id, or every platform of a station when centered on
        // one (see primaryStopIds) -- shares ONE display allotment across all its platforms, since
        // they're conceptually the same place; a big hub with a dozen platforms otherwise ends up
        // with a dozen platforms' worth of markers just from being centered there. Each separately
        // expanded NEARBY stop still gets its own independent allotment -- that's the existing,
        // deliberate "select more stops to show more buses" behavior MAX_DISPLAYED_BUSES_PER_STOP's
        // own doc comment describes for Nearby Vehicles.
        val primaryBuses = activeStopIds.filter { it in primaryStopIds }
            .flatMap { candidatesFor(it) }
            .sortedWith(busComparator)
            .take(MAX_DISPLAYED_BUSES_PER_STOP)
        val nearbyBuses = activeStopIds.filter { it !in primaryStopIds }
            .flatMap { activeStopId -> candidatesFor(activeStopId).sortedWith(busComparator).take(MAX_DISPLAYED_BUSES_PER_STOP) }
        val buses = primaryBuses + nearbyBuses
        // The same live vehicle could be inbound to two selected stops close together on one route
        // -- keep only its earliest-ETA entry rather than showing it twice.
        val dedupedBuses = buses.groupBy { it.tripId }.values.map { group -> group.minBy { it.etaEpochSeconds } }

        // "See Everything" drops the schedule-anchored pipeline above entirely (its whole point is
        // showing vehicles regardless of whether they're relevant to any stop this screen cares
        // about) and replaces it wholesale -- see buildSeeEverythingBuses's own doc.
        val displayedBuses = if (context.seeEverythingEnabled) {
            buildSeeEverythingBuses(
                repository, context.stop.lat, context.stop.lon, context.zoom,
                vehiclePositionsFeed, tripUpdatesFeed, today, nowEpochSeconds,
                expandedStopIds.value.toList(), context.filterByStopEnabled, stopId,
                context.seeEverythingShowBus, context.seeEverythingShowSubway, context.seeEverythingShowCommuterRail,
            )
        } else {
            dedupedBuses
        }

        _state.value = MapState.Loaded(
            context.streetContext, context.stop.lat, context.stop.lon, context.zoom, context.mapTiles,
            displayedBuses, context.nearbyStops, LiveFeedStatus.OK,
            context.tapHoldArrivalsEnabled, context.darkMapEnabled, context.doubleTapStationEnabled, context.centerStation,
            context.seeEverythingEnabled, context.tapHoldVehicleEnabled,
        )
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
        geocoder.close()
        tileClient.close()
    }
}

/**
 * "See Everything" map mode (Settings toggle, off by default) -- every live vehicle whose
 * position falls within the map's own rendered radius, regardless of route or whether it's
 * actually scheduled to serve any stop this screen cares about. A materially different pipeline
 * from the rest of [MapViewModel.refresh]: it starts from every reported vehicle position and
 * asks "is this in view", rather than starting from a specific stop's schedule and asking "is a
 * live vehicle matching one of these trips". Deliberately GTFS-RT-only, even for commuter rail --
 * MbtaV3VehicleSource's whole value (platform assignment) is scoped to a specific station's own
 * platforms, which doesn't fit a mode that shows vehicles regardless of relevance to any
 * particular station; commuter rail vehicles here still show up fine via ordinary GTFS-RT.
 *
 * With "Filter by stop" also on and at least one stop selected in [selectedStopIds] (the same
 * tap-a-stop selection Track Tapped Stops reuses), narrows down to just vehicles whose own trip
 * visits one of those stops, tagged TO/FROM/AT it (reusing the exact same current_status/
 * current_stop_sequence comparison the schedule-anchored pipeline already does, just for a label
 * instead of an inclusion filter) -- with a real ETA against that specific stop. With the toggle
 * on but nothing selected yet, there's nothing to filter BY, so this falls through to the
 * unfiltered list, same as if it were off.
 *
 * Top-level (not a MapViewModel member) so both the main Map screen and Map-Station mode's own
 * ViewModel can share one implementation.
 */
internal fun buildSeeEverythingBuses(
    repository: GtfsRepository,
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    vehiclePositionsFeed: GtfsRtFeedMessage,
    tripUpdatesFeed: GtfsRtFeedMessage?,
    today: LocalDate,
    nowEpochSeconds: Long,
    selectedStopIds: List<String>,
    filterByStopEnabled: Boolean,
    /** Used only as [BusMarker.targetStopId]'s inert placeholder for an unfiltered candidate,
     * which never actually consults it (see that param's own doc). */
    inertPlaceholderStopId: String,
    /** Settings screen's per-mode "Modes shown" toggles (all on by default) -- a vehicle whose
     * resolved [LineType] has its toggle off is excluded entirely, same as if it were never in
     * bounds at all. */
    showBus: Boolean = true,
    showSubway: Boolean = true,
    showCommuterRail: Boolean = true,
): List<BusMarker> {
    val fetchRadiusMeters = MAP_TARGET_RADIUS_PIXELS * metersPerPixel(centerLat, zoom)
    val inBounds = vehiclePositionsFeed.vehiclePositionsByTripId.filterValues { vehicle ->
        val position = vehicle.position ?: return@filterValues false
        haversineMeters(centerLat, centerLon, position.latitude.toDouble(), position.longitude.toDouble()) <= fetchRadiusMeters
    }
    if (inBounds.isEmpty()) return emptyList()

    val routesByTripId = repository.getRoutesForTrips(inBounds.keys)
    val filteringByStop = filterByStopEnabled && selectedStopIds.isNotEmpty()
    val stopTimesByTripId = if (filteringByStop) {
        repository.getScheduledArrivalsForTrips(inBounds.keys, selectedStopIds).groupBy { it.tripId }
    } else {
        emptyMap()
    }

    return inBounds.mapNotNull { (tripId, vehicle) ->
        val position = vehicle.position ?: return@mapNotNull null
        val routeInfo = routesByTripId[tripId] ?: return@mapNotNull null
        val lineType = LineType.forGtfsRouteType(routeInfo.route.routeType)
        val modeShown = when (lineType) {
            LineType.BUS, null -> showBus
            LineType.SUBWAY -> showSubway
            LineType.COMMUTER_RAIL -> showCommuterRail
        }
        if (!modeShown) return@mapNotNull null
        val routeLabel = routeInfo.route.shortName?.takeIf { it.isNotBlank() } ?: routeInfo.route.displayName
        val lat = position.latitude.toDouble()
        val lon = position.longitude.toDouble()

        if (filteringByStop) {
            // A candidate whose trip doesn't visit any tap-selected stop at all is excluded
            // entirely -- "Filter by stop" narrows the whole visible set, not just the label.
            val stopTime = stopTimesByTripId[tripId]?.firstOrNull() ?: return@mapNotNull null
            val currentSeq = vehicle.currentStopSequence
            val relation = when {
                vehicle.currentStatus == GtfsRtVehicleStatus.STOPPED_AT && currentSeq == stopTime.stopSequence -> StopRelation.AT
                currentSeq != null && currentSeq > stopTime.stopSequence -> StopRelation.FROM
                else -> StopRelation.TO
            }
            val rtStopUpdate = tripUpdatesFeed?.tripUpdatesByTripId?.get(tripId)?.updateFor(stopTime.stopId, stopTime.stopSequence)
            val eta = computeArrivalEta(stopTime.departureTime, today, rtStopUpdate)
            BusMarker(
                tripId = tripId,
                targetStopId = stopTime.stopId,
                routeLabel = routeLabel,
                directionLabel = routeInfo.direction.displayLabel(),
                vehicleIcon = lineType.toVehicleIcon(),
                etaEpochSeconds = eta?.etaEpochSeconds ?: nowEpochSeconds,
                status = eta?.status,
                isArrived = relation == StopRelation.AT,
                lat = lat,
                lon = lon,
                platformLabel = stopTime.platformLabel,
                stopRelation = relation,
            )
        } else {
            BusMarker(
                tripId = tripId,
                // Inert placeholder -- isArrived is always false here (no specific stop to snap
                // to), so this is never actually consulted for a render position.
                targetStopId = inertPlaceholderStopId,
                routeLabel = routeLabel,
                directionLabel = routeInfo.direction.displayLabel(),
                vehicleIcon = lineType.toVehicleIcon(),
                etaEpochSeconds = nowEpochSeconds,
                status = null,
                isArrived = false,
                lat = lat,
                lon = lon,
                liveStatusText = currentStatusText(vehicle.currentStatus) ?: "Live",
            )
        }
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
        MapViewModel(dbFile, agency, stopId, MapPreferences(lightContext.dataStore), TapHoldPreferences(lightContext.dataStore))

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val nearbyVehiclesEnabled by viewModel.nearbyVehiclesEnabled.collectAsState()
        val expandedStopIds by viewModel.expandedStopIds.collectAsState()
        val expandedVehicleTripIds by viewModel.expandedVehicleTripIds.collectAsState()
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
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
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
                            onStopLongPressed = { longPressStopId, longPressStopLabel ->
                                navigateTo(screenFactory = { activity ->
                                    UpcomingArrivalsScreen(activity, dbFile, agency, listOf(longPressStopId), longPressStopLabel)
                                })
                            },
                            doubleTapStationEnabled = s.doubleTapStationEnabled,
                            centerIsStation = s.centerStation != null,
                            centerStationMemberIds = s.centerStation?.memberStopIds ?: emptyList(),
                            onOpenStation = { memberStopIds, stationName ->
                                navigateTo(screenFactory = { activity ->
                                    MapStationScreen(activity, dbFile, agency, memberStopIds, stationName)
                                })
                            },
                            seeEverythingEnabled = s.seeEverythingEnabled,
                            expandedVehicleTripIds = expandedVehicleTripIds,
                            onToggleVehicle = viewModel::toggleVehicleExpanded,
                            tapHoldVehicleEnabled = s.tapHoldVehicleEnabled,
                            // fromStopSequence 0 -- a vehicle tapped on the map has no specific
                            // "anchor stop" the way an arrival row does, so this just opens the trip
                            // from its very start, same as any other "open this trip fresh" entry
                            // point with nothing more specific to anchor to.
                            onVehicleLongPressed = { bus ->
                                navigateTo(screenFactory = { activity ->
                                    TripDetailScreen(activity, dbFile, bus.tripId, 0, bus.routeLabel, bus.directionLabel)
                                })
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                }
                BackToHomeFooter(onGoBackOnce = { goBack() })
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
 * actually lands on a real marker.
 */
@Composable
internal fun MapCanvas(
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
    /** Fires for a long press on a stop marker (the center stop or any nearby one) when
     * [tapHoldArrivalsEnabled] is on -- see the Settings screen's "Tap and hold a stop" toggle. */
    onStopLongPressed: (stopId: String, stopLabel: String) -> Unit,
    /** Settings screen's "Double-tap to open a station" toggle -- gates whether double-tapping a
     * station marker (center or nearby) opens Map-Station mode at all. */
    doubleTapStationEnabled: Boolean = false,
    /** Whether the CENTER stop itself qualifies as a station -- see [StopLocation.isStation]. Nearby
     * stations are already carried on each [NearbyStopMarker] directly. */
    centerIsStation: Boolean = false,
    centerStationMemberIds: List<String> = emptyList(),
    /** Fires when a station marker (center or nearby) is double-tapped with [doubleTapStationEnabled]
     * on -- the caller navigates to Map-Station mode with the tapped station's platform ids/name. */
    onOpenStation: (memberStopIds: List<String>, stationName: String) -> Unit = { _, _ -> },
    /** False only for Map-Station mode itself, which has no single "center" stop -- every platform
     * renders as an equal nearby-style pin instead (see MapStationScreen). */
    showCenterPin: Boolean = true,
    /** Non-null only in Map-Station mode: the station's own name, drawn in a taller scrim bar in
     * place of the plain attribution-only bar, making it visually clear you're zoomed into a
     * station rather than the main map. */
    scrimTitle: String? = null,
    /** Fires on a long press of [scrimTitle] when [tapHoldArrivalsEnabled] is on -- opens Upcoming
     * Arrivals for the WHOLE station (all platforms), distinct from long-pressing a single platform
     * pin (see [onStopLongPressed]). */
    onScrimTitleLongPressed: (() -> Unit)? = null,
    /** Fires on a double-tap of [scrimTitle] when [doubleTapStationEnabled] is on -- the Map-Station
     * mode side of the same "Double-tap to open a station" gesture that zooms IN from a station
     * marker on the main Map screen (see [onOpenStation]): this is the zoom OUT back to the main
     * map, symmetric with it. */
    onScrimTitleDoubleTapped: (() -> Unit)? = null,
    /** Settings screen's "See Everything" toggle -- see MapPreferences.seeEverythingEnabledFlow.
     * Only changes how [buses] are LABELED here (short by default, full on tap) -- which vehicles
     * are even in [buses] to begin with is entirely the caller's own decision (see MapViewModel's
     * buildSeeEverythingBuses). False in Map-Station mode, which doesn't support this yet. */
    seeEverythingEnabled: Boolean = false,
    expandedVehicleTripIds: Set<String> = emptySet(),
    /** Fires on a plain tap of a vehicle marker while [seeEverythingEnabled] is on -- toggles that
     * vehicle between [BusMarker.shortLabel] and its full details label. No-op otherwise. */
    onToggleVehicle: (String) -> Unit = {},
    /** Settings screen's "Tap and hold -- Vehicles" toggle (on by default) -- see
     * TapHoldPreferences.tapHoldVehicleEnabledFlow. Gates [onVehicleLongPressed], independent of
     * [seeEverythingEnabled] -- a vehicle marker is tap-and-hold-able either way. */
    tapHoldVehicleEnabled: Boolean = true,
    /** Fires on a long press of a vehicle marker (regardless of [seeEverythingEnabled]) when
     * [tapHoldVehicleEnabled] is on -- the caller opens that vehicle's own Trip Detail. */
    onVehicleLongPressed: (BusMarker) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Loaded once per icon+size+tint (remember only works at composition time, not inside the
    // Canvas draw-phase lambda below), then just referenced as a plain Bitmap during drawing.
    val iconTint = if (darkMapEnabled) Color.White else Color.Black
    val centerMarkerBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_ARRIVAL, CENTER_MARKER_ICON_PX, iconTint)
    // Matches the DIRECTIONS_MIDDLE_FORK glyph every other screen already uses to mark a
    // multi-platform station (NearbyStopsScreen, StopConnectionsScreen, UpcomingArrivalsScreen,
    // StationListScreen, HomeScreen, TripDetailScreen) -- the Map screen's own pins never had this
    // distinction before, so a station's center/nearby marker looked identical to a plain stop's.
    // A roughly symmetric glyph (no pin "point" the way DIRECTIONS_ARRIVAL has), so it's anchored by
    // bounding-box center like the vehicle icons below, not by PIN_TIP_FRACTION_X/Y.
    val centerStationMarkerBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_MIDDLE_FORK, CENTER_MARKER_ICON_PX, iconTint)
    val nearbyMarkerBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_ARRIVAL, NEARBY_MARKER_ICON_PX, iconTint)
    val nearbyStationMarkerBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_MIDDLE_FORK, NEARBY_MARKER_ICON_PX, iconTint)
    val busIconBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_BUS, VEHICLE_MARKER_ICON_PX, iconTint)
    val subwayIconBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_SUBWAY, VEHICLE_MARKER_ICON_PX, iconTint)
    val trainIconBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_TRAIN, VEHICLE_MARKER_ICON_PX, iconTint)

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(
                nearbyStops, centerLat, centerLon, zoom, tapHoldArrivalsEnabled,
                doubleTapStationEnabled, centerIsStation, showCenterPin, scrimTitle,
                buses, seeEverythingEnabled, tapHoldVehicleEnabled,
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = minOf(size.width, size.height) / 2f * 0.8f
                    val scrimHeightPx = if (scrimTitle != null) STATION_SCRIM_HEIGHT_PX else SCRIM_HEIGHT_PX

                    val hitStop = nearbyStops.firstOrNull { stop ->
                        val rel = projectRelativeToCenter(centerLat, centerLon, stop.lat, stop.lon, zoom)
                        val point = clipToRadius(Offset(center.x + rel.x, center.y + rel.y), center, maxRadius)
                        val dx = down.position.x - point.x
                        val dy = down.position.y - point.y
                        sqrt(dx * dx + dy * dy) < STOP_HIT_RADIUS_PX
                    }
                    // Map-Station mode has no center pin at all -- gated so a stray tap near the
                    // canvas's geometric center can never spuriously register as "hit" there.
                    val hitCenter = showCenterPin && run {
                        val dx = down.position.x - center.x
                        val dy = down.position.y - center.y
                        sqrt(dx * dx + dy * dy) < STOP_HIT_RADIUS_PX
                    }
                    // The station name drawn in Map-Station mode's taller scrim bar -- tap-and-hold
                    // it for the whole station's arrivals (see onScrimTitleLongPressed).
                    val hitScrimTitle = scrimTitle != null && down.position.y < scrimHeightPx

                    // Vehicle markers -- always hit-testable now (not just in "See Everything"),
                    // since tap-and-hold-to-open-trip (see onVehicleLongPressed) works regardless of
                    // that mode; a plain tap toggling the short/long label still only means anything
                    // in "See Everything" itself (see BusMarker.shortLabel/drawBusMarker). Position
                    // mirrors the draw loop's own isArrived-snap logic exactly (see
                    // BusMarker.targetStopId's own doc) so a tap lands on the marker exactly where
                    // it's actually drawn.
                    val hitBus = run {
                        val primaryStopIdsForHitTest = if (centerIsStation) centerStationMemberIds else listOf(stopId)
                        val stopCoordsForHitTest = buildMap {
                            primaryStopIdsForHitTest.forEach { put(it, centerLat to centerLon) }
                            nearbyStops.forEach { put(it.stopId, it.lat to it.lon) }
                        }
                        buses.firstOrNull { bus ->
                            val (lat, lon) = if (bus.isArrived) {
                                stopCoordsForHitTest[bus.targetStopId] ?: (bus.lat to bus.lon)
                            } else {
                                bus.lat to bus.lon
                            }
                            val rel = projectRelativeToCenter(centerLat, centerLon, lat, lon, zoom)
                            val point = clipToRadius(Offset(center.x + rel.x, center.y + rel.y), center, maxRadius)
                            val dx = down.position.x - point.x
                            val dy = down.position.y - point.y
                            sqrt(dx * dx + dy * dy) < STOP_HIT_RADIUS_PX
                        }
                    }

                    if (hitStop == null && !hitCenter && !hitScrimTitle && hitBus == null) {
                        // Not on anything we handle -- leave the event completely untouched so
                        // ancestor/system gestures (like edge-swipe back) still see it normally.
                        return@awaitEachGesture
                    }
                    down.consume()

                    // Resolved once so both the tap-hold-enabled and tap-hold-disabled paths below
                    // (whichever ends up handling a completed double-tap) agree on exactly what
                    // "the station under this touch" means -- a nearby marker that's itself a
                    // station, or the center stop when it's a station (Map-Station mode itself never
                    // has stationed hits here, since showCenterPin is false and none of its platform
                    // markers are themselves stations).
                    val tappedStationMemberIds: List<String>? = when {
                        hitStop != null && hitStop.isStation -> hitStop.memberStopIds
                        hitCenter && centerIsStation -> centerStationMemberIds
                        else -> null
                    }
                    val tappedStationLabel = hitStop?.takeIf { it.isStation }?.stopName ?: stopLabel

                    // What double-tap does here -- opening a station's own sub-map from a station
                    // marker on the main Map screen (zoom IN), or, symmetrically, jumping back to the
                    // main map from the scrim title in Map-Station mode itself (zoom OUT). Both
                    // directions of the same "Double-tap to open a station" gesture, gated by the
                    // same Settings toggle either way -- see doubleTapStationEnabled below.
                    val onDoubleTapAction: (() -> Unit)? = when {
                        hitScrimTitle -> onScrimTitleDoubleTapped
                        tappedStationMemberIds != null -> {
                            { onOpenStation(tappedStationMemberIds, tappedStationLabel) }
                        }
                        else -> null
                    }

                    // Tap-and-hold on a stop marker (the center stop or any nearby one), the
                    // Map-Station scrim title, or a vehicle marker, jumps to arrivals/Trip Detail --
                    // opt-in via Settings ("Tap and hold a stop" for the first two, "Tap and hold --
                    // Vehicles" for the last), since each is an extra gesture layered on top of the
                    // tap-to-toggle behavior below. Fires as soon as the hold threshold is reached
                    // rather than waiting for release, matching how a long press reads everywhere
                    // else on Android. When more than one of these overlaps the same touch (e.g. a
                    // vehicle arrived right at a station), only one action fires -- station pins win
                    // first (they're the harder target to find/tap again), then vehicles, then an
                    // ordinary stop, each still gated by its own Settings toggle.
                    val stopTapHoldActive = tapHoldArrivalsEnabled && (hitStop != null || hitCenter || hitScrimTitle)
                    val vehicleTapHoldActive = tapHoldVehicleEnabled && hitBus != null
                    if (stopTapHoldActive || vehicleTapHoldActive) {
                        val shortTapUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                        if (shortTapUp == null) {
                            when {
                                hitScrimTitle -> onScrimTitleLongPressed?.invoke()
                                tapHoldArrivalsEnabled && hitStop != null && hitStop.isStation ->
                                    onStopLongPressed(hitStop.stopId, hitStop.stopName ?: "Stop ${hitStop.stopId}")
                                tapHoldArrivalsEnabled && hitCenter && centerIsStation -> onStopLongPressed(stopId, stopLabel)
                                tapHoldVehicleEnabled && hitBus != null -> onVehicleLongPressed(hitBus)
                                tapHoldArrivalsEnabled && hitStop != null -> onStopLongPressed(hitStop.stopId, hitStop.stopName ?: "Stop ${hitStop.stopId}")
                                tapHoldArrivalsEnabled && hitCenter -> onStopLongPressed(stopId, stopLabel)
                            }
                        } else {
                            shortTapUp.consume()
                            if (doubleTapStationEnabled && onDoubleTapAction != null) {
                                val secondDown = awaitSecondTapDown(shortTapUp)
                                if (secondDown != null) {
                                    secondDown.consume()
                                    waitForUpOrCancellation()?.consume()
                                    onDoubleTapAction()
                                    return@awaitEachGesture
                                }
                            }
                            hitStop?.let { onToggleStop(it.stopId) }
                            if (seeEverythingEnabled) hitBus?.let { onToggleVehicle(it.tripId) }
                        }
                        return@awaitEachGesture
                    }

                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        up.consume()
                        if (doubleTapStationEnabled && onDoubleTapAction != null) {
                            val secondDown = awaitSecondTapDown(up)
                            if (secondDown != null) {
                                secondDown.consume()
                                waitForUpOrCancellation()?.consume()
                                onDoubleTapAction()
                                return@awaitEachGesture
                            }
                        }
                        hitStop?.let { onToggleStop(it.stopId) }
                        if (seeEverythingEnabled) hitBus?.let { onToggleVehicle(it.tripId) }
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
        val scrimHeightPx = if (scrimTitle != null) STATION_SCRIM_HEIGHT_PX else SCRIM_HEIGHT_PX
        val scrimPaint = Paint().apply {
            color = android.graphics.Color.BLACK
        }
        nativeCanvas.drawRect(0f, 0f, size.width, scrimHeightPx, scrimPaint)
        val overlayAttributionPaint = Paint().apply {
            textSize = 16f
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            alpha = 160
        }
        if (scrimTitle != null) {
            // Map-Station mode: the station's own name takes the scrim's primary line, making it
            // clear you're zoomed into a station rather than the main map -- tap-and-hold it (see
            // onScrimTitleLongPressed) for the whole station's arrivals. Attribution moves down to a
            // second line rather than being dropped, since it's still required either way.
            val titlePaint = Paint().apply {
                textSize = STATION_TITLE_TEXT_SIZE_PX
                textAlign = Paint.Align.LEFT
                isAntiAlias = true
                color = android.graphics.Color.WHITE
            }
            nativeCanvas.drawText(scrimTitle, OVERLAY_INSET_X, STATION_TITLE_Y, titlePaint)
            nativeCanvas.drawText("© OpenStreetMap contributors © CARTO", OVERLAY_INSET_X, STATION_ATTRIBUTION_Y, overlayAttributionPaint)
        } else {
            nativeCanvas.drawText("© OpenStreetMap contributors © CARTO", OVERLAY_INSET_X, ATTRIBUTION_Y, overlayAttributionPaint)
        }

        // Fixed north-up compass letters, each anchored a matching margin from its own screen edge
        // (top/bottom/right/left) rather than a shared circle around center -- accurate since the
        // map background is itself a north-up Mercator projection. N's margin is measured from the
        // bottom of the solid top bar so it never sits under it; S/E/W use that same margin value
        // from their own edge for consistent spacing on all four sides.
        val compassEdgeMarginPx = scrimHeightPx + COMPASS_SCRIM_MARGIN_PX
        listOf(
            "N" to Offset(center.x, compassEdgeMarginPx),
            "S" to Offset(center.x, size.height - compassEdgeMarginPx),
            "E" to Offset(size.width - compassEdgeMarginPx, center.y),
            "W" to Offset(compassEdgeMarginPx, center.y),
        ).forEach { (letter, point) ->
            nativeCanvas.drawText(letter, point.x, point.y, labelOutlinePaint)
            nativeCanvas.drawText(letter, point.x, point.y, labelPaint)
        }

        // Nearby stops: icon-only by default, drawn as a background layer so bus markers stay
        // legible on top; a tap on a stop toggles its own name label on/off, independent per stop
        // (and, when the Settings screen's "Track Tapped Stops" toggle is on, also toggles whether
        // it contributes vehicles). Markers themselves are never grouped/clustered -- only the
        // expanded labels below get basic collision handling, since those are the only thing that
        // can visually stack. Station markers are the one exception -- they're deferred to draw
        // AFTER vehicle markers instead (see below the buses.forEach loop), since an arrived vehicle
        // snapping onto a station's own coordinate would otherwise bury the one marker riders
        // actually need to find and tap (reported live at MBTA South Station with several Silver
        // Line buses arrived there at once).
        val stopPoints = nearbyStops.map { stop ->
            val rel = projectRelativeToCenter(centerLat, centerLon, stop.lat, stop.lon, zoom)
            val point = clipToRadius(Offset(center.x + rel.x, center.y + rel.y), center, maxRadius)
            stop to point
        }
        stopPoints.filter { (stop, _) -> !stop.isStation }.forEach { (_, point) ->
            nativeCanvas.drawBitmap(
                nearbyMarkerBitmap,
                point.x - NEARBY_MARKER_ICON_PX * PIN_TIP_FRACTION_X,
                point.y - NEARBY_MARKER_ICON_PX * PIN_TIP_FRACTION_Y,
                null,
            )
        }

        // Anchored to the right of the marker's own base (its bottom edge, where the pin visually
        // touches the map) rather than centered underneath it, so the label text never overlaps the
        // icon itself -- flips to the marker's left instead when the label would otherwise clip off
        // the canvas's right edge (see resolveLabelSide). A station's own marker (see
        // nearbyStationMarkerBitmap below) is a roughly symmetric glyph anchored by bounding-box
        // center rather than a pointed pin, so its own bottom edge is just half its size below its
        // coordinate instead of PIN_TIP_FRACTION_Y's offset.
        fun nearbyMarkerBottomEdgeOffset(stop: NearbyStopMarker): Float =
            if (stop.isStation) NEARBY_MARKER_ICON_PX / 2f else NEARBY_MARKER_ICON_PX * (1f - PIN_TIP_FRACTION_Y)
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
                    initialY = point.y + nearbyMarkerBottomEdgeOffset(stop) + LABEL_GAP_PX,
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

        fun vehicleIconBitmapFor(bus: BusMarker): Bitmap = when (bus.vehicleIcon) {
            LightIcons.DIRECTIONS_SUBWAY -> subwayIconBitmap
            LightIcons.DIRECTIONS_TRAIN -> trainIconBitmap
            else -> busIconBitmap
        }

        // Every stop_id this map currently knows the real coordinate of -- the primary/center stop
        // (every member platform when it's a station, since StopLocation doesn't carry distinct
        // per-platform coordinates) and every nearby stop. Used only for isArrived below.
        val primaryStopIds = if (centerIsStation) centerStationMemberIds else listOf(stopId)
        val stopCoordsById = buildMap {
            primaryStopIds.forEach { put(it, centerLat to centerLon) }
            nearbyStops.forEach { put(it.stopId, it.lat to it.lon) }
        }

        // Every bus drawn at its true projected position — an arrived vehicle snaps to its target
        // stop's own known coordinate rather than its own live GPS ping, since a vehicle that's
        // literally stopped at a platform is genuinely there regardless of GPS noise, and this
        // guarantees it renders exactly on that stop's own marker rather than next to it. Falls
        // back to the vehicle's own position if its target stop's coordinate isn't known for some
        // reason. Clipped to the visible radius (preserving true direction) only as a fallback for
        // rare far-outliers, with no grouping/de-overlap logic -- multiple vehicles arrived at the
        // exact same platform at once will overlap, same as this already accepted for any other
        // cluster of nearby markers.
        buses.forEach { bus ->
            val (lat, lon) = if (bus.isArrived) {
                stopCoordsById[bus.targetStopId] ?: (bus.lat to bus.lon)
            } else {
                bus.lat to bus.lon
            }
            val rel = projectRelativeToCenter(centerLat, centerLon, lat, lon, zoom)
            val clipped = clipToRadius(Offset(center.x + rel.x, center.y + rel.y), center, maxRadius)
            // "See Everything" mode defaults every marker to its compact route-only label, until
            // tapped (see onToggleVehicle) -- everywhere else, always the full label, unchanged.
            val showShortLabel = seeEverythingEnabled && bus.tripId !in expandedVehicleTripIds
            drawBusMarker(
                nativeCanvas, vehicleIconBitmapFor(bus), vehicleLabelPaint, vehicleLabelOutlinePaint,
                vehicleSmallLabelPaint, vehicleSmallLabelOutlinePaint, bus, clipped.x, clipped.y, size.width, showShortLabel,
            )
        }

        // Nearby STATION markers draw last, on top of every vehicle marker above (see the
        // stopPoints filter earlier that skipped them in the background layer) -- guarantees a
        // station's own distinguishing icon is never buried under a crowd of vehicles arrived at or
        // near it. Bounding-box centered, matching DIRECTIONS_MIDDLE_FORK's own roughly symmetric
        // shape (no pin "point" to anchor by), same convention as vehicle icons.
        stopPoints.filter { (stop, _) -> stop.isStation }.forEach { (_, point) ->
            nativeCanvas.drawBitmap(
                nearbyStationMarkerBitmap,
                point.x - NEARBY_MARKER_ICON_PX / 2f,
                point.y - NEARBY_MARKER_ICON_PX / 2f,
                null,
            )
        }

        // Center stop pin, name, and street context — always pinned dead center, and (like a nearby
        // station marker above) always drawn after every vehicle marker so an arrived bus snapped
        // onto this exact coordinate never buries it. Skipped entirely in Map-Station mode
        // (showCenterPin = false), where every platform renders as an equal nearby-style pin instead
        // and the station's own name lives in the scrim (see scrimTitle). Uses the same
        // DIRECTIONS_MIDDLE_FORK station glyph (bounding-box centered) as a nearby station marker
        // when centerIsStation, matching every other screen's own station convention -- otherwise
        // the ordinary pin-tip-anchored DIRECTIONS_ARRIVAL marker, unchanged from before.
        if (showCenterPin) {
            if (centerIsStation) {
                nativeCanvas.drawBitmap(
                    centerStationMarkerBitmap,
                    center.x - CENTER_MARKER_ICON_PX / 2f,
                    center.y - CENTER_MARKER_ICON_PX / 2f,
                    null,
                )
            } else {
                nativeCanvas.drawBitmap(
                    centerMarkerBitmap,
                    center.x - CENTER_MARKER_ICON_PX * PIN_TIP_FRACTION_X,
                    center.y - CENTER_MARKER_ICON_PX * PIN_TIP_FRACTION_Y,
                    null,
                )
            }
            // Same 32px/60px gaps below the marker's own bottom edge as before this was tip-anchored
            // (that edge used to just be CENTER_MARKER_ICON_PX/2 below center; now it's wherever the
            // marker's actual bottom renders -- PIN_TIP_FRACTION_Y for a plain stop, half its size for
            // a station's own symmetric glyph).
            val centerMarkerBottomEdge = center.y +
                if (centerIsStation) CENTER_MARKER_ICON_PX / 2f else CENTER_MARKER_ICON_PX * (1f - PIN_TIP_FRACTION_Y)
            nativeCanvas.drawText(stopLabel, center.x, centerMarkerBottomEdge + 32f, labelOutlinePaint)
            nativeCanvas.drawText(stopLabel, center.x, centerMarkerBottomEdge + 32f, labelPaint)
            streetContext?.let {
                nativeCanvas.drawText(it, center.x, centerMarkerBottomEdge + 60f, smallLabelOutlinePaint)
                nativeCanvas.drawText(it, center.x, centerMarkerBottomEdge + 60f, smallLabelPaint)
            }
        }
    }
}

/**
 * Waits for a second tap-down following [firstUp], within the platform's own double-tap window --
 * mirrors Compose Foundation's own [androidx.compose.foundation.gestures.detectTapGestures] double-tap
 * detection (read directly from its source, since this hand-written gesture loop can't just delegate
 * to that helper -- see MapCanvas's own doc comment on why). Returns null if no second down arrives in
 * time, meaning the original tap should be treated as a single tap instead.
 */
private suspend fun AwaitPointerEventScope.awaitSecondTapDown(firstUp: PointerInputChange): PointerInputChange? =
    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
        var secondDown: PointerInputChange
        do {
            secondDown = awaitFirstDown()
        } while (secondDown.uptimeMillis < firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis)
        secondDown
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
    /** "See Everything" mode's compact default -- draws just [BusMarker.shortLabel], a single line,
     * instead of the full stack below. Always false outside that mode (see MapCanvas's own
     * showShortLabel computation). */
    showShortLabel: Boolean = false,
) {
    canvas.drawBitmap(vehicleIconBitmap, x - VEHICLE_MARKER_ICON_PX / 2f, y - VEHICLE_MARKER_ICON_PX / 2f, null)
    val baseY = y + VEHICLE_MARKER_ICON_PX / 2f

    if (showShortLabel) {
        val shortText = bus.shortLabel()
        val side = resolveLabelSide(x, VEHICLE_MARKER_ICON_PX.toFloat(), smallLabelPaint.measureText(shortText), canvasWidth)
        val anchorX = side.anchorX(x, VEHICLE_MARKER_ICON_PX.toFloat())
        smallLabelPaint.textAlign = side.paintAlign()
        smallLabelOutlinePaint.textAlign = side.paintAlign()
        canvas.drawText(shortText, anchorX, baseY, smallLabelOutlinePaint)
        canvas.drawText(shortText, anchorX, baseY, smallLabelPaint)
        return
    }

    // Anchored to the right of the icon's own base, the same offset-from-icon approach used for
    // tapped stop labels (see the expandedLabels block above) -- flips to the icon's left instead
    // when the label would otherwise clip off the canvas's right edge (see resolveLabelSide). All
    // three lines share one side decision (based on the widest of them) so they stay aligned with
    // each other rather than each flipping independently.
    val tripText = bus.tripDescription()
    // A "See Everything" vehicle with no specific stop to measure an ETA against shows its live
    // current_status text here instead (see BusMarker.liveStatusText's own doc).
    val secondLineText = bus.liveStatusText ?: bus.etaDisplay()
    val statusText = bus.statusLabel()
    val widestTextPx = maxOf(
        smallLabelPaint.measureText(tripText),
        labelPaint.measureText(secondLineText),
        statusText?.let { smallLabelPaint.measureText(it) } ?: 0f,
    )
    val side = resolveLabelSide(x, VEHICLE_MARKER_ICON_PX.toFloat(), widestTextPx, canvasWidth)
    val anchorX = side.anchorX(x, VEHICLE_MARKER_ICON_PX.toFloat())
    val align = side.paintAlign()
    labelPaint.textAlign = align
    labelOutlinePaint.textAlign = align
    smallLabelPaint.textAlign = align
    smallLabelOutlinePaint.textAlign = align
    canvas.drawText(tripText, anchorX, baseY, smallLabelOutlinePaint)
    canvas.drawText(tripText, anchorX, baseY, smallLabelPaint)
    canvas.drawText(secondLineText, anchorX, baseY + 24f, labelOutlinePaint)
    canvas.drawText(secondLineText, anchorX, baseY + 24f, labelPaint)
    statusText?.let {
        canvas.drawText(it, anchorX, baseY + 48f, smallLabelOutlinePaint)
        canvas.drawText(it, anchorX, baseY + 48f, smallLabelPaint)
    }
}
