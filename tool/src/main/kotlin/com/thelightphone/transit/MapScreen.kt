package com.thelightphone.transit

import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.ArrivalStatus
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRealtimeClient
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.GtfsRtVehicleStatus
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.NominatimGeocoder
import com.thelightphone.transit.gtfs.ScheduledArrival
import com.thelightphone.transit.gtfs.StopLocation
import com.thelightphone.transit.gtfs.bearingDegrees
import com.thelightphone.transit.gtfs.computeArrivalEta
import com.thelightphone.transit.gtfs.currentGtfsTimeOfDay
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.transit.gtfs.haversineMeters
import com.thelightphone.transit.gtfs.todayForGtfs
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

private const val POLL_INTERVAL_MS = 15_000L
private const val MAX_DISPLAYED_BUSES = 4
private const val MAX_SCHEDULED_BUSES = 2
private const val MAX_DISPLAY_RADIUS_MILES = 3.0
private const val MAX_DISPLAY_TIME_MINUTES = 30.0
private const val METERS_PER_MILE = 1609.344
private const val NEARBY_STOP_LIMIT = 10
private const val MIN_NEARBY_STOP_RADIUS_FRACTION = 0.25

data class BusMarker(
    val tripId: String,
    val routeLabel: String,
    val directionLabel: String,
    val lineLabel: String?,
    val etaEpochSeconds: Long,
    val status: ArrivalStatus?,
    val isArrived: Boolean,
    /** False when this trip has no VehiclePosition match — position is approximated instead of
     * tracked, and the radar draws it grayed out with a clock badge. */
    val isLive: Boolean,
    val bearingDegrees: Double,
    /**
     * Pre-normalized 0..1 (already capped), so rendering doesn't need to know which underlying
     * scale produced it: live buses cap on physical distance (MAX_DISPLAY_RADIUS_MILES),
     * scheduled-only buses cap on time-until-arrival (MAX_DISPLAY_TIME_MINUTES) instead, since
     * there's no live position to measure real distance from.
     */
    val radiusFraction: Double,
)

fun BusMarker.etaDisplay(): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(etaEpochSeconds), ZoneId.systemDefault())
    return formatGtfsTime("%02d:%02d:00".format(time.hour, time.minute))
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
    val bearingDegrees: Double,
    val radiusFraction: Double,
)

sealed class EtaRadarState {
    object Loading : EtaRadarState()
    data class Loaded(
        val streetContext: String?,
        val buses: List<BusMarker>,
        val nearbyStops: List<NearbyStopMarker>,
        val isOffline: Boolean,
    ) : EtaRadarState()
    data class Error(val message: String) : EtaRadarState()
}

