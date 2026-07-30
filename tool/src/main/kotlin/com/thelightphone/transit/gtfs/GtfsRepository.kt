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
 * Groups GTFS's numeric `route_type` into the three categories riders think in. Type 0 (tram/
 * light rail, e.g. MBTA's Green Line) is bucketed with Subway rather than broken out separately,
 * since that's how riders colloquially refer to it.
 */
enum class LineType(val gtfsRouteTypes: Set<Int>, val label: String) {
    SUBWAY(setOf(0, 1), "Subway"),
    COMMUTER_RAIL(setOf(2), "Commuter Rail"),
    BUS(setOf(3), "Bus");

    companion object {
        fun forGtfsRouteType(routeType: Int): LineType? = entries.find { routeType in it.gtfsRouteTypes }
    }
}

data class DirectionOption(val directionId: Int, val headsign: String?)

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
    val route: RouteOption,
    val direction: DirectionOption,
)

data class StopLocation(val stopId: String, val stopName: String?, val lat: Double, val lon: Double)

data class ScheduledArrival(
    val tripId: String,
    val stopSequence: Int,
    val departureTime: String,
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

    fun getDirections(routeId: String): List<DirectionOption> =
        db.rawQuery(
            "SELECT DISTINCT direction_id, trip_headsign FROM trips WHERE route_id = ?",
            arrayOf(routeId),
        ).use { cursor ->
            cursor.mapRowsNotNull {
                val directionId = getIntOrNull(0) ?: return@mapRowsNotNull null
                DirectionOption(directionId, getStringOrNull(1))
            }
        }

    /**
     * Every distinct stop served by any trip on [routeId]+[directionId], ordered by each stop's
     * earliest stop_sequence across those trips — an approximation of physical route order,
     * since GTFS doesn't guarantee stop_sequence numbering is identical across trip variants.
     */
    fun getStops(routeId: String, directionId: Int): List<StopOption> =
        db.rawQuery(
            """
            SELECT st.stop_id, s.stop_name, s.stop_lat, s.stop_lon
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE t.route_id = ? AND t.direction_id = ?
            GROUP BY st.stop_id, s.stop_name, s.stop_lat, s.stop_lon
            ORDER BY MIN(st.stop_sequence)
            """,
            arrayOf(routeId, directionId.toString()),
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

    /**
     * Departures for [stopId] on [routeId]+[directionId], restricted to trips whose service_id
     * is active on [today]: regularly scheduled per `calendar` (weekday flag + date range) minus
     * any `calendar_dates` removal (exception_type 2), plus any `calendar_dates` addition
     * (exception_type 1) regardless of the `calendar` row.
     *
     * [stopId] may occur anywhere in a trip's stop sequence now that stop selection isn't
     * restricted to route termini; each result carries the matched stop_sequence so trip detail
     * can filter to "from this stop onward" instead of assuming the trip starts there.
     */
    fun getDepartures(routeId: String, directionId: Int, stopId: String, today: LocalDate): List<Departure> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()

        val sql = """
            SELECT st.departure_time, t.trip_id, t.trip_headsign, st.stop_sequence
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            WHERE t.route_id = ? AND t.direction_id = ? AND st.stop_id = ?
              AND ${activeTodayClause(dayColumn)}
            ORDER BY st.departure_time
        """.trimIndent()

        return db.rawQuery(
            sql,
            arrayOf(routeId, directionId.toString(), stopId, todayGtfs, todayGtfs, todayGtfs),
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
     * The next scheduled departures from [stopId] after [afterTime], across every route and
     * direction serving that stop (not just the one [excludeTripId] belongs to) — used to show
     * connecting service from a stop selected on a trip's detail screen. Restricted to trips
     * active on [today] using the same calendar/calendar_dates logic as [getDepartures].
     */
    fun getNextConnections(
        stopId: String,
        afterTime: String,
        excludeTripId: String,
        today: LocalDate,
    ): List<StopConnection> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()

        val sql = """
            SELECT st.departure_time, t.trip_id, st.stop_sequence,
                   r.route_id, r.route_short_name, r.route_long_name, r.route_type,
                   t.direction_id, t.trip_headsign
            FROM trips t
            JOIN stop_times st ON st.trip_id = t.trip_id
            JOIN routes r ON r.route_id = t.route_id
            WHERE st.stop_id = ? AND st.departure_time > ? AND t.trip_id != ?
              AND ${activeTodayClause(dayColumn)}
            ORDER BY st.departure_time
        """.trimIndent()

        return db.rawQuery(
            sql,
            arrayOf(stopId, afterTime, excludeTripId, todayGtfs, todayGtfs, todayGtfs),
        ).use { cursor ->
            cursor.mapRowsNotNull {
                val directionId = getIntOrNull(7) ?: return@mapRowsNotNull null
                StopConnection(
                    departureTime = getString(0),
                    tripId = getString(1),
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

    /** Every stop with valid coordinates, for nearest-stop distance ranking. */
    fun getStopsWithLocation(): List<StopLocation> =
        db.rawQuery(
            "SELECT stop_id, stop_name, stop_lat, stop_lon FROM stops WHERE stop_lat IS NOT NULL AND stop_lon IS NOT NULL",
            null,
        ).use { cursor ->
            cursor.mapRows {
                StopLocation(
                    stopId = getString(0),
                    stopName = getStringOrNull(1),
                    lat = getDouble(2),
                    lon = getDouble(3),
                )
            }
        }

    /** A single stop's coordinates, for the ETA radar screen's bearing/distance math. */
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
                )
            }.firstOrNull()
        }

    /**
     * Every route+direction scheduled to serve [stopId] at or after [afterTime] today, across
     * every route (not just one), active-today-filtered the same way as [getDepartures]. This is
     * the static half of the "Leave Now" upcoming-arrivals screen; the caller merges it with
     * GTFS-RT predictions where available.
     */
    fun getScheduledArrivals(stopId: String, afterTime: String, today: LocalDate): List<ScheduledArrival> {
        val todayGtfs = today.toGtfsDateString()
        val dayColumn = today.dayOfWeek.toGtfsColumnName()

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
            arrayOf(stopId, afterTime, todayGtfs, todayGtfs, todayGtfs),
        ).use { cursor ->
            cursor.mapRowsNotNull {
                val directionId = getIntOrNull(7) ?: return@mapRowsNotNull null
                ScheduledArrival(
                    departureTime = getString(0),
                    tripId = getString(1),
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
     * The stop immediately before [beforeStopSequence] in [tripId]'s stop_sequence, if any. Used
     * to approximate a scheduled (non-live) bus's approach bearing when no VehiclePosition exists
     * for it — see EtaRadarScreen. Returns null for a trip's first stop (nothing precedes it).
     */
    fun getPreviousStopLocation(tripId: String, beforeStopSequence: Int): StopLocation? =
        db.rawQuery(
            """
            SELECT s.stop_id, s.stop_name, s.stop_lat, s.stop_lon
            FROM stop_times st
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE st.trip_id = ? AND st.stop_sequence < ?
              AND s.stop_lat IS NOT NULL AND s.stop_lon IS NOT NULL
            ORDER BY st.stop_sequence DESC
            LIMIT 1
            """,
            arrayOf(tripId, beforeStopSequence.toString()),
        ).use { cursor ->
            cursor.mapRows {
                StopLocation(
                    stopId = getString(0),
                    stopName = getStringOrNull(1),
                    lat = getDouble(2),
                    lon = getDouble(3),
                )
            }.firstOrNull()
        }

    /**
     * Every stop with coordinates, ranked by distance from ([anchorLat], [anchorLon]), nearest
     * first, capped at [limit]. Shared by the "Leave Now" nearby-stops flow (anchored at a
     * geocoded search point) and the ETA radar's nearby-stops overlay (anchored at the selected
     * stop), so both draw from the same ranking logic rather than duplicating it.
     */
    fun rankStopsByDistance(
        anchorLat: Double,
        anchorLon: Double,
        limit: Int,
        excludeStopId: String? = null,
    ): List<StopWithDistance> =
        getStopsWithLocation()
            .asSequence()
            .filter { it.stopId != excludeStopId }
            .map { stop ->
                StopWithDistance(
                    stopId = stop.stopId,
                    stopName = stop.stopName,
                    lat = stop.lat,
                    lon = stop.lon,
                    distanceMeters = haversineMeters(anchorLat, anchorLon, stop.lat, stop.lon),
                )
            }
            .sortedBy { it.distanceMeters }
            .take(limit)
            .toList()

    fun close() {
        db.close()
    }
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

fun todayForGtfs(): LocalDate = LocalDate.now()

/** Current wall-clock time as a GTFS "HH:MM:SS" string, for bounding "departures from now on". */
fun currentGtfsTimeOfDay(): String {
    val now = java.time.LocalTime.now()
    return "%02d:%02d:%02d".format(now.hour, now.minute, now.second)
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
