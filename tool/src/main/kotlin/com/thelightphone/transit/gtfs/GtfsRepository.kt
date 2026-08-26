package com.thelightphone.transit.gtfs

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

data class RouteOption(
    val routeId: String,
    val shortName: String?,
    val longName: String?,
    val routeType: Int,
) {
    val displayName: String
        get() = when {
            !shortName.isNullOrBlank() && !longName.isNullOrBlank() -> "$shortName - $longName"
            !shortName.isNullOrBlank() -> shortName
            !longName.isNullOrBlank() -> longName
            else -> routeId
        }
}

/**
 * Groups GTFS's numeric `route_type` into the categories riders think in. Type 0 (tram/light
 * rail, e.g. MBTA's Green Line) is bucketed with Subway rather than broken out separately, since
 * that's how riders colloquially refer to it.
 */
enum class LineType(val gtfsRouteTypes: Set<Int>, val label: String, val emoji: String) {
    SUBWAY(setOf(0, 1), "Subway", "🚇"),
    COMMUTER_RAIL(setOf(2), "Commuter Rail", "🚆"),
    BUS(setOf(3), "Bus", "🚌"),
    FERRY(setOf(4), "Ferry", "⛴️");

    companion object {
        fun forGtfsRouteType(routeType: Int): LineType? = entries.find { routeType in it.gtfsRouteTypes }
    }
}

data class DirectionOption(
    val directionId: Int?,
    val headsign: String?,
    /** e.g. "Inbound"/"Outbound"/"Northbound" -- from the feed's optional directions.txt (an
     * MBTA-originated GTFS extension most agencies don't publish), the authoritative rider-facing
     * word for this direction, since direction_id itself is just a binary flag with no fixed
     * meaning across agencies. Null when the feed doesn't publish the file; [displayLabel] then
     * falls back to the representative headsign. */
    val directionName: String? = null,
    /** This direction's curated destination name from the same directions.txt row as
     * [directionName] (e.g. "Ashmont/Braintree"), distinct from [headsign], which is just
     * whichever headsign is most common among this direction's trips. Null under the same
     * conditions as [directionName]. */
    val destination: String? = null,
    /** The real stop_id every trip in this group actually ends at, used ONLY as a grouping/matching
     * fallback for an agency with no [headsign] at all (e.g. CTA) -- see [getDirections]'s own doc.
     * Always null when [headsign] is non-null; a headsign-having agency already groups/matches by
     * the real column, so this never needs to kick in. Distinct from [lastStopName] (the display
     * text) because downstream stop/departure matching needs the real id, not the label -- see
     * [getStopsForVariant]'s own doc for why conflating the two would silently match zero trips. */
    val lastStopId: String? = null,
    /** [lastStopId]'s own stop_name -- e.g. "Navy Pier Terminal" for CTA Route 124's eastbound
     * group -- purely for display ("Toward Navy Pier Terminal"), see [rowLabel]. */
    val lastStopName: String? = null,
)

data class StopOption(
    val stopId: String,
    val stopName: String?,
    val lat: Double? = null,
    val lon: Double? = null,
)

data class StopWithDistance(
    val stopId: String,
    val stopName: String?,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Double,
    /** See [StopLocation.memberStopIds]. */
    val memberStopIds: List<String>,
    /** See [StopLocation.isStation]. */
    val isStation: Boolean,
)

data class Departure(
    val tripId: String,
    val departureTime: String,
    val headsign: String?,
    val stopSequence: Int,
)

data class TripStopRow(
    val stopSequence: Int,
    val stopId: String,
    val stopName: String?,
    val arrivalTime: String?,
    val departureTime: String?,
)

data class StopConnection(
    val tripId: String,
    val stopSequence: Int,
    val departureTime: String,
    /** This connection's own platform within the station, only populated when the query spanned
     * an actual multi-platform grouped station (e.g. "Track 1", "Ashmont/Braintree") -- see
     * GtfsRepository.getNextConnections(stopIds: List<String>, ...). Null for a plain stop. */
    val platformLabel: String?,
    val route: RouteOption,
    val direction: DirectionOption,
)

data class StopLocation(
    val stopId: String,
    val stopName: String?,
    val lat: Double,
    val lon: Double,
    /**
     * The real, queryable stop_id(s) this location represents: its own id for a plain standalone
     * stop, or every child platform/entrance stop_id grouped under it for a deduplicated GTFS
     * station (see [groupStationsByParent]) -- stations themselves typically have no stop_times of
     * their own, so lookups need a real child id, not the station's.
     */
    val memberStopIds: List<String>,
    /**
     * True only when [stopId] is a real GTFS Station record (`location_type=1`) with 2 or more
     * child platforms grouped under it via `parent_station` -- see [groupStationsByParent]. A stop
     * where routes merely happen to converge, with no such parent record, does not qualify. Powers
     * the Station sub-map feature (transfer icon, double-tap-to-zoom on the Map screen).
     */
    val isStation: Boolean,
)

/** The one line of required-by-convention attribution for wherever this agency's GTFS data came
 * from -- see [GtfsRepository.getFeedAttribution]'s own doc for the fallback chain that produces
 * this. [url] is informational only today (no screen renders it as a tappable link). */
data class FeedAttribution(val name: String, val url: String?)

data class ScheduledArrival(
    val tripId: String,
    /** The specific child platform stop_id this arrival was actually found at -- for a grouped
     * multi-platform station (see [groupStationsByParent]) this may differ between rows even
     * though they're all the same station to the rider. Live/RT lookups must match against this,
     * not whichever stop_id the caller originally asked about. */
    val stopId: String,
    val stopSequence: Int,
    val departureTime: String,
    val route: RouteOption,
    val direction: DirectionOption,
    /** This platform's own identifying label within a multi-platform station (e.g. "Track 1"),
     * derived from the child stop's stop_desc -- see [platformLabelFromStopDesc]. Null for a
     * plain, non-grouped stop lookup, where there's nothing more specific to show. */
    val platformLabel: String? = null,
)

/** See [GtfsRepository.getRoutesForTrips]. */
data class TripRouteInfo(
    val route: RouteOption,
    val direction: DirectionOption,
)

/**
 * Joins in one trip's own real last stop (id + name) as `ls.stop_id`/`ls.stop_name` -- see
 * [DirectionOption]'s own doc for why. Guarded by `t.trip_headsign IS NULL` directly on the first
 * join, not just in the final SELECT list, so a headsign-having trip never even evaluates the
 * correlated MAX(stop_sequence) lookup: for every currently-wired agency except CTA, this join
 * short-circuits on that one column check and costs nothing. Every caller's outer query already
 * joins `trips t` filtered down to a small set of rows (one stop's upcoming departures, a specific
 * trip_id set, etc.), so unlike [GtfsRepository.getDirections]'s own version of this same lookup
 * (which has to explicitly scope to one route to avoid scanning the whole feed), this one can join
 * `stop_times` directly rather than through a route-scoped derived table -- `t.trip_id` is already
 * narrow per outer row, so SQLite can use stop_times' own trip_id index straight through.
 */
private const val LAST_STOP_JOIN = """
    LEFT JOIN stop_times lst ON lst.trip_id = t.trip_id AND t.trip_headsign IS NULL
        AND lst.stop_sequence = (SELECT MAX(st2.stop_sequence) FROM stop_times st2 WHERE st2.trip_id = t.trip_id)
    LEFT JOIN stops ls ON ls.stop_id = lst.stop_id
"""

/**
 * Read-only access to one agency's ingested GTFS SQLite database. Every method issues a single
 * targeted query and returns a small result list — never a full table — so screens query
 * directly instead of holding parsed GTFS data in memory.
 */
class GtfsRepository(dbFile: File) {
    private val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)

    /** Which of [LineType] have at least one route in this feed — e.g. RIPTA has no subway/rail. */
    fun getAvailableLineTypes(): List<LineType> =
        db.rawQuery("SELECT DISTINCT route_type FROM routes", null).use { cursor ->
            val presentTypes = cursor.mapRowsNotNull { getIntOrNull(0) }.toSet()
            LineType.entries.filter { it.gtfsRouteTypes.any { type -> type in presentTypes } }
        }

    fun getRoutes(lineType: LineType): List<RouteOption> {
        val placeholders = lineType.gtfsRouteTypes.joinToString(",") { "?" }
        return db.rawQuery(
            """
            SELECT DISTINCT route_id, route_short_name, route_long_name, route_type
            FROM routes
            WHERE route_type IN ($placeholders)
            ORDER BY route_id
            """,
            lineType.gtfsRouteTypes.map { it.toString() }.toTypedArray(),
        ).use { cursor ->
            cursor.mapRows {
                RouteOption(
                    routeId = getString(0),
                    shortName = getStringOrNull(1),
                    longName = getStringOrNull(2),
                    routeType = getInt(3),
                )
            }
        }
    }

    
