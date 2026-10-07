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

/** GTFS route_type grouped into the categories riders use. Light rail (type 0) counts as Subway. */
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
    /** The direction's name from directions.txt (e.g. "Inbound"), when the feed has one. */
    val directionName: String? = null,
    /** The direction's destination from directions.txt, when the feed has one. */
    val destination: String? = null,
    /**
     * For trips with no headsign: the stop every trip in this group ends at, used to group and
     * match them. Always null when there's a headsign.
     */
    val lastStopId: String? = null,
    /** [lastStopId]'s name, for labels like "Toward Navy Pier Terminal". */
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
    val memberStopIds: List<String>,
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
    /** The connection's platform within a station; null for a plain stop. */
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
     * The stop_ids to query: the stop's own id, or every platform of a station (stations themselves
     * usually have no stop_times).
     */
    val memberStopIds: List<String>,
    /** True for a real GTFS station (location_type 1) with two or more platforms. */
    val isStation: Boolean,
)

/**
 * The data credit for this agency's feed. [requiredLegend] replaces [name] when set; [partner] is
 * credited alongside it.
 */
data class FeedAttribution(
    val name: String,
    val url: String?,
    val requiredLegend: String? = null,
    val partner: String? = null,
)

data class ScheduledArrival(
    val tripId: String,
    /** The platform stop_id the arrival was found at; live lookups must match against this. */
    val stopId: String,
    val stopSequence: Int,
    val departureTime: String,
    val route: RouteOption,
    val direction: DirectionOption,
    /** The platform's label within a station (e.g. "Track 1"); null for a plain stop. */
    val platformLabel: String? = null,
)

data class TripRouteInfo(
    val route: RouteOption,
    val direction: DirectionOption,
)

/**
 * Joins a trip's last stop (ls.stop_id, ls.stop_name), only for trips without a headsign so other
 * trips skip the lookup.
 */
private const val LAST_STOP_JOIN = """
    LEFT JOIN stop_times lst ON lst.trip_id = t.trip_id AND t.trip_headsign IS NULL
        AND lst.stop_sequence = (SELECT MAX(st2.stop_sequence) FROM stop_times st2 WHERE st2.trip_id = t.trip_id)
    LEFT JOIN stops ls ON ls.stop_id = lst.stop_id
"""

/** Read-only queries against one agency's GTFS database. Each returns a small, targeted result. */
class GtfsRepository(dbFile: File) {
    private val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)

    /** The modes that have at least one route in this feed. */
    fun getAvailableLineTypes(): List<LineType> =
        db.rawQuery("SELECT DISTINCT route_type FROM routes", null).use { cursor ->
            val presentTypes = cursor.mapRowsNotNull { getIntOrNull(0) }.toSet()
            LineType.entries.filter { it.gtfsRouteTypes.any { type -> type in presentTypes } }
        }

    /**
     * Only routes with at least one trip are listed, so routes a feed lists but doesn't run aren't
     * dead ends.
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
 * One entry per direction and headsign actually running on the route, so branches and short turns
 * stay separately selectable. Entries carry directions.txt data when the feed has it. Trips without
 * a headsign are told apart by their last stop instead.
 */
    fun getDirections(routeId: String): List<DirectionOption> =
        // The last-stop lookup is scoped to this route's trips so it doesn't scan all of
        // stop_times.
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

    
/**
 * Whether the route has any trips, to tell a route with no directions apart from one with no
 * service.
 */
    fun routeHasTrips(routeId: String): Boolean =
        db.rawQuery("SELECT 1 FROM trips WHERE route_id = ? LIMIT 1", arrayOf(routeId)).use { it.moveToFirst() }

    
