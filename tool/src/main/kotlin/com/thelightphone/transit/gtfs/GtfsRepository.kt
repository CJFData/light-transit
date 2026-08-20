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
 * Groups GTFS's numeric `route_type` into the categories riders think in. Type 0 (tram/
 * light rail, e.g. MBTA's Green Line) is bucketed with Subway rather than broken out separately,
 * since that's how riders colloquially refer to it.
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
    /** e.g. "Inbound"/"Outbound"/"Northbound" -- from the feed's own optional directions.txt (an
     * MBTA-originated GTFS extension; most agencies don't publish it), the authoritative rider-
     * facing word for this direction. GTFS's direction_id itself is just a binary flag with no
     * fixed meaning across routes or agencies, so this can't be inferred from direction_id alone.
     * Null when the feed doesn't publish the file, in which case [displayLabel] falls back to the
     * representative headsign. */
    val directionName: String? = null,
    /** This direction's curated destination name from the same directions.txt row as
     * [directionName] (e.g. "Ashmont/Braintree" for the Red Line's south direction) -- distinct
     * from [headsign], which is just whichever headsign happens to be most common among this
     * direction's trips. Null under the same conditions as [directionName]. */
    val destination: String? = null,
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
     * The real, queryable stop_id(s) this location represents. For a plain standalone stop this is
     * just its own id. For a deduplicated GTFS station (see [groupStationsByParent]) this is every
     * child platform/entrance stop_id grouped under it -- stations themselves typically have no
     * stop_times of their own, so schedule/arrival lookups need a real child id, not the station's.
     */
    val memberStopIds: List<String>,
    /**
     * True only when [stopId] is a real GTFS Station record (`location_type=1`) with 2 or more
     * child platforms/entrances grouped under it via `parent_station` -- see
     * [groupStationsByParent]. A stop where several routes merely happen to converge, but with no
     * such parent record, does NOT qualify, regardless of how many routes serve it. Powers the
     * Station sub-map feature (search-result/trip-detail transfer icon, double-tap-to-zoom on the
     * Map screen).
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
     * multi-platform station (see [groupStationsByParent]), this may differ between rows even
     * though they're all "the same station" from the rider's perspective. Live/RT prediction
     * lookups must match against this, not whichever stop_id the caller originally asked about. */
    val stopId: String,
    val stopSequence: Int,
    val departureTime: String,
    val route: RouteOption,
    val direction: DirectionOption,
    /** This platform's own identifying label within a multi-platform station (e.g. "Track 1",
     * "Ashmont/Braintree"), derived from the child stop's own stop_desc -- see
     * [platformLabelFromStopDesc]. Null for a plain, non-grouped stop lookup, where there's nothing
     * more specific to show than the stop's own name. */
    val platformLabel: String? = null,
)