/**
     * One entry per distinct (direction_id, trip_headsign) pair actually running on this route, so
     * every real headsign variant stays individually selectable. A route with branch or short-turn
     * trips can have several distinct headsigns within one direction_id -- e.g. LTC's Route 01
     * splits into "1A Pond Mills"/"1B King Edward" nearly 50/50, and RIPTA's Route 20 has three
     * genuinely different termini. Collapsing those to a single "most common" entry would hide real
     * destinations riders need to tell apart.
     *
     * Each entry also carries the feed's optional directions.txt data (direction, destination) when
     * published (MBTA does; most agencies don't) -- [DirectionSelectionScreen] uses that to group
     * same-direction_id entries under a real "Inbound"/"Outbound" header when available, without
     * ever hiding a variant.
     *
     * A trip with no headsign at all (e.g. every CTA trip) has nothing to split branches by, which
     * would flatten real branch/short-turn distinctions the same way a headsign collapse would.
     * [lastStopId]/[lastStopName] fill that gap instead -- e.g. CTA Route 124's two directions each
     * terminate at a real, consistent stop ("Clinton & Quincy" westbound, "Navy Pier Terminal"
     * eastbound) -- and are only populated when trip_headsign is null.
     */
    fun getDirections(routeId: String): List<DirectionOption> =
        // LEFT JOINed on directions.txt's own documented key (route_id, direction_id) -- every
        // trip_headsign variant within a direction_id carries the same joined direction/
        // destination, since that join key doesn't depend on headsign at all.
        //
        // last_stop is scoped to this route's own trips (via its own trips join, not a bare
        // stop_times/stops join), so the correlated MAX(stop_sequence) subquery only runs over one
        // route's trips rather than the whole feed's stop_times table -- matters on an agency the
        // size of CTA's (~6M stop_times rows).
        db.rawQuery(
            """
            SELECT DISTINCT t.direction_id, t.trip_headsign, d.direction, d.direction_destination,
                CASE WHEN t.trip_headsign IS NULL THEN ls.stop_id END,
                CASE WHEN t.trip_headsign IS NULL THEN ls.stop_name END
            FROM trips t
            LEFT JOIN directions d ON d.route_id = t.route_id AND d.direction_id = t.direction_id
            LEFT JOIN (
                SELECT st.trip_id, s.stop_id, s.stop_name
                FROM stop_times st
                JOIN stops s ON s.stop_id = st.stop_id
                JOIN trips rt ON rt.trip_id = st.trip_id AND rt.route_id = ?
                WHERE st.stop_sequence = (SELECT MAX(st2.stop_sequence) FROM stop_times st2 WHERE st2.trip_id = st.trip_id)
            ) ls ON ls.trip_id = t.trip_id
            WHERE t.route_id = ? AND t.direction_id IS NOT NULL
            ORDER BY t.direction_id, t.trip_headsign, ls.stop_id
            """,
            arrayOf(routeId, routeId),
        ).use { cursor ->
            cursor.mapRows {
                DirectionOption(
                    directionId = getInt(0),
                    headsign = getStringOrNull(1),
                    directionName = getStringOrNull(2),
                    destination = getStringOrNull(3),
                    lastStopId = getStringOrNull(4),
                    lastStopName = getStringOrNull(5),
                )
            }
        }

    
/** [getDirections] returning empty is ambiguous on its own: either every trip on this route has a
     * null direction_id (a loop route with no meaningful "direction" -- skip straight to stop
     * selection), or the route has no trips scheduled at all (confirmed to genuinely happen: LTC's
     * feed publishes some routes with zero active trips -- skipping ahead would land on a dead-end
     * "Nothing found" screen). Callers use this to tell the two apart before deciding whether to
     * auto-skip. */
    fun routeHasTrips(routeId: String): Boolean =
        db.rawQuery("SELECT 1 FROM trips WHERE route_id = ? LIMIT 1", arrayOf(routeId)).use { it.moveToFirst() }

    
/**
     * Every distinct stop served by any trip on [routeId]+[directionId] with at least one
     * departure remaining today (calendar-active on [today] per [activeTodayClause], departing at
     * or after [afterTime]), ordered by each stop's earliest stop_sequence -- an approximation of
     * physical route order, since GTFS doesn't guarantee identical numbering across trip variants.
     * A stop with no remaining/today service is excluded outright rather than shown with "No
     * departures today". Used only for the auto-skip case where every trip has a null direction_id
     * (see [routeHasTrips]); a real chosen direction goes through [getStopsForVariant] instead,
     * which also narrows by headsign.
     */
    fun getStops(routeId: String, directionId: Int?, afterTime: String, today: LocalDate): List<StopOption> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val yesterday = today.minusDays(1)
        val yesterdayGtfs = yesterday.toGtfsDateString()
        val yesterdayDayColumn = yesterday.dayOfWeek.toGtfsColumnName()
        val directionClause = if (directionId == null) "t.direction_id IS NULL" else "t.direction_id = ?"
        val args = buildList {
            add(routeId)
            directionId?.let { add(it.toString()) }
            add(afterTime)
            addAll(listOf(todayGtfs, todayGtfs, todayGtfs))
            add(shiftedToNextDay(afterTime))
            addAll(listOf(yesterdayGtfs, yesterdayGtfs, yesterdayGtfs))
        }.toTypedArray()
        return db.rawQuery(
            """
            SELECT st.stop_id, s.stop_name, s.stop_lat, s.stop_lon
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE t.route_id = ? AND $directionClause AND ${activeTransitDayClause(dayColumn, yesterdayDayColumn)}
            GROUP BY st.stop_id, s.stop_name, s.stop_lat, s.stop_lon
            ORDER BY MIN(st.stop_sequence)
            """,
            args,
        ).use { cursor ->
            cursor.mapRows {
                StopOption(
                    stopId = getString(0),
                    stopName = getStringOrNull(1),
                    lat = getDoubleOrNull(2),
                    lon = getDoubleOrNull(3),
                )
            }
        }
    }

    
/**
     * Same as [getStops], further narrowed to only trips carrying the exact [headsign] of the
     * direction variant a rider picked in [DirectionSelectionScreen]. MBTA's Franklin/Foxboro Line,
     * for example, runs most inbound trips all the way to "South Station" but short-turns a handful
     * as "Readville" under the same direction_id -- without this narrowing, "Toward Readville" would
     * list stops all the way to South Station. Matched via `IS` rather than `=` so a genuinely
     * blank/absent headsign, a real distinct [DirectionOption] (see [getDirections]), still matches.
     *
     * Deliberately an exact headsign match, not [getDeparturesForVariant]'s broader "reaches at
     * least this far" inclusion -- this list should promise only what the exact chosen variant
     * guarantees today. Same "no departures today" exclusion as [getStops].
     *
     * [lastStopId] (see [DirectionOption]'s own doc) plays [headsign]'s exact same narrowing role
     * for an agency with no headsign at all -- always null whenever [headsign] is non-null, so
     * exactly one of the three [variantClause] branches below is ever live for a given call.
     */
    fun getStopsForVariant(routeId: String, directionId: Int, headsign: String?, lastStopId: String?, afterTime: String, today: LocalDate): List<StopOption> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val yesterday = today.minusDays(1)
        val yesterdayGtfs = yesterday.toGtfsDateString()
        val yesterdayDayColumn = yesterday.dayOfWeek.toGtfsColumnName()
        // Same reasoning as getStops's own directionClause -- Android's rawQuery(sql, String[])
        // throws IllegalArgumentException on a null array element (confirmed live: CTA's trips have
        // no trip_headsign at all, so this is null on every real call for it), so a null value
        // needs its own no-bind-arg clause rather than trying to pass null through "IS ?"/"= ?".
        val variantClause = when {
            headsign != null -> "t.trip_headsign = ?"
            // A trip's own real last stop stands in for headsign -- see getDirections's own doc.
            lastStopId != null -> """
                t.trip_id IN (
                    SELECT st2.trip_id FROM stop_times st2
                    WHERE st2.stop_id = ?
                      AND st2.stop_sequence = (SELECT MAX(st3.stop_sequence) FROM stop_times st3 WHERE st3.trip_id = st2.trip_id)
                )
            """.trimIndent()
            else -> "t.trip_headsign IS NULL"
        }
        val args = buildList {
            add(routeId)
            add(directionId.toString())
            headsign?.let { add(it) } ?: lastStopId?.let { add(it) }
            add(afterTime)
            addAll(listOf(todayGtfs, todayGtfs, todayGtfs))
            add(shiftedToNextDay(afterTime))
            addAll(listOf(yesterdayGtfs, yesterdayGtfs, yesterdayGtfs))
        }.toTypedArray()
        return db.rawQuery(
            """
            SELECT st.stop_id, s.stop_name, s.stop_lat, s.stop_lon
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE t.route_id = ? AND t.direction_id = ? AND $variantClause AND ${activeTransitDayClause(dayColumn, yesterdayDayColumn)}
            GROUP BY st.stop_id, s.stop_name, s.stop_lat, s.stop_lon
            ORDER BY MIN(st.stop_sequence)
            """,
            args,
        ).use { cursor ->
            cursor.mapRows {
                StopOption(
                    stopId = getString(0),
                    stopName = getStringOrNull(1),
                    lat = getDoubleOrNull(2),
                    lon = getDoubleOrNull(3),
                )
            }
        }
    }

    
