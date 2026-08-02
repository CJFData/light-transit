package com.thelightphone.transit.gtfs

/**
 * Marker for an optional extra data source a specific [GtfsAgency] can plug in, beyond the core
 * GTFS static feed + GTFS-RT TripUpdates/VehiclePositions every agency already has (see
 * [GtfsAgency.feedUrl] et al). Not every agency needs one -- [GtfsAgency.components] is empty for
 * any that doesn't -- but the type exists so adding a NEW kind of integration for one agency is
 * just a new AgencyComponent subtype plugged into that agency's own entry, never a change to
 * [GtfsAgency]'s constructor or any other agency's entry. Retrieved via [GtfsAgency.component].
 */
interface AgencyComponent

/**
 * A live GPS position + platform assignment for one trip, from an agency's own richer API rather
 * than the standard GTFS-RT VehiclePositions.pb feed -- see [MbtaV3VehicleSource], MBTA's own
 * implementation of this for commuter rail.
 */
data class LiveVehicleInfo(
    val latitude: Double,
    val longitude: Double,
    /** Same value space as [GtfsRtVehicleStatus] (INCOMING_AT/STOPPED_AT/IN_TRANSIT_TO) -- kept as
     * a plain Int rather than reusing that GTFS-RT type directly, so this stays a source-agnostic
     * shape any future agency component could populate the same way. */
    val currentStatus: Int?,
    val currentStopSequence: Int?,
    /** The specific platform stop_id this trip is assigned to, if the agency has decided one yet.
     * Null means "not decided yet" -- for MBTA commuter rail in particular, that's the normal,
     * common case (see [MbtaV3VehicleSource]'s own doc), not missing data. */
    val assignedStopId: String?,
)

/**
 * Looks up live vehicle data an agency publishes outside the standard GTFS-RT feeds. A caller
 * missing a trip from the returned map should fall back to that trip's ordinary GTFS-RT
 * VehiclePositions match, exactly as if this component didn't exist at all -- this is always a
 * preferred, richer source layered on top of GTFS-RT, never a hard replacement.
 *
 * Scoped by [routeIds] rather than trip_ids or stop_ids -- MBTA's V3 API `/vehicles` endpoint
 * doesn't support filtering by stop at all (verified live: a 400 "Unsupported filter(s): stop"),
 * and filtering by trip_id requires already knowing which trips exist, which defeats the point for
 * a caller trying to discover a trip it doesn't have a snapshot for yet. Filtering by route returns
 * every vehicle currently running on it regardless of prior knowledge, so a caller can match the
 * result against its own known stop_ids afterward.
 */
fun interface LiveVehicleSource : AgencyComponent {
    suspend fun vehiclesByRoute(routeIds: Set<String>): Map<String, LiveVehicleInfo>
}
