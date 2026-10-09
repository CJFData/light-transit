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
import com.thelightphone.transit.gtfs.AgencyPreferences
import com.thelightphone.transit.gtfs.ArrivalStatus
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.GtfsRtTripUpdate
import com.thelightphone.transit.gtfs.GtfsRtVehiclePosition
import com.thelightphone.transit.gtfs.GtfsRtVehicleStatus
import com.thelightphone.transit.gtfs.SeeEverythingUnsupported
import com.thelightphone.transit.gtfs.fetchMergedTripUpdates
import com.thelightphone.transit.gtfs.fetchMergedVehiclePositions
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.MapPreferences
import com.thelightphone.transit.gtfs.TapHoldPreferences
import com.thelightphone.transit.gtfs.MapTiles
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
import com.thelightphone.sdk.ui.LightTopBarCenter
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.sqrt

// How often live vehicles are polled.
private const val LIVE_VEHICLE_POLL_INTERVAL_MS = 10_000L
// Per active stop, so selecting more stops shows more vehicles.
private const val MAX_DISPLAYED_BUSES_PER_STOP = 4

// A vehicle stopped somewhere else (a layover or terminal) with an arrival further out than this
// isn't shown.
private const val DWELLING_FAR_ETA_THRESHOLD_SECONDS = 15 * 60L

// Widens the schedule snapshot back in time so a trip past its scheduled time but still at the stop
// stays a candidate.
private const val SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS = 10 * 60

// How many of the nearest stops the zoom is fitted to.
private const val ZOOM_FIT_NEAREST_STOP_COUNT = 8

// Half the map's width in pixels, used to fit nearby stops before the canvas is measured.
private const val MAP_TARGET_RADIUS_PIXELS = 420f

// Zoom limits, and the fallback when there's nothing to fit.
private const val MIN_ZOOM = 17
private const val MAX_ZOOM = 20
private const val FALLBACK_ZOOM = 20
// Minimum size of the box stops are fitted to, so a tiny cluster doesn't zoom in too far.
private const val MIN_BOUNDING_BOX_MILES = 0.15
private const val STOP_HIT_RADIUS_PX = 44f
private const val LABEL_LINE_HEIGHT_PX = 22f
// Gap between a stop marker and its label to the right.
private const val LABEL_GAP_PX = 12f

// Marker sizes in canvas pixels. Nearby stops are drawn smaller than the selected stop.
private const val CENTER_MARKER_ICON_PX = 64
private const val NEARBY_MARKER_ICON_PX = 40
private const val VEHICLE_MARKER_ICON_PX = 46

// Where the pin's tip sits in the arrival icon, so the tip, not the icon's center, lands on the
// coordinate.
private const val PIN_TIP_FRACTION_X = 13.7f / 27.6f
private const val PIN_TIP_FRACTION_Y = 27.3f / 27.6f

// Height of the solid bar at the top of the map that carries the data credits.
private const val SCRIM_HEIGHT_PX = 40f
private const val OVERLAY_INSET_X = 28f
private const val ATTRIBUTION_Y = 26f
private const val COMPASS_SCRIM_MARGIN_PX = 24f

// Taller bar in station view, which also shows the station's name.
private const val STATION_SCRIM_HEIGHT_PX = 76f
private const val STATION_TITLE_TEXT_SIZE_PX = 30f
private const val STATION_TITLE_Y = 44f
private const val STATION_ATTRIBUTION_Y = 68f

data class BusMarker(
    val tripId: String,
    /**
     * The stop this vehicle is heading to. Only the primary stop gets the "arrived" position on the
     * pin.
     */
    val targetStopId: String,
    /** Short route name only (e.g. "R"); the icon shows the mode. */
    val routeLabel: String,
    val directionLabel: String,
    val vehicleIcon: LightIconConfiguration,
    val etaEpochSeconds: Long,
    /** Null when there's no delay info yet; markers only exist for live vehicles. */
    val status: ArrivalStatus?,
    val isArrived: Boolean,
    val lat: Double,
    val lon: Double,
    /** The platform the vehicle is heading to or at, for multi-platform stations. */
    val platformLabel: String? = null,
    /** TO/AT/FROM relative to a tapped stop, when tapped stops filter "See everything". */
    val stopRelation: StopRelation? = null,
    
    /**
     * Live status text (e.g. "In transit") for a "See everything" vehicle with no stop to give an ETA
     * for.
     */
    val liveStatusText: String? = null,
    /** The agency's time zone, for showing the ETA. */
    val zoneId: ZoneId,
)

enum class StopRelation { TO, AT, FROM }

/** The vehicle icon for a mode; bus is the fallback. */
fun LineType?.toVehicleIcon(): LightIconConfiguration = when (this) {
    LineType.SUBWAY -> LightIcons.DIRECTIONS_SUBWAY
    LineType.COMMUTER_RAIL -> LightIcons.DIRECTIONS_TRAIN
    LineType.FERRY -> LightIcons.DIRECTIONS_FERRY
    LineType.BUS, null -> LightIcons.DIRECTIONS_BUS
}