/**
     * Departures for [stopId] on [routeId]+[directionId], restricted to trips whose service_id is
     * active on [today]: scheduled per `calendar` (weekday + date range), minus any
     * `calendar_dates` removal (exception_type 2), plus any addition (exception_type 1) regardless
     * of the `calendar` row.
     *
     * [stopId] may occur anywhere in a trip's stop sequence now that stop selection isn't
     * restricted to termini. Each result carries the matched stop_sequence so trip detail can
     * filter to "from this stop onward". Used only for the auto-skip case (see [getStops]); a real
     * chosen direction goes through [getDeparturesForVariant] instead.
     */
    fun getDepartures(routeId: String, directionId: Int?, stopId: String, today: LocalDate): List<Departure> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()

        val directionClause = if (directionId == null) "t.direction_id IS NULL" else "t.direction_id = ?"
        val sql = """
            SELECT st.departure_time, t.trip_id, t.trip_headsign, st.stop_sequence
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            WHERE t.route_id = ? AND $directionClause AND st.stop_id = ?
              AND ${activeTodayClause(dayColumn)}
            ORDER BY st.departure_time
        """.trimIndent()

        val args = if (directionId == null) {
            arrayOf(routeId, stopId, todayGtfs, todayGtfs, todayGtfs)
        } else {
            arrayOf(routeId, directionId.toString(), stopId, todayGtfs, todayGtfs, todayGtfs)
        }
        return db.rawQuery(
            sql,
            args,
        ).use { cursor ->
            cursor.mapRows {
                Departure(
                    departureTime = getString(0),
                    tripId = getString(1),
                    headsign = getStringOrNull(2),
                    stopSequence = getInt(3),
                )
            }
        }
    }

    /**
     * Same as [getDepartures], but for [stopId] on the direction variant a rider actually picked,
     * and deliberately not narrowed to trips with that exact [headsign]. A candidate trip qualifies
     * if it covers at least one complete real trip pattern among the chosen variant's own trips
     * that itself reaches [stopId], so it's included whenever it's known to run at least as far as
     * some real "H"-labeled run gets from here, whether or not it continues further.
     *
     * Deliberately not the simpler "union of every stop any H-labeled trip visits": a single
     * headsign string doesn't always correspond to one consistent physical path. RIPTA's Route 20
     * outbound "Kennedy Plaza via Elmwood Ave" covers three distinct real patterns (30, 46, and 50
     * stops) that happen to share a headsign. Their union is a 51-stop path no single real trip,
     * not even another "Kennedy Plaza" trip, actually runs, so requiring full coverage of that
     * union matched nothing at all and silently emptied the departures list. Anchoring the
     * comparison to one real reference trip that itself reaches the stop being viewed avoids that
     * trap: every "H"-labeled trip trivially covers itself, so the chosen variant's own departures
     * always qualify at any stop it actually serves, regardless of how many different real patterns
     * share its headsign.
     *
     * This is deliberately asymmetric, matching how these variants actually relate to each other.
     * MBTA's Franklin/Foxboro "Readville" trips are a strict subset of "South Station" trips, same
     * corridor, shorter run, so a rider who picked "Toward Readville" also sees "South Station"
     * departures at a shared stop like Franklin, since either one gets them to Readville and hiding
     * the extra option would be needlessly conservative. But a rider who picked "Toward South
     * Station" never sees a "Readville" departure sneak in, since a Readville trip doesn't cover
     * South Station's full stop set, and boarding one under a false assumption would strand them
     * early. Same real relationship on RIPTA's Route 20: "TF Green Airport" trips are a subset of
     * "New England Tech" trips (confirmed against real stop data), so TF-Green riders also see NEIT
     * departures, but NEIT riders never see a TF-Green-only trip that stops short of NEIT. "Job Lot"
     * trips aren't a subset or superset of either, a genuinely different branch rather than a
     * shorter or longer version of the same run, so it stays fully isolated from both with no
     * agency-specific logic needed to keep it that way; the containment check alone does it.
     *
     * Gated behind [DeparturePreferences.includeLongerTripsEnabledFlow] (on by default). When a
     * rider turns it off, callers use [getDeparturesForExactVariant] instead, which drops back to
     * an exact-headsign match with none of the above.
     *
     * The expensive relational-containment check only ever runs over trips carrying a different
     * headsign than [headsign], since a same-headsign trip trivially covers itself and is included
     * directly via the cheap `t.trip_headsign IS ?` branch below, without ever entering the
     * containment check at all. This matters a lot in practice: on a high-frequency subway line
     * where one headsign covers virtually the whole direction (confirmed on MBTA's Blue Line, 714
     * of 722 trips this direction), excluding those 714 self-matching trips from the candidate pool
     * cut real measured query time from about 3.7s to about 0.04s for an identical result set,
     * since the earlier version cross-joined every one of them against every reference trip
     * needlessly.
     */
    fun getDeparturesForVariant(routeId: String, directionId: Int, headsign: String?, lastStopId: String?, stopId: String, today: LocalDate): List<Departure> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        // Same reasoning as getStops's own directionClause -- Android's rawQuery(sql, String[])
        // throws IllegalArgumentException on a null array element (confirmed live: CTA's trips have
        // no trip_headsign at all), so a null value needs its own no-bind-arg clause rather than
        // trying to pass null through "IS ?"/"IS NOT ?". [lastStopId] (see [DirectionOption]'s own
        // doc) plays [headsign]'s exact same role here, including in this "reaches at least as far"
        // containment check -- a shorter-running trip's own real last stop is a fine substitute for
        // "which variant is this" the same way its headsign would be.
        val variantClause = when {
            headsign != null -> "t.trip_headsign = ?"
            lastStopId != null -> """
                t.trip_id IN (
                    SELECT st2.trip_id FROM stop_times st2
                    WHERE st2.stop_id = ?
                      AND st2.stop_sequence = (SELECT MAX(st3.stop_sequence) FROM stop_times st3 WHERE st3.trip_id = st2.trip_id)
                )
            """.trimIndent()
            else -> "t.trip_headsign IS NULL"
        }
        val notVariantClause = when {
            headsign != null -> "t.trip_headsign IS NOT ?"
            lastStopId != null -> """
                t.trip_id NOT IN (
                    SELECT st2.trip_id FROM stop_times st2
                    WHERE st2.stop_id = ?
                      AND st2.stop_sequence = (SELECT MAX(st3.stop_sequence) FROM stop_times st3 WHERE st3.trip_id = st2.trip_id)
                )
            """.trimIndent()
            else -> "t.trip_headsign IS NOT NULL"
        }
        val variantArg: String? = headsign ?: lastStopId
        val sql = """
            WITH h_trips_at_stop AS (
                SELECT DISTINCT t.trip_id
                FROM trips t
                JOIN stop_times st ON st.trip_id = t.trip_id
                WHERE t.route_id = ? AND t.direction_id = ? AND $variantClause AND st.stop_id = ?
            ),
            other_candidate_trips AS (
                SELECT DISTINCT t.trip_id
                FROM trips t
                JOIN stop_times st ON st.trip_id = t.trip_id
                WHERE t.route_id = ? AND t.direction_id = ? AND st.stop_id = ? AND $notVariantClause
            ),
            qualifying_other_trips AS (
                -- A candidate (already known to be a DIFFERENT variant -- see
                -- other_candidate_trips) qualifies if, for at least one reference trip that itself
                -- reaches this stop under the chosen variant, the candidate visits every stop that
                -- reference trip visits (relational containment, not a raw row/stop count).
                SELECT DISTINCT c.trip_id
                FROM other_candidate_trips c, h_trips_at_stop h
                WHERE NOT EXISTS (
                    SELECT 1 FROM stop_times hs
                    WHERE hs.trip_id = h.trip_id
                      AND NOT EXISTS (
                          SELECT 1 FROM stop_times cs WHERE cs.trip_id = c.trip_id AND cs.stop_id = hs.stop_id
                      )
                )
            )
            SELECT st.departure_time, t.trip_id, t.trip_headsign, st.stop_sequence
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            WHERE t.route_id = ? AND t.direction_id = ? AND st.stop_id = ?
              AND ($variantClause OR t.trip_id IN (SELECT trip_id FROM qualifying_other_trips))
              AND ${activeTodayClause(dayColumn)}
            ORDER BY st.departure_time
        """.trimIndent()
        val args = buildList {
            add(routeId); add(directionId.toString()); variantArg?.let { add(it) }; add(stopId)
            add(routeId); add(directionId.toString()); add(stopId); variantArg?.let { add(it) }
            add(routeId); add(directionId.toString()); add(stopId); variantArg?.let { add(it) }
            addAll(listOf(todayGtfs, todayGtfs, todayGtfs))
        }.toTypedArray()
        return db.rawQuery(sql, args).use { cursor ->
            cursor.mapRows {
                Departure(
                    departureTime = getString(0),
                    tripId = getString(1),
                    headsign = getStringOrNull(2),
                    stopSequence = getInt(3),
                )
            }
        }
    }

    /**
     * The strict counterpart to [getDeparturesForVariant] -- an exact match on [headsign], with
     * none of that function's "reaches at least this far" inclusion. Used when
     * [DeparturePreferences.includeLongerTripsEnabledFlow] is off: picking "Toward Readville" then
     * shows only Readville-headsign trips, never the longer "South Station" ones that happen to
     * reach Readville along the way.
     */
    fun getDeparturesForExactVariant(routeId: String, directionId: Int, headsign: String?, lastStopId: String?, stopId: String, today: LocalDate): List<Departure> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        // Same reasoning as getStops's own directionClause -- see getStopsForVariant's own doc.
        // [lastStopId] plays [headsign]'s exact same role here too.
        val variantClause = when {
            headsign != null -> "t.trip_headsign = ?"
            lastStopId != null -> """
                t.trip_id IN (
                    SELECT st2.trip_id FROM stop_times st2
                    WHERE st2.stop_id = ?
                      AND st2.stop_sequence = (SELECT MAX(st3.stop_sequence) FROM stop_times st3 WHERE st3.trip_id = st2.trip_id)
                )
            """.trimIndent()
            else -> "t.trip_headsign IS NULL"
        }
        val args = buildList {
            add(routeId); add(directionId.toString()); (headsign ?: lastStopId)?.let { add(it) }; add(stopId)
            addAll(listOf(todayGtfs, todayGtfs, todayGtfs))
        }.toTypedArray()
        return db.rawQuery(
            """
            SELECT st.departure_time, t.trip_id, t.trip_headsign, st.stop_sequence
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            WHERE t.route_id = ? AND t.direction_id = ? AND $variantClause AND st.stop_id = ?
              AND ${activeTodayClause(dayColumn)}
            ORDER BY st.departure_time
            """.trimIndent(),
            args,
        ).use { cursor ->
            cursor.mapRows {
                Departure(
                    departureTime = getString(0),
                    tripId = getString(1),
                    headsign = getStringOrNull(2),
                    stopSequence = getInt(3),
                )
            }
        }
    }

    /**
     * The single scheduled trip on [routeId] whose FIRST stop_time departs at exactly [startTime],
     * active on [serviceDate] -- the standard GTFS-RT way to identify a trip when a live source
     * hands back (route, start_date, start_time) instead of a trip_id directly, e.g. CTA Bus
     * Tracker's own `stsd`/`stst` fields (see [RunAssociatedTripSource]'s own doc). Null if zero or
     * more than one trip matches -- an ambiguous match is left unresolved rather than guessed at,
     * so a caller just doesn't show a live position for that vehicle this poll rather than ever
     * linking it to the wrong trip.
     */
    fun tripIdForScheduledStart(routeId: String, startTime: String, serviceDate: LocalDate): String? {
        val serviceDateGtfs = serviceDate.toGtfsDateString()
        val dayColumn = serviceDate.dayOfWeek.toGtfsColumnName()
        val tripIds = db.rawQuery(
            """
            SELECT t.trip_id
            FROM trips t
            JOIN (
                SELECT trip_id, departure_time, MIN(stop_sequence) AS first_seq
                FROM stop_times
                GROUP BY trip_id
            ) first ON first.trip_id = t.trip_id
            WHERE t.route_id = ? AND first.departure_time = ?
              AND ${activeTodayClause(dayColumn)}
            """.trimIndent(),
            arrayOf(routeId, startTime, serviceDateGtfs, serviceDateGtfs, serviceDateGtfs),
        ).use { cursor -> cursor.mapRows { getString(0) } }
        return tripIds.singleOrNull()
    }

    /** A trip's route_type, for picking its live-vehicle emoji (see [LineType]) on the Trip Detail
     * screen -- a trip belongs to exactly one route, so this is a single-value lookup, not a list. */
    fun getRouteTypeForTrip(tripId: String): Int? =
        db.rawQuery(
            "SELECT r.route_type FROM trips t JOIN routes r ON r.route_id = t.route_id WHERE t.trip_id = ?",
            arrayOf(tripId),
        ).use { cursor ->
            cursor.mapRows { getInt(0) }.firstOrNull()
        }

    fun getTripStops(tripId: String, fromStopSequence: Int): List<TripStopRow> =
        db.rawQuery(
            """
            SELECT st.stop_sequence, st.stop_id, s.stop_name, st.arrival_time, st.departure_time
            FROM stop_times st
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE st.trip_id = ? AND st.stop_sequence >= ?
            ORDER BY st.stop_sequence
            """,
            arrayOf(tripId, fromStopSequence.toString()),
        ).use { cursor ->
            cursor.mapRows {
                TripStopRow(
                    stopSequence = getInt(0),
                    stopId = getString(1),
                    stopName = getStringOrNull(2),
                    arrivalTime = getStringOrNull(3),
                    departureTime = getStringOrNull(4),
                )
            }
        }

    /**
     * stop_lat/stop_lon for every stop on a trip from [fromStopSequence] onward, keyed by stop_id --
     * paired with [getTripStops]'s identically-scoped query to support GPS-proximity current-stop
     * inference (see [matchCurrentStopByProximity]) for agencies whose VehiclePositions never
     * populates current_stop_sequence (RIPTA, confirmed empirically).
     */
    fun getTripStopLocations(tripId: String, fromStopSequence: Int): Map<String, Pair<Double, Double>> =
        db.rawQuery(
            """
            SELECT st.stop_id, s.stop_lat, s.stop_lon
            FROM stop_times st
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE st.trip_id = ? AND st.stop_sequence >= ?
            """,
            arrayOf(tripId, fromStopSequence.toString()),
        ).use { cursor ->
            cursor.mapRows { getString(0) to (getDouble(1) to getDouble(2)) }.toMap()
        }

    /**
     * The next scheduled departures across every id in [stopIds] after [afterTime], across every
     * route and direction serving those stops, not just [excludeTripId]'s own -- used to show
     * connecting service from a stop selected on a trip's detail screen. When [stopIds] is a real
     * station's full [StopLocation.memberStopIds] (see [getStationContaining]), this unions every
     * platform's schedule the same way [getScheduledArrivals]'s list overload does, tagging each
     * result with its platform ([StopConnection.platformLabel]) when there's more than one entry.
     * Restricted to trips active on [today], same calendar logic as [getDepartures].
     */
    fun getNextConnections(
        stopIds: List<String>,
        afterTime: String,
        excludeTripId: String,
        today: LocalDate,
    ): List<StopConnection> {
        if (stopIds.isEmpty()) return emptyList()
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val yesterday = today.minusDays(1)
        val yesterdayGtfs = yesterday.toGtfsDateString()
        val yesterdayDayColumn = yesterday.dayOfWeek.toGtfsColumnName()
        val placeholders = stopIds.joinToString(",") { "?" }
        val isGrouped = stopIds.size > 1

        val sql = """
            SELECT st.departure_time, t.trip_id, st.stop_sequence, s.stop_desc,
                   r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign, d.direction, d.direction_destination, ls.stop_id, ls.stop_name
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN routes r ON r.route_id = t.route_id
            JOIN stops s ON s.stop_id = st.stop_id
            LEFT JOIN directions d ON d.route_id = t.route_id AND d.direction_id = t.direction_id
            $LAST_STOP_JOIN
            WHERE st.stop_id IN ($placeholders) AND t.trip_id != ?
              AND ${activeTransitDayClause(dayColumn, yesterdayDayColumn, comparison = ">")}
            ORDER BY st.departure_time
        """.trimIndent()

        return db.rawQuery(
            sql,
            (stopIds + listOf(
                excludeTripId,
                afterTime, todayGtfs, todayGtfs, todayGtfs,
                shiftedToNextDay(afterTime), yesterdayGtfs, yesterdayGtfs, yesterdayGtfs,
            )).toTypedArray(),
        ).use { cursor ->
            cursor.mapRowsNotNull {
                val directionId = getIntOrNull(8) ?: return@mapRowsNotNull null
                StopConnection(
                    departureTime = getString(0),
                    tripId = getString(1),
                    stopSequence = getInt(2),
                    platformLabel = if (isGrouped) platformLabelFromStopDesc(getStringOrNull(3)) else null,
                    route = RouteOption(
                        routeId = getString(4),
                        shortName = getStringOrNull(5),
                        longName = getStringOrNull(6),
                        routeType = getInt(7),
                    ),
                    direction = DirectionOption(
                        directionId, getStringOrNull(9), directionName = getStringOrNull(10), destination = getStringOrNull(11),
                        lastStopId = getStringOrNull(12), lastStopName = getStringOrNull(13),
                    ),
                )
            }
        }
    }

    /**
     * Every stop with valid coordinates, for nearest-stop distance ranking -- deduplicated per GTFS
     * station grouping (see [groupStationsByParent]), shared by [rankStopsByDistance] (stop search)
     * and [getStopsWithinRadius] (Map markers) so a station with several platform stop_ids appears
     * once, not once per platform.
     */
    fun getStopsWithLocation(): List<StopLocation> =
        db.rawQuery(
            "SELECT stop_id, stop_name, stop_lat, stop_lon, parent_station, location_type FROM stops " +
                "WHERE stop_lat IS NOT NULL AND stop_lon IS NOT NULL",
            null,
        ).use { cursor ->
            val rows = cursor.mapRows {
                RawStopRow(
                    stopId = getString(0),
                    stopName = getStringOrNull(1),
                    lat = getDouble(2),
                    lon = getDouble(3),
                    parentStation = getStringOrNull(4),
                    locationType = getIntOrNull(5),
                )
            }
            groupStationsByParent(rows)
        }

    /** A single stop's coordinates, used for bearing and distance math. Looked up directly by id and
     * not deduplicated, since callers already have a specific, resolved stop_id in hand, such as
     * the one a schedule or arrival was looked up for. */
    fun getStopLocation(stopId: String): StopLocation? =
        db.rawQuery(
            "SELECT stop_id, stop_name, stop_lat, stop_lon FROM stops WHERE stop_id = ? AND stop_lat IS NOT NULL AND stop_lon IS NOT NULL",
            arrayOf(stopId),
        ).use { cursor ->
            cursor.mapRows {
                StopLocation(
                    stopId = getString(0),
                    stopName = getStringOrNull(1),
                    lat = getDouble(2),
                    lon = getDouble(3),
                    memberStopIds = listOf(getString(0)),
                    isStation = false,
                )
            }.firstOrNull()
        }

    /**
     * Resolves [stopId] to its full station group if it belongs to one, whether [stopId] is the
     * station's own representative id or one of its member platforms. Used by the Map screen's
     * double-tap-to-open-Station gesture so it behaves identically for the centered stop or a
     * nearby one. Null if [stopId] isn't part of any qualifying station.
     */
    fun getStationContaining(stopId: String): StopLocation? =
        getStopsWithLocation().firstOrNull { it.isStation && (it.stopId == stopId || stopId in it.memberStopIds) }

    /**
     * Every stop_id that's part of a real, qualifying multi-platform station: the station's own
     * representative id plus every child platform id (see [StopLocation.isStation]). Computed once
     * from the same grouping used everywhere else, so a caller checking many stop_ids (e.g. Trip
     * Detail's stop list) can test cheap set membership instead of re-querying per row.
     */
    fun getMultiPlatformStationStopIds(): Set<String> =
        getStopsWithLocation().filter { it.isStation }.flatMapTo(mutableSetOf()) { it.memberStopIds + it.stopId }

    /** Every real, qualifying multi-platform station this agency has (see [StopLocation.isStation]),
     * alphabetically by name -- powers the HomeScreen's direct "Station" browse list, which lists
     * every station up front rather than asking the rider to search a location first. */
    fun getAllStations(): List<StopLocation> =
        getStopsWithLocation().filter { it.isStation }.sortedBy { it.stopName ?: it.stopId }

    /**
     * Attribution for wherever this agency's GTFS feed says it actually came from -- prefers
     * feed_info.txt's own `feed_publisher_name`/`_url`, falling back to agency.txt's first row
     * (required by the GTFS spec, so every feed has at least this) if feed_info.txt is omitted.
     * Null only if a feed has neither -- shouldn't happen for any agency this app supports today,
     * but a screen showing this should treat null as "say nothing" rather than falling back to
     * this app's own hardcoded [GtfsAgency.displayName], since that's this app's label, not a
     * claim about who published the data.
     */
    fun getFeedAttribution(): FeedAttribution? {
        db.rawQuery("SELECT feed_publisher_name, feed_publisher_url FROM feed_info LIMIT 1", null).use { cursor ->
            cursor.mapRows { FeedAttribution(getString(0), getStringOrNull(1)) }.firstOrNull()?.let { return it }
        }
        return db.rawQuery("SELECT agency_name, agency_url FROM agency LIMIT 1", null).use { cursor ->
            cursor.mapRows { FeedAttribution(getString(0), getStringOrNull(1)) }.firstOrNull()
        }
    }

    /** stop_desc for every given stop_id, keyed by stop_id -- used to derive each platform's own
     * label within a station (see [platformLabelFromStopDesc]) for screens that already have
     * platform stop_ids in hand, rather than going through the unioned arrivals query that already
     * carries this (see the other [getScheduledArrivals] overload). */
    fun getStopDescriptions(stopIds: List<String>): Map<String, String?> {
        if (stopIds.isEmpty()) return emptyMap()
        val placeholders = stopIds.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT stop_id, stop_desc FROM stops WHERE stop_id IN ($placeholders)",
            stopIds.toTypedArray(),
        ).use { cursor -> cursor.mapRows { getString(0) to getStringOrNull(1) }.toMap() }
    }

    /**
     * Every route+direction scheduled to serve [stopId] at or after [afterTime] today (minus
     * [graceSeconds], if given), across every route, active-today-filtered the same way as
     * [getDepartures]. This is the static half of the "Leave Now" upcoming-arrivals screen; the
     * caller merges it with GTFS-RT predictions where available.
     *
     * [graceSeconds] exists for callers that keep polling live vehicle data against this same
     * candidate list over time (see MapScreen's SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS): a plain
     * `departure_time >= afterTime` filter would permanently drop a trip once its scheduled time
     * ticks past, even if the live feed shows it still dwelling at the stop. Widening the window
     * backward keeps recently-scheduled trips as candidates, leaving the real include/exclude call
     * to the live-position logic downstream. Zero by default, so a plain one-shot snapshot caller
     * (e.g. Upcoming Arrivals) keeps its existing behavior.
     */
    fun getScheduledArrivals(stopId: String, afterTime: String, today: LocalDate, graceSeconds: Int = 0): List<ScheduledArrival> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val yesterday = today.minusDays(1)
        val yesterdayGtfs = yesterday.toGtfsDateString()
        val yesterdayDayColumn = yesterday.dayOfWeek.toGtfsColumnName()
        val effectiveAfterTime = if (graceSeconds > 0) subtractSecondsFromGtfsTime(afterTime, graceSeconds) else afterTime

        val sql = """
            SELECT st.departure_time, t.trip_id, st.stop_sequence,
                   r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign, d.direction, d.direction_destination, ls.stop_id, ls.stop_name
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN routes r ON r.route_id = t.route_id
            LEFT JOIN directions d ON d.route_id = t.route_id AND d.direction_id = t.direction_id
            $LAST_STOP_JOIN
            WHERE st.stop_id = ?
              AND ${activeTransitDayClause(dayColumn, yesterdayDayColumn)}
            ORDER BY st.departure_time
        """.trimIndent()

        return db.rawQuery(
            sql,
            arrayOf(
                stopId,
                effectiveAfterTime, todayGtfs, todayGtfs, todayGtfs,
                shiftedToNextDay(effectiveAfterTime), yesterdayGtfs, yesterdayGtfs, yesterdayGtfs,
            ),
        ).use { cursor ->
            cursor.mapRowsNotNull {
                val directionId = getIntOrNull(7) ?: return@mapRowsNotNull null
                ScheduledArrival(
                    departureTime = getString(0),
                    tripId = getString(1),
                    stopId = stopId,
                    stopSequence = getInt(2),
                    route = RouteOption(
                        routeId = getString(3),
                        shortName = getStringOrNull(4),
                        longName = getStringOrNull(5),
                        routeType = getInt(6),
                    ),
                    direction = DirectionOption(
                        directionId, getStringOrNull(8), directionName = getStringOrNull(9), destination = getStringOrNull(10),
                        lastStopId = getStringOrNull(11), lastStopName = getStringOrNull(12),
                    ),
                )
            }
        }
    }

    /**
     * Real scheduled trips still upcoming for one (route_id, direction_id), each trip's own earliest
     * remaining departure time -- the candidate pool a [FuzzyRunTrips] implementation ordinally pairs
     * live runs against (see [matchFuzzyRunsOrdinally]). Returned as raw GTFS "HH:MM:SS" strings, not
     * epoch seconds -- same zone-agnostic convention every other query in this class follows; the
     * caller (which already has the agency's own zoneId) converts via [gtfsTimeToEpochSeconds].
     *
     * `MIN(st.departure_time)` per trip, not the trip's own first stop -- a trip already in progress
     * relative to [afterTime] should rank by where it currently stands in its own remaining schedule,
     * the same "how far along is this trip" signal a live run's own soonest predicted time already
     * represents, not by a stale origin time. A trip within [MIN_REMAINING_STOPS_FOR_CANDIDATE] stops
     * of its own end is excluded entirely rather than ranked normally -- confirmed live 2026-08-23: on
     * a subway line with tight headways, a trip only 2 stops from its own terminal can still have a
     * "soonest remaining stop" time just as close as a genuinely fresh candidate, but almost
     * certainly already passed whatever stop the rider is actually viewing (a real CTA Blue Line trip
     * ranked as "soonest" this way had already departed Belmont 17 minutes earlier), silently
     * poisoning every match at that stop; the same root cause produced MBTA Green Line's own
     * confirmed early-skewed status. This can't be fixed by ranking alone -- excluding it is what
     * keeps a route-wide, stop-agnostic candidate pool usable for any specific stop along it.
     */
    fun getScheduledTripCandidates(routeId: String, directionId: Int, afterTime: String, today: LocalDate): List<Pair<String, String>> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val yesterday = today.minusDays(1)
        val yesterdayGtfs = yesterday.toGtfsDateString()
        val yesterdayDayColumn = yesterday.dayOfWeek.toGtfsColumnName()
        data class UpcomingStop(val tripId: String, val time: String, val stopSequence: Int, val maxStopSequence: Int)
        val upcomingStops = db.rawQuery(
            """
            SELECT t.trip_id, st.departure_time, st.stop_sequence,
                   (SELECT MAX(st2.stop_sequence) FROM stop_times st2 WHERE st2.trip_id = t.trip_id)
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            WHERE t.route_id = ? AND t.direction_id = ?
              AND ${activeTransitDayClause(dayColumn, yesterdayDayColumn)}
            ORDER BY st.departure_time
            """,
            arrayOf(
                routeId, directionId.toString(),
                afterTime, todayGtfs, todayGtfs, todayGtfs,
                shiftedToNextDay(afterTime), yesterdayGtfs, yesterdayGtfs, yesterdayGtfs,
            ),
        ).use { cursor ->
            cursor.mapRows { UpcomingStop(getString(0), getString(1), getInt(2), getInt(3)) }
        }
        val seenTripIds = mutableSetOf<String>()
        val result = mutableListOf<Pair<String, String>>()
        for (stop in upcomingStops) {
            // Already ordered by time ascending, so a trip_id's FIRST occurrence here is genuinely
            // its own earliest remaining stop -- marked seen regardless of the filter below so a
            // later, further-along stop for the same trip never gets substituted in its place.
            if (!seenTripIds.add(stop.tripId)) continue
            if (stop.maxStopSequence - stop.stopSequence < MIN_REMAINING_STOPS_FOR_CANDIDATE) continue
            result.add(stop.tripId to stop.time)
        }
        return result
    }

    /**
     * Union of [getScheduledArrivals] across every id in [stopIds] -- for a deduplicated
     * multi-platform station (see [groupStationsByParent]), looks up every child platform grouped
     * under the station and merges their schedules into one chronological list. Each result is
     * tagged with the platform it was found at ([ScheduledArrival.stopId]/[platformLabel]),
     * populated only when [stopIds] has more than one entry (a real grouped station).
     */
    fun getScheduledArrivals(stopIds: List<String>, afterTime: String, today: LocalDate): List<ScheduledArrival> {
        if (stopIds.isEmpty()) return emptyList()
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val yesterday = today.minusDays(1)
        val yesterdayGtfs = yesterday.toGtfsDateString()
        val yesterdayDayColumn = yesterday.dayOfWeek.toGtfsColumnName()
        val placeholders = stopIds.joinToString(",") { "?" }
        val isGrouped = stopIds.size > 1

        val sql = """
            SELECT st.departure_time, t.trip_id, st.stop_sequence, st.stop_id, s.stop_desc,
                   r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign, d.direction, d.direction_destination, ls.stop_id, ls.stop_name
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN routes r ON r.route_id = t.route_id
            JOIN stops s ON s.stop_id = st.stop_id
            LEFT JOIN directions d ON d.route_id = t.route_id AND d.direction_id = t.direction_id
            $LAST_STOP_JOIN
            WHERE st.stop_id IN ($placeholders)
              AND ${activeTransitDayClause(dayColumn, yesterdayDayColumn)}
            ORDER BY st.departure_time
        """.trimIndent()

        return db.rawQuery(
            sql,
            (stopIds + listOf(
                afterTime, todayGtfs, todayGtfs, todayGtfs,
                shiftedToNextDay(afterTime), yesterdayGtfs, yesterdayGtfs, yesterdayGtfs,
            )).toTypedArray(),
        ).use { cursor ->
            cursor.mapRowsNotNull {
                val directionId = getIntOrNull(9) ?: return@mapRowsNotNull null
                ScheduledArrival(
                    departureTime = getString(0),
                    tripId = getString(1),
                    stopId = getString(3),
                    stopSequence = getInt(2),
                    platformLabel = if (isGrouped) platformLabelFromStopDesc(getStringOrNull(4)) else null,
                    route = RouteOption(
                        routeId = getString(5),
                        shortName = getStringOrNull(6),
                        longName = getStringOrNull(7),
                        routeType = getInt(8),
                    ),
                    direction = DirectionOption(
                        directionId, getStringOrNull(10), directionName = getStringOrNull(11), destination = getStringOrNull(12),
                        lastStopId = getStringOrNull(13), lastStopName = getStringOrNull(14),
                    ),
                )
            }
        }
    }

    /**
     * Same per-platform [ScheduledArrival] shape as [getScheduledArrivals], but keyed directly by
     * [tripIds] instead of a stop_id + time window, with no time filter at all -- for a trip
     * GTFS-RT/an agency's live API already confirms is running and heading to one of [stopIds],
     * but which fell outside an earlier schedule snapshot's window (see MapScreen's live-vehicle
     * backfill, which merges this into the same cache [getScheduledArrivals] populates). A trip
     * already confirmed live is relevant regardless of its originally-scheduled time.
     */
    fun getScheduledArrivalsForTrips(tripIds: Set<String>, stopIds: List<String>): List<ScheduledArrival> {
        if (tripIds.isEmpty() || stopIds.isEmpty()) return emptyList()
        val tripPlaceholders = tripIds.joinToString(",") { "?" }
        val stopPlaceholders = stopIds.joinToString(",") { "?" }
        val isGrouped = stopIds.size > 1

        val sql = """
            SELECT st.departure_time, t.trip_id, st.stop_sequence, st.stop_id, s.stop_desc,
                   r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign, d.direction, d.direction_destination, ls.stop_id, ls.stop_name
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN routes r ON r.route_id = t.route_id
            JOIN stops s ON s.stop_id = st.stop_id
            LEFT JOIN directions d ON d.route_id = t.route_id AND d.direction_id = t.direction_id
            $LAST_STOP_JOIN
            WHERE t.trip_id IN ($tripPlaceholders) AND st.stop_id IN ($stopPlaceholders)
        """.trimIndent()

        return db.rawQuery(sql, (tripIds.toList() + stopIds).toTypedArray()).use { cursor ->
            cursor.mapRowsNotNull {
                val directionId = getIntOrNull(9) ?: return@mapRowsNotNull null
                ScheduledArrival(
                    departureTime = getString(0),
                    tripId = getString(1),
                    stopId = getString(3),
                    stopSequence = getInt(2),
                    platformLabel = if (isGrouped) platformLabelFromStopDesc(getStringOrNull(4)) else null,
                    route = RouteOption(
                        routeId = getString(5),
                        shortName = getStringOrNull(6),
                        longName = getStringOrNull(7),
                        routeType = getInt(8),
                    ),
                    direction = DirectionOption(
                        directionId, getStringOrNull(10), directionName = getStringOrNull(11), destination = getStringOrNull(12),
                        lastStopId = getStringOrNull(13), lastStopName = getStringOrNull(14),
                    ),
                )
            }
        }
    }

    /**
     * A trip's own route + direction, independent of any particular stop -- for "See Everything"
     * map mode, whose vehicles aren't matched against a stop_time row at all: every live vehicle in
     * view gets plotted regardless of whether its trip serves a stop this screen cares about, so
     * there's no stop_id to join against here, unlike [getScheduledArrivalsForTrips].
     */
    fun getRoutesForTrips(tripIds: Set<String>): Map<String, TripRouteInfo> {
        if (tripIds.isEmpty()) return emptyMap()
        val placeholders = tripIds.joinToString(",") { "?" }
        val sql = """
            SELECT t.trip_id, r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign, d.direction, d.direction_destination, ls.stop_id, ls.stop_name
            FROM trips t
            JOIN routes r ON r.route_id = t.route_id
            LEFT JOIN directions d ON d.route_id = t.route_id AND d.direction_id = t.direction_id
            $LAST_STOP_JOIN
            WHERE t.trip_id IN ($placeholders)
        """.trimIndent()

        return db.rawQuery(sql, tripIds.toTypedArray()).use { cursor ->
            cursor.mapRowsNotNull {
                val directionId = getIntOrNull(5) ?: return@mapRowsNotNull null
                getString(0) to TripRouteInfo(
                    route = RouteOption(
                        routeId = getString(1),
                        shortName = getStringOrNull(2),
                        longName = getStringOrNull(3),
                        routeType = getInt(4),
                    ),
                    direction = DirectionOption(
                        directionId, getStringOrNull(6), directionName = getStringOrNull(7), destination = getStringOrNull(8),
                        lastStopId = getStringOrNull(9), lastStopName = getStringOrNull(10),
                    ),
                )
            }.toMap()
        }
    }

    /**
     * Every stop with coordinates, ranked by distance from ([anchorLat], [anchorLon]), nearest
     * first, capped at [limit]. Used by the "Leave Now" nearby-stops flow, anchored at a geocoded
     * search point.
     */
    fun rankStopsByDistance(
        anchorLat: Double,
        anchorLon: Double,
        limit: Int,
        excludeStopId: String? = null,
    ): List<StopWithDistance> =
        getStopsWithLocation()
            .asSequence()
            .filter { excludeStopId == null || excludeStopId !in it.memberStopIds }
            .map { stop ->
                StopWithDistance(
                    stopId = stop.stopId,
                    stopName = stop.stopName,
                    lat = stop.lat,
                    lon = stop.lon,
                    distanceMeters = haversineMeters(anchorLat, anchorLon, stop.lat, stop.lon),
                    memberStopIds = stop.memberStopIds,
                    isStation = stop.isStation,
                )
            }
            .sortedBy { it.distanceMeters }
            .take(limit)
            .toList()

    /**
     * Every stop with coordinates actually within [radiusMeters] of ([anchorLat], [anchorLon]) --
     * true geographic containment, not a "nearest N" ranking, so what's returned matches what's
     * visible on the map. Used by MapScreen to plot nearby stops at their real positions.
     * [maxResults] is only a safety cap for unusually dense areas, not the selection method.
     */
    fun getStopsWithinRadius(
        anchorLat: Double,
        anchorLon: Double,
        radiusMeters: Double,
        excludeStopId: String? = null,
        maxResults: Int = 60,
    ): List<StopWithDistance> =
        getStopsWithLocation()
            .asSequence()
            .filter { excludeStopId == null || excludeStopId !in it.memberStopIds }
            .map { stop ->
                StopWithDistance(
                    stopId = stop.stopId,
                    stopName = stop.stopName,
                    lat = stop.lat,
                    lon = stop.lon,
                    distanceMeters = haversineMeters(anchorLat, anchorLon, stop.lat, stop.lon),
                    memberStopIds = stop.memberStopIds,
                    isStation = stop.isStation,
                )
            }
            .filter { it.distanceMeters <= radiusMeters }
            .sortedBy { it.distanceMeters }
            .take(maxResults)
            .toList()

    fun close() {
        db.close()
    }
}

