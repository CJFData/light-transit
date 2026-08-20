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
 *
 * [repository] is this agency's own already-open [GtfsRepository] -- MBTA's V3 API doesn't need it
 * (V3 hands back real GTFS trip_ids directly), but a source keyed by something else (e.g. CTA Bus
 * Tracker's own scheduled-start-time fields, see [CtaBusTrackerSource]) needs to query
 * trips/stop_times/calendar itself to resolve a trip_id, and has no other way to reach this
 * agency's own database.
 *
 * A source that genuinely can't resolve a trip_id at all (e.g. CTA Train Tracker, which identifies
 * trains by run number with no static-GTFS bridge back to trip_id -- see [GtfsAgency.CTA]'s own
 * doc) doesn't implement this interface -- there's no [Map]<trip_id, ...> it could honestly return.
 * That kind of source gets its own separate shape and its own separate MapScreen consumption,
 * rather than being forced into this one just because both involve "live vehicles."
 */
interface LiveVehicleSource : AgencyComponent {
    /** Which [LineType]s this source should actually be queried for -- e.g. MBTA's V3 API is
     * deliberately scoped to commuter rail only (subway/Silver Line platforms are already fully
     * resolved via parent_station, so querying V3 for them would be pure waste -- see
     * [MbtaV3VehicleSource]'s own doc), while CTA Bus Tracker only ever covers buses
     * ([LineType.BUS]). Callers (see MapScreen's own MapViewModel) use this to decide which of a
     * station's route_ids to actually pass into [vehiclesByRoute], instead of one agency's own
     * scope being hardcoded into shared map code.
     */
    val coveredLineTypes: Set<LineType>

    suspend fun vehiclesByRoute(routeIds: Set<String>, repository: GtfsRepository): Map<String, LiveVehicleInfo>
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

/**
 * Documents that this agency's [GtfsAgency.realtimeTripUpdatesUrl]/[realtimeVehiclePositionsUrl]
 * are sourced from a shared multi-agency regional aggregator (e.g. 511.org's SF Bay Area feed)
 * rather than a feed dedicated to this agency alone. The URLs themselves already point at
 * pico-transit-proxy's own per-agency-filtered result -- server-side, the proxy fetches the
 * ONE shared regional payload once per cache window, filters it down to this agency's own
 * entities (matched by a "<regionalOperatorCode><separator>" prefix on trip_id/route_id), and
 * strips that prefix back off before returning, so the result is byte-for-byte indistinguishable
 * from a feed dedicated to this agency alone -- this app never sees or needs to know the data
 * came from a shared source. That's what actually bounds the proxy's own upstream request rate to
 * the aggregator to a small constant (one TripUpdates + one VehiclePositions fetch per cache
 * window, total, across every agency sharing it), rather than growing with the number of agencies
 * wired for realtime, which is the whole reason this exists as a distinct pattern from a normal
 * dedicated feed.
 *
 * [regionalOperatorCode] is DELIBERATELY NOT named anything containing "agency" or "id" -- it is
 * NOT this app's own [GtfsAgency.id], and the two must never be assumed interchangeable or
 * derived from one another. [GtfsAgency.id] is this app's own internal identifier (lowercase,
 * full-word, e.g. "vta"); [regionalOperatorCode] is a value defined entirely by the aggregator's
 * own operator list and has no required relationship to it -- e.g. [GtfsAgency.VTA]'s own id is
 * "vta", but its 511 operator code is "SC" (Santa Clara), not "VT" or "VTA". Always copy this
 * value from the aggregator's own operator list (or a hand-verified live sample, the way every
 * entry using this component already was), never infer it from the agency's own id, displayName,
 * or any abbreviation that merely seems plausible.
 *
 * Purely documentary from this app's side -- there is no code here that reads
 * [regionalOperatorCode] or [regionName], since the proxy already did the filtering server-side.
 * It exists so the relationship (and the shared upstream quota it implies) is visible next to the
 * agency itself for anyone reading this file, the same way [SecondaryGtfsFeed] and
 * [LiveVehicleSource] document their own agencies' special-cased data sources. See
 * pico-transit-proxy's own REGIONAL_FEEDS config and `serveRegionalAgencyRoute` for the actual
 * filtering implementation; onboarding a second regional aggregator elsewhere is a new entry
 * there, not a change to either that function or this class.
 */
class RegionalGtfsFeed(
    /** Human-readable name of the regional aggregator, e.g. "511.org SF Bay Area". */
    val regionName: String,
    /** This agency's own code within the aggregator's trip_id/route_id namespace -- see this
     * class's own doc comment for why this is NOT [GtfsAgency.id] and must never be guessed from
     * it. */
    val regionalOperatorCode: String,
) : AgencyComponent