/** e.g. "R · Toward Pawtucket · Track 1". */
fun BusMarker.tripDescription(): String {
    val base = "$routeLabel · $directionLabel"
    return platformLabel?.let { "$base · $it" } ?: base
}

fun BusMarker.etaDisplay(): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(etaEpochSeconds), zoneId)
    return "ETA: " + formatGtfsTime("%02d:%02d:00".format(time.hour, time.minute))
}

fun BusMarker.statusLabel(): String? = when (val s = status) {
    null -> null
    ArrivalStatus.OnTime -> "On time"
    is ArrivalStatus.Late -> "Late ${(s.seconds / 60).coerceAtLeast(1)}m"
    is ArrivalStatus.Early -> "Early ${(s.seconds / 60).coerceAtLeast(1)}m"
}

/** "See everything"'s compact label: the route, plus TO/FROM/AT when filtered by stop. */
fun BusMarker.shortLabel(): String = stopRelation?.let { "$routeLabel-${it.name}" } ?: routeLabel

/** Readable text for a vehicle's current status. */
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
    /** Whether double-tapping this marker opens its station view. */
    val isStation: Boolean = false,
    /** The station's platform stop_ids. */
    val memberStopIds: List<String> = emptyList(),
)

/** Why no live vehicles are shown. */
enum class LiveFeedStatus {
    NOT_SUPPORTED,
    UNAVAILABLE,
    OK,
}

sealed class MapState {
    object Loading : MapState()
    data class Loaded(
        val centerLat: Double,
        val centerLon: Double,
        val zoom: Int,
        val mapTiles: MapTiles?,
        val buses: List<BusMarker>,
        val nearbyStops: List<NearbyStopMarker>,
        val liveFeedStatus: LiveFeedStatus,
        val tapHoldArrivalsEnabled: Boolean,
        val darkMapEnabled: Boolean,
        val doubleTapStationEnabled: Boolean,
        
        /** The selected stop's station, when it's a multi-platform station. */
        val centerStation: StopLocation?,
        val seeEverythingEnabled: Boolean,
        val tapHoldVehicleEnabled: Boolean,
    ) : MapState()
    data class Error(val message: String) : MapState()
}