/** See [GtfsRepository.getRoutesForTrips]. */
data class TripRouteInfo(
    val route: RouteOption,
    val direction: DirectionOption,
)

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
     * One entry per distinct (direction_id, trip_headsign) pair actually running on this route --
     * every real headsign variant stays individually selectable. A route with branch/short-turn
     * trips can have several distinct headsigns within the same direction_id (e.g. LTC's Route 01
     * splits into "1A Pond Mills" and "1B King Edward" branches nearly 50/50, and RIPTA's Route 20
     * has three genuinely different termini -- TF Green Airport, Job Lot, and NEIT -- all under one
     * direction_id); collapsing those down to a single "most common" entry would silently hide real
     * destinations riders specifically need to tell apart, so every variant is kept.
     *
     * Each entry also carries the feed's optional directions.txt extension data (route_id,
     * direction_id) -> (direction, direction_destination) -- see [DirectionOption]'s own doc --
     * when the feed publishes it (MBTA does; most agencies don't). [DirectionSelectionScreen] uses
     * that to group same-direction_id entries under a real "Inbound"/"Outbound"-style header when
     * available, so a branchy route reads as "2 directions, each with variants" instead of a flat
     * list of what look like 4+ unrelated directions -- without ever hiding a variant to get there.
     * Agencies with no directions.txt (RIPTA/LTC/RTD today) render exactly as they always have: a
     * flat list of every headsign, one tap away from its stop list.
     */
    fun getDirections(routeId: String): List<DirectionOption> =
        // LEFT JOINed on directions.txt's own documented key (route_id, direction_id) -- every
        // trip_headsign variant within a direction_id carries the same joined direction/
        // destination, since that join key doesn't depend on headsign at all.
        db.rawQuery(
            """
            SELECT DISTINCT t.direction_id, t.trip_headsign, d.direction, d.direction_destination
            FROM trips t
            LEFT JOIN directions d ON d.route_id = t.route_id AND d.direction_id = t.direction_id
            WHERE t.route_id = ? AND t.direction_id IS NOT NULL
            ORDER BY t.direction_id, t.trip_headsign
            """,
            arrayOf(routeId),
        ).use { cursor ->
            cursor.mapRows {
                DirectionOption(
                    directionId = getInt(0),
                    headsign = getStringOrNull(1),
                    directionName = getStringOrNull(2),
                    destination = getStringOrNull(3),
                )
            }
        }

    /** [getDirections] returning empty is ambiguous on its own: either every one of this route's
     * trips has a null direction_id (a real, common case -- e.g. a loop route with no meaningful
     * "direction" to distinguish, where skipping straight to stop selection is the right call), or
     * the route has no trips scheduled at all (confirmed to genuinely happen -- e.g. LTC's own feed
     * publishes some routes with zero currently-active trips), where skipping ahead just lands on a
     * dead-end "Nothing found in today's schedule" screen. Callers use this to tell the two apart
     * before deciding whether to auto-skip direction selection. */
    fun routeHasTrips(routeId: String): Boolean =
        db.rawQuery("SELECT 1 FROM trips WHERE route_id = ? LIMIT 1", arrayOf(routeId)).use { it.moveToFirst() }

    /**
     * Every distinct stop served by any trip on [routeId]+[directionId] with at least one
     * departure remaining today (calendar-active on [today] per [activeTodayClause], departing at
     * or after [afterTime]) -- ordered by each stop's earliest stop_sequence across those trips,
     * an approximation of physical route order since GTFS doesn't guarantee stop_sequence
     * numbering is identical across trip variants. A stop whose only service today has already
     * left, or that's only ever served on a different day, is excluded entirely rather than left
     * for a rider to tap into and find "No departures today" -- there'd be nothing useful there
     * for them right now regardless. Used only for the auto-skip case where no specific
     * direction/headsign was ever chosen (every trip on the route has a null direction_id -- see
     * [routeHasTrips]'s own doc); a real chosen direction goes through [getStopsForVariant]
     * instead, which also narrows by headsign.
     */
    fun getStops(routeId: String, directionId: Int?, afterTime: String, today: LocalDate): List<StopOption> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val directionClause = if (directionId == null) "t.direction_id IS NULL" else "t.direction_id = ?"
        val args = buildList {
            add(routeId)
            directionId?.let { add(it.toString()) }
            add(afterTime)
            addAll(listOf(todayGtfs, todayGtfs, todayGtfs))
        }.toTypedArray()
        return db.rawQuery(
            """
            SELECT st.stop_id, s.stop_name, s.stop_lat, s.stop_lon
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE t.route_id = ? AND $directionClause AND st.departure_time >= ?
              AND ${activeTodayClause(dayColumn)}
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
     * direction variant a rider actually picked in [DirectionSelectionScreen] -- e.g. MBTA's
     * Franklin/Foxboro Line has 92 "South Station" inbound trips running the full line plus 5
     * "Readville" trips that short-turn partway through and share the same direction_id; without
     * this narrowing, choosing "Toward Readville" would still list every stop all the way to
     * South Station (direction_id alone can't tell the two apart), misleading a rider into
     * picking a stop their actual train never reaches. Matched via `IS` rather than `=` so a
     * variant with a genuinely blank/absent headsign (a real, distinct [DirectionOption] in its
     * own right -- see [getDirections]) is matched correctly too, not silently excluded.
     *
     * Deliberately an *exact* headsign match, not [getDeparturesForVariant]'s more generous
     * "reaches at least this far" inclusion -- regardless of whether that's enabled in Settings,
     * this list should promise only what the exact chosen variant itself guarantees today, never
     * a stop that's merely reachable via some other headsign's trip. Same "no departures today"
     * exclusion as [getStops] -- see that function's own doc.
     */
    fun getStopsForVariant(routeId: String, directionId: Int, headsign: String?, afterTime: String, today: LocalDate): List<StopOption> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        return db.rawQuery(
            """
            SELECT st.stop_id, s.stop_name, s.stop_lat, s.stop_lon
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE t.route_id = ? AND t.direction_id = ? AND t.trip_headsign IS ? AND st.departure_time >= ?
              AND ${activeTodayClause(dayColumn)}
            GROUP BY st.stop_id, s.stop_name, s.stop_lat, s.stop_lon
            ORDER BY MIN(st.stop_sequence)
            """,
            arrayOf(routeId, directionId.toString(), headsign, afterTime, todayGtfs, todayGtfs, todayGtfs),
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
     * Departures for [stopId] on [routeId]+[directionId], restricted to trips whose service_id
     * is active on [today]: regularly scheduled per `calendar` (weekday flag + date range) minus
     * any `calendar_dates` removal (exception_type 2), plus any `calendar_dates` addition
     * (exception_type 1) regardless of the `calendar` row.
     *
     * [stopId] may occur anywhere in a trip's stop sequence now that stop selection isn't
     * restricted to route termini; each result carries the matched stop_sequence so trip detail
     * can filter to "from this stop onward" instead of assuming the trip starts there. Used only
     * for the auto-skip case (see [getStops]'s own doc) -- a real chosen direction goes through
     * [getDeparturesForVariant] instead, which also narrows by headsign.
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
     * Same as [getDepartures], but for [stopId] on the direction variant a rider actually picked
     * -- and, deliberately, not narrowed to trips with that *exact* [headsign]. A candidate trip
     * qualifies if it covers at least one *complete* real trip pattern among the chosen variant's
     * own trips that itself reaches [stopId] -- so it's included whenever it's known to run at
     * least as far as some real "H"-labeled run gets from here, whether or not it continues
     * further.
     *
     * Deliberately NOT the simpler "union of every stop any H-labeled trip visits" -- a single
     * headsign string doesn't always correspond to one consistent physical path. RIPTA's Route 20
     * outbound "Kennedy Plaza via Elmwood Ave" covers three distinct real patterns (30, 46, and 50
     * stops) that happen to share a headsign; their union is a 51-stop path no single real trip
     * -- not even another "Kennedy Plaza" trip -- actually runs, so requiring full coverage of
     * that union matched nothing at all and silently emptied the departures list. Anchoring the
     * comparison to one real reference trip that itself reaches the stop being viewed avoids that
     * trap: every "H"-labeled trip trivially covers itself, so the chosen variant's own departures
     * always qualify at any stop it actually serves, regardless of how many different real
     * patterns share its headsign.
     *
     * This is deliberately asymmetric, matching how these variants actually relate to each other:
     * MBTA's Franklin/Foxboro "Readville" trips are a strict subset of "South Station" trips (same
     * corridor, shorter run) -- so a rider who picked "Toward Readville" also sees "South Station"
     * departures at a shared stop like Franklin (either one gets them to Readville, so hiding the
     * extra option would be needlessly conservative), but a rider who picked "Toward South Station"
     * never sees a "Readville" departure sneak in, since a Readville trip doesn't cover South
     * Station's full stop set -- boarding one under a false assumption would strand them early.
     * Same real relationship on RIPTA's Route 20: "TF Green Airport" trips are a subset of "New
     * England Tech" trips (confirmed against real stop data), so TF-Green riders also see NEIT
     * departures, but NEIT riders never see a TF-Green-only trip that stops short of NEIT. "Job
     * Lot" trips aren't a subset of (or superset of) either -- a genuinely different branch, not a
     * shorter/longer version of the same run -- so it stays fully isolated from both, with no
     * agency-specific logic needed to keep it that way; the containment check alone does it.
     *
     * Gated behind [DeparturePreferences.includeLongerTripsEnabledFlow] (on by default) -- when a
     * rider turns it off, callers use [getDeparturesForExactVariant] instead, which drops back to
     * an exact-headsign match with none of the above.
     *
     * The expensive relational-containment check only ever runs over trips carrying a *different*
     * headsign than [headsign] -- a same-headsign trip trivially covers itself, so it's included
     * directly via the cheap `t.trip_headsign IS ?` branch below without ever entering the
     * containment check at all. This matters a lot in practice: on a high-frequency subway line
     * where one headsign covers virtually the whole direction (confirmed on MBTA's Blue Line --
     * 714 of 722 trips this direction), excluding those 714 self-matching trips from the candidate
     * pool cut real measured query time from ~3.7s to ~0.04s for an identical result set, since the
     * earlier version cross-joined every one of them against every reference trip needlessly.
     */
    fun getDeparturesForVariant(routeId: String, directionId: Int, headsign: String?, stopId: String, today: LocalDate): List<Departure> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val sql = """
            WITH h_trips_at_stop AS (
                SELECT DISTINCT t.trip_id
                FROM trips t
                JOIN stop_times st ON st.trip_id = t.trip_id
                WHERE t.route_id = ? AND t.direction_id = ? AND t.trip_headsign IS ? AND st.stop_id = ?
            ),
            other_candidate_trips AS (
                SELECT DISTINCT t.trip_id
                FROM trips t
                JOIN stop_times st ON st.trip_id = t.trip_id
                WHERE t.route_id = ? AND t.direction_id = ? AND st.stop_id = ? AND t.trip_headsign IS NOT ?
            ),
            qualifying_other_trips AS (
                -- A candidate (already known to carry a DIFFERENT headsign -- see
                -- other_candidate_trips) qualifies if, for at least one reference trip that itself
                -- reaches this stop under the chosen headsign, the candidate visits every stop that
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
              AND (t.trip_headsign IS ? OR t.trip_id IN (SELECT trip_id FROM qualifying_other_trips))
              AND ${activeTodayClause(dayColumn)}
            ORDER BY st.departure_time
        """.trimIndent()
        val args = arrayOf(
            routeId, directionId.toString(), headsign, stopId,
            routeId, directionId.toString(), stopId, headsign,
            routeId, directionId.toString(), stopId, headsign,
            todayGtfs, todayGtfs, todayGtfs,
        )
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
     * none of that function's "reaches at least this far" inclusion. Used when a rider has turned
     * off [DeparturePreferences.includeLongerTripsEnabledFlow]: picking "Toward Readville" then
     * shows only the Readville-headsign trips themselves, never the longer "South Station" ones
     * that happen to reach Readville along the way -- for a rider who wants that guarantee even
     * though the broader trips were never actually misleading the other direction.
     */
    fun getDeparturesForExactVariant(routeId: String, directionId: Int, headsign: String?, stopId: String, today: LocalDate): List<Departure> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        return db.rawQuery(
            """
            SELECT st.departure_time, t.trip_id, t.trip_headsign, st.stop_sequence
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            WHERE t.route_id = ? AND t.direction_id = ? AND t.trip_headsign IS ? AND st.stop_id = ?
              AND ${activeTodayClause(dayColumn)}
            ORDER BY st.departure_time
            """.trimIndent(),
            arrayOf(routeId, directionId.toString(), headsign, stopId, todayGtfs, todayGtfs, todayGtfs),
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
     * Tracker's own `stsd`/`stst` fields (see [CtaBusTrackerSource]'s own doc). Null if zero or
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
     * paired with [getTripStops]'s own identically-scoped query to support GPS-proximity current-
     * stop inference (see [matchCurrentStopByProximity]) for agencies whose VehiclePositions never
     * populates current_stop_sequence at all (confirmed empirically for RIPTA).
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
     * route and direction serving those stops (not just the one [excludeTripId] belongs to) --
     * used to show connecting service from a stop selected on a trip's detail screen. When
     * [stopIds] is a real multi-platform station's full [StopLocation.memberStopIds] (see
     * [getStationContaining]), this unions every platform's schedule the same way
     * [getScheduledArrivals]'s list overload does, tagging each result with the specific platform
     * it was found at ([StopConnection.platformLabel]) -- only populated when [stopIds] has more
     * than one entry. A plain single stop just passes a one-element list. Restricted to trips
     * active on [today] using the same calendar/calendar_dates logic as [getDepartures].
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
        val placeholders = stopIds.joinToString(",") { "?" }
        val isGrouped = stopIds.size > 1

        val sql = """
            SELECT st.departure_time, t.trip_id, st.stop_sequence, s.stop_desc,
                   r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN routes r ON r.route_id = t.route_id
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE st.stop_id IN ($placeholders) AND st.departure_time > ? AND t.trip_id != ?
              AND ${activeTodayClause(dayColumn)}
            ORDER BY st.departure_time
        """.trimIndent()

        return db.rawQuery(
            sql,
            (stopIds + listOf(afterTime, excludeTripId, todayGtfs, todayGtfs, todayGtfs)).toTypedArray(),
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
                    direction = DirectionOption(directionId, getStringOrNull(9)),
                )
            }
        }
    }

    /**
     * Every stop with valid coordinates, for nearest-stop distance ranking -- deduplicated per GTFS
     * station grouping (see [groupStationsByParent]), shared by both [rankStopsByDistance] (the
     * stop search flow) and [getStopsWithinRadius] (Map screen markers) so a physical station with
     * several platform-level stop_ids appears once, not once per platform.
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

    /** A single stop's coordinates, for the ETA radar screen's bearing/distance math. Looked up
     * directly by id -- not deduplicated, since callers already have a specific, resolved stop_id
     * in hand (e.g. the one a schedule/arrival was actually looked up for). */
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
     * Resolves [stopId] to its full station group if it belongs to one -- whether [stopId] is the
     * station's own representative id or one of its member platforms. Used by the Map screen's
     * double-tap-to-open-Station gesture so it behaves identically whether the tapped marker is the
     * currently centered/selected stop or a nearby one -- both call this same function rather than
     * two separate detection paths. Null if [stopId] isn't part of any qualifying station.
     */
    fun getStationContaining(stopId: String): StopLocation? =
        getStopsWithLocation().firstOrNull { it.isStation && (it.stopId == stopId || stopId in it.memberStopIds) }

    /**
     * Every stop_id that's part of a real, qualifying multi-platform station -- the station's own
     * representative id plus every one of its child platform ids (see [StopLocation.isStation]).
     * Computed once from the same grouping used everywhere else (not a separate detection method),
     * so a caller checking many individual stop_ids (e.g. Trip Detail's stop-by-stop list) can test
     * cheap set membership per row instead of re-querying per row.
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
     * feed_info.txt's own `feed_publisher_name`/`_url` (the file GTFS agencies publish specifically
     * to answer "who do we credit for this data"), falling back to agency.txt's first row (that
     * file's required by the GTFS spec, unlike feed_info.txt, so every feed has at least this) if a
     * feed omits feed_info.txt entirely. Null only if a feed has neither -- shouldn't happen for any
     * agency this app supports today, but a screen showing this should treat null as "say nothing"
     * rather than falling back to this app's own hardcoded [GtfsAgency.displayName], since that name
     * is this app's label for the agency, not a claim about who published the underlying data.
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
     * individual platform stop_ids in hand, rather than going through the unioned arrivals query
     * that already carries this (see the other [getScheduledArrivals] overload). */
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
     * [graceSeconds], if given), across every route (not just one), active-today-filtered the same
     * way as [getDepartures]. This is the static half of the "Leave Now" upcoming-arrivals screen;
     * the caller merges it with GTFS-RT predictions where available.
     *
     * [graceSeconds] exists for callers that keep polling live vehicle data against this same
     * candidate list over time (see MapScreen's own SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS): a
     * plain `departure_time >= afterTime` filter permanently drops a trip the moment its scheduled
     * time ticks past, even if the *live* feed shows the vehicle still dwelling right at the stop --
     * once dropped from this list, no later poll (querying a still-later "afterTime") can ever bring
     * it back. Widening the window backward keeps recently-scheduled trips as candidates so the
     * live-position-based inclusion/exclusion logic downstream (not this query) gets to make the
     * real call on whether they're still relevant. Zero by default, so a plain one-shot snapshot
     * caller (e.g. Upcoming Arrivals, which has no live-position data to make that downstream call
     * with) keeps its exact existing behavior.
     */
    fun getScheduledArrivals(stopId: String, afterTime: String, today: LocalDate, graceSeconds: Int = 0): List<ScheduledArrival> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val effectiveAfterTime = if (graceSeconds > 0) subtractSecondsFromGtfsTime(afterTime, graceSeconds) else afterTime

        val sql = """
            SELECT st.departure_time, t.trip_id, st.stop_sequence,
                   r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN routes r ON r.route_id = t.route_id
            WHERE st.stop_id = ? AND st.departure_time >= ?
              AND ${activeTodayClause(dayColumn)}
            ORDER BY st.departure_time
        """.trimIndent()

        return db.rawQuery(
            sql,
            arrayOf(stopId, effectiveAfterTime, todayGtfs, todayGtfs, todayGtfs),
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
                    direction = DirectionOption(directionId, getStringOrNull(8)),
                )
            }
        }
    }

    /**
     * Union of [getScheduledArrivals] across every id in [stopIds] -- for a deduplicated
     * multi-platform station (see [groupStationsByParent]), a single representative stop_id's own
     * schedule is only one of several platforms actually serving it; this looks up every child
     * platform grouped under the same station and merges their schedules into one chronological
     * list. Each result is tagged with the specific platform it was found at
     * ([ScheduledArrival.stopId]/[ScheduledArrival.platformLabel]) so the UI can tell riders which
     * platform each arrival uses -- [platformLabel] is only populated when [stopIds] has more than
     * one entry (an actual grouped station), since a plain single-platform stop has nothing more
     * specific to show than its own name.
     */
    fun getScheduledArrivals(stopIds: List<String>, afterTime: String, today: LocalDate): List<ScheduledArrival> {
        if (stopIds.isEmpty()) return emptyList()
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val placeholders = stopIds.joinToString(",") { "?" }
        val isGrouped = stopIds.size > 1

        val sql = """
            SELECT st.departure_time, t.trip_id, st.stop_sequence, st.stop_id, s.stop_desc,
                   r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN routes r ON r.route_id = t.route_id
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE st.stop_id IN ($placeholders) AND st.departure_time >= ?
              AND ${activeTodayClause(dayColumn)}
            ORDER BY st.departure_time
        """.trimIndent()

        return db.rawQuery(
            sql,
            (stopIds + listOf(afterTime, todayGtfs, todayGtfs, todayGtfs)).toTypedArray(),
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
                    direction = DirectionOption(directionId, getStringOrNull(10)),
                )
            }
        }
    }

    /**
     * Same per-platform [ScheduledArrival] shape as [getScheduledArrivals], but keyed directly by
     * [tripIds] instead of a stop_id + time window, and with NO time filter at all -- for a trip
     * GTFS-RT/an agency's own live API already confirms is running and heading to/at one of
     * [stopIds] right now, but which fell outside an earlier-fetched schedule snapshot (an added
     * trip, or the snapshot simply outliving its own grace window -- see MapScreen's own "loosened"
     * live-vehicle matching, which merges this into the same cache [getScheduledArrivals] populates
     * so downstream matching logic doesn't need to know the difference). A trip already confirmed
     * live is definitionally relevant regardless of its originally-scheduled time, hence no filter.
     */
    fun getScheduledArrivalsForTrips(tripIds: Set<String>, stopIds: List<String>): List<ScheduledArrival> {
        if (tripIds.isEmpty() || stopIds.isEmpty()) return emptyList()
        val tripPlaceholders = tripIds.joinToString(",") { "?" }
        val stopPlaceholders = stopIds.joinToString(",") { "?" }
        val isGrouped = stopIds.size > 1

        val sql = """
            SELECT st.departure_time, t.trip_id, st.stop_sequence, st.stop_id, s.stop_desc,
                   r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN routes r ON r.route_id = t.route_id
            JOIN stops s ON s.stop_id = st.stop_id
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
                    direction = DirectionOption(directionId, getStringOrNull(10)),
                )
            }
        }
    }

    /**
     * A trip's own route + direction, independent of any particular stop -- for "See Everything"
     * map mode (see MapScreen's own MapViewModel), whose vehicles aren't matched against a specific
     * stop_time row at all: every live vehicle in view gets plotted regardless of whether its trip
     * serves any stop this screen cares about, so there's no stop_id to join against here, unlike
     * [getScheduledArrivalsForTrips].
     */
    fun getRoutesForTrips(tripIds: Set<String>): Map<String, TripRouteInfo> {
        if (tripIds.isEmpty()) return emptyMap()
        val placeholders = tripIds.joinToString(",") { "?" }
        val sql = """
            SELECT t.trip_id, r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign
            FROM trips t
            JOIN routes r ON r.route_id = t.route_id
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
                    direction = DirectionOption(directionId, getStringOrNull(6)),
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
     * Every stop with coordinates that's actually within [radiusMeters] of ([anchorLat],
     * [anchorLon]) — true geographic containment, not a "nearest N" ranking, so what's returned
     * matches exactly what's visible on the map rather than an arbitrary top-K cutoff. Used by
     * MapScreen to plot nearby stops at their real positions. [maxResults] is only a safety cap for
     * unusually stop-dense areas, not the intended selection method.
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
 * station with several platform-level stop_ids is represented once instead of once per platform.
 * Grouping itself only depends on the `parent_station` linkage -- a row with no `parent_station` is
 * its own representative regardless of its own `location_type`, and either way any rows pointing at
 * it via `parent_station` get folded into it. `location_type` only matters separately for
 * [StopLocation.isStation] (see below) and for [isRealPlatform] filtering, which decides which
 * children actually make it into [StopLocation.memberStopIds].
 *
 * If a `parent_station` value doesn't resolve to any row with coordinates (a missing station record,
 * or one without lat/lon), the first child (by stop_id, for determinism) is promoted to represent
 * the group instead of silently dropping those stops from the results -- this fallback case is never
 * `isStation`, since by definition there's no real Station record backing it (see [isStation]'s own
 * doc for why this distinction matters for the Station sub-map feature).
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
 * (boarding area, e.g. a bus bay within a larger platform). Excludes 2 (station entrance/exit, which
 * includes elevators -- verified against real MBTA data, e.g. South Station's "door-sstat-deweyelev")
 * and 3 (generic pathway node, e.g. the top/bottom of an escalator -- South Station alone has well
 * over a hundred of these). Unset (`null`) is treated as a platform per the GTFS spec's own default.
 * Falls back to the unfiltered child list if filtering would leave zero members, so a station's own
 * schedule lookups never go empty just because its feed is missing usable `location_type` data.
 */
private fun isRealPlatform(row: RawStopRow): Boolean =
    row.locationType == null || row.locationType == 0 || row.locationType == 4

/**
 * A child platform's own identifying label within a multi-platform GTFS station, derived from its
 * stop_desc (e.g. "South Station - Commuter Rail - Track 1" -> "Track 1", "South Station - Red
 * Line - Alewife" -> "Alewife"). stop_name is deliberately not used for this -- verified against
 * real MBTA data, every platform under a station shares the exact same stop_name as the parent
 * station itself, so it can't distinguish anything; stop_desc's last " - "-delimited segment
 * reliably names just that one platform. Null when there's no such segment to extract (a plain,
 * non-grouped stop, or one with a blank/single-segment desc) -- internal rather than private so
 * it's unit-testable without a real database, same as [groupStationsByParent].
 */
internal fun platformLabelFromStopDesc(stopDesc: String?): String? {
    if (stopDesc.isNullOrBlank()) return null
    val lastSeparator = stopDesc.lastIndexOf(" - ")
    if (lastSeparator == -1) return null
    return stopDesc.substring(lastSeparator + 3).trim().takeIf { it.isNotBlank() }
}

private const val EARTH_RADIUS_METERS = 6_371_000.0

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
 * [distanceMeters]/[distanceToNextStopMeters] are the same two haversine distances that search
 * already computed to decide that -- exposed so a caller (see HomeScreen's own progress bar) can
 * interpolate smoothly between this stop and the next by relative distance, rather than only ever
 * snapping stop-to-stop. [distanceToNextStopMeters] is null when [stopSequence] is the last stop in
 * the list the match was found against (nothing further to interpolate toward).
 */
data class StopProximityMatch(
    val stopSequence: Int,
    val distanceMeters: Double,
    val distanceToNextStopMeters: Double?,
)

/**
 * Infers which stop a vehicle currently occupies from its own raw GPS position, for agencies whose
 * VehiclePositions feed never populates current_stop_sequence at all (confirmed empirically for
 * RIPTA -- see [getTripStopLocations]'s own doc). [stops] must be sorted ascending by stop_sequence
 * (as [GtfsRepository.getTripStops] already returns them); [lastMatchedStopSequence] is whatever
 * [StopProximityMatch.stopSequence] this function itself returned on the previous poll (or null for
 * the very first poll of a trip).
 *
 * Forward-only with hysteresis once an anchor exists: starting from the last matched stop, only
 * ever advances to the NEXT stop in sequence, and only once the vehicle is measurably closer to it
 * than to the one it's leaving -- "hold at the current stop until closer to the next one," never
 * snapping backward or jumping ahead out of order. Repeatedly advances (not just by one) so a poll
 * interval that missed several stops in a row still catches up in one call.
 *
 * [lastMatchedStopSequence] being null (the very first poll of a trip) is a special case: starting
 * a forward-only walk at [stops]' own first entry assumes straight-line distance to the vehicle
 * roughly decreases monotonically stop by stop from there, which fails outright on a long route
 * where the vehicle is actually near the far end -- confirmed live (a RIPTA route 60 trip, vehicle
 * genuinely at Kennedy Plaza near the end of a ~100-stop route, matched to Newport Transit Center,
 * its very first stop, because nothing beyond that first stop ever measured closer than it while
 * walking forward one at a time from a route that doesn't approach Kennedy Plaza monotonically).
 * So cold start alone gets a one-time global nearest-of-all-stops search to establish a sane anchor;
 * every later poll (a real [lastMatchedStopSequence]) uses the cheaper, flicker-resistant walk below.
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
    var currentDistance = distanceToIndex(index) ?: return null
    while (true) {
        val nextDistance = distanceToIndex(index + 1) ?: break
        if (nextDistance >= currentDistance) break
        index += 1
        currentDistance = nextDistance
    }
    return StopProximityMatch(
        stopSequence = stops[index].stopSequence,
        distanceMeters = currentDistance,
        distanceToNextStopMeters = distanceToIndex(index + 1),
    )
}

/**
 * True when a trip's service_id (aliased `t` in the enclosing query) is active on the date bound
 * to the three `?` placeholders this fragment introduces: regularly scheduled per `calendar`
 * (weekday flag + date range) minus any `calendar_dates` removal (exception_type 2), plus any
 * `calendar_dates` addition (exception_type 1) regardless of the `calendar` row.
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
