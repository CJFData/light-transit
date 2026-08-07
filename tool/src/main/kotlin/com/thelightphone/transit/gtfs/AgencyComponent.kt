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

/**
 * An extra GTFS feed merged into an agency's own database alongside its primary feed, under its
 * own id-prefixed namespace (see [GtfsIngestor]'s `idPrefix` handling) -- e.g. Bustang, CDOT's
 * intercity coach service, whose static schedule RTD Denver re-hosts and this app merges into
 * RTD's own on-device database so a single "RTD Denver" schedule lookup covers both without the
 * rider needing to pick a separate agency (see [GtfsAgency.RTD]). An [AgencyComponent] like
 * [LiveVehicleSource], so a future second merged feed for some other agency is just another entry
 * in that agency's own `components` list, not a change to [GtfsAgency]'s shape.
 *
 * [name] is this feed's own short, rider-facing label (e.g. "Bustang") -- used two places: (1)
 * appended to one of this feed's own routes/stops whose name doesn't already mention it (see
 * [GtfsIngestor]'s `disambiguatedName`), so a merged route/stop reads as e.g. "West Line -
 * Bustang" rather than looking like one of the parent agency's own; (2) folded into the parent
 * agency's own feed attribution line alongside the primary feed's, so both sources get credited
 * (see HomeScreen's own feed-attribution handling).
 *
 * [realtimeTripUpdatesUrl]/[realtimeVehiclePositionsUrl] carry this feed's OWN live vehicle data,
 * distinct from the parent agency's -- null when this feed has no realtime data of its own
 * (verify this the same way every other agency's realtime URL is verified before trusting it --
 * see [GtfsAgency]'s own doc comment). A caller polling an agency's live data should treat a
 * [SecondaryGtfsFeed] with non-null realtime URLs as a second feed to fetch and merge in, keyed by
 * trip_id the same way the primary feed already is -- this feed's trip_ids are prefixed the same
 * way its static data's are, so the two never collide.
 */
class SecondaryGtfsFeed(
    val name: String,
    val feedUrl: String,
    val realtimeTripUpdatesUrl: String? = null,
    val realtimeVehiclePositionsUrl: String? = null,
) : AgencyComponent