/** One `stops` row, exactly as needed for [groupStationsByParent]. Internal rather than private
 * so the grouping logic (pure data transformation, no Android/SQLite dependency) is unit-testable
 * without needing a real database. */
internal data class RawStopRow(
    val stopId: String,
    val stopName: String?,
    val lat: Double,
    val lon: Double,
    val parentStation: String?,
    /** GTFS `location_type` -- 1 means this row is itself a real Station record. Only relevant for
     * [StopLocation.isStation]; the grouping itself still keys purely on `parent_station`. */
    val locationType: Int? = null,
)

/**
 * Groups GTFS child platforms/entrances (`location_type=0` rows with a populated `parent_station`)
 * under their parent station (`location_type=1`) record, per the GTFS spec, so a single physical
 * station with several platform-level stop_ids is represented once. Grouping itself only depends on
 * the `parent_station` linkage -- a row with no `parent_station` is its own representative
 * regardless of its own `location_type`. `location_type` only matters separately for
 * [StopLocation.isStation] and for [isRealPlatform] filtering, which decides which children make it
 * into [StopLocation.memberStopIds].
 *
 * If a `parent_station` value doesn't resolve to any row with coordinates, the first child (by
 * stop_id, for determinism) is promoted to represent the group instead of dropping those stops --
 * this fallback case is never `isStation`, since there's no real Station record backing it (see
 * [isStation]'s own doc).
 */
