package com.thelightphone.transit.gtfs

/**
 * Marker for an optional extra data source a specific [GtfsAgency] can plug in, beyond the core
 * GTFS static feed + GTFS-RT TripUpdates/VehiclePositions every agency already has (see
 * [GtfsAgency.feedUrl] et al). [GtfsAgency.components] is empty for any agency that doesn't need
 * one, since adding a new kind of integration for one agency is just a new AgencyComponent
 * subtype plugged into that agency's own entry, never a change to [GtfsAgency]'s constructor or
 * any other agency's entry. Retrieved via [GtfsAgency.component].
 *
 * Current subtypes: [LiveVehicleSource] (a richer live-position source keyed by a real trip_id,
 * see its own doc), [MultiGtfsFeed] (an extra merged static and/or realtime feed, see its own
 * doc), [FuzzyRunTrips] (closest-match live tracking for an agency with no real trip_id bridge at
 * all, see its own doc), [StopPredictionSource] (per-stop predicted arrival times outside the
 * standard GTFS-RT feed, see its own doc), and [TripShapeSource] (a trip's own route-polyline
 * points, see its own doc).
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
    /** The source's own identifier for this specific vehicle (e.g. CTA Bus Tracker's `vid`), if it
     * has one -- null for a source with no such concept (e.g. MBTA V3, keyed by trip_id already).
     * Lets a caller ask [StopPredictionSource.nextStopForVehicle] for this exact vehicle's own
     * authoritative next stop, instead of guessing from position alone -- see that method's own doc. */
    val vehicleId: String? = null,
)

/**
 * Live vehicle data an agency publishes outside the standard GTFS-RT feeds, layered on top of
 * GTFS-RT rather than replacing it: a trip missing from the result falls back to its ordinary
 * VehiclePositions match.
 *
 * Scoped by [routeIds], the filter these APIs reliably support (trip_ids aren't known in advance,
 * and not every API can filter by stop); callers match the returned vehicles against their own
 * stop_ids.
 *
 * [repository] is this agency's open [GtfsRepository], for sources that resolve trip_ids
 * themselves (e.g. from a scheduled start time). A source that can't resolve a trip_id at all
 * doesn't implement this interface.
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
 * An extra GTFS-RT source for an agency, in one of two shapes:
 *
 * - **Additional static and realtime feeds** ([feedUrl] non-null), whose own static schedule is
 *   merged into the agency's on-device database under its own id-prefixed namespace (see
 *   [GtfsIngestor]'s `idPrefix` handling), e.g. Bustang, CDOT's intercity coach service, whose
 *   static schedule RTD Denver re-hosts and this app merges into RTD's own database (see
 *   [GtfsAgency.RTD]). Its realtime data, if any, is prefixed the same way its static data is, so
 *   it never collides with the primary feed's trip_ids.
 * - **Single static feed, multiple realtime feeds** ([feedUrl] null), with no separate static
 *   schedule of its own, since this agency already has one static feed on-device whose trip_ids
 *   already match this extra realtime feed directly -- unioned into the merged realtime view with
 *   no id prefix, since there's no collision to guard against. No current agency needs this shape:
 *   NYC Subway used to (its realtime is split across 8 line-group MTA feeds rather than one
 *   combined feed the way LIRR/Metro-North's is), but pico-transit-proxy now merges those 8
 *   server-side into one combined URL (see [GtfsAgency.NYC_SUBWAY]'s own doc), so it's a plain
 *   single-realtime-URL agency again. Kept as a supported shape for a future agency split the same
 *   way NYC Subway's own feeds used to be, where a worker-side merge either isn't possible or
 *   isn't worth it (e.g. if the split ever varied per rider rather than being the same fixed set
 *   of upstream feeds for everyone).
 *
 * [name] is this feed's short, rider-facing label (e.g. "Bustang"), meaningful only when
 * [feedUrl] is non-null: it's appended to one of this feed's own routes/stops whose name doesn't
 * already mention it (e.g. "West Line - Bustang", see [GtfsIngestor]'s `disambiguatedName`), and
 * folded into the parent agency's own feed attribution line.
 *
 * [realtimeTripUpdatesUrl]/[realtimeVehiclePositionsUrl] carry this feed's own live data, distinct
 * from the parent agency's, and are null when this specific feed has no realtime data of that
 * kind.
 */
