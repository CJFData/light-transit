package com.thelightphone.transit.gtfs

/**
 * Marker for an optional extra data source a specific [GtfsAgency] can plug in, beyond the core
 * GTFS static feed + GTFS-RT TripUpdates/VehiclePositions every agency already has (see
 * [GtfsAgency.feedUrl] et al). [GtfsAgency.components] is empty for any agency that doesn't need
 * one -- adding a new kind of integration for one agency is just a new AgencyComponent subtype
 * plugged into that agency's own entry, never a change to [GtfsAgency]'s constructor or any other
 * agency's entry. Retrieved via [GtfsAgency.component].
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
 * Looks up live vehicle data an agency publishes outside the standard GTFS-RT feeds. A caller
 * missing a trip from the returned map should fall back to that trip's ordinary GTFS-RT
 * VehiclePositions match, exactly as if this component didn't exist -- always a preferred, richer
 * source layered on top of GTFS-RT, never a hard replacement.
 *
 * Scoped by [routeIds] rather than trip_ids or stop_ids -- MBTA's V3 API `/vehicles` endpoint
 * doesn't support filtering by stop at all (verified live: a 400 "Unsupported filter(s): stop"),
 * and filtering by trip_id requires already knowing which trips exist, defeating the point for a
 * caller trying to discover a trip it doesn't have a snapshot for yet. Filtering by route returns
 * every vehicle currently running on it, so a caller can match the result against its own known
 * stop_ids afterward.
 *
 * [repository] is this agency's own already-open [GtfsRepository] -- MBTA's V3 API doesn't need it
 * (V3 hands back real GTFS trip_ids directly), but a source keyed by something else (e.g. CTA Bus
 * Tracker's own scheduled-start-time fields, see [RunAssociatedTripSource]) needs to query
 * trips/stop_times/calendar itself to resolve a trip_id.
 *
 * A source that genuinely can't resolve a trip_id at all (e.g. CTA Train Tracker, which identifies
 * trains by run number with no static-GTFS bridge back to trip_id) doesn't implement this
 * interface -- that kind of source gets its own separate shape and MapScreen consumption instead.
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
 * - **A real second feed** ([feedUrl] non-null): its own static schedule is merged into the
 *   agency's on-device database under its own id-prefixed namespace (see [GtfsIngestor]'s
 *   `idPrefix` handling) -- e.g. Bustang, CDOT's intercity coach service, whose static schedule
 *   RTD Denver re-hosts and this app merges into RTD's own database (see [GtfsAgency.RTD]). Its
 *   realtime data, if any, is prefixed the same way its static data is, so it never collides with
 *   the primary feed's trip_ids.
 * - **Just another realtime feed** ([feedUrl] null): no separate static schedule -- this agency
 *   already has one static feed on-device whose trip_ids already match this extra realtime feed
 *   directly. E.g. NYC Subway, whose realtime is split across 8 line-group MTA feeds rather than
 *   one combined feed the way LIRR/Metro-North's is (see [GtfsAgency.NYC_SUBWAY]) -- each of the
 *   other 7 feeds (beyond the one occupying the agency's own primary URL fields) is a
 *   [MultiGtfsFeed] with [feedUrl] left null, unioned into the merged realtime view with no id
 *   prefix, since there's no collision to guard against.
 *
 * An [AgencyComponent] like [LiveVehicleSource], so more merged/extra feeds are just more entries
 * in that agency's own `components` list, never a change to [GtfsAgency]'s own shape.
 *
 * [name] is this feed's short, rider-facing label (e.g. "Bustang") -- only meaningful when
 * [feedUrl] is non-null, where it's used two places: (1) appended to one of this feed's own
 * routes/stops whose name doesn't already mention it (see [GtfsIngestor]'s `disambiguatedName`),
 * so a merged route/stop reads as e.g. "West Line - Bustang"; (2) folded into the parent agency's
 * own feed attribution line. Ignored entirely when [feedUrl] is null -- there's no separate
 * static data to disambiguate or credit.
 *
 * [realtimeTripUpdatesUrl]/[realtimeVehiclePositionsUrl] carry this feed's own live data, distinct
 * from the parent agency's -- null when this specific feed has no realtime data of that kind.
 */
class MultiGtfsFeed(
    val name: String,
    val feedUrl: String? = null,
    val realtimeTripUpdatesUrl: String? = null,
    val realtimeVehiclePositionsUrl: String? = null,
) : AgencyComponent