/** Values worked out when the screen opens, reused for out-of-cycle refreshes. */
private data class LoadedMapContext(
    val stop: StopLocation,
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
    private val agencyPreferences: AgencyPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val tileClient = MapTileClient()

    private val _state = MutableStateFlow<MapState>(MapState.Loading)
    val state: StateFlow<MapState> = _state

    val nearbyVehiclesEnabled = MutableStateFlow(false)
    
    /**
     * Nearby stops the rider has tapped open. Shows their names, adds their vehicles when "Track tapped
     * stops" is on, and with "See everything" they filter the map.
     */
    val expandedStopIds = MutableStateFlow<Set<String>>(emptySet())

    
    /** Vehicles tapped open in "See everything" mode to show their full label. */
    val expandedVehicleTripIds = MutableStateFlow<Set<String>>(emptySet())

    private var pollJob: Job? = null
    private var loadedContext: LoadedMapContext? = null
    /** Each stop's scheduled arrivals, fetched the first time the stop is active. */
    private val scheduledArrivalsByStopId = mutableMapOf<String, List<ScheduledArrival>>()

    
    /**
     * Wakes the poll loop early. Conflated, so several requests wake it once and refreshes never
     * overlap.
     */
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
                // Settings are read once when the screen opens.
                val darkMode = mapPreferences.darkMapEnabledFlow.first()
                val tapHoldArrivalsEnabled = mapPreferences.tapHoldArrivalsEnabledFlow.first()
                val doubleTapStationEnabled = mapPreferences.doubleTapStationEnabledFlow.first()
                nearbyVehiclesEnabled.value = mapPreferences.trackTappedStopsEnabledFlow.first()
                val seeEverythingEnabled = mapPreferences.seeEverythingEnabledFlow.first() &&
                    agency.component<SeeEverythingUnsupported>() == null
                // With "See everything", tracking tapped stops filters to their vehicles.
                val filterByStopEnabled = nearbyVehiclesEnabled.value
                val seeEverythingShowBus = mapPreferences.seeEverythingShowBusFlow.first()
                val seeEverythingShowSubway = mapPreferences.seeEverythingShowSubwayFlow.first()
                val seeEverythingShowCommuterRail = mapPreferences.seeEverythingShowCommuterRailFlow.first()
                val tapHoldVehicleEnabled = tapHoldPreferences.tapHoldVehicleEnabledFlow.first()
                val mergeFeedStationsEnabled = agencyPreferences.mergeFeedStationsEnabledFlow.first()
                val centerStation = repository.getStationContaining(stopId, mergeFeedStationsEnabled)
                
                // For a station, snapshot every platform's schedule, not just the one passed in.
                val primaryStopIds = centerStation?.memberStopIds ?: listOf(stopId)
                // Scheduled trips from now (minus the grace window). Only these trips are matched
                // against live data.
                for (id in primaryStopIds) {
                    scheduledArrivalsByStopId[id] = repository.getScheduledArrivals(
                        id, currentGtfsTimeOfDay(agency.zoneId), todayForGtfs(agency.zoneId), SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS,
                    )
                }
                // Fit the zoom to the nearest stops around the selected stop.
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

                
                // Map tiles are fetched once, for the area visible at this zoom; nearby stops use the same radius.
                val fetchRadiusMeters = MAP_TARGET_RADIUS_PIXELS * metersPerPixel(stop.lat, zoom)
                val mapTiles = try {
                    tileClient.fetchTilesAround(stop.lat, stop.lon, zoom, fetchRadiusMeters, darkMode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("MapScreen", "Map tile fetch failed for stop $stopId", e)
                    null
                }
                val nearbyStops = repository.getStopsWithinRadius(
                    stop.lat, stop.lon, fetchRadiusMeters, excludeStopId = stopId,
                    mergeFeedStationsEnabled = mergeFeedStationsEnabled,
                ).map { nearby ->
                    NearbyStopMarker(nearby.stopId, nearby.stopName, nearby.lat, nearby.lon, nearby.isStation, nearby.memberStopIds)
                }

                loadedContext = LoadedMapContext(
                    stop, zoom, mapTiles, nearbyStops,
                    tapHoldArrivalsEnabled, darkMode, doubleTapStationEnabled, centerStation,
                    seeEverythingEnabled, filterByStopEnabled,
                    seeEverythingShowBus, seeEverythingShowSubway, seeEverythingShowCommuterRail,
                    tapHoldVehicleEnabled,
                )

                while (isActive) {
                    refresh()
                    // Waits for the interval or an early refresh, so only one fetch is in flight.
                    withTimeoutOrNull(LIVE_VEHICLE_POLL_INTERVAL_MS) { refreshTrigger.receive() }
                }
            } catch (e: CancellationException) {
                throw e
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

    /** Shows or hides the stop's name, and refreshes right away when its vehicles are tracked. */
    fun toggleStopExpanded(stopId: String) {
        expandedStopIds.value = expandedStopIds.value.let { if (stopId in it) it - stopId else it + stopId }
        if (nearbyVehiclesEnabled.value) refreshNow()
    }

    fun toggleVehicleExpanded(tripId: String) {
        expandedVehicleTripIds.value = expandedVehicleTripIds.value.let { if (tripId in it) it - tripId else it + tripId }
    }

    private fun refreshNow() {
        refreshTrigger.trySend(Unit)
    }

    private suspend fun refresh() {
        val context = loadedContext ?: return
        val today = todayForGtfs(agency.zoneId)
        val nowEpochSeconds = System.currentTimeMillis() / 1000

        
        // The primary stop (every platform of a station) plus expanded nearby stops while tracking is on.
        val activeStopIds = buildSet {
            addAll(context.centerStation?.memberStopIds ?: listOf(stopId))
            if (nearbyVehiclesEnabled.value && !context.seeEverythingEnabled) {
                val nearbyIds = context.nearbyStops.mapTo(mutableSetOf()) { it.stopId }
                addAll(expandedStopIds.value.filter { it in nearbyIds })
            }
        }
        for (id in activeStopIds) {
            if (id !in scheduledArrivalsByStopId) {
                scheduledArrivalsByStopId[id] = repository.getScheduledArrivals(
                    id, currentGtfsTimeOfDay(agency.zoneId), today, SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS,
                )
            }
        }

        // Checked first, so an agency without a VehiclePositions feed can still show vehicles.
        val liveVehicleSource = agency.component<LiveVehicleSource>()

        if (agency.realtimeVehiclePositionsUrl == null && liveVehicleSource == null) {
            _state.value = MapState.Loaded(
                context.stop.lat, context.stop.lon, context.zoom, context.mapTiles,
                emptyList(), context.nearbyStops, LiveFeedStatus.NOT_SUPPORTED,
                context.tapHoldArrivalsEnabled, context.darkMapEnabled, context.doubleTapStationEnabled, context.centerStation,
                context.seeEverythingEnabled, context.tapHoldVehicleEnabled,
            )
            return
        }

        // Includes any extra feeds' vehicles; feed status comes from the primary feed.
        val vehiclePositions = agency.fetchMergedVehiclePositions(repository, "MapScreen")
        if (vehiclePositions.primary == null && liveVehicleSource == null) {
            _state.value = MapState.Loaded(
                context.stop.lat, context.stop.lon, context.zoom, context.mapTiles,
                emptyList(), context.nearbyStops, LiveFeedStatus.UNAVAILABLE,
                context.tapHoldArrivalsEnabled, context.darkMapEnabled, context.doubleTapStationEnabled, context.centerStation,
                context.seeEverythingEnabled, context.tapHoldVehicleEnabled,
            )
            return
        }
        val vehiclePositionsByTripId = vehiclePositions.byTripId

        val tripUpdatesByTripId = agency.fetchMergedTripUpdates(repository, "MapScreen").byTripId

        // Only trips both scheduled at an active stop and reporting a live position become markers.
        // Coordinates per active stop, for the distance tiebreak.
        val stopLocationsById = buildMap {
            (context.centerStation?.memberStopIds ?: listOf(stopId)).forEach { put(it, context.stop.lat to context.stop.lon) }
            context.nearbyStops.forEach { put(it.stopId, it.lat to it.lon) }
        }

        // Only a multi-platform primary station gets platform labels.
        val groupedStopIds = context.centerStation?.memberStopIds?.takeIf { it.size > 1 }?.toSet() ?: emptySet()
        val platformLabelByStopId = if (groupedStopIds.isEmpty()) {
            emptyMap()
        } else {
            repository.getStopDescriptions(groupedStopIds.toList()).mapValues { (_, desc) -> platformLabelFromStopDesc(desc) }
        }

        // Vehicles from the agency's own API, if it has one, for the modes it covers. Trips missing
        // from it fall back to GTFS-RT.
        val primaryStopIds = context.centerStation?.memberStopIds ?: listOf(stopId)
        // Routes at every stop shown, including tapped stops while they're tracked.
        val liveSourceRouteIds = liveVehicleSource?.let { source ->
            activeStopIds.flatMapTo(mutableSetOf()) { id ->
                scheduledArrivalsByStopId[id].orEmpty()
                    .filter { LineType.forGtfsRouteType(it.route.routeType) in source.coveredLineTypes }
                    .map { it.route.routeId }
            }
        }.orEmpty()
        val liveVehiclesByTripId = liveVehicleSource
            ?.takeIf { liveSourceRouteIds.isNotEmpty() }
            ?.let { source ->
                try {
                    source.vehiclesByRoute(liveSourceRouteIds, repository)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("MapScreen", "Live vehicle fetch failed", e)
                    emptyMap()
                }
            } ?: emptyMap()

        
        // Live trips heading to the primary station that aren't in the schedule snapshot are looked up and
        // added.
        val knownPrimaryTripIds = primaryStopIds.flatMapTo(mutableSetOf()) { id ->
            scheduledArrivalsByStopId[id].orEmpty().map { it.tripId }
        }
        val missingLiveTripIds = (vehiclePositionsByTripId.keys + liveVehiclesByTripId.keys) - knownPrimaryTripIds
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
                // Only use the agency API's vehicles for modes it covers.
                val preferredLiveVehicle = liveVehicleSource
                    ?.takeIf { lineType != null && lineType in it.coveredLineTypes }
                    ?.let { liveVehiclesByTripId[arrival.tripId] }

                val lat: Double
                val lon: Double
                val currentStatus: Int?
                val currentSeq: Int?
                if (preferredLiveVehicle != null) {
                    lat = preferredLiveVehicle.latitude
                    lon = preferredLiveVehicle.longitude
                    currentStatus = preferredLiveVehicle.currentStatus
                    currentSeq = preferredLiveVehicle.currentStopSequence
                } else {
                    val vehicle = vehiclePositionsByTripId[arrival.tripId] ?: return@mapNotNull null
                    val gpsPosition = vehicle.position
                    currentSeq = vehicle.currentStopSequence
                    // Without GPS, use the vehicle's current stop's coordinates.
                    val stopFallback = if (gpsPosition == null) {
                        currentSeq?.let { seq -> repository.getStopLocationForTripSequence(arrival.tripId, seq) }
                    } else {
                        null
                    }
                    if (gpsPosition == null && stopFallback == null) return@mapNotNull null
                    lat = gpsPosition?.latitude?.toDouble() ?: stopFallback!!.first
                    lon = gpsPosition?.longitude?.toDouble() ?: stopFallback!!.second
                    currentStatus = vehicle.currentStatus
                }

                
                // Stopped at this stop counts as arrived, even after its predicted departure.
                val isArrived = currentStatus == GtfsRtVehicleStatus.STOPPED_AT && currentSeq == arrival.stopSequence

                val rtStopUpdate = tripUpdatesByTripId[arrival.tripId]
                    ?.updateFor(activeStopId, arrival.stopSequence)
                val eta = computeArrivalEta(arrival.departureTime, today, rtStopUpdate, agency.zoneId) ?: return@mapNotNull null

                
                // Gone if it has moved past this stop, or its departure time here has passed, unless it's still at
                // the stop.
                val hasDeparted = !isArrived && (
                    (currentSeq != null && currentSeq > arrival.stopSequence) || eta.etaEpochSeconds < nowEpochSeconds
                )
                if (hasDeparted) return@mapNotNull null

                
                // Stopped somewhere else with a distant arrival isn't coming soon.
                val isDwellingFar = currentStatus == GtfsRtVehicleStatus.STOPPED_AT && !isArrived &&
                    eta.etaEpochSeconds - nowEpochSeconds > DWELLING_FAR_ETA_THRESHOLD_SECONDS
                if (isDwellingFar) return@mapNotNull null

                
                // Commuter rail only shows a platform from a confirmed track assignment; its scheduled stop_id is
                // often a generic placeholder.
                val assignedStopId = preferredLiveVehicle?.assignedStopId
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
                    zoneId = agency.zoneId,
                )
            }
        }

        // Soonest first, rounded to the minute so near-ties fall to the distance tiebreak.
        val busComparator = compareBy<BusMarker> { Math.round(it.etaEpochSeconds / 60.0) }
            .thenBy { bus ->
                val (stopLat, stopLon) = stopLocationsById[bus.targetStopId] ?: return@thenBy Double.MAX_VALUE
                haversineMeters(stopLat, stopLon, bus.lat, bus.lon)
            }

        
        // All of the primary station's platforms share one allotment; each expanded nearby stop gets its
        // own.
        val primaryBuses = activeStopIds.filter { it in primaryStopIds }
            .flatMap { candidatesFor(it) }
            .sortedWith(busComparator)
            .take(MAX_DISPLAYED_BUSES_PER_STOP)
        val nearbyBuses = activeStopIds.filter { it !in primaryStopIds }
            .flatMap { activeStopId -> candidatesFor(activeStopId).sortedWith(busComparator).take(MAX_DISPLAYED_BUSES_PER_STOP) }
        val buses = primaryBuses + nearbyBuses
        // A vehicle heading to two selected stops is shown once, for its earliest ETA.
        val dedupedBuses = buses.groupBy { it.tripId }.values.map { group -> group.minBy { it.etaEpochSeconds } }

        // "See everything" replaces the schedule-based list.
        val displayedBuses = if (context.seeEverythingEnabled) {
            buildSeeEverythingBuses(
                repository, context.stop.lat, context.stop.lon, context.zoom,
                vehiclePositionsByTripId, tripUpdatesByTripId, today, agency.zoneId, nowEpochSeconds,
                expandedStopIds.value.toList(), context.filterByStopEnabled, stopId,
                context.seeEverythingShowBus, context.seeEverythingShowSubway, context.seeEverythingShowCommuterRail,
            )
        } else {
            // Only vehicles within the map's area, at their real positions, like "See everything".
            val radiusMeters = MAP_TARGET_RADIUS_PIXELS * metersPerPixel(context.stop.lat, context.zoom)
            dedupedBuses.filter { bus ->
                bus.isArrived || haversineMeters(context.stop.lat, context.stop.lon, bus.lat, bus.lon) <= radiusMeters
            }
        }

        _state.value = MapState.Loaded(
            context.stop.lat, context.stop.lon, context.zoom, context.mapTiles,
            displayedBuses, context.nearbyStops, LiveFeedStatus.OK,
            context.tapHoldArrivalsEnabled, context.darkMapEnabled, context.doubleTapStationEnabled, context.centerStation,
            context.seeEverythingEnabled, context.tapHoldVehicleEnabled,
        )
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
        tileClient.close()
    }
}