class MultiGtfsFeed(
    val name: String,
    val feedUrl: String? = null,
    val realtimeTripUpdatesUrl: String? = null,
    val realtimeVehiclePositionsUrl: String? = null,
) : AgencyComponent

/**
 * An agency whose GTFS-RT trip_id doesn't literally match its static trip_id, but packs a scheduled
 * start time that bridges to one via [GtfsRepository.tripIdForScheduledStart] -- the same
 * run-associated-trip concept [RunAssociatedTripSource] already handles for CTA Bus Tracker's
 * separate stsd/stst fields, here packed into GTFS-RT's own trip_id string instead of a second API's
 * fields. NYC Subway is the only current example: its trip_id (e.g. "119000_L..S") packs an origin
 * time in NYCT's own hundredths-of-a-minute encoding (verified against its real static schedule:
 * 120050 -> 20:00:30) -- route_id/direction_id need no extraction, GTFS-RT's own TripDescriptor
 * already carries them as separate fields, same as every other agency.
 *
 * Applied by [fetchMerged]/[fetchTripUpdate]/[fetchVehiclePosition] when present: each entity's raw
 * trip_id is resolved to its real static trip_id and the entity's own [GtfsRtTripDescriptor.tripId]
 * is rewritten to match, so every existing downstream consumer keeps reading a plain, already-real
 * trip_id with zero awareness this agency needed bridging at all.
 */
interface RealtimeTripIdBridge : AgencyComponent {
    /** Parses [rawTripId] into a GTFS "HH:MM:SS" scheduled start time, or null if it doesn't match
     * this agency's expected format -- treated the same as any other "not currently live" case,
     * never guessed. */
    fun scheduledStartTime(rawTripId: String): String?
}

/**
 * An agency whose realtime feed's own StopTimeUpdate.stop_id values don't match its static
 * schedule's stop_id space. Applied by [fetchTripUpdate] the same way [RealtimeTripIdBridge] is:
 * each StopTimeUpdate's own raw stop_id is rewritten to the matching local static stop_id right
 * after parsing, so every existing downstream consumer ([GtfsRtTripUpdate.updateFor], etc.) keeps
 * reading a plain, already-real stop_id with zero awareness this agency needed bridging at all. Not
 * wired into [fetchVehiclePosition] -- [GtfsRtVehiclePosition] carries no stop_id field of its own,
 * only [GtfsRtVehiclePosition.currentStopSequence], which this mismatch never touches.
 */
interface RealtimeStopIdBridge : AgencyComponent {
    /** Converts one raw stop_id from the realtime feed into this agency's own static schedule's
     * stop_id space, or null if it doesn't match this agency's expected format -- treated the same
     * as any other "no match" case, never guessed. */
    fun bridgeStopId(rawStopId: String): String?
}

/**
 * [RealtimeStopIdBridge] for 511.org's SF Bay Area regional feed: each operator's stop_ids get a
 * fixed leading-digit [prefix] plus the native stop_id zero-padded to 4 digits (VTA's prefix is
 * "6"). Copy [prefix] from a real feed sample, never guess it. Returns null for a stop_id without
 * [prefix], or one that isn't numeric after stripping it.
 */
class RegionalStopIdPrefixBridge(private val prefix: String) : RealtimeStopIdBridge {
    override fun bridgeStopId(rawStopId: String): String? =
        rawStopId.removePrefix(prefix).takeIf { it != rawStopId }?.toIntOrNull()?.toString()
}

