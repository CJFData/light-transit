package com.thelightphone.transit.gtfs

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/** Matches [PROXIMITY_ARRIVAL_RADIUS_METERS]'s own steady-state window sizing reasoning (10s polls,
 * ordinary city-bus speed), but sized much larger -- this is the window a stuck steady-state walk
 * needs to self-correct across, e.g. several minutes of coarse GPS updates or missed polling, not
 * just one interval. Confirmed live 2026-09-13 (see [[project_pico_transit_proximity_stall_bug]]) that
 * RIPTA's real GPS cadence (~30-60s) let a vehicle advance many stops between two distinct position
 * reports; too small a window would just reproduce that same stall in a milder form. Too large risks
 * matching across a genuine loop back near the route's own start -- 3km is a practical middle ground
 * for a typical urban route, not derived from a hard guarantee. */
private const val FORWARD_SEARCH_WINDOW_METERS = 3_000.0

/** How close a cold-start hint's own windowed match must be to the shape before it's trusted --
 * mirrors [COLD_START_HINT_SANITY_RADIUS_METERS]'s own role for the point-radius matcher (reject an
 * implausible hint, don't blindly trust it), but arguably a stronger check here: a genuinely
 * on-route vehicle's GPS should sit within ordinary GPS-accuracy/map-inaccuracy distance of the
 * shape's own path (tens of meters), not just "somewhere near a stop." Kept as its own constant
 * rather than reusing that one directly -- distance-to-a-continuous-path and distance-to-a-single-
 * point are different enough measures to warrant their own tuned value. */
private const val SHAPE_HINT_SANITY_RADIUS_METERS = 500.0

/** Only lets bearing break a genuine near-tie between two candidate segments at similar distance from
 * the shape -- never lets it override a segment that's meaningfully closer than the rest. See
 * [projectOntoShape]'s own doc. */
private const val BEARING_TIEBREAK_MARGIN_METERS = 15.0

/** Initial compass bearing (0-360, degrees) from (lat1,lon1) to (lat2,lon2) -- standard great-circle
 * bearing formula. Used to compare a shape segment's own local heading against a vehicle's reported
 * [com.thelightphone.transit.gtfs.GtfsRtPosition.bearing] (confirmed populated by RIPTA's live feed,
 * though unused anywhere in this app before [projectOntoShape]). */
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

/** Closest point on a single short segment (p1->p2) to (lat,lon), as (fraction along the segment in
 * [0,1], distance from (lat,lon) to that projected point in meters). Approximates the local area as
 * flat -- degrees scaled to meters by a per-latitude conversion factor -- which is accurate enough
 * for this purpose since GTFS shape segments (consecutive shape points) are always short; every
 * actual reported distance elsewhere in this app still goes through the exact great-circle
 * [haversineMeters], this projection only ever decides WHERE on the segment is closest. */
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

/** [distanceAlongShapeMeters] is how far along the shape's own polyline the projected point sits
 * (from the shape's first point); [distanceFromShapeMeters] is how far the raw (lat, lon) sat from
 * that projected point -- the plausibility signal cold-start validation checks against
 * [SHAPE_HINT_SANITY_RADIUS_METERS]. */
data class ShapeProjection(val distanceAlongShapeMeters: Double, val distanceFromShapeMeters: Double)

/**
 * Closest point on [shapePoints]' own polyline to (lat, lon) -- the path-aware analog of
 * [matchCurrentStopByProximity]'s point-radius matching, for any agency with a [TripShapeSource]
 * attached. [searchFromMeters]/[searchWindowMeters] restrict the search to segments whose own
 * [ShapePoint.cumulativeMeters] falls within that range -- null [searchFromMeters] searches the whole
 * shape (cold start only, mirroring [matchCurrentStopByProximity]'s own global-nearest cold-start
 * fallback and its same loop/backtrack risk); a steady-state caller always supplies a forward-only
 * window from the last known anchor, since re-matching against the *whole* shape every poll could
 * jump backward on a route that loops near itself, the same reasoning
 * [matchCurrentStopByProximity]'s own forward-only walk is built on.
 *
 * Among every candidate segment within [BEARING_TIEBREAK_MARGIN_METERS] of the single closest one,
 * prefers whichever segment's own local [bearingDegrees] most closely matches [vehicleBearing]
 * (circular difference) when it's non-null -- a tie-breaker for a shape that loops/self-intersects
 * within one search window, never the primary signal: a segment meaningfully closer than the rest
 * always wins regardless of bearing agreement. Falls back to pure closest-distance when
 * [vehicleBearing] is null, same "never force a link off missing data" convention every other live
 * source in this app already follows.
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

/** [stopSequence] is the same "at or approaching" semantics [matchCurrentStopByProximity] already
 * uses; [distanceAlongShapeMeters] is the raw projected distance, threaded back in as this trip's own
 * anchor on the next poll (see [TripPositionAnchor]). */
data class ShapeMatch(val stopSequence: Int, val distanceAlongShapeMeters: Double)

/**
 * Path-aware alternative to [matchCurrentStopByProximity], for any trip whose agency has a
 * [TripShapeSource] attached (RIPTA today) -- see [[project_pico_transit_proximity_stall_bug]] for
 * why point-radius matching alone wasn't enough: it requires a GPS sample to land within
 * [PROXIMITY_ARRIVAL_RADIUS_METERS] of *each* intervening stop in sequence, which stalled for 7+
 * minutes on a real RIPTA trip once GPS update cadence (~30-60s) outpaced that per-stop confirmation.
 * A continuous distance-along-shape position instead resolves correctly in one step even when the
 * vehicle passed several stops between two coarse GPS samples.
 *
 * [lastAnchorMeters] is whatever [ShapeMatch.distanceAlongShapeMeters] this function returned last
 * poll, or null for the first poll of a trip (or after any reset -- see [TripPositionAnchor]'s own
 * doc for when that happens). When non-null, the search is strictly forward-windowed from it (never
 * backward) -- both the stall fix (a continuous window search catches up across many stops at once,
 * unlike sequential single-stop checks) and a loop/backtrack guard (never re-matching the whole shape
 * from scratch every poll, same reasoning [matchCurrentStopByProximity]'s forward-only walk is built
 * on).
 *
 * When null (cold start), tries [coldStartSequenceHint] first -- the same
 * [GtfsRtTripUpdate.inferCurrentStopSequence]-sourced hint [matchCurrentStopByProximity] validates,
 * here validated by projecting near that hinted stop's own precomputed shape position and requiring
 * [ShapeProjection.distanceFromShapeMeters] to be within [SHAPE_HINT_SANITY_RADIUS_METERS] before
 * trusting it -- and falls back to an unconstrained whole-shape search otherwise, mirroring
 * [matchCurrentStopByProximity]'s own two-tier cold-start fallback exactly.
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
    } ?: return null

    val matchedStopSequence = stops
        .filter { (stopDistancesAlongShape[it.stopSequence] ?: Double.MAX_VALUE) <= projection.distanceAlongShapeMeters }
        .maxByOrNull { it.stopSequence }
        ?.stopSequence
        ?: stops.first().stopSequence

    return ShapeMatch(matchedStopSequence, projection.distanceAlongShapeMeters)
}