/** The map top bar's reminder of which gestures are on, or null when neither is. */
fun mapGestureHints(doubleTapEnabled: Boolean, tapHoldEnabled: Boolean): LightTopBarCenter? {
    val doubleTapHint = "Double-tap zooms stations".takeIf { doubleTapEnabled }
    val tapHoldHint = "Tap & hold shows arrivals".takeIf { tapHoldEnabled }
    return when {
        doubleTapHint != null && tapHoldHint != null -> LightTopBarCenter.TwoLineDetail(doubleTapHint, tapHoldHint)
        doubleTapHint != null -> LightTopBarCenter.Text(doubleTapHint)
        tapHoldHint != null -> LightTopBarCenter.Text(tapHoldHint)
        else -> null
    }
}

/**
 * "See everything": every live vehicle within the map's radius, whether or not it serves a stop on
 * screen. GTFS-RT only. With "Track tapped stops" and stops tapped, keeps only vehicles whose trip
 * visits one of [selectedStopIds], labeled TO/AT/FROM with an ETA. Shared by the map and station view.
 */
internal fun buildSeeEverythingBuses(
    repository: GtfsRepository,
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    vehiclePositionsByTripId: Map<String, GtfsRtVehiclePosition>,
    tripUpdatesByTripId: Map<String, GtfsRtTripUpdate>,
    today: LocalDate,
    zoneId: ZoneId,
    nowEpochSeconds: Long,
    selectedStopIds: List<String>,
    filterByStopEnabled: Boolean,
    /** Placeholder target stop for unfiltered vehicles; never used for positioning. */
    inertPlaceholderStopId: String,
    /** The "Modes shown" toggles; vehicles of a hidden mode are left out. */
    showBus: Boolean = true,
    showSubway: Boolean = true,
    showCommuterRail: Boolean = true,
    showFerry: Boolean = true,
): List<BusMarker> {
    val fetchRadiusMeters = MAP_TARGET_RADIUS_PIXELS * metersPerPixel(centerLat, zoom)
    // Vehicles without GPS are placed at their current stop.
    val resolvedPositionByTripId = vehiclePositionsByTripId.mapNotNull { (tripId, vehicle) ->
        val gpsPosition = vehicle.position
        val resolved = if (gpsPosition != null) {
            gpsPosition.latitude.toDouble() to gpsPosition.longitude.toDouble()
        } else {
            vehicle.currentStopSequence?.let { seq -> repository.getStopLocationForTripSequence(tripId, seq) }
        }
        resolved?.let { tripId to it }
    }.toMap()
    val inBounds = vehiclePositionsByTripId.filterKeys { tripId ->
        val (lat, lon) = resolvedPositionByTripId[tripId] ?: return@filterKeys false
        haversineMeters(centerLat, centerLon, lat, lon) <= fetchRadiusMeters
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
        val (lat, lon) = resolvedPositionByTripId.getValue(tripId)
        val routeInfo = routesByTripId[tripId] ?: return@mapNotNull null
        val lineType = LineType.forGtfsRouteType(routeInfo.route.routeType)
        val modeShown = when (lineType) {
            LineType.BUS, null -> showBus
            LineType.SUBWAY -> showSubway
            LineType.COMMUTER_RAIL -> showCommuterRail
            LineType.FERRY -> showFerry
        }
        if (!modeShown) return@mapNotNull null
        val routeLabel = routeInfo.route.shortName?.takeIf { it.isNotBlank() } ?: routeInfo.route.displayName

        if (filteringByStop) {
            // Vehicles whose trip doesn't visit a selected stop are left out.
            val stopTime = stopTimesByTripId[tripId]?.firstOrNull() ?: return@mapNotNull null
            val currentSeq = vehicle.currentStopSequence
            val relation = when {
                vehicle.currentStatus == GtfsRtVehicleStatus.STOPPED_AT && currentSeq == stopTime.stopSequence -> StopRelation.AT
                currentSeq != null && currentSeq > stopTime.stopSequence -> StopRelation.FROM
                else -> StopRelation.TO
            }
            val rtStopUpdate = tripUpdatesByTripId[tripId]?.updateFor(stopTime.stopId, stopTime.stopSequence)
            val eta = computeArrivalEta(stopTime.departureTime, today, rtStopUpdate, zoneId)
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
                zoneId = zoneId,
            )
        } else {
            BusMarker(
                tripId = tripId,
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
                zoneId = zoneId,
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
        MapViewModel(
            dbFile, agency, stopId, MapPreferences(lightContext.dataStore), TapHoldPreferences(lightContext.dataStore),
            AgencyPreferences(lightContext.dataStore),
        )

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

                // A real top bar above the map. Its title reminds the rider of whichever map gestures are turned
                // on.
                val loadedState = state as? MapState.Loaded
                val topBarCenter = mapGestureHints(
                    doubleTapEnabled = loadedState?.doubleTapStationEnabled == true,
                    tapHoldEnabled = loadedState?.tapHoldArrivalsEnabled == true,
                )
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = topBarCenter,
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
                            // A vehicle opened from the map has no anchor stop, so its trip opens
                            // from the start.
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

/** Renders an SDK icon to a cached bitmap, tinted, for drawing on the native canvas. */
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
 * Draws the whole map on one native canvas, with markers at their projected positions and only
 * tapped-open labels nudged apart. Touch handling is hand-written so taps that miss every marker
 * are left for edge-swipe back navigation.
 */
@Composable
internal fun MapCanvas(
    stopId: String,
    stopLabel: String,
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    mapTiles: MapTiles?,
    buses: List<BusMarker>,
    nearbyStops: List<NearbyStopMarker>,
    expandedStopIds: Set<String>,
    nearbyVehiclesEnabled: Boolean,
    tapHoldArrivalsEnabled: Boolean,
    /** Light tiles need dark icons; dark tiles keep white ones. */
    darkMapEnabled: Boolean,
    onToggleStop: (String) -> Unit,
    /** Long press on a stop marker, when tap and hold is on. */
    onStopLongPressed: (stopId: String, stopLabel: String) -> Unit,
    /** Whether double-tapping a station marker opens its station view. */
    doubleTapStationEnabled: Boolean = false,
    /** Whether the center stop is a station. */
    centerIsStation: Boolean = false,
    centerStationMemberIds: List<String> = emptyList(),
    /** Double-tap on a station marker, when that gesture is on. */
    onOpenStation: (memberStopIds: List<String>, stationName: String) -> Unit = { _, _ -> },
    /** False in station view, where every platform is an equal pin. */
    showCenterPin: Boolean = true,
    /** The station name shown in station view's top bar. */
    scrimTitle: String? = null,
    /** Long press on the station name: arrivals for the whole station. */
    onScrimTitleLongPressed: (() -> Unit)? = null,
    /** Double-tap on the station name: back to the main map. */
    onScrimTitleDoubleTapped: (() -> Unit)? = null,
    
    /**
     * Only changes vehicle labels (short until tapped); the caller decides which vehicles are shown.
     */
    seeEverythingEnabled: Boolean = false,
    expandedVehicleTripIds: Set<String> = emptySet(),
    /** Tap on a vehicle in "See everything": toggles its full label. */
    onToggleVehicle: (String) -> Unit = {},
    
    /** Whether a long press on a vehicle opens its trip. */
    tapHoldVehicleEnabled: Boolean = true,
    /** Long press on a vehicle: opens its Trip Detail. */
    onVehicleLongPressed: (BusMarker) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Icons are rendered once at composition time, then drawn as bitmaps.
    val iconTint = if (darkMapEnabled) Color.White else Color.Black
    val centerMarkerBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_ARRIVAL, CENTER_MARKER_ICON_PX, iconTint)
    
    // Stations use the same station icon as other screens, anchored at its center.
    val centerStationMarkerBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_MIDDLE_FORK, CENTER_MARKER_ICON_PX, iconTint)
    val nearbyMarkerBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_ARRIVAL, NEARBY_MARKER_ICON_PX, iconTint)
    val nearbyStationMarkerBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_MIDDLE_FORK, NEARBY_MARKER_ICON_PX, iconTint)
    val busIconBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_BUS, VEHICLE_MARKER_ICON_PX, iconTint)
    val subwayIconBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_SUBWAY, VEHICLE_MARKER_ICON_PX, iconTint)
    val trainIconBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_TRAIN, VEHICLE_MARKER_ICON_PX, iconTint)
    val ferryIconBitmap = rememberIconBitmap(LightIcons.DIRECTIONS_FERRY, VEHICLE_MARKER_ICON_PX, iconTint)

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
                    val hitCenter = showCenterPin && run {
                        val dx = down.position.x - center.x
                        val dy = down.position.y - center.y
                        sqrt(dx * dx + dy * dy) < STOP_HIT_RADIUS_PX
                    }
                    val hitScrimTitle = scrimTitle != null && down.position.y < scrimHeightPx

                    
                    // Vehicle hit testing uses the same arrived-snap position as drawing.
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
                            val point = Offset(center.x + rel.x, center.y + rel.y)
                            val dx = down.position.x - point.x
                            val dy = down.position.y - point.y
                            sqrt(dx * dx + dy * dy) < STOP_HIT_RADIUS_PX
                        }
                    }

                    if (hitStop == null && !hitCenter && !hitScrimTitle && hitBus == null) {
                        // Missed everything: leave the event for other gestures.
                        return@awaitEachGesture
                    }
                    down.consume()

                    
                    // The station under the touch, if any, for double-tap.
                    val tappedStationMemberIds: List<String>? = when {
                        hitStop != null && hitStop.isStation -> hitStop.memberStopIds
                        hitCenter && centerIsStation -> centerStationMemberIds
                        else -> null
                    }
                    val tappedStationLabel = hitStop?.takeIf { it.isStation }?.stopName ?: stopLabel

                    
                    // Double-tap opens a station view from a station marker, or returns to the map from the station
                    // name.
                    val onDoubleTapAction: (() -> Unit)? = when {
                        hitScrimTitle -> onScrimTitleDoubleTapped
                        tappedStationMemberIds != null -> {
                            { onOpenStation(tappedStationMemberIds, tappedStationLabel) }
                        }
                        else -> null
                    }

                    
                    // Long press opens arrivals or a trip, when that gesture is on. If several targets overlap,
                    // stations win, then vehicles, then stops.
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

        // Map tiles, each drawn at its own offset.
        mapTiles?.tiles?.forEach { tile ->
            val offset = mapTiles.screenOffset(tile.tileX, tile.tileY)
            nativeCanvas.drawBitmap(tile.bitmap, center.x + offset.x, center.y + offset.y, null)
        }

        // White text with a black outline stays readable on any tile.
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
        // Vehicle labels are left-aligned to the right of their icon.
        val vehicleLabelPaint = Paint(labelPaint).apply { textAlign = Paint.Align.LEFT }
        val vehicleLabelOutlinePaint = Paint(labelOutlinePaint).apply { textAlign = Paint.Align.LEFT }
        val vehicleSmallLabelPaint = Paint(smallLabelPaint).apply { textAlign = Paint.Align.LEFT }
        val vehicleSmallLabelOutlinePaint = Paint(smallLabelOutlinePaint).apply { textAlign = Paint.Align.LEFT }
        
        // Stop labels: white with a black outline, left-aligned to the right of the marker.
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

        
        // Data credits on a solid black bar at the top of the map.
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
            // In station view, the station name takes the first line and the credits move to a
            // second.
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

        
        // North-up compass letters, each the same distance from its screen edge.
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

        
        // Nearby stops are drawn first, under the vehicles. Stations are drawn after the vehicles so
        // arrived vehicles don't cover them.
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

        
        // Where a stop's label starts: the marker's bottom edge.
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
            LightIcons.DIRECTIONS_FERRY -> ferryIconBitmap
            else -> busIconBitmap
        }

        // Stops whose coordinates are known, for the arrived check.
        val primaryStopIds = if (centerIsStation) centerStationMemberIds else listOf(stopId)
        val stopCoordsById = buildMap {
            primaryStopIds.forEach { put(it, centerLat to centerLon) }
            nearbyStops.forEach { put(it.stopId, it.lat to it.lon) }
        }

        
        // Vehicles at their projected positions. An arrived vehicle snaps to its stop's marker.
        buses.forEach { bus ->
            val (lat, lon) = if (bus.isArrived) {
                stopCoordsById[bus.targetStopId] ?: (bus.lat to bus.lon)
            } else {
                bus.lat to bus.lon
            }
            val rel = projectRelativeToCenter(centerLat, centerLon, lat, lon, zoom)
            val point = Offset(center.x + rel.x, center.y + rel.y)
            val showShortLabel = seeEverythingEnabled && bus.tripId !in expandedVehicleTripIds
            drawBusMarker(
                nativeCanvas, vehicleIconBitmapFor(bus), vehicleLabelPaint, vehicleLabelOutlinePaint,
                vehicleSmallLabelPaint, vehicleSmallLabelOutlinePaint, bus, point.x, point.y, size.width, showShortLabel,
            )
        }

        
        // Station markers drawn on top of vehicles.
        stopPoints.filter { (stop, _) -> stop.isStation }.forEach { (_, point) ->
            nativeCanvas.drawBitmap(
                nearbyStationMarkerBitmap,
                point.x - NEARBY_MARKER_ICON_PX / 2f,
                point.y - NEARBY_MARKER_ICON_PX / 2f,
                null,
            )
        }

        
        // The selected stop's pin and name, drawn after vehicles so they're never covered. Skipped in
        // station view.
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
            val centerMarkerBottomEdge = center.y +
                if (centerIsStation) CENTER_MARKER_ICON_PX / 2f else CENTER_MARKER_ICON_PX * (1f - PIN_TIP_FRACTION_Y)
            nativeCanvas.drawText(stopLabel, center.x, centerMarkerBottomEdge + 32f, labelOutlinePaint)
            nativeCanvas.drawText(stopLabel, center.x, centerMarkerBottomEdge + 32f, labelPaint)
        }
    }
}