internal fun groupStationsByParent(rows: List<RawStopRow>): List<StopLocation> {
    val (withParent, withoutParent) = rows.partition { !it.parentStation.isNullOrBlank() }
    val childrenByParent = withParent.groupBy { it.parentStation!! }

    val result = mutableListOf<StopLocation>()
    val claimedParentIds = mutableSetOf<String>()

    withoutParent.forEach { row ->
        val children = childrenByParent[row.stopId]
        val platformChildren = children?.filter { isRealPlatform(it) }
        val effectiveChildren = platformChildren?.takeIf { it.isNotEmpty() } ?: children
        val childIds = effectiveChildren?.map { it.stopId }?.sorted()
        result += StopLocation(
            stopId = row.stopId,
            stopName = row.stopName,
            lat = row.lat,
            lon = row.lon,
            memberStopIds = childIds ?: listOf(row.stopId),
            isStation = row.locationType == 1 && (platformChildren?.size ?: 0) >= 2,
        )
        claimedParentIds += row.stopId
    }

    childrenByParent.forEach { (parentId, children) ->
        if (parentId in claimedParentIds) return@forEach
        val platformChildren = children.filter { isRealPlatform(it) }
        val effectiveChildren = platformChildren.ifEmpty { children }
        val representative = effectiveChildren.minBy { it.stopId }
        result += StopLocation(
            stopId = representative.stopId,
            stopName = representative.stopName,
            lat = representative.lat,
            lon = representative.lon,
            memberStopIds = effectiveChildren.map { it.stopId }.sorted(),
            isStation = false,
        )
    }

    return result
}

