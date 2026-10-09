package com.thelightphone.transit.gtfs

/**
 * An optional extra capability an agency can plug in beyond its GTFS schedule and standard GTFS-RT
 * feeds. Added to that agency's [GtfsAgency.components] and looked up with [GtfsAgency.component],
 * so agency-specific behavior never touches shared code.
 */
interface AgencyComponent

/** A live position for one trip from an agency API other than GTFS-RT VehiclePositions. */
data class LiveVehicleInfo(
    val latitude: Double,
    val longitude: Double,
    /** Same values as GTFS-RT's vehicle status (incoming at, stopped at, in transit to). */
    val currentStatus: Int?,
    val currentStopSequence: Int?,
    /** The platform stop_id the trip is assigned to; null until the agency assigns one. */
    val assignedStopId: String?,
    /** The source's own vehicle id, when it has one; lets [StopPredictionSource.nextStopForVehicle]
     * look up this vehicle's next stop directly. */
    val vehicleId: String? = null,
)

/**
 * Live vehicles from an agency API, layered on top of GTFS-RT: a trip missing from the result falls
 * back to its VehiclePositions match. Queried by route, which these APIs reliably support.
 */
interface LiveVehicleSource : AgencyComponent {
    /** The modes this source covers, so callers only query it for those routes. */
    val coveredLineTypes: Set<LineType>

    suspend fun vehiclesByRoute(routeIds: Set<String>, repository: GtfsRepository): Map<String, LiveVehicleInfo>
}

/**
 * An extra feed for an agency.
 *
 * With [feedUrl], its schedule is merged into the agency's database under an id prefix, and its
 * realtime data is prefixed the same way (e.g. Bustang inside RTD Denver). [name] labels its routes
 * and stops where needed and is added to the data credits.
 *
 * Without [feedUrl], it's only an extra realtime feed whose trip_ids already match the agency's
 * schedule, merged in unprefixed.
 */
class MultiGtfsFeed(
    val name: String,
    val feedUrl: String? = null,
    val realtimeTripUpdatesUrl: String? = null,
    val realtimeVehiclePositionsUrl: String? = null,
) : AgencyComponent

/**
 * For a realtime feed whose trip_ids don't match the schedule but encode a scheduled start time.
 * The fetch functions resolve each trip_id through [GtfsRepository.tripIdForScheduledStart] and
 * rewrite it, so callers only ever see real trip_ids.
 */
interface RealtimeTripIdBridge : AgencyComponent {
    /** The "HH:MM:SS" start time encoded in [rawTripId], or null if it isn't in the expected form. */
    fun scheduledStartTime(rawTripId: String): String?
}

/**
 * For realtime feeds that use different stop_ids or route_ids from the schedule. Trip update stop_ids
 * and alert stop_ids and route_ids are rewritten after fetching. Vehicle positions match by trip_id,
 * so they need no stop bridging.
 */
interface RealtimeIdBridge : AgencyComponent {
    /** The schedule's stop_id for [rawStopId], or null when it doesn't match. */
    fun bridgeStopId(rawStopId: String): String?

    /** The schedule's route_id for [rawRouteId], or null when it doesn't match. [routeIdsByShortName]
     * maps the schedule's route_short_name to its route_id. */
    fun bridgeRouteId(rawRouteId: String, routeIdsByShortName: Map<String, String>): String? = null
}

/**
 * Bridge for an agency whose realtime comes from 511.org's regional feed but whose schedule comes
 * from the agency itself. 511 writes stop_ids as [stopIdPrefix] plus the native id zero-padded to 4
 * digits, and uses the route short name as the route_id.
 */
class RegionalIdBridge(private val stopIdPrefix: String) : RealtimeIdBridge {
    override fun bridgeStopId(rawStopId: String): String? =
        rawStopId.removePrefix(stopIdPrefix).takeIf { it != rawStopId }?.toIntOrNull()?.toString()

    override fun bridgeRouteId(rawRouteId: String, routeIdsByShortName: Map<String, String>): String? =
        routeIdsByShortName[rawRouteId]
}

/**
 * Marks an agency whose realtime comes from a shared regional feed that the proxy slices per agency.
 * Informational only: the filtering happens in the proxy.
 */
class RegionalGtfsFeed(
    val regionName: String,
    /** The agency's code in the regional feed's ids, which differs from [GtfsAgency.id]. */
    val regionalOperatorCode: String,
) : AgencyComponent

/**
 * Reads per-trip direction labels from a non-standard trips.txt column into the same table
 * directions.txt fills, so direction names work the same either way.
 */
class TripDirectionColumn(val columnName: String) : AgencyComponent

/** Attribution wording an agency's license requires, shown word for word instead of the usual credit. */
class AttributionLegend(val text: String) : AgencyComponent

/**
 * Credits the organization an agency's data comes through alongside the agency, e.g. "Sound Transit
 * & Pierce Transit". Agencies with the same [name] are listed together after it.
 */
class AttributionPartner(val name: String) : AgencyComponent

