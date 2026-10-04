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
    SUBWAY(setOf(0, 1), "Subway/Light Rail", "🚇"),
    COMMUTER_RAIL(setOf(2), "Commuter Rail", "🚆"),
    // 11 is a trackless trolley (trolleybus), which riders treat as a bus.
    BUS(setOf(3, 11), "Bus", "🚌"),
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
 * this. [url] is informational only today (no screen renders it as a tappable link).
 * [requiredLegend], when set (see [AttributionLegend]), is shown word for word instead of [name].
 * [partner], when set (see [AttributionPartner]), is credited ahead of [name]. */
data class FeedAttribution(
    val name: String,
    val url: String?,
    val requiredLegend: String? = null,
    val partner: String? = null,
)

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

    /**
     * `AND EXISTS (... trips ...)`: only routes with at least one trip are listed. Some agencies'
     * routes.txt lists routes they don't run (e.g. a catalog shared across several feeds), which
     * would otherwise lead to a dead end at direction selection.
     */
    fun getRoutes(lineType: LineType): List<RouteOption> {
        val placeholders = lineType.gtfsRouteTypes.joinToString(",") { "?" }
        return db.rawQuery(
            """
            SELECT DISTINCT route_id, route_short_name, route_long_name, route_type
            FROM routes
            WHERE route_type IN ($placeholders) AND EXISTS (SELECT 1 FROM trips t WHERE t.route_id = routes.route_id)
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
        // Same reasoning as getStops's directionClause: rawQuery(sql, String[]) throws on a null array
        // element, and headsign can be null (some agencies publish no trip_headsign), so null gets its
        // own no-bind-arg clause.
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
     * and deliberately not narrowed to an exact headsign match. A trip qualifies if it's either an
     * exact match for the picked variant, or if it covers every stop some exact-match trip visits
     * across that trip's own entire route, not just from [stopId] onward.
     *
     * Deliberately not the simpler "union of every stop any exact-match trip visits," since a
     * single headsign string doesn't always correspond to one consistent physical path. For
     * example, a variant with the same headsign can have a different number of stops it covers,
     * and combining every stop across all of them into one list can produce a path no single real
     * trip actually drives. Requiring full coverage of that combined list can then match nothing
     * at all and silently empty the departures list. Comparing against one real exact-match trip
     * instead avoids that trap, since an exact-match trip always covers itself completely. The
     * picked variant's own departures always qualify at any stop it actually serves, regardless of
     * how many different real patterns share its headsign.
     *
     * When one variant is a shorter version of another on the same corridor, picking the shorter
     * one also shows the longer variant's departures, since it still gets a rider where they're
     * going. Picking the longer variant, though, never shows the shorter one's departures, since
     * boarding one under that assumption would strand a rider early. A variant that's a genuinely
     * different branch, neither a shorter nor longer version of any other, stays fully isolated
     * from both directions of that relationship. No route-specific logic is required for that
     * isolation; the coverage check alone keeps it that way.
     *
     * Whether the longer variant's trips are included this way is controlled by a toggle on the
     * Settings screen ([DeparturePreferences.includeLongerTripsEnabledFlow]), on by default. When
     * a rider turns it off, callers use [getDeparturesForExactVariant] instead, which drops back
     * to an exact headsign match only, with none of the above.
     *
     * The coverage check only ever runs on trips that aren't an exact match, since an exact match
     * already qualifies for free through a cheap direct comparison. This matters most on
     * high-frequency routes where one headsign covers nearly the whole direction. Without this
     * shortcut, every one of those already-qualifying trips would still get compared against the
     * reference trips for no reason, adding real, avoidable query cost at scale.
     */
    fun getDeparturesForVariant(routeId: String, directionId: Int, headsign: String?, lastStopId: String?, stopId: String, today: LocalDate): List<Departure> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        // Same null handling as getStops's directionClause. [lastStopId] (see [DirectionOption]) plays
        // the same role as [headsign], including in the "reaches at least as far" check: a shorter
        // trip's last stop identifies its variant just as a headsign would.
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
        // Scoped to this route's trips first, so it never scans all of stop_times.
        val tripIds = db.rawQuery(
            """
            SELECT t.trip_id
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            WHERE t.route_id = ?
              AND st.stop_sequence = (SELECT MIN(stop_sequence) FROM stop_times WHERE trip_id = t.trip_id)
              AND st.departure_time = ?
              AND ${activeTodayClause(dayColumn)}
            """.trimIndent(),
            arrayOf(routeId, startTime, serviceDateGtfs, serviceDateGtfs, serviceDateGtfs),
        ).use { cursor -> cursor.mapRows { getString(0) } }
        return tripIds.singleOrNull()
    }

    /**
     * Every (route_id, first-stop scheduled departure_time) pair active on [serviceDate], mapped to
     * the one real trip_id that matches -- null when 2+ trips share the same pair, same ambiguous-
     * match fail-safe [tripIdForScheduledStart] applies. A batched version of that lookup for
     * resolving every entity in a live feed at once: one stop_times pass per poll instead of one
     * lookup per entity.
     */
    fun scheduledStartTimesByRoute(serviceDate: LocalDate): Map<Pair<String, String>, String?> {
        val serviceDateGtfs = serviceDate.toGtfsDateString()
        val dayColumn = serviceDate.dayOfWeek.toGtfsColumnName()
        val rows = db.rawQuery(
            """
            SELECT t.route_id, first.departure_time, t.trip_id
            FROM trips t
            JOIN (
                SELECT trip_id, departure_time, MIN(stop_sequence) AS first_seq
                FROM stop_times
                GROUP BY trip_id
            ) first ON first.trip_id = t.trip_id
            WHERE ${activeTodayClause(dayColumn)}
            """.trimIndent(),
            arrayOf(serviceDateGtfs, serviceDateGtfs, serviceDateGtfs),
        ).use { cursor ->
            cursor.mapRows { Triple(getString(0), getString(1), getString(2)) }
        }
        return rows.groupBy { (routeId, startTime, _) -> routeId to startTime }
            .mapValues { (_, group) -> group.singleOrNull()?.third }
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

    /** [tripId]'s own route_id, still carrying whatever "feedN:" prefix (see [MultiGtfsFeed]) this
     * trip was ingested under -- a collision safeguard for a prefixed feed's realtime match (see
     * [fetchTripUpdate]/[fetchVehiclePosition]'s own doc): stripping the prefix and looking a raw
     * trip_id up directly in that feed's own upstream response doesn't by itself prove the matched
     * live entity is really that feed's own trip, rather than a different real operator's trip that
     * happens to share the same literal raw trip_id on the wire (e.g. MTA Bus Company and NYCT
     * sharing one combined GTFS-RT feed, see [GtfsAgency.MTA_BUS]) -- comparing this against the
     * matched entity's own (unprefixed) route_id catches that. */
    fun getRouteIdForTrip(tripId: String): String? =
        db.rawQuery("SELECT route_id FROM trips WHERE trip_id = ?", arrayOf(tripId)).use { cursor ->
            cursor.mapRows { getString(0) }.firstOrNull()
        }

    fun getDirectionIdForTrip(tripId: String): Int? =
        db.rawQuery("SELECT direction_id FROM trips WHERE trip_id = ?", arrayOf(tripId)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
        }

    /** [tripId]'s own shape_id, already stored on every ingested trip (trips.txt's own optional
     * column) but otherwise unread anywhere in this codebase -- see [TripShapeSource]'s own doc for
     * the one current consumer. Null for any trip whose feed doesn't publish shapes.txt at all, or
     * doesn't set this column for this specific trip -- both are normal, common cases, not errors. */
    fun getShapeIdForTrip(tripId: String): String? =
        db.rawQuery("SELECT shape_id FROM trips WHERE trip_id = ?", arrayOf(tripId)).use { cursor ->
            cursor.mapRows { getString(0) }.firstOrNull()
        }

    /** Every trip_id -> route_id pair loaded under [prefix] (a [MultiGtfsFeed]'s own "feedN:"),
     * both stripped back to their real, unprefixed form -- the same collision safeguard as
     * [getRouteIdForTrip], batched for [fetchMerged]'s bulk case (matching an entire live feed's
     * worth of entities at once) instead of one query per entity. [prefix] is always this app's own
     * internal "feedN:" literal (see [secondaryFeedPrefix]), never external input, so the plain
     * `LIKE` pattern below needs no escaping. Computed once per poll, not once per entity -- same
     * reasoning as [scheduledStartTimesByRoute]'s own doc. */
    fun tripRouteIdsForPrefix(prefix: String): Map<String, String> =
        db.rawQuery("SELECT trip_id, route_id FROM trips WHERE trip_id LIKE ?", arrayOf("$prefix%")).use { cursor ->
            cursor.mapRows { getString(0).removePrefix(prefix) to getString(1).removePrefix(prefix) }.toMap()
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
     * The static lat/lon of [tripId]'s stop at [stopSequence]: a coarse map-marker fallback for
     * agencies whose VehiclePositions carry only current_stop_sequence/current_status, with no GPS
     * coordinates. Null if the trip has no stop at that sequence.
     */
    fun getStopLocationForTripSequence(tripId: String, stopSequence: Int): Pair<Double, Double>? =
        db.rawQuery(
            "SELECT s.stop_lat, s.stop_lon FROM stop_times st JOIN stops s ON s.stop_id = st.stop_id WHERE st.trip_id = ? AND st.stop_sequence = ?",
            arrayOf(tripId, stopSequence.toString()),
        ).use { cursor ->
            cursor.mapRows { getDouble(0) to getDouble(1) }.firstOrNull()
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
     * once, not once per platform. [mergeFeedStationsEnabled] additionally folds a MultiGtfsFeed
     * secondary's own physically-co-located stops into their parent agency's station (see
     * [mergeFeedStations]) -- on by default, matching [AgencyPreferences.mergeFeedStationsEnabledFlow]'s
     * own default; a cheap no-op for every agency without a secondary feed at all.
     */
    fun getStopsWithLocation(mergeFeedStationsEnabled: Boolean = true): List<StopLocation> =
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
            val grouped = groupStationsByParent(rows)
            if (mergeFeedStationsEnabled) mergeFeedStations(grouped) else grouped
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
    fun getStationContaining(stopId: String, mergeFeedStationsEnabled: Boolean = true): StopLocation? =
        getStopsWithLocation(mergeFeedStationsEnabled).firstOrNull { it.isStation && (it.stopId == stopId || stopId in it.memberStopIds) }

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
    fun getAllStations(mergeFeedStationsEnabled: Boolean = true): List<StopLocation> =
        getStopsWithLocation(mergeFeedStationsEnabled).filter { it.isStation }.sortedBy { it.stopName ?: it.stopId }

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

    /** Every stop's parent station, for matching alerts across a station and its platforms. */
    fun getStopGraph(): StopGraph =
        db.rawQuery("SELECT stop_id, parent_station FROM stops WHERE parent_station IS NOT NULL AND parent_station != ''", null)
            .use { cursor -> StopGraph(cursor.mapRows { getString(0) to getString(1) }.toMap()) }

    /** Rider-facing route names (short name, else long name) for the given route_ids. */
    fun getRouteNames(routeIds: Collection<String>): Map<String, String> =
        routeIds.distinct().chunked(500).flatMap { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT route_id, route_short_name, route_long_name FROM routes WHERE route_id IN ($placeholders)",
                chunk.toTypedArray(),
            ).use { cursor ->
                cursor.mapRows {
                    val name = getStringOrNull(1)?.takeIf { it.isNotBlank() } ?: getStringOrNull(2)?.takeIf { it.isNotBlank() }
                    getString(0) to (name ?: getString(0))
                }
            }
        }.toMap()

    /** Every route's route_id, keyed by its route_short_name (routes without one are left out). */
    fun getRouteIdsByShortName(): Map<String, String> =
        db.rawQuery("SELECT route_short_name, route_id FROM routes WHERE route_short_name IS NOT NULL AND route_short_name != ''", null)
            .use { cursor -> cursor.mapRows { getString(0) to getString(1) } }
            .toMap()

    /** Stop names for the given stop_ids. */
    fun getStopNames(stopIds: Collection<String>): Map<String, String> =
        stopIds.distinct().chunked(500).flatMap { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery("SELECT stop_id, stop_name FROM stops WHERE stop_id IN ($placeholders)", chunk.toTypedArray())
                .use { cursor -> cursor.mapRows { getString(0) to (getStringOrNull(1) ?: getString(0)) } }
        }.toMap()

    /** How many routes actually run, for deciding whether an alert covers most of the agency. */
    fun countRoutesWithTrips(): Int =
        db.rawQuery("SELECT COUNT(*) FROM routes r WHERE EXISTS (SELECT 1 FROM trips t WHERE t.route_id = r.route_id)", null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

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
     * Real scheduled trips still upcoming for one (route_id, direction_id), each paired with its
     * own soonest remaining stop time, the candidate pool a [FuzzyRunTrips] implementation pairs
     * live runs against (see [matchFuzzyRunsOrdinally]). Times are returned as raw GTFS
     * "HH:MM:SS" strings, not a calculated instant, since GTFS times are only meaningful relative
     * to the agency's own timezone. The [FuzzyRunTrips] implementation calling this already has
     * that timezone, so it converts via [gtfsTimeToEpochSeconds] itself.
     *
     * A trip's soonest remaining stop time is measured from wherever it currently stands in its
     * own schedule, not from its very first stop. A trip already underway should rank by how far
     * along it actually is right now, the same signal a live run's own soonest predicted time
     * represents, rather than by a stale origin time from earlier in its run.
     *
     * A trip within [MIN_REMAINING_STOPS_FOR_CANDIDATE] (3) stops of its own end is excluded
     * entirely from this pool, rather than just ranked lower. Since matching pairs live runs and
     * scheduled trips purely by rank position, a trip that close to finishing occupying a rank
     * meant for one still actually coming shifts every pairing after it out of alignment. This
     * only affects the automatic, unpinned match recomputed each poll (see
     * [FuzzyRunTrips.matchedTripUpdates]), not a rider's own explicit Select Run pick (see
     * [FuzzyRunTrips.tripUpdateForRun]), which looks up that vehicle directly and is unaffected,
     * so a rider affected by this can always pick a different run themselves.
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
        mergeFeedStationsEnabled: Boolean = true,
    ): List<StopWithDistance> =
        getStopsWithLocation(mergeFeedStationsEnabled)
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

/** ~250m -- loose enough to cover a secondary feed's own stops sitting a real city block from its
 * parent agency's own physically-same station (e.g. Bustang's Denver Union Station gates vs RTD's
 * own platform there), tight enough that two genuinely distinct, unrelated stations don't fold
 * together just for being in the same neighborhood. A single tunable UX-clustering constant, not
 * a measured distance for any specific agency. */
const val STATION_MERGE_RADIUS_METERS = 250.0

/** The "feed{n}:" prefix [GtfsIngestor] applies to a [MultiGtfsFeed]'s own stop_ids (see
 * [secondaryFeedPrefix]'s own doc) -- "" for a primary-feed stop_id, which never carries one. Used
 * to find a [StopLocation] that came from a secondary feed within one agency's own database, for
 * [mergeFeedStations] below -- purely derived from the id string itself, no extra column needed. */
private val feedPrefixPattern = Regex("^feed\\d+:")
internal fun feedPrefixOf(stopId: String): String = feedPrefixPattern.find(stopId)?.value ?: ""

/**
 * The actual logic behind Settings' "Merge feed stations" toggle: within one agency's own
 * database, folds a [MultiGtfsFeed] secondary's own nearby stop(s) into an existing real station
 * (one [groupStationsByParent] already grouped platforms under, e.g. RTD Denver's own Union
 * Station), when they sit within [STATION_MERGE_RADIUS_METERS] of it (e.g. Bustang's own gates at
 * that same Union Station). Only a real station can absorb a stop this way; a plain stop is never
 * promoted into one just by receiving a merge, and two plain stops are never merged together just
 * for being close. The absorbed stop's id is unioned into the station's own
 * [StopLocation.memberStopIds], so schedule/arrival lookups already written to accept a station's
 * full member list pick up the secondary feed's trips automatically. This works because both the
 * primary and secondary feed live in the same database already, distinguished only by a
 * "feed{n}:" prefix [GtfsIngestor] writes into a secondary feed's own stop_ids (see
 * [feedPrefixOf]); a cross-schedule merge, spanning separate database files entirely, would need
 * a different approach.
 *
 * Restricted to real stations rather than every stop for performance: checking every stop for a
 * possible merge significantly slows this down for an agency with a large stop count.
 */
internal fun mergeFeedStations(stations: List<StopLocation>): List<StopLocation> {
    val secondaryStops = stations.filter { feedPrefixOf(it.stopId).isNotEmpty() }
    if (secondaryStops.isEmpty()) return stations
    val anchors = stations.filter { it.isStation }
    if (anchors.isEmpty()) return stations

    val extraMemberIdsByAnchorId = mutableMapOf<String, MutableList<String>>()
    val absorbedSecondaryStopIds = mutableSetOf<String>()
    for (secondary in secondaryStops) {
        val nearestAnchor = anchors.minByOrNull { haversineMeters(it.lat, it.lon, secondary.lat, secondary.lon) } ?: continue
        if (haversineMeters(nearestAnchor.lat, nearestAnchor.lon, secondary.lat, secondary.lon) > STATION_MERGE_RADIUS_METERS) continue
        extraMemberIdsByAnchorId.getOrPut(nearestAnchor.stopId) { mutableListOf() } += secondary.memberStopIds
        absorbedSecondaryStopIds += secondary.stopId
    }
    if (absorbedSecondaryStopIds.isEmpty()) return stations

    return stations.mapNotNull { station ->
        when {
            station.stopId in absorbedSecondaryStopIds -> null
            extraMemberIdsByAnchorId.containsKey(station.stopId) ->
                station.copy(memberStopIds = (station.memberStopIds + extraMemberIdsByAnchorId.getValue(station.stopId)).distinct())
            else -> station
        }
    }
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

/** ~165 ft, wide enough that a vehicle can't pass a stop between 10-second polls without
 * matching it. See [matchCurrentStopByProximity]. */
private const val PROXIMITY_ARRIVAL_RADIUS_METERS = 50

/** See [matchCurrentStopByProximity]'s own doc -- a looser plausibility check than
 * [PROXIMITY_ARRIVAL_RADIUS_METERS] on purpose: a cold-start hint can legitimately mean the vehicle
 * is still "en route to" that stop (up to a full inter-stop distance away, per the GTFS-RT spec), not
 * necessarily already arrived, so a tight radius would reject most correct hints. Still tight enough
 * to reject a hint that's off by kilometers. */
private const val COLD_START_HINT_SANITY_RADIUS_METERS = 500

/** Guards [matchCurrentStopByProximity]'s *second* cold-start tier (nearest-of-all-remaining-stops,
 * tried only when there's no usable [coldStartSequenceHint]). Its own constant rather than reusing
 * [COLD_START_HINT_SANITY_RADIUS_METERS]: that one checks distance to one specific, externally-hinted
 * stop; this one takes a minimum across every remaining stop on the trip, which is a looser signal by
 * nature -- more candidates means a higher chance some sequentially-distant stop is coincidentally
 * close by. Without a cap, a route that loops or backtracks near itself could match a stop late in the
 * sequence to a GPS fix that's actually near the true, much-earlier position. Kept tighter than
 * [COLD_START_HINT_SANITY_RADIUS_METERS] and looser than [PROXIMITY_ARRIVAL_RADIUS_METERS]: an
 * unanchored first fix hasn't necessarily converged yet, so the arrival radius would reject too many
 * legitimate cold starts. A rejected cold start returns null rather than guessing -- no anchor gets
 * recorded on a null result, so the next poll just retries with fresh GPS. */
private const val COLD_START_NEAREST_STOP_SANITY_RADIUS_METERS = 200

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
 * VehiclePositions feed never populates current_stop_sequence. Only ever invoked for an agency with a
 * [TripShapeSource] attached (RIPTA today) -- the same gate [matchCurrentStopByShapeProjection] uses --
 * rather than as an automatic fallback for any agency missing current_stop_sequence: GPS-geometry
 * matching has real failure modes of its own (see this function's and [matchCurrentStopByShapeProjection]'s
 * cold-start handling below), so an agency opts into it explicitly via that component instead of it
 * silently applying wherever the ordinary feed data happens to be incomplete. [stops] must be
 * sorted ascending by stop_sequence (as [GtfsRepository.getTripStops] already returns them); [lastMatchedStopSequence] is
 * whatever [StopProximityMatch.stopSequence] this function returned on the previous poll, or null for
 * the first poll of a trip.
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
 * real path to reach it, causing an incorrect multi-stop forward jump. Checking only the immediate
 * next stop's own absolute distance makes a geometrically-close-but-sequentially-distant stop simply
 * never a candidate.
 *
 * A null [lastMatchedStopSequence] (first poll, or an anchor that hasn't been established yet) is a
 * special case with two fallbacks, tried in order:
 *
 * 1. [coldStartSequenceHint], when it resolves to a stop actually in [stops] AND the vehicle's own
 *    GPS position is within [COLD_START_HINT_SANITY_RADIUS_METERS] of that specific stop --
 *    [GtfsRtTripUpdate.inferCurrentStopSequence] (the intended source for this hint) is only reliable
 *    when an agency's feed actually prunes already-passed stops, which isn't universal. Requiring GPS
 *    agreement keeps the hint useful for genuinely close-in disambiguation while discarding it the
 *    moment it's stale.
 * 2. Otherwise, a one-time global nearest-of-all-stops search by straight-line distance -- a
 *    forward-only walk starting at [stops]' own first entry would instead get stuck near the start on
 *    a long route where the vehicle is actually far along it already. Only trusted if that nearest
 *    stop is within [COLD_START_NEAREST_STOP_SANITY_RADIUS_METERS] of the vehicle (see that constant's
 *    own doc for why it needs a tighter, separate check than tier 1's); returns null rather than
 *    guessing when even the closest stop fails this check.
 *
 * Every later poll (an anchor already exists) uses the cheaper, sequence-safe walk below instead.
 */
fun matchCurrentStopByProximity(
    stops: List<TripStopRow>,
    stopLocations: Map<String, Pair<Double, Double>>,
    vehicleLat: Double,
    vehicleLon: Double,
    lastMatchedStopSequence: Int?,
    coldStartSequenceHint: Int? = null,
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
        ?: coldStartSequenceHint
            ?.let { seq -> stops.indexOfFirst { it.stopSequence == seq } }
            ?.takeIf { it >= 0 && (distanceToIndex(it) ?: Double.MAX_VALUE) <= COLD_START_HINT_SANITY_RADIUS_METERS }
        ?: stops.indices.minByOrNull { distanceToIndex(it) ?: Double.MAX_VALUE }
            ?.takeIf { (distanceToIndex(it) ?: Double.MAX_VALUE) <= COLD_START_NEAREST_STOP_SANITY_RADIUS_METERS }
        ?: return null
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
 * Shared in-memory anchor for [matchCurrentStopByProximity]'s forward-only walk, keyed to whichever
 * trip is currently boarded. A rider only ever tracks one boarded trip's live position at a time,
 * but two independent ViewModels poll it: HomeScreenViewModel, which is alive for the entire
 * boarded-trip lifetime, and TripDetailViewModel, which is torn down and rebuilt fresh every single
 * time that screen is navigated to (see [SimpleLightScreen]/[LightScreen]'s own per-navigation
 * ViewModelStore). A private per-ViewModel anchor field would reset on every Trip Detail revisit
 * mid-trip, discarding an already-correct match and re-running the riskier cold-start guess from
 * scratch -- confirmed as the cause of the two screens disagreeing on a real RIPTA trip, since
 * RIPTA's VehiclePositions feed never populates current_stop_sequence, forcing both screens through
 * this GPS-proximity path at all (an agency that does populate it never touches this). Cleared only
 * when the boarded trip's own identity changes -- see HomeScreenViewModel's boardedTripFlow
 * collector, the sole owner of that transition since it's the one ViewModel alive for the whole
 * boarded-trip lifetime.
 */
object TripPositionAnchor {
    @Volatile private var tripId: String? = null
    @Volatile private var stopSequence: Int? = null
    /** Only meaningful for a trip whose agency has a [TripShapeSource] attached -- see
     * [matchCurrentStopByShapeProjection]'s own doc. Null for every other agency, and for a
     * shape-tracked trip until its first successful shape match. Cleared/set independently of
     * [stopSequence] is intentional: a plain point-radius match (this tier's own fallback when shape
     * matching itself fails for one poll) still advances [stopSequence] without a corresponding shape
     * position, since it has none to report. */
    @Volatile private var distanceAlongShapeMeters: Double? = null

    @Synchronized
    fun get(forTripId: String): Int? = stopSequence.takeIf { tripId == forTripId }

    @Synchronized
    fun getShapeDistance(forTripId: String): Double? = distanceAlongShapeMeters.takeIf { tripId == forTripId }

    @Synchronized
    fun record(forTripId: String, sequence: Int) {
        tripId = forTripId
        stopSequence = sequence
    }

    @Synchronized
    fun recordShapeDistance(forTripId: String, meters: Double) {
        tripId = forTripId
        distanceAlongShapeMeters = meters
    }

    @Synchronized
    fun clear() {
        tripId = null
        stopSequence = null
        distanceAlongShapeMeters = null
    }
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
 * Includes a trip whose departure time technically falls on the next calendar day, but still
 * counts as part of the same transit day it started on, since GTFS represents this by letting a
 * transit day's own trips use hour values 24 or higher (e.g. "25:30:00" for 1:30 AM), rather than
 * rolling over to a new service_id. A query that only checks whether a trip's service is active
 * on today's calendar date would wrongly exclude that trip, since its service_id belongs to
 * yesterday's transit day, not today's, even though it's genuinely still running right now.
 *
 * Checks both transit days at once and includes a trip if either one matches, today's transit
 * day, compared against the plain [afterTime] as normal, and yesterday's transit day, compared
 * against [afterTime] shifted forward 24 hours via [shiftedToNextDay] (the same real moment,
 * expressed in yesterday's own hour-24-or-higher numbering). [dayColumn]/[yesterdayDayColumn] are
 * today's and yesterday's own [DayOfWeek.toGtfsColumnName] values. See [activeTodayClause]'s own
 * doc for the calendar/calendar_dates logic reused here, unchanged, for each transit day.
 *
 * Bind, in order, [afterTime], today's own three date values (see [activeTodayClause]), the
 * result of [shiftedToNextDay], then yesterday's own three date values.
 *
 * [comparison] defaults to `>=`, meaning "at or after" the given time. Pass `>` instead for a
 * caller like [getNextConnections], which needs "strictly after": a connections screen shows
 * other trips departing after the rider's own trip arrives at that stop, not one departing at
 * that exact same moment.
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