/** Waits for a second tap within the double-tap window; null if none comes. */
private suspend fun AwaitPointerEventScope.awaitSecondTapDown(firstUp: PointerInputChange): PointerInputChange? =
    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
        var secondDown: PointerInputChange
        do {
            secondDown = awaitFirstDown()
        } while (secondDown.uptimeMillis < firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis)
        secondDown
    }

/** Clamps a point to [maxRadius] from [center], keeping its direction. */
private fun clipToRadius(point: Offset, center: Offset, maxRadius: Float): Offset {
    val dx = point.x - center.x
    val dy = point.y - center.y
    val dist = sqrt(dx * dx + dy * dy)
    if (dist <= maxRadius || dist == 0f) return point
    val scale = maxRadius / dist
    return Offset(center.x + dx * scale, center.y + dy * scale)
}

private enum class LabelSide { RIGHT, LEFT }

/** Labels go to the right of their icon, or the left if they'd run off the canvas. */
private fun resolveLabelSide(iconCenterX: Float, iconSizePx: Float, textWidth: Float, canvasWidth: Float): LabelSide {
    val rightAnchorX = iconCenterX + iconSizePx / 2f + LABEL_GAP_PX
    val wouldClipRight = rightAnchorX + textWidth > canvasWidth - OVERLAY_INSET_X
    return if (wouldClipRight) LabelSide.LEFT else LabelSide.RIGHT
}

/** The x to draw text at, with the matching alignment. */
private fun LabelSide.anchorX(iconCenterX: Float, iconSizePx: Float): Float = when (this) {
    LabelSide.RIGHT -> iconCenterX + iconSizePx / 2f + LABEL_GAP_PX
    LabelSide.LEFT -> iconCenterX - iconSizePx / 2f - LABEL_GAP_PX
}

private fun LabelSide.paintAlign(): Paint.Align = when (this) {
    LabelSide.RIGHT -> Paint.Align.LEFT
    LabelSide.LEFT -> Paint.Align.RIGHT
}

/** A tapped stop label's intended position and size. */
private data class LabelBox(val key: String, val text: String, val anchorX: Float, val align: Paint.Align, val initialY: Float, val width: Float)

/**
 * Stacks overlapping stop labels by pushing each down below the one above. Returns each label's y.
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
    /** Draw just the short label. */
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

    
    // All three lines share the side of the widest one.
    val tripText = bus.tripDescription()
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
