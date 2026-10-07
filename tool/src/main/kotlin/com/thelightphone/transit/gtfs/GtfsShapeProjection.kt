package com.thelightphone.transit.gtfs

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How far ahead along the shape a steady-state match can look. Large enough to catch a vehicle that
 * passed several stops between updates, small enough not to match across a route that loops back
 * near itself.
 */
private const val FORWARD_SEARCH_WINDOW_METERS = 3_000.0

/** How close a cold-start match must sit to the shape to be trusted. */
private const val SHAPE_HINT_SANITY_RADIUS_METERS = 500.0

/** Bearing only breaks near-ties between segments within this distance of the closest. */
private const val BEARING_TIEBREAK_MARGIN_METERS = 15.0

/**
 * Initial compass bearing (0-360) from (lat1, lon1) to (lat2, lon2), compared against the vehicle's
 * reported bearing.
 */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val deltaLambda = Math.toRadians(lon2 - lon1)
    val y = kotlin.math.sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * kotlin.math.sin(phi2) - kotlin.math.sin(phi1) * cos(phi2) * cos(deltaLambda)
    val theta = atan2(y, x)
    return (Math.toDegrees(theta) + 360.0) % 360.0
}

private fun circularDiffDegrees(a: Double, b: Double): Double {
    val diff = kotlin.math.abs(a - b) % 360.0
    return if (diff > 180.0) 360.0 - diff else diff
}

/**
 * The closest point on segment p1-p2 to (lat, lon), as (fraction along the segment, distance in
 * meters). Treats the short segment as flat.
 */
private fun closestPointOnSegment(
    lat: Double, lon: Double,
    lat1: Double, lon1: Double, lat2: Double, lon2: Double,
): Pair<Double, Double> {
    val metersPerDegLat = 111_320.0
    val metersPerDegLon = 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
    val x2 = (lon2 - lon1) * metersPerDegLon
    val y2 = (lat2 - lat1) * metersPerDegLat
    val px = (lon - lon1) * metersPerDegLon
    val py = (lat - lat1) * metersPerDegLat
    val lengthSquared = x2 * x2 + y2 * y2
    val t = if (lengthSquared == 0.0) 0.0 else ((px * x2 + py * y2) / lengthSquared).coerceIn(0.0, 1.0)
    val projX = t * x2
    val projY = t * y2
    val distanceMeters = sqrt((px - projX).pow(2) + (py - projY).pow(2))
    return t to distanceMeters
}

/**
 * [distanceAlongShapeMeters] is how far along the shape the projected point is;
 * [distanceFromShapeMeters] is how far the position is from the shape.
 */
data class ShapeProjection(val distanceAlongShapeMeters: Double, val distanceFromShapeMeters: Double)

/**
 * The closest point on the shape to (lat, lon). [searchFromMeters] and [searchWindowMeters] limit
 * the search to that stretch of the shape; a null start searches the whole shape (cold start only).
 * Searching forward only keeps a looping route from matching backward.
 *
 * Among segments within [BEARING_TIEBREAK_MARGIN_METERS] of the closest, prefers the one heading
 * most like [vehicleBearing], when known.
 */