/**
 * Documents that this agency's [GtfsAgency.realtimeTripUpdatesUrl]/[realtimeVehiclePositionsUrl]
 * are sourced from a shared multi-agency regional aggregator (e.g. 511.org's SF Bay Area feed)
 * rather than a feed dedicated to this agency alone. The URLs already point at
 * pico-transit-proxy's own per-agency-filtered result -- server-side, the proxy fetches the one
 * shared regional payload once per cache window, filters it down to this agency's own entities
 * (matched by a "<regionalOperatorCode><separator>" prefix on trip_id/route_id), and strips that
 * prefix back off before returning, so the result is indistinguishable from a feed dedicated to
 * this agency alone. That's what bounds the proxy's own upstream request rate to a small constant
 * (one TripUpdates + one VehiclePositions fetch per cache window, total, across every agency
 * sharing it), rather than growing with the number of agencies wired for realtime.
 *
 * [regionalOperatorCode] is deliberately NOT named anything containing "agency" or "id" -- it is
 * NOT this app's own [GtfsAgency.id], and the two must never be assumed interchangeable or derived
 * from one another. [GtfsAgency.id] is this app's own internal identifier (lowercase, full-word,
 * e.g. "vta"); [regionalOperatorCode] is defined entirely by the aggregator's own operator list --
 * e.g. [GtfsAgency.VTA]'s own id is "vta", but its 511 operator code is "SC" (Santa Clara), not
 * "VT" or "VTA". Always copy this value from the aggregator's own operator list (or a
 * hand-verified live sample), never infer it from the agency's own id, displayName, or a
 * plausible-looking abbreviation.
 *
 * Purely documentary from this app's side -- no code here reads [regionalOperatorCode] or
 * [regionName], since the proxy already did the filtering server-side. It exists so the
 * relationship (and the shared upstream quota it implies) is visible next to the agency itself,
 * the same way [MultiGtfsFeed] and [LiveVehicleSource] document their own agencies' special-
 * cased data sources. See pico-transit-proxy's own REGIONAL_FEEDS/serveRegionalAgencyRoute for the
 * actual filtering; onboarding a second regional aggregator is a new entry there, not a change to
 * this class.
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
 * A **fuzzy-run trip** is a live vehicle/run with no real trip in the static schedule to resolve to
 * at all -- contrast with a **run-associated trip** (see [RunAssociatedTripSource]'s own doc), which
 * always has a real scheduled trip underneath, just not identified by trip_id in the live payload.
 * MBTA Green Line's own live feed marks these with GTFS-RT's `schedule_relationship: ADDED` and a
 * null `service` relationship on the trip itself -- the official way of saying "this trip was never
 * in the static schedule, ever" (confirmed live: ~96% of Green Line's currently-running vehicles on
 * a given sample). CTA's 'L' trains are the same shape from a different angle: Train Tracker
 * identifies a train by a persistent run number, with no schedule-time-equivalent field bridging
 * back to a trip_id the way CTA Bus Tracker's `stsd`/`stst` do for buses -- there's no static trip
 * on the other end to bridge to.
 *
 * Since there's no real trip to match against, [matchedTripUpdates] never fabricates a synthetic
 * trip: it resolves each live fuzzy run to the *closest real scheduled trip* (same route_id +
 * direction_id) by ordinal rank -- soonest live run paired with the soonest scheduled trip, and so
 * on -- since every existing screen (Upcoming Arrivals, Trip Detail) already works correctly for a
 * real trip_id with zero query-layer changes; the only new work is finding the live runs and picking
 * which real trip each corresponds to. This is necessarily an approximation, never a genuine match
 * the way a [RunAssociatedTripSource] match is -- a caller MUST label it as such (e.g. "closest
 * match"), never present it as a certain live ETA.
 *
 * [routeIds] scopes this component to the specific routes that actually have this problem --
 * deliberately NOT a whole-agency flag, since e.g. MBTA's Orange/Red/Blue lines have real trip_ids
 * and don't need this at all; only Green Line (`Green-B`/`Green-C`/`Green-D`/`Green-E`) does.
 */
interface FuzzyRunTrips : AgencyComponent {
    val routeIds: Set<String>