/**
 * Marks this agency's realtime URLs as coming from a shared regional aggregator (e.g. 511.org's
 * SF Bay Area feed). The proxy fetches the regional feed once per cache window, filters it to this
 * agency's entities by a "<regionalOperatorCode><separator>" prefix on trip_id/route_id, and strips
 * the prefix, so the app sees what looks like a dedicated feed.
 *
 * [regionalOperatorCode] is the aggregator's own operator code, unrelated to [GtfsAgency.id]
 * (VTA is "vta" here but "SC" at 511). Copy it from the aggregator's operator list, never derive
 * it from the agency's id or name.
 *
 * Documentary only: no app code reads these fields, since the filtering happens in the proxy
 * (see its REGIONAL_FEEDS).
 */
class RegionalGtfsFeed(
    /** Human-readable name of the regional aggregator, e.g. "511.org SF Bay Area". */
    val regionName: String,
    /** This agency's own code within the aggregator's trip_id/route_id namespace -- see this
     * class's own doc comment for why this is NOT [GtfsAgency.id] and must never be guessed from
     * it. */
    val regionalOperatorCode: String,
) : AgencyComponent

/**
 * Some agencies publish a real, agency-curated per-trip direction label as a non-standard extra
 * column directly on trips.txt, instead of (or in addition to) the standard optional directions.txt
 * file MBTA uses (see [GtfsIngestor]'s own `loadDirections`). CTA is the first confirmed case: its
 * trips.txt has no trip_headsign column at all, but does carry a `direction` column ("North"/
 * "South"/"East"/"West") that's identical for every trip sharing a given (route_id, direction_id)
 * pair -- confirmed across CTA's entire feed, zero exceptions.
 *
 * Wiring this synthesizes the same `directions` table a real directions.txt would have populated
 * (see [GtfsIngestor]'s `loadTrips`), so [GtfsRepository.getDirections]'s directionName-based
 * grouping/labeling works identically whether the source was a real directions.txt file or this
 * column -- no downstream screen needs to know which.
 *
 * [columnName] is the trips.txt column to read -- named explicitly, not hardcoded to "direction",
 * since a future agency with this same non-standard-column setup might use a different name.
 */
class TripDirectionColumn(val columnName: String) : AgencyComponent

/**
 * The exact attribution wording an agency's data license requires, shown word for word in place of
 * the usual "Transit data © <publisher>" credit. Add one to any agency whose terms of use spell out
 * a required legend.
 */
class AttributionLegend(val text: String) : AgencyComponent

/**
 * Credits the organization this agency's data comes through alongside the agency itself, e.g.
 * "Sound Transit & Pierce Transit". Agencies sharing the same [name] are listed together after it.
 */
class AttributionPartner(val name: String) : AgencyComponent

/**
 * A **fuzzy-run trip** is a live run with no trip in the static schedule to resolve to, unlike a
 * **run-associated trip** (see [RunAssociatedTripSource]), which has a real scheduled trip that the
 * live feed just doesn't identify by trip_id. Examples: GTFS-RT `ADDED` trips with no static
 * counterpart, or a rail API that identifies trains only by run number.
 *
 * [matchedTripUpdates] never fabricates a trip: it pairs each live run with the closest real
 * scheduled trip (same route_id and direction_id) by ordinal rank, so existing screens work
 * unchanged. This is an approximation, and callers must label it as one (e.g. "closest match").
 *
 * [routeIds] scopes this to the routes that need it, not the whole agency.
 */
interface FuzzyRunTrips : AgencyComponent {
    val routeIds: Set<String>

    /**
     * Resolves each live fuzzy run on [requestedRouteIds] to its closest scheduled trip_id, as a
     * synthetic [GtfsRtTripUpdate] built from the run's predicted per-stop times, so callers reuse
     * [GtfsRtTripUpdate.updateFor] and [computeArrivalEta] unchanged. Lowest priority of the live
     * sources, since it's the only approximate one. A missing trip falls back to the schedule.
     *
     * [requestedRouteIds] is a caller-scoped subset of [routeIds]: only the routes on screen, never
     * every fuzzy route on every poll.
     *
     * [agency] is the owning agency, for implementations whose runs come from the standard GTFS-RT
     * feed. Fetching that feed again in the same poll is served from the proxy's cache.
     */
    suspend fun matchedTripUpdates(
        requestedRouteIds: Set<String>,
        repository: GtfsRepository,
        agency: GtfsAgency,
        zoneId: java.time.ZoneId,
    ): Map<String, GtfsRtTripUpdate>