/**
 * A child row a rider could actually board/alight at -- GTFS `location_type` 0 (platform/stop) or 4
 * (boarding area, e.g. a bus bay within a larger platform). Excludes 2 (station entrance/exit,
 * including elevators) and 3 (generic pathway node, e.g. an escalator's top/bottom -- verified
 * against real MBTA South Station data). Unset (`null`) is treated as a platform per the GTFS
 * spec's default. Falls back to the unfiltered child list if filtering would leave zero members, so
 * a station's schedule lookups never go empty just from missing `location_type` data.
 */
private fun isRealPlatform(row: RawStopRow): Boolean =
    row.locationType == null || row.locationType == 0 || row.locationType == 4

/**
 * A child platform's own identifying label within a multi-platform GTFS station, derived from its
 * stop_desc (e.g. "South Station - Commuter Rail - Track 1" -> "Track 1"). stop_name is
 * deliberately not used -- verified against real MBTA data, every platform under a station shares
 * the parent's stop_name, so it can't distinguish anything; stop_desc's last " - "-delimited
 * segment reliably names just that platform. Null when there's no such segment to extract.
 * Internal rather than private so it's unit-testable without a real database, same as
 * [groupStationsByParent].
 */
internal fun platformLabelFromStopDesc(stopDesc: String?): String? {
    if (stopDesc.isNullOrBlank()) return null
    val lastSeparator = stopDesc.lastIndexOf(" - ")
    if (lastSeparator == -1) return null
    return stopDesc.substring(lastSeparator + 3).trim().takeIf { it.isNotBlank() }
}

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** ~33ft, kept a round metric value -- see [matchCurrentStopByProximity]'s own doc for why this
 * replaced a relative "is the next stop closer than the current one" comparison. */