    /**
     * Resolves every currently-live fuzzy run on [requestedRouteIds] to its closest real scheduled
     * trip_id, returning a synthetic [GtfsRtTripUpdate] built from that run's own real predicted
     * per-stop times -- reusing [GtfsRtTripUpdate]'s existing shape means a caller needs zero new
     * consumption logic: [GtfsRtTripUpdate.updateFor] and [computeArrivalEta] already work unchanged,
     * this is just one more source to check alongside [StopPredictionSource]/[LiveVehicleSource],
     * always at *lowest* priority since it's the only approximate one. A trip missing from the
     * returned map means either nothing live matched it or this route currently has no live runs at
     * all -- same "never force a link" fallback-to-schedule rule every other live source follows.
     *
     * [requestedRouteIds] is caller-scoped (a subset of [routeIds], the same [LiveVehicleSource.vehiclesByRoute]
     * distinction between "what this source could cover" vs. "what's actually needed right now") --
     * a caller must scope this to only the route(s) actually on screen, never fetch every
     * fuzzy-scoped route system-wide on every poll.
     *
     * [agency] is this component's own owning agency -- needed by an implementation whose live data
     * comes from the *standard* GTFS-RT feed (e.g. MBTA Green Line's own ADDED trips, already present
     * in the same TripUpdates feed [GtfsAgency.fetchMergedTripUpdates] fetches) rather than a separate
     * proprietary API the way CTA Train Tracker's does. A caller that already fetched that same feed
     * for its own primary live-status check this poll cycle pays a redundant-but-cached network call
     * here (the worker's own 10s cache makes a same-URL repeat effectively free -- confirmed live
     * elsewhere in this codebase), not a second real round trip.
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
     */
    suspend fun liveRunOptions(routeId: String, agency: GtfsAgency, zoneId: java.time.ZoneId): List<FuzzyRunOption>

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

/** One live run a rider can pick from a "Select Run" screen -- see [FuzzyRunTrips.liveRunOptions]'s
 * own doc. [destinationLabel] is best-effort (some sources, e.g. MBTA's ADDED trips, have no clean
 * destination field to draw from) -- never blank, but not guaranteed to be a real stop name either.
 * [nextStopId] is this run's own immediate next stop_id -- [liveRunOptionsForTrip] filters against a
 * boarded trip's own remaining stop_ids with it, so a rider picking a run only ever sees live runs
 * that could plausibly BE their trip (same route, same direction, still ahead of them), not every
 * live run system-wide on the route regardless of direction.
 *
 * [isDelayed] is the source's own native delay flag (e.g. CTA ttpositions' `isDly`) -- deliberately
 * NOT a computed on-time/late/early diffed against the nearest scheduled trip the way the rest of
 * this app shows status: confirmed live 2026-08-23 that a computed "Late by Nm" reads as
 * self-contradictory right on the one screen whose whole purpose is picking which run has the
 * accurate ETA to begin with. Null means this source has no native delay signal at all (e.g. MBTA's
 * ADDED trips carry none) -- render that as no status shown, never a fabricated "on time". */
data class FuzzyRunOption(
    val runId: String,
    val destinationLabel: String,
    val soonestPredictedEpochSeconds: Long,
    val nextStopId: String,
    val isDelayed: Boolean?,
)

/** Every live run for [routeId] whose own next stop is somewhere on [tripId]'s own path from
 * [fromStopSequence] onward -- restricts [FuzzyRunTrips.liveRunOptions]' route-wide (both
 * directions) result down to only runs that could plausibly be this exact boarded trip. A stop
 * before [fromStopSequence] is excluded too, not just the opposite direction's stops -- the rider
 * already boarded past that point, so a run still approaching an earlier stop on this same trip
 * can't be their own train either. Verified against the real CTA schedule (Addison-Blue,
 * Belmont-Blue, Jefferson Park, Fullerton all checked 2026-08-23) that rail platforms use distinct
 * stop_ids per direction, so this is a real check against the static schedule already on-device,
 * not a guess.
 *
 * Also excludes any run whose own next stop is at or past [alightStopId] (the rider's own chosen
 * getting-off point, if they've set one) -- or, if they haven't, at the trip's own real final stop.
 * Either boundary means picking that run would read as "you've arrived" immediately, never the
 * intent of adjusting which run this is. Confirmed live 2026-08-23: an unguarded Next Run tap
 * landed on a run already at the trip's own final stop, which correctly (but unintentionally)
 * triggered the "you've arrived" flow and ended the boarded trip right then. A rider can still
 * naturally reach their real alight stop through normal live progression once a valid run is
 * pinned -- this only stops the picker itself from jumping straight there.
 *
 * Sorted soonest-first, same convention [liveRunOptions] itself already returns. */
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
    return liveRunOptions(routeId, agency, zoneId)
        .filter { it.nextStopId in tripStopIds }
        .sortedBy { it.soonestPredictedEpochSeconds }
}