    /**
     * Every currently-live run on [routeId], in *both* directions -- the pool a "Select Run" screen
     * lets a rider choose from directly, by its own real destination/current stop/status, entirely
     * bypassing [matchedTripUpdates]' own ranking. Exists specifically so boarding can be backed by
     * a rider's own explicit choice rather than an automatic (if sticky) guess -- see
     * [tripUpdateForRun]'s own doc for the other half of that pairing. Not direction-scoped here --
     * see [liveRunOptionsForTrip], which every real caller should use instead of this directly.
     * [repository] matches [matchedTripUpdates]/[tripUpdateForRun]'s own signatures -- unused by a
     * source with no need to query it (e.g. [CtaTrainTrackerSource], keyed entirely by run number),
     * needed by one that has to resolve a trip_id itself (e.g. [MbtaSubwayFuzzyRunSource] calling
     * [GtfsAgency.fetchMergedTripUpdates]).
     */
    suspend fun liveRunOptions(routeId: String, agency: GtfsAgency, repository: GtfsRepository, zoneId: java.time.ZoneId): List<FuzzyRunOption>

    /**
     * One specific run's current live data, addressed directly by [FuzzyRunOption.runId] -- no
     * ranking or matching at all, the fuzzy-run analog of a certain source's own vehicle-scoped
     * lookup (see [StopPredictionSource.nextStopForVehicle]'s own doc). [tripId] is echoed into the
     * returned [GtfsRtTripUpdate]'s own trip descriptor only for shape-consistency with
     * [matchedTripUpdates] -- the caller already knows it and keeps it fixed once a run is selected
     * (only this run's own live position keeps refreshing against that same trip_id from poll to
     * poll, which is what "the run behaves like the trip" means for boarded progress tracking).
     * Null once this run is no longer live at all (its own physical trip finished, or it dropped off
     * live tracking) -- callers fall back to schedule-only, same as every other live source's own
     * "nothing live right now" case. [repository] lets an implementation with only one real live
     * data point (e.g. CTA's own next-stop-only ttpositions row) still cover this trip's other
     * stops by propagating that one delay across its own static schedule -- see
     * [CtaTrainTrackerSource]'s own doc on why a single-stop update alone isn't enough.
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

/** One live run a rider can pick on the Select Run screen (see [FuzzyRunTrips.liveRunOptions]).
 * [destinationLabel] is best-effort: never blank, but not always a real stop name. [nextStopId]
 * lets [liveRunOptionsForTrip] keep only runs still ahead of the rider on their trip.
 *
 * [isDelayed] is the source's own delay flag, deliberately not an on-time/late diff against a
 * scheduled trip, which would contradict the run being picked. Null means the source has no
 * delay signal: show no status, never "on time". */
data class FuzzyRunOption(
    val runId: String,
    val destinationLabel: String,
    val soonestPredictedEpochSeconds: Long,
    val nextStopId: String,
    val isDelayed: Boolean?,
)

/** Live runs for [routeId] whose next stop is on [tripId]'s path from [fromStopSequence] onward,
 * so only runs that could be this boarded trip are offered (stops have distinct stop_ids per
 * direction). Runs whose next stop is at or past [alightStopId] (or the trip's final stop, if none
 * is set) are excluded, since picking one would immediately trigger "you've arrived". Sorted
 * soonest-first, like [liveRunOptions]. */
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
 * Predicted arrival times an agency publishes per stop outside GTFS-RT TripUpdates. Preferred
 * over [LiveVehicleSource] for arrivals: a predicted time reflects real delays, while a vehicle
 * position can only confirm a trip is running.
 *
 * Scoped by [stopIds], since arrival screens already know which stops they need.
 *
 * Keyed by trip_id; values are raw predicted epoch seconds, not an [ArrivalEta]. A source may only
 * know a trip's first-stop scheduled time, so callers diff the prediction against their own
 * scheduled time for that exact stop (e.g. via [computeArrivalEta]). A missing trip falls back
 * the same way as a TripUpdates miss.
 */
interface StopPredictionSource : AgencyComponent {
    suspend fun predictionsByStop(stopIds: Set<String>, repository: GtfsRepository, zoneId: java.time.ZoneId): Map<String, Long>