/**
 * Stops served by [routeId] and [directionId] with a departure left today, in rough route order.
 * Used when trips have no direction; a chosen direction uses [getStopsForVariant].
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
 * Stops for the exact direction variant the rider picked, matched by [headsign] (or [lastStopId]
 * when trips have no headsign).
 */
    fun getStopsForVariant(routeId: String, directionId: Int, headsign: String?, lastStopId: String?, afterTime: String, today: LocalDate): List<StopOption> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        val yesterday = today.minusDays(1)
        val yesterdayGtfs = yesterday.toGtfsDateString()
        val yesterdayDayColumn = yesterday.dayOfWeek.toGtfsColumnName()
        // rawQuery can't bind null, so a null headsign gets its own clause.
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
 * Departures from [stopId] on [routeId] and [directionId] for trips running today. Used when trips
 * have no direction; a chosen direction uses [getDeparturesForVariant].
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
     * Departures from [stopId] for the picked direction variant, plus trips that go at least as
     * far: a trip qualifies if it matches the variant or covers every stop of some trip that does.
     * Picking a short turn also shows the longer trips, never the reverse. Used while "Include
     * longer trips in departures" is on; otherwise [getDeparturesForExactVariant].
     */
    fun getDeparturesForVariant(routeId: String, directionId: Int, headsign: String?, lastStopId: String?, stopId: String, today: LocalDate): List<Departure> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
        // rawQuery can't bind null, so null gets its own clause. [lastStopId] stands in for
        // [headsign] when trips have none.
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
     * Departures for the exact direction variant only, used when "Include longer trips in
     * departures" is off.
     */
    fun getDeparturesForExactVariant(routeId: String, directionId: Int, headsign: String?, lastStopId: String?, stopId: String, today: LocalDate): List<Departure> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()
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
     * The one trip on [routeId] whose first stop departs at [startTime] on [serviceDate]. Null when
     * none or several match.
     */
    fun tripIdForScheduledStart(routeId: String, startTime: String, serviceDate: LocalDate): String? {
        val serviceDateGtfs = serviceDate.toGtfsDateString()
        val dayColumn = serviceDate.dayOfWeek.toGtfsColumnName()
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
     * Every (route_id, first departure time) pair on [serviceDate] mapped to its trip_id, or null
     * when several trips share it. A batched [tripIdForScheduledStart].
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

    fun getRouteTypeForTrip(tripId: String): Int? =
        db.rawQuery(
            "SELECT r.route_type FROM trips t JOIN routes r ON r.route_id = t.route_id WHERE t.trip_id = ?",
            arrayOf(tripId),
        ).use { cursor ->
            cursor.mapRows { getInt(0) }.firstOrNull()
        }

    /**
     * The trip's route_id including any feed prefix, used to confirm a prefixed feed's live match
     * is really its trip.
     */
    fun getRouteIdForTrip(tripId: String): String? =
        db.rawQuery("SELECT route_id FROM trips WHERE trip_id = ?", arrayOf(tripId)).use { cursor ->
            cursor.mapRows { getString(0) }.firstOrNull()
        }

    fun getDirectionIdForTrip(tripId: String): Int? =
        db.rawQuery("SELECT direction_id FROM trips WHERE trip_id = ?", arrayOf(tripId)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
        }

    /** The trip's shape_id, or null when it has none. */
    fun getShapeIdForTrip(tripId: String): String? =
        db.rawQuery("SELECT shape_id FROM trips WHERE trip_id = ?", arrayOf(tripId)).use { cursor ->
            cursor.mapRows { getString(0) }.firstOrNull()
        }

    /**
     * Every trip_id to route_id pair under [prefix], with the prefix removed. A batched
     * [getRouteIdForTrip].
     */
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

    /** Coordinates of a trip's stops from [fromStopSequence] onward, keyed by stop_id. */
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
     * Coordinates of the trip's stop at [stopSequence], for placing a vehicle that reports only its
     * stop.
     */
    fun getStopLocationForTripSequence(tripId: String, stopSequence: Int): Pair<Double, Double>? =
        db.rawQuery(
            "SELECT s.stop_lat, s.stop_lon FROM stop_times st JOIN stops s ON s.stop_id = st.stop_id WHERE st.trip_id = ? AND st.stop_sequence = ?",
            arrayOf(tripId, stopSequence.toString()),
        ).use { cursor ->
            cursor.mapRows { getDouble(0) to getDouble(1) }.firstOrNull()
        }

    /**
     * The next departures after [afterTime] from [stopIds] on every route except [excludeTripId],
     * for a stop's Connections. Platforms are labeled when there are several.
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
     * Every stop with coordinates, with stations' platforms grouped into one entry.
     * [mergeFeedStationsEnabled] also folds a secondary feed's nearby stops into stations.
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

    /** One stop's coordinates, looked up directly by id. */
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
     * The station [stopId] belongs to, whether it's the station or one of its platforms; null if
     * none.
     */
    fun getStationContaining(stopId: String, mergeFeedStationsEnabled: Boolean = true): StopLocation? =
        getStopsWithLocation(mergeFeedStationsEnabled).firstOrNull { it.isStation && (it.stopId == stopId || stopId in it.memberStopIds) }

    /** Every stop_id in a multi-platform station, the stations and their platforms. */
    fun getMultiPlatformStationStopIds(): Set<String> =
        getStopsWithLocation().filter { it.isStation }.flatMapTo(mutableSetOf()) { it.memberStopIds + it.stopId }

    /** Every multi-platform station, sorted by name. */
    fun getAllStations(mergeFeedStationsEnabled: Boolean = true): List<StopLocation> =
        getStopsWithLocation(mergeFeedStationsEnabled).filter { it.isStation }.sortedBy { it.stopName ?: it.stopId }

    /** The feed publisher from feed_info.txt, falling back to the first agency in agency.txt. */
    fun getFeedAttribution(): FeedAttribution? {
        db.rawQuery("SELECT feed_publisher_name, feed_publisher_url FROM feed_info LIMIT 1", null).use { cursor ->
            cursor.mapRows { FeedAttribution(getString(0), getStringOrNull(1)) }.firstOrNull()?.let { return it }
        }
        return getAgencyAttribution()
    }

    /** The operating agency's name and URL from agency.txt, rather than the feed's publisher. */
    fun getAgencyAttribution(): FeedAttribution? =
        db.rawQuery("SELECT agency_name, agency_url FROM agency LIMIT 1", null).use { cursor ->
            cursor.mapRows { FeedAttribution(getString(0), getStringOrNull(1)) }.firstOrNull()
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

    /** How many routes have trips, for deciding whether an alert covers most of the agency. */
    fun countRoutesWithTrips(): Int =
        db.rawQuery("SELECT COUNT(*) FROM routes r WHERE EXISTS (SELECT 1 FROM trips t WHERE t.route_id = r.route_id)", null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /** stop_desc for each stop_id, for platform labels. */
    fun getStopDescriptions(stopIds: List<String>): Map<String, String?> {
        if (stopIds.isEmpty()) return emptyMap()
        val placeholders = stopIds.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT stop_id, stop_desc FROM stops WHERE stop_id IN ($placeholders)",
            stopIds.toTypedArray(),
        ).use { cursor -> cursor.mapRows { getString(0) to getStringOrNull(1) }.toMap() }
    }

    /**
     * Every route and direction scheduled at [stopId] from [afterTime] today. [graceSeconds] widens
     * the window back so a trip that's late but still at the stop stays a candidate.
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
     * Scheduled trips still to come for one route and direction, each with its next remaining stop
     * time, for closest-match pairing. Trips within [MIN_REMAINING_STOPS_FOR_CANDIDATE] stops of
     * their end are left out so they don't shift the pairing.
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
            // Rows are ordered by time, so a trip's first row is its next stop.
            if (!seenTripIds.add(stop.tripId)) continue
            if (stop.maxStopSequence - stop.stopSequence < MIN_REMAINING_STOPS_FOR_CANDIDATE) continue
            result.add(stop.tripId to stop.time)
        }
        return result
    }

    /**
     * [getScheduledArrivals] for several stop_ids, such as a station's platforms, merged in time
     * order and labeled by platform.
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
     * Scheduled arrivals at [stopIds] for specific trips, with no time window, for live trips
     * outside an earlier snapshot.
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

    /** Each trip's route and direction, for live vehicles not tied to a stop. */
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

    /** Stops nearest the anchor point, closest first, up to [limit]. */
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

    /** Stops within [radiusMeters] of the anchor point, capped at [maxResults]. */
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

/** One stops row, as used by [groupStationsByParent]. */
internal data class RawStopRow(
    val stopId: String,
    val stopName: String?,
    val lat: Double,
    val lon: Double,
    val parentStation: String?,
    /** GTFS location_type; 1 is a station. */
    val locationType: Int? = null,
)

/**
 * Groups platforms under their parent station so a station appears once. If the parent isn't in the
 * feed, the first child stands in for it.
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

/** How close a secondary feed's stop must be to a station to merge into it. */
const val STATION_MERGE_RADIUS_METERS = 250.0

/** The "feedN:" prefix on a secondary feed's ids. */
private val feedPrefixPattern = Regex("^feed\\d+:")
internal fun feedPrefixOf(stopId: String): String = feedPrefixPattern.find(stopId)?.value ?: ""

/**
 * Folds a secondary feed's stops near a real station into that station, so its schedule lookups
 * include them. Only stations absorb stops, which keeps it fast.
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
 * Platforms and boarding areas (location_type 0, 4, or unset), not entrances or pathway nodes.
 * Falls back to every child if none qualify.
 */
private fun isRealPlatform(row: RawStopRow): Boolean =
    row.locationType == null || row.locationType == 0 || row.locationType == 4

/**
 * A platform's label from the last " - " segment of its stop_desc (e.g. "Track 1"), since platforms
 * share their station's name.
 */
internal fun platformLabelFromStopDesc(stopDesc: String?): String? {
    if (stopDesc.isNullOrBlank()) return null
    val lastSeparator = stopDesc.lastIndexOf(" - ")
    if (lastSeparator == -1) return null
    return stopDesc.substring(lastSeparator + 3).trim().takeIf { it.isNotBlank() }
}

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Wide enough that a vehicle can't pass a stop between polls without matching it. */
private const val PROXIMITY_ARRIVAL_RADIUS_METERS = 50

/** How close the vehicle must be to a hinted stop for a cold-start match. */
private const val COLD_START_HINT_SANITY_RADIUS_METERS = 500

/** How close the nearest stop must be for a cold-start match without a hint. */
private const val COLD_START_NEAREST_STOP_SANITY_RADIUS_METERS = 200

private const val MIN_REMAINING_STOPS_FOR_CANDIDATE = 3

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2).let { it * it }
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}