/**
 * "See everything" doesn't work for this agency, so maps use the stop-based view instead and
 * Settings shows the option as unavailable while it's the primary agency.
 */
object SeeEverythingUnsupported : AgencyComponent

/**
 * Closest-match tracking for live runs with no matching scheduled trip, such as GTFS-RT ADDED trips
 * or trains identified only by run number. Each run is paired with the closest real scheduled trip
 * on the same route and direction, in order. It's an approximation, so callers label it "closest
 * match". [routeIds] limits this to the routes that need it.
 */
interface FuzzyRunTrips : AgencyComponent {
    val routeIds: Set<String>

    /**
     * Pairs each live run on [requestedRouteIds] with its closest scheduled trip, as a synthetic trip
     * update keyed by that trip_id. The lowest-priority live source; trips without a match fall back
     * to the schedule.
     */
    suspend fun matchedTripUpdates(
        requestedRouteIds: Set<String>,
        repository: GtfsRepository,
        agency: GtfsAgency,
        zoneId: java.time.ZoneId,
    ): Map<String, GtfsRtTripUpdate>

    /** Every live run on [routeId] in both directions, for Select Run. Callers use
     * [liveRunOptionsForTrip] to narrow it to one trip. */
    suspend fun liveRunOptions(routeId: String, agency: GtfsAgency, repository: GtfsRepository, zoneId: java.time.ZoneId): List<FuzzyRunOption>

    /**
     * The live data for one run the rider picked, reported against [tripId]. Null once the run is no
     * longer live, and the trip falls back to its schedule.
     */
    suspend fun tripUpdateForRun(
        runId: String,
        tripId: String,
        routeId: String,
        repository: GtfsRepository,
        agency: GtfsAgency,
        zoneId: java.time.ZoneId,
    ): GtfsRtTripUpdate?
}

/** One live run offered on Select Run. [destinationLabel] is never blank but isn't always a stop
 * name. [isDelayed] is the source's own delay flag; null means it has none, so no status is shown. */
data class FuzzyRunOption(
    val runId: String,
    val destinationLabel: String,
    val soonestPredictedEpochSeconds: Long,
    val nextStopId: String,
    val isDelayed: Boolean?,
)

/** Live runs that could be this trip: their next stop is on the trip from [fromStopSequence] and
 * before [alightStopId] (or the last stop). Sorted soonest first. */
suspend fun FuzzyRunTrips.liveRunOptionsForTrip(
    tripId: String,
    fromStopSequence: Int,
    routeId: String,
    repository: GtfsRepository,
    agency: GtfsAgency,
    zoneId: java.time.ZoneId,
    alightStopId: String? = null,
): List<FuzzyRunOption> {
    val tripStops = repository.getTripStops(tripId, fromStopSequence)
    if (tripStops.isEmpty()) return emptyList()
    val boundarySequence = alightStopId?.let { id -> tripStops.find { it.stopId == id }?.stopSequence }
        ?: tripStops.last().stopSequence
    val tripStopIds = tripStops
        .filter { it.stopSequence < boundarySequence }
        .mapTo(mutableSetOf()) { it.stopId }
    return liveRunOptions(routeId, agency, repository, zoneId)
        .filter { it.nextStopId in tripStopIds }
        .sortedBy { it.soonestPredictedEpochSeconds }
}

/**
 * Per-stop predicted arrival times from an agency API outside GTFS-RT. Preferred over
 * [LiveVehicleSource] for arrivals, since a prediction reflects delays. Returns raw predicted epoch
 * seconds by trip_id; callers compare them with their own scheduled time for that stop.
 */
interface StopPredictionSource : AgencyComponent {
    suspend fun predictionsByStop(stopIds: Set<String>, repository: GtfsRepository, zoneId: java.time.ZoneId): Map<String, Long>

    /** A vehicle's next stop, looked up by its id. More reliable than GPS proximity on looping
     * routes. Null when unsupported or when the vehicle has no predictions. */
    suspend fun nextStopForVehicle(vehicleId: String, repository: GtfsRepository, zoneId: java.time.ZoneId): VehicleNextStop? = null
}

data class VehicleNextStop(val stopId: String, val predictedEpochSeconds: Long)

/** One point on a trip's shape, with its distance from the shape's start. */
data class ShapePoint(val latitude: Double, val longitude: Double, val sequence: Int, val cumulativeMeters: Double)

/** A trip's shape from shapes.txt, read on demand one trip at a time. Used for path-aware live
 * tracking ([matchCurrentStopByShapeProjection]). */
interface TripShapeSource : AgencyComponent {
    /** The trip's shape points in order, or null when it has none. */
    suspend fun shapePoints(tripId: String, repository: GtfsRepository, gtfsZipFile: java.io.File): List<ShapePoint>?

    /** Each stop's distance along the trip's shape, by stop sequence; null when there's no shape. */
    suspend fun stopDistancesAlongShape(
        tripId: String,
        repository: GtfsRepository,
        gtfsZipFile: java.io.File,
        stops: List<TripStopRow>,
        stopLocations: Map<String, Pair<Double, Double>>,
    ): Map<Int, Double>?
}