private const val PROXIMITY_ARRIVAL_RADIUS_METERS = 10

/** See [GtfsRepository.getScheduledTripCandidates]'s own doc for why a trip this close to its own
 * end is excluded outright rather than just ranked normally. */
private const val MIN_REMAINING_STOPS_FOR_CANDIDATE = 3

/** Great-circle distance between two lat/lon points, in meters. */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2).let { it * it }
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}

/**
 * [stopSequence] is which stop the vehicle currently occupies (see [matchCurrentStopByProximity]).
 * [distanceMeters]/[distanceToNextStopMeters] are the same two haversine distances already computed
 * to decide that, exposed so a caller (HomeScreen's progress bar) can interpolate smoothly between
 * this stop and the next rather than only snapping stop-to-stop. [distanceToNextStopMeters] is null
 * when [stopSequence] is the last stop in the list.
 */
data class StopProximityMatch(
    val stopSequence: Int,
    val distanceMeters: Double,
    val distanceToNextStopMeters: Double?,
)

/**
 * Infers which stop a vehicle currently occupies from its own raw GPS position, for agencies whose
 * VehiclePositions feed never populates current_stop_sequence (confirmed empirically for RIPTA --
 * see [getTripStopLocations]'s own doc). [stops] must be sorted ascending by stop_sequence (as
 * [GtfsRepository.getTripStops] already returns them); [lastMatchedStopSequence] is whatever
 * [StopProximityMatch.stopSequence] this function returned on the previous poll, or null for the
 * first poll of a trip.
 *
 * Forward-only once an anchor exists: starting from the last matched stop, only ever advances to
 * the immediate next stop in sequence, and only once the vehicle has come within
 * [PROXIMITY_ARRIVAL_RADIUS_METERS] of that specific stop's own coordinates -- never by comparing
 * against any stop farther ahead. Repeatedly advances (not just by one) so a poll interval that
 * missed several closely-spaced stops in a row still catches up in one call, but each step of that
 * catch-up still only checks its own immediate next stop.
 *
 * Deliberately NOT a relative "is the next stop closer than the current one" comparison -- a route
 * that loops or backtracks near itself could put a stop several positions ahead in sequence
 * geometrically closer than the true current one, before the vehicle has actually traveled the
 * real path to reach it, causing an incorrect multi-stop forward jump (confirmed live on a real
 * RIPTA trip). Checking only the immediate next stop's own absolute distance makes a
 * geometrically-close-but-sequentially-distant stop simply never a candidate.
 *
 * A null [lastMatchedStopSequence] (first poll) is a special case: a forward-only walk starting at
 * [stops]' own first entry assumes distance to the vehicle roughly decreases monotonically from
 * there, which fails on a long route where the vehicle is actually near the far end -- confirmed
 * live on a RIPTA Route 60 trip, where a vehicle near the end of a ~100-stop route matched to the
 * very first stop instead. So cold start alone gets a one-time global nearest-of-all-stops search
 * to establish a sane anchor; every later poll uses the cheaper walk below.
 */