/** The stop the vehicle is at, with distances to it and to the next stop for smooth progress. */
data class StopProximityMatch(
    val stopSequence: Int,
    val distanceMeters: Double,
    val distanceToNextStopMeters: Double?,
)

/**
 * Which stop a vehicle is at, from its GPS position, for agencies with a [TripShapeSource]. Once
 * anchored, it only advances to the next stop, and only within [PROXIMITY_ARRIVAL_RADIUS_METERS] of
 * it, so looping routes can't jump ahead. The first match uses [coldStartSequenceHint] if the
 * vehicle is near that stop, otherwise the nearest stop if it's close enough, otherwise null.
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
 * The boarded trip's last matched stop, shared by Home and Trip Detail so revisiting Trip Detail
 * doesn't restart matching. Cleared when the boarded trip changes.
 */
object TripPositionAnchor {
    @Volatile private var tripId: String? = null
    @Volatile private var stopSequence: Int? = null
    /** The vehicle's distance along the trip's shape, for agencies with a [TripShapeSource]. */
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
 * True when the trip's service_id is active on the date bound to this fragment's three
 * placeholders.
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

/**
 * [afterTime] plus 24 hours, in the numbering yesterday's service day uses ("08:15:30" becomes
 * "32:15:30").
 */
private fun shiftedToNextDay(afterTime: String): String {
    val parts = afterTime.split(":")
    val hour = parts[0].toInt() + 24
    return "%02d:%s".format(hour, parts.drop(1).joinToString(":"))
}

/**
 * Matches trips running now from either today's service day or yesterday's (whose times can pass
 * 24:00). Bind [afterTime], today's three dates, [shiftedToNextDay], then yesterday's three dates.
 * Use ">" for [comparison] to mean strictly after.
 */
private fun activeTransitDayClause(dayColumn: String, yesterdayDayColumn: String, comparison: String = ">="): String = """
    (
      (st.departure_time $comparison ? AND ${activeTodayClause(dayColumn)})
      OR (st.departure_time $comparison ? AND ${activeTodayClause(yesterdayDayColumn)})
    )
""".trimIndent()

/** Today in the agency's time zone. */
fun todayForGtfs(zoneId: java.time.ZoneId): LocalDate = LocalDate.now(zoneId)

/** The current time as GTFS "HH:MM:SS" in the agency's time zone. */
fun currentGtfsTimeOfDay(zoneId: java.time.ZoneId): String {
    val now = java.time.LocalTime.now(zoneId)
    return "%02d:%02d:%02d".format(now.hour, now.minute, now.second)
}

/** Subtracts [seconds] from a GTFS time, stopping at "00:00:00". */
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