    /**
     * The next stop for a live vehicle, from a source that can be queried by vehicle id. More reliable
     * than GPS proximity ([matchCurrentStopByProximity]), which can jump ahead on looping routes.
     * [vehicleId] comes from [LiveVehicleInfo.vehicleId]. Returns null when unsupported (the default)
     * or when the vehicle has no predictions; callers then fall back to position-based matching.
     */
    suspend fun nextStopForVehicle(vehicleId: String, repository: GtfsRepository, zoneId: java.time.ZoneId): VehicleNextStop? = null
}

/** See [StopPredictionSource.nextStopForVehicle]. */
data class VehicleNextStop(val stopId: String, val predictedEpochSeconds: Long)

/** One point along a trip's own route polyline (GTFS shapes.txt), ordered by [sequence] --
 * [shape_pt_sequence]'s own name in the spec, shortened here since this type only ever appears
 * already scoped to one shape. [cumulativeMeters] is this point's own distance along the polyline
 * from the shape's first point -- computed locally in [StaticGtfsShapeSource] (RIPTA's real feed
 * confirmed to have no `shape_dist_traveled` column in either shapes.txt or stop_times.txt, so this
 * can't just be read off the feed the way that GTFS-optional column is meant to provide it), used by
 * [projectOntoShape] to turn a raw lat/lon into a position along the route. */
data class ShapePoint(val latitude: Double, val longitude: Double, val sequence: Int, val cumulativeMeters: Double)

/**
 * A trip's own route-polyline points, from GTFS's optional shapes.txt -- not ingested into this
 * app's shared SQLite schema at all (see [StaticGtfsShapeSource]'s own doc for why), so this is the
 * only way any screen can reach shape data today. Piloted narrowly: opt-in per agency (an agency
 * with no [TripShapeSource] component just has no shape data available, same universal fallback
 * convention every other component follows) and looked up one trip at a time, never bulk-fetched
 * for a whole schedule.
 *
 * Consumed by [matchCurrentStopByShapeProjection] as a higher-priority, path-aware alternative to
 * [matchCurrentStopByProximity]'s straight-line-to-stop heuristic for any agency with this component
 * attached (RIPTA today) -- see that function's own doc. A second intended future consumer, not yet
 * built: drawing a trip's actual path on a map.
 */
interface TripShapeSource : AgencyComponent {
    /** Null = no shape data for this trip (missing shape_id, or the feed has no shapes.txt at
     * all) -- both normal, common cases, never an error. Ordered by [ShapePoint.sequence]. */
    suspend fun shapePoints(tripId: String, repository: GtfsRepository, gtfsZipFile: java.io.File): List<ShapePoint>?

    /** Each of [stops]' own position along this trip's shape (stopSequence -> distanceAlongShapeMeters)
     * -- see [StaticGtfsShapeSource.stopDistancesAlongShape]'s own doc. Null when [shapePoints] has no
     * shape for this trip at all. */
    suspend fun stopDistancesAlongShape(
        tripId: String,
        repository: GtfsRepository,
        gtfsZipFile: java.io.File,
        stops: List<TripStopRow>,
        stopLocations: Map<String, Pair<Double, Double>>,
    ): Map<Int, Double>?
}