fun matchCurrentStopByProximity(
    stops: List<TripStopRow>,
    stopLocations: Map<String, Pair<Double, Double>>,
    vehicleLat: Double,
    vehicleLon: Double,
    lastMatchedStopSequence: Int?,
): StopProximityMatch? {
    if (stops.isEmpty()) return null
    fun distanceToIndex(index: Int): Double? {
        val stop = stops.getOrNull(index) ?: return null
        val (lat, lon) = stopLocations[stop.stopId] ?: return null
        return haversineMeters(vehicleLat, vehicleLon, lat, lon)
    }

    var index = lastMatchedStopSequence
        ?.let { seq -> stops.indexOfFirst { it.stopSequence == seq } }
        ?.takeIf { it >= 0 }
        ?: (stops.indices.minByOrNull { distanceToIndex(it) ?: Double.MAX_VALUE } ?: 0)
    while (true) {
        val nextDistance = distanceToIndex(index + 1) ?: break
        if (nextDistance > PROXIMITY_ARRIVAL_RADIUS_METERS) break
        index += 1
    }
    val currentDistance = distanceToIndex(index) ?: return null
    return StopProximityMatch(
        stopSequence = stops[index].stopSequence,
        distanceMeters = currentDistance,
        distanceToNextStopMeters = distanceToIndex(index + 1),
    )
}

/**
 * True when a trip's service_id (aliased `t`) is active on the date bound to the three `?`
 * placeholders this fragment introduces: scheduled per `calendar` (weekday + date range) minus any
 * `calendar_dates` removal (exception_type 2), plus any addition (exception_type 1) regardless of
 * the `calendar` row.
 */
private fun activeTodayClause(dayColumn: String): String = """
    (
      (
        EXISTS (
          SELECT 1 FROM calendar c
          WHERE c.service_id = t.service_id
            AND c.$dayColumn = 1
            AND ? BETWEEN c.start_date AND c.end_date
        )
        AND NOT EXISTS (
          SELECT 1 FROM calendar_dates cd
          WHERE cd.service_id = t.service_id AND cd.date = ? AND cd.exception_type = 2
        )
      )
      OR EXISTS (
        SELECT 1 FROM calendar_dates cd
        WHERE cd.service_id = t.service_id AND cd.date = ? AND cd.exception_type = 1
      )
    )
""".trimIndent()

/** [afterTime] shifted forward 24 hours, into the numbering space a trip still running from
 * yesterday's own transit day would use for the same real moment -- see
 * [activeTransitDayClause]'s own doc. "08:15:30" becomes "32:15:30"; callers never need to reason
 * about the value itself, just bind it where [activeTransitDayClause] expects it. */
private fun shiftedToNextDay(afterTime: String): String {
    val parts = afterTime.split(":")
    val hour = parts[0].toInt() + 24
    return "%02d:%s".format(hour, parts.drop(1).joinToString(":"))
}

/**
 * The real-time-aware replacement for a plain `st.departure_time >= ? AND ${activeTodayClause(...)}`
 * pair -- see [activeTodayClause]'s own doc for the calendar/calendar_dates logic this reuses
 * unchanged, twice. GTFS lets a transit day's own trips run past midnight using hour values >=24
 * (e.g. "25:30:00" for 1:30 AM) rather than rolling over to a new service_id, precisely so a late
 * trip stays attached to the transit day it started on rather than the calendar day it happens to
 * finish on. A query that only checks whether a trip's service is active on the CALENDAR day of
 * "right now" misses exactly those still-running trips for the first several hours of a new
 * calendar day, since their own service_id belongs to YESTERDAY's transit day, not today's --
 * confirmed live 2026-08-24 as the root cause of both a CTA/MBTA fuzzy-run mismatch (a live vehicle
 * still finishing yesterday's transit day got ordinally paired against today's own first trip,
 * hours away) and the same gap in Upcoming Arrivals itself.
 *
 * Returns an OR'd pair of self-contained clauses, each with its own `st.departure_time >= ?` bound
 * to its own transit day's numbering: today's own service compared against the plain [afterTime] as
 * normal, and yesterday's service compared against [afterTime] shifted forward 24 hours via
 * [shiftedToNextDay] (the equivalent point in yesterday's own >=24:00 numbering). [dayColumn]/
 * [yesterdayDayColumn] are today's and yesterday's own [DayOfWeek.toGtfsColumnName] values.
 *
 * Callers bind, in this order, in place of the old plain afterTime + three today-date params:
 * [afterTime], today's own three date params (see [activeTodayClause]), [shiftedToNextDay]'s
 * result, then yesterday's own three date params.
 *
 * [comparison] defaults to `>=`; pass `>` for a caller (e.g. [getNextConnections]) that means
 * "strictly after", not "at or after".
 */
private fun activeTransitDayClause(dayColumn: String, yesterdayDayColumn: String, comparison: String = ">="): String = """
    (
      (st.departure_time $comparison ? AND ${activeTodayClause(dayColumn)})
      OR (st.departure_time $comparison ? AND ${activeTodayClause(yesterdayDayColumn)})
    )
""".trimIndent()

/** [zoneId] should always be the specific agency's own [GtfsAgency.zoneId] -- GTFS service days
 * are defined relative to the agency's own clock, not the rider's device's, which only coincides
 * with the device's default zone when the rider happens to be physically in that timezone. */
fun todayForGtfs(zoneId: java.time.ZoneId): LocalDate = LocalDate.now(zoneId)

/** Current wall-clock time as a GTFS "HH:MM:SS" string, for bounding "departures from now on" --
 * see [todayForGtfs]'s own doc for why [zoneId] must be the agency's own, not the device's. */
fun currentGtfsTimeOfDay(zoneId: java.time.ZoneId): String {
    val now = java.time.LocalTime.now(zoneId)
    return "%02d:%02d:%02d".format(now.hour, now.minute, now.second)
}

/** Subtracts [seconds] from a GTFS "HH:MM:SS" time string -- see [GtfsRepository.getScheduledArrivals]'s
 * graceSeconds param. Clamped at "00:00:00" rather than going negative; a query a few minutes wide
 * near midnight pulling in nothing extra (there's essentially never real service exactly then) is a
 * fine trade for not having to represent a negative GTFS time. */
private fun subtractSecondsFromGtfsTime(time: String, seconds: Int): String {
    val parts = time.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return time
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: return time
    val second = parts.getOrNull(2)?.toIntOrNull() ?: 0
    val totalSeconds = (hour * 3600 + minute * 60 + second - seconds).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
}

private fun LocalDate.toGtfsDateString(): String = "%04d%02d%02d".format(year, monthValue, dayOfMonth)

private fun DayOfWeek.toGtfsColumnName(): String = when (this) {
    DayOfWeek.MONDAY -> "monday"
    DayOfWeek.TUESDAY -> "tuesday"
    DayOfWeek.WEDNESDAY -> "wednesday"
    DayOfWeek.THURSDAY -> "thursday"
    DayOfWeek.FRIDAY -> "friday"
    DayOfWeek.SATURDAY -> "saturday"
    DayOfWeek.SUNDAY -> "sunday"
}

private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

private fun Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

private inline fun <T> Cursor.mapRows(transform: Cursor.() -> T): List<T> {
    val results = mutableListOf<T>()
    while (moveToNext()) {
        results += transform()
    }
    return results
}

private inline fun <T> Cursor.mapRowsNotNull(transform: Cursor.() -> T?): List<T> {
    val results = mutableListOf<T>()
    while (moveToNext()) {
        transform()?.let { results += it }
    }
    return results
}