fun projectOntoShape(
    lat: Double,
    lon: Double,
    shapePoints: List<ShapePoint>,
    vehicleBearing: Float? = null,
    searchFromMeters: Double? = null,
    searchWindowMeters: Double = FORWARD_SEARCH_WINDOW_METERS,
): ShapeProjection? {
    if (shapePoints.size < 2) return null
    val searchToMeters = searchFromMeters?.let { it + searchWindowMeters }

    data class Candidate(val distanceAlong: Double, val distanceFrom: Double, val bearing: Double)

    val candidates = mutableListOf<Candidate>()
    for (i in 0 until shapePoints.lastIndex) {
        val p1 = shapePoints[i]
        val p2 = shapePoints[i + 1]
        if (searchFromMeters != null && p2.cumulativeMeters < searchFromMeters) continue
        if (searchToMeters != null && p1.cumulativeMeters > searchToMeters) continue
        val (fraction, distanceFrom) = closestPointOnSegment(lat, lon, p1.latitude, p1.longitude, p2.latitude, p2.longitude)
        val distanceAlong = p1.cumulativeMeters + fraction * (p2.cumulativeMeters - p1.cumulativeMeters)
        val segmentBearing = bearingDegrees(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
        candidates += Candidate(distanceAlong, distanceFrom, segmentBearing)
    }
    if (candidates.isEmpty()) return null

    val bestDistance = candidates.minOf { it.distanceFrom }
    val nearTies = candidates.filter { it.distanceFrom <= bestDistance + BEARING_TIEBREAK_MARGIN_METERS }
    val chosen = if (vehicleBearing != null && nearTies.size > 1) {
        nearTies.minBy { circularDiffDegrees(it.bearing, vehicleBearing.toDouble()) }
    } else {
        nearTies.minBy { it.distanceFrom }
    }
    return ShapeProjection(chosen.distanceAlong, chosen.distanceFrom)
}

/**
 * [stopSequence] is the stop the vehicle is at or approaching; [distanceAlongShapeMeters] is saved
 * as the next poll's anchor (see [TripPositionAnchor]).
 */
data class ShapeMatch(val stopSequence: Int, val distanceAlongShapeMeters: Double)

/**
 * Finds the current stop by projecting the vehicle onto the trip's shape. Unlike
 * [matchCurrentStopByProximity], it doesn't need a GPS sample near every stop, so it keeps up when
 * a vehicle passes several stops between updates.
 *
 * With a [lastAnchorMeters] from the previous poll, searches forward from it only. On a cold start,
 * tries near [coldStartSequenceHint]'s stop first, then the whole shape, trusting either only
 * within [SHAPE_HINT_SANITY_RADIUS_METERS] of the shape.
 */
fun matchCurrentStopByShapeProjection(
    stops: List<TripStopRow>,
    stopDistancesAlongShape: Map<Int, Double>,
    shapePoints: List<ShapePoint>,
    vehicleLat: Double,
    vehicleLon: Double,
    vehicleBearing: Float?,
    lastAnchorMeters: Double?,
    coldStartSequenceHint: Int?,
): ShapeMatch? {
    if (stops.isEmpty()) return null

    val projection = if (lastAnchorMeters != null) {
        projectOntoShape(
            vehicleLat, vehicleLon, shapePoints, vehicleBearing,
            searchFromMeters = lastAnchorMeters, searchWindowMeters = FORWARD_SEARCH_WINDOW_METERS,
        )
    } else {
        val hintDistance = coldStartSequenceHint?.let { stopDistancesAlongShape[it] }
        val hintProjection = hintDistance?.let {
            projectOntoShape(
                vehicleLat, vehicleLon, shapePoints, vehicleBearing,
                searchFromMeters = (it - FORWARD_SEARCH_WINDOW_METERS / 2).coerceAtLeast(0.0),
                searchWindowMeters = FORWARD_SEARCH_WINDOW_METERS,
            )
        }
        hintProjection?.takeIf { it.distanceFromShapeMeters <= SHAPE_HINT_SANITY_RADIUS_METERS }
            ?: projectOntoShape(vehicleLat, vehicleLon, shapePoints, vehicleBearing)
                ?.takeIf { it.distanceFromShapeMeters <= SHAPE_HINT_SANITY_RADIUS_METERS }
    } ?: return null

    val matchedStopSequence = stops
        .filter { (stopDistancesAlongShape[it.stopSequence] ?: Double.MAX_VALUE) <= projection.distanceAlongShapeMeters }
        .maxByOrNull { it.stopSequence }
        ?.stopSequence
        ?: stops.first().stopSequence

    return ShapeMatch(matchedStopSequence, projection.distanceAlongShapeMeters)
}