/**
 * Looks up real predicted arrival times an agency publishes per-stop, outside the standard GTFS-RT
 * TripUpdates feed -- e.g. CTA Bus Tracker's `getpredictions` (stpid-scoped), a genuinely better fit
 * for Upcoming Arrivals than [LiveVehicleSource] alone: that interface only ever hands back a raw
 * vehicle position, so a caller pairing it with a scheduled arrival has nothing better to do than
 * trust the static schedule time and just mark the row confirmed-live (see [RunAssociatedTripSource]'s
 * own `getvehicles`-based implementation of [LiveVehicleSource]) -- `getpredictions` instead returns
 * the agency's own real predicted time per stop, already accounting for actual delays.
 *
 * Scoped by [stopIds] rather than route -- the natural shape for a screen like Upcoming Arrivals,
 * which already knows exactly which stop_ids it's asking about and has no reason to fetch every
 * route running system-wide the way [LiveVehicleSource.vehiclesByRoute] does for the map.
 *
 * Returned keyed by trip_id (resolved the same way [RunAssociatedTripSource] resolves one for
 * [LiveVehicleSource] -- via [GtfsRepository.tripIdForScheduledStart], since `getpredictions` carries
 * the same `stst`/`stsd` scheduled-start fields `getvehicles` does), value is the raw predicted
 * epoch-seconds instant -- deliberately NOT a full [ArrivalEta] with a baked-in Late/Early/OnTime
 * status: this component only knows the trip's own *first*-stop scheduled time (needed for the
 * trip_id bridge), not the scheduled time at whichever specific stop a caller is actually asking
 * about, so it has no correct basis to compute a diff itself. A caller must diff this predicted
 * instant against its OWN already-known scheduled time for that exact stop (e.g. via
 * [computeArrivalEta]'s own synthetic-update pattern), the same way it already does for a real
 * GTFS-RT match -- computing a fake "67 minutes late" by diffing against the trip's origin time
 * instead was a confirmed live bug from an earlier version of this exact component.
 *
 * A caller missing a trip from the returned map should fall back the same way it already does for a
 * standard TripUpdates miss -- either a [LiveVehicleSource]-confirmed "live, trust the schedule" row,
 * or the plain scheduled time, never a hard requirement.
 */
interface StopPredictionSource : AgencyComponent {
    suspend fun predictionsByStop(stopIds: Set<String>, repository: GtfsRepository, zoneId: java.time.ZoneId): Map<String, Long>

    /**
     * A richer alternative to guessing a vehicle's current stop from GPS proximity (see
     * [matchCurrentStopByProximity]): when a source can be queried directly by its own vehicle
     * identifier (e.g. CTA Bus Tracker's `getpredictions?vid=`), the *first* result it returns is
     * authoritatively that vehicle's next stop -- computed from the agency's own real pattern
     * progress, not straight-line distance, so it's immune to the looping-route failure mode
     * proximity-matching has (a vehicle can be geometrically closer to a stop several positions
     * ahead, before it's actually reached it, if the route loops back near that later stop --
     * confirmed live on a CTA route entering and circling back through downtown). Confirmed live
     * 2026-08-23: `getpredictions?vid=X` returns a real vehicle's entire remaining trip, correctly
     * ordered, each stop carrying its own real predicted time -- only the first entry is used here.
     *
     * [vehicleId] comes from [LiveVehicleInfo.vehicleId] -- a caller only reaches for this once it
     * already has a live vehicle match, never as a first lookup. Returns null when the source has no
     * vehicle-scoped query capability (the default, so [LiveVehicleSource] implementations that
     * don't also implement this don't need to override it) or when this specific vehicle currently
     * has no predictions (e.g. near a short-turn or route end) -- either way, a caller falls back to
     * whatever position-based resolution it already has, same "never force a link" rule every other
     * live source in this app follows.
     */
    suspend fun nextStopForVehicle(vehicleId: String, repository: GtfsRepository, zoneId: java.time.ZoneId): VehicleNextStop? = null
}

/** See [StopPredictionSource.nextStopForVehicle]. */
data class VehicleNextStop(val stopId: String, val predictedEpochSeconds: Long)