class EtaRadarViewModel(
    dbFile: File,
    private val agency: GtfsAgency,
    private val stopId: String,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val geocoder = NominatimGeocoder()

    private val _state = MutableStateFlow<EtaRadarState>(EtaRadarState.Loading)
    val state: StateFlow<EtaRadarState> = _state

    private var pollJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val stop = repository.getStopLocation(stopId)
                if (stop == null) {
                    _state.value = EtaRadarState.Error("Stop location not found.")
                    return@launch
                }
                // Snapshot once: which trips are scheduled for this stop from now on. Live polling
                // below only refreshes position/prediction data for these same trips — a trip that
                // wasn't scheduled at screen-open time won't appear mid-session.
                val scheduledArrivals = repository.getScheduledArrivals(stopId, currentGtfsTimeOfDay(), todayForGtfs())
                val streetContext = try {
                    geocoder.reverseGeocode(stop.lat, stop.lon)
                } catch (e: Exception) {
                    Log.e("EtaRadarScreen", "Reverse geocoding failed for stop $stopId", e)
                    null
                }
                // Nearby stops don't change while the screen is open, so this is computed once too,
                // reusing the same ranking function as the Leave Now flow, anchored here at the
                // selected stop instead of a geocoded search point.
                val nearbyStops = repository.rankStopsByDistance(stop.lat, stop.lon, NEARBY_STOP_LIMIT, excludeStopId = stopId)
                    .map { nearby ->
                        val trueFraction = (nearby.distanceMeters / METERS_PER_MILE)
                            .coerceAtMost(MAX_DISPLAY_RADIUS_MILES) / MAX_DISPLAY_RADIUS_MILES
                        NearbyStopMarker(
                            stopId = nearby.stopId,
                            stopName = nearby.stopName,
                            bearingDegrees = bearingDegrees(stop.lat, stop.lon, nearby.lat, nearby.lon),
                            // Floored so a stop genuinely close to the selected one never renders
                            // stacked on/behind the center pin.
                            radiusFraction = trueFraction.coerceAtLeast(MIN_NEARBY_STOP_RADIUS_FRACTION),
                        )
                    }

                while (isActive) {
                    refresh(stop, scheduledArrivals, streetContext, nearbyStops)
                    delay(POLL_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e("EtaRadarScreen", "Failed to load ETA radar for stop $stopId", e)
                _state.value = EtaRadarState.Error("Unable to load bus positions.")
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun refresh(
        stop: StopLocation,
        scheduledArrivals: List<ScheduledArrival>,
        streetContext: String?,
        nearbyStops: List<NearbyStopMarker>,
    ) {
        val today = todayForGtfs()

        val tripUpdatesFeed = agency.realtimeTripUpdatesUrl?.let { url ->
            try {
                GtfsRealtimeClient.fetchFeed(url)
            } catch (e: Exception) {
                Log.e("EtaRadarScreen", "TripUpdates fetch failed for ${agency.displayName}", e)
                null
            }
        }
        val vehiclePositionsFeed = agency.realtimeVehiclePositionsUrl?.let { url ->
            try {
                GtfsRealtimeClient.fetchFeed(url)
            } catch (e: Exception) {
                Log.e("EtaRadarScreen", "VehiclePositions fetch failed for ${agency.displayName}", e)
                null
            }
        }

        val nowEpochSeconds = System.currentTimeMillis() / 1000

        val buses = scheduledArrivals.mapNotNull { arrival ->
            val rtStopUpdate = tripUpdatesFeed?.tripUpdatesByTripId?.get(arrival.tripId)
                ?.updateFor(stopId, arrival.stopSequence)
            val eta = computeArrivalEta(arrival.departureTime, today, rtStopUpdate) ?: return@mapNotNull null

            val vehicle = vehiclePositionsFeed?.vehiclePositionsByTripId?.get(arrival.tripId)
            if (vehicle != null) {
                val position = vehicle.position ?: return@mapNotNull null

                // Already past our stop on this trip -> disappear rather than show it moving away.
                val currentSeq = vehicle.currentStopSequence
                if (currentSeq != null && currentSeq > arrival.stopSequence) return@mapNotNull null

                val isArrived = vehicle.currentStatus == GtfsRtVehicleStatus.STOPPED_AT &&
                    (currentSeq == arrival.stopSequence || vehicle.stopId == stopId)

                val lat = position.latitude.toDouble()
                val lon = position.longitude.toDouble()
                val bearing = if (isArrived) 0.0 else bearingDegrees(stop.lat, stop.lon, lat, lon)
                val radiusFraction = if (isArrived) {
                    0.0
                } else {
                    val distanceMiles = haversineMeters(stop.lat, stop.lon, lat, lon) / METERS_PER_MILE
                    distanceMiles.coerceAtMost(MAX_DISPLAY_RADIUS_MILES) / MAX_DISPLAY_RADIUS_MILES
                }

                BusMarker(
                    tripId = arrival.tripId,
                    routeLabel = arrival.route.displayName,
                    directionLabel = arrival.direction.displayLabel(),
                    lineLabel = LineType.forGtfsRouteType(arrival.route.routeType)?.label,
                    etaEpochSeconds = eta.etaEpochSeconds,
                    status = eta.status,
                    isArrived = isArrived,
                    isLive = true,
                    bearingDegrees = bearing,
                    radiusFraction = radiusFraction,
                )
            } else {
                // No VehiclePosition for this trip: approximate its approach direction from the
                // previous stop on the route rather than an actual tracked position, and use
                // time-until-arrival in place of physical distance for radial placement.
                val previousStop = repository.getPreviousStopLocation(arrival.tripId, arrival.stopSequence)
                    ?: return@mapNotNull null // first stop of the trip — no previous stop to bear from
                val bearing = bearingDegrees(stop.lat, stop.lon, previousStop.lat, previousStop.lon)
                val minutesUntilArrival = ((eta.etaEpochSeconds - nowEpochSeconds) / 60.0).coerceAtLeast(0.0)
                val radiusFraction = minutesUntilArrival.coerceAtMost(MAX_DISPLAY_TIME_MINUTES) / MAX_DISPLAY_TIME_MINUTES

                BusMarker(
                    tripId = arrival.tripId,
                    routeLabel = arrival.route.displayName,
                    directionLabel = arrival.direction.displayLabel(),
                    lineLabel = LineType.forGtfsRouteType(arrival.route.routeType)?.label,
                    etaEpochSeconds = eta.etaEpochSeconds,
                    status = eta.status,
                    isArrived = false,
                    isLive = false,
                    bearingDegrees = bearing,
                    radiusFraction = radiusFraction,
                )
            }
        }.sortedBy { it.etaEpochSeconds }

        // Soonest-first, up to MAX_DISPLAYED_BUSES total, but at most MAX_SCHEDULED_BUSES of those
        // may be non-live — an extra scheduled-only candidate is skipped so a later live one can
        // still take its slot, rather than crowding the radar with unconfirmed estimates.
        val selectedBuses = mutableListOf<BusMarker>()
        var scheduledCount = 0
        for (bus in buses) {
            if (selectedBuses.size >= MAX_DISPLAYED_BUSES) break
            if (!bus.isLive) {
                if (scheduledCount >= MAX_SCHEDULED_BUSES) continue
                scheduledCount++
            }
            selectedBuses += bus
        }

        _state.value = EtaRadarState.Loaded(
            streetContext = streetContext,
            buses = selectedBuses,
            nearbyStops = nearbyStops,
            // Offline means "no live position data at all" — a feed that fetched fine but happens
            // to have zero matches among currently-scheduled trips still counts as offline, while
            // even one live match means we're genuinely tracking something and shouldn't say so.
            isOffline = vehiclePositionsFeed == null ||
                (selectedBuses.isNotEmpty() && selectedBuses.none { it.isLive }),
        )
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
        geocoder.close()
    }
}

class EtaRadarScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val agency: GtfsAgency,
    private val stopId: String,
    private val stopLabel: String,
) : LightScreen<Unit, EtaRadarViewModel>(sealedActivity) {

    override val viewModelClass: Class<EtaRadarViewModel>
        get() = EtaRadarViewModel::class.java

    override fun createViewModel(): EtaRadarViewModel = EtaRadarViewModel(dbFile, agency, stopId)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(16.dp)
            ) {
                LightText(
                    text = "ETA",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                when (val s = state) {
                    is EtaRadarState.Loading -> LightText(
                        text = "Loading...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is EtaRadarState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is EtaRadarState.Loaded -> {
                        if (s.isOffline) {
                            LightText(
                                text = "Offline - live positions unavailable",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        LightText(
                            text = "🚏 Nearby stop · 🚌 Live · 🚌🕐 Scheduled",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        RadarCanvas(
                            stopLabel = stopLabel,
                            streetContext = s.streetContext,
                            buses = s.buses,
                            nearbyStops = s.nearbyStops,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws the whole radar entirely on a single Canvas, including text — emoji glyphs and labels are
 * drawn via the underlying native android.graphics.Canvas/Paint (Compose's own text APIs are built
 * for styled text layout, not single positioned glyphs). Sizing/offset constants below are a first
 * pass; verify spacing on an actual device/emulator and adjust, since this can't be rendered here.
 */
@Composable
private fun RadarCanvas(
    stopLabel: String,
    streetContext: String?,
    buses: List<BusMarker>,
    nearbyStops: List<NearbyStopMarker>,
    modifier: Modifier = Modifier,
) {
    val contentColor = LightThemeTokens.colors.content.toArgb()

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = minOf(size.width, size.height) / 2f * 0.8f
        val nativeCanvas = drawContext.canvas.nativeCanvas

        val emojiPaint = Paint().apply {
            textSize = 56f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val labelPaint = Paint().apply {
            textSize = 26f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            color = contentColor
        }
        val smallLabelPaint = Paint(labelPaint).apply {
            textSize = 22f
            alpha = 180
        }
        val nearbyStopEmojiPaint = Paint().apply {
            textSize = 34f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val nearbyStopLabelPaint = Paint(smallLabelPaint).apply {
            textSize = 18f
        }

        // Fixed north-up compass letters around the edge.
        val compassRadius = maxRadius + 28f
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, angle) ->
            val rad = Math.toRadians(angle)
            val x = center.x + compassRadius * sin(rad).toFloat()
            val y = center.y - compassRadius * cos(rad).toFloat()
            nativeCanvas.drawText(label, x, y, labelPaint)
        }

        // Nearby stops drawn first (background layer) so bus markers stay legible on top. Ones
        // close enough to overlap are grouped into one icon with aligned name lines underneath,
        // same de-stacking approach as the bus markers below.
        val stopPoints = nearbyStops.map { stop ->
            val rad = Math.toRadians(stop.bearingDegrees)
            val r = stop.radiusFraction.toFloat() * maxRadius
            RadarPoint(stop, center.x + r * sin(rad).toFloat(), center.y - r * cos(rad).toFloat())
        }
        clusterRadarPoints(stopPoints, CLUSTER_THRESHOLD_PX).forEach { cluster ->
            val anchor = cluster.first()
            nativeCanvas.drawText("🚏", anchor.x, anchor.y, nearbyStopEmojiPaint)
            cluster.forEachIndexed { index, point ->
                val label = point.item.stopName ?: "Stop ${point.item.stopId}"
                nativeCanvas.drawText(label, anchor.x, anchor.y + 22f + index * 18f, nearbyStopLabelPaint)
            }
        }

        // Center stop pin, name, and street context.
        nativeCanvas.drawText("📍", center.x, center.y, emojiPaint)
        nativeCanvas.drawText(stopLabel, center.x, center.y + 64f, labelPaint)
        streetContext?.let {
            nativeCanvas.drawText(it, center.x, center.y + 92f, smallLabelPaint)
        }

        val arrivedBuses = buses.filter { it.isArrived }
        val enRouteBuses = buses.filterNot { it.isArrived }

        arrivedBuses.forEachIndexed { index, bus ->
            val xOffset = (index - (arrivedBuses.size - 1) / 2f) * 110f
            val x = center.x + xOffset
            val y = center.y - maxRadius * 0.45f
            drawBusMarker(nativeCanvas, emojiPaint, labelPaint, smallLabelPaint, bus, x, y)
        }

        // Buses whose computed positions would land close enough to visually overlap (e.g. two
        // trips approaching from the same direction) are grouped into one icon with aligned ETA
        // lines underneath, rather than stacking icons on top of each other. Only far-enough-apart
        // buses get their own individually placed icon.
        val busPoints = enRouteBuses.map { bus ->
            val rad = Math.toRadians(bus.bearingDegrees)
            val r = bus.radiusFraction.toFloat() * maxRadius
            RadarPoint(bus, center.x + r * sin(rad).toFloat(), center.y - r * cos(rad).toFloat())
        }
        clusterRadarPoints(busPoints, CLUSTER_THRESHOLD_PX).forEach { cluster ->
            val anchor = cluster.first()
            if (cluster.size == 1) {
                drawBusMarker(nativeCanvas, emojiPaint, labelPaint, smallLabelPaint, anchor.item, anchor.x, anchor.y)
            } else {
                drawBusCluster(nativeCanvas, emojiPaint, smallLabelPaint, cluster.map { it.item }, anchor.x, anchor.y)
            }
        }
    }
}

private const val SCHEDULED_BUS_ALPHA = 130
private const val CLUSTER_THRESHOLD_PX = 80f

/** A radar-placed item at its computed screen position, for proximity clustering. */
private data class RadarPoint<T>(val item: T, val x: Float, val y: Float)

/**
 * Greedily groups points within [thresholdPx] of each other (Euclidean, in the already-computed
 * screen positions) into clusters, so visually overlapping markers — bus or stop — can be drawn as
 * one icon with an aligned list underneath instead of stacking on top of each other.
 */
private fun <T> clusterRadarPoints(points: List<RadarPoint<T>>, thresholdPx: Float): List<List<RadarPoint<T>>> {
    val clusters = mutableListOf<MutableList<RadarPoint<T>>>()
    points.forEach { point ->
        val cluster = clusters.find { existing ->
            val anchor = existing.first()
            val dx = point.x - anchor.x
            val dy = point.y - anchor.y
            kotlin.math.sqrt(dx * dx + dy * dy) < thresholdPx
        }
        if (cluster != null) cluster += point else clusters += mutableListOf(point)
    }
    return clusters
}

private fun drawBusMarker(
    canvas: android.graphics.Canvas,
    emojiPaint: Paint,
    labelPaint: Paint,
    smallLabelPaint: Paint,
    bus: BusMarker,
    x: Float,
    y: Float,
) {
    val alpha = if (bus.isLive) 255 else SCHEDULED_BUS_ALPHA
    val busEmojiPaint = Paint(emojiPaint).apply { this.alpha = alpha }
    val busLabelPaint = Paint(labelPaint).apply { this.alpha = alpha }
    val busSmallLabelPaint = Paint(smallLabelPaint).apply { this.alpha = smallLabelPaint.alpha * alpha / 255 }

    canvas.drawText("🚌", x, y, busEmojiPaint)
    if (!bus.isLive) {
        // Small clock badge marks this as a scheduled-time estimate, not a tracked live position.
        val clockBadgePaint = Paint(emojiPaint).apply {
            textSize = emojiPaint.textSize * 0.5f
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("🕐", x + emojiPaint.textSize * 0.3f, y - emojiPaint.textSize * 0.35f, clockBadgePaint)
    }
    val routeText = bus.lineLabel?.let { "$it - ${bus.routeLabel}" } ?: bus.routeLabel
    canvas.drawText(routeText, x, y + 30f, busSmallLabelPaint)
    canvas.drawText(bus.directionLabel, x, y + 54f, busSmallLabelPaint)
    canvas.drawText(bus.etaDisplay(), x, y + 78f, busLabelPaint)
    bus.statusLabel()?.let {
        canvas.drawText(it, x, y + 102f, busSmallLabelPaint)
    }
}

/**
 * Draws one icon for a group of buses that would otherwise overlap, with one aligned line per bus
 * underneath (route/direction/ETA), instead of stacking multiple icons on top of each other.
 */
private fun drawBusCluster(
    canvas: android.graphics.Canvas,
    emojiPaint: Paint,
    smallLabelPaint: Paint,
    buses: List<BusMarker>,
    x: Float,
    y: Float,
) {
    val anyLive = buses.any { it.isLive }
    val clusterEmojiPaint = Paint(emojiPaint).apply { alpha = if (anyLive) 255 else SCHEDULED_BUS_ALPHA }
    canvas.drawText("🚌", x, y, clusterEmojiPaint)
    if (!anyLive) {
        val clockBadgePaint = Paint(emojiPaint).apply {
            textSize = emojiPaint.textSize * 0.5f
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("🕐", x + emojiPaint.textSize * 0.3f, y - emojiPaint.textSize * 0.35f, clockBadgePaint)
    }
    buses.sortedBy { it.etaEpochSeconds }.forEachIndexed { index, bus ->
        val alpha = if (bus.isLive) 255 else SCHEDULED_BUS_ALPHA
        val linePaint = Paint(smallLabelPaint).apply { this.alpha = smallLabelPaint.alpha * alpha / 255 }
        val badge = if (bus.isLive) "" else "🕐 "
        val routeText = bus.lineLabel?.let { "$it - ${bus.routeLabel}" } ?: bus.routeLabel
        val line = "$badge$routeText ${bus.directionLabel}: ${bus.etaDisplay()}"
        canvas.drawText(line, x, y + 30f + index * 24f, linePaint)
    }
}
