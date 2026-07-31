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
enum class LineType(val gtfsRouteTypes: Set<Int>, val label: String, val emoji: String) {
    SUBWAY(setOf(0, 1), "Subway", "🚇"),
    COMMUTER_RAIL(setOf(2), "Commuter Rail", "🚆"),
    BUS(setOf(3), "Bus", "🚌");

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
    /** See [StopLocation.memberStopIds]. */
    val memberStopIds: List<String>,
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
)

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

    /**
     * Every stop with valid coordinates, for nearest-stop distance ranking -- deduplicated per GTFS
     * station grouping (see [groupStationsByParent]), shared by both [rankStopsByDistance] (the
     * stop search flow) and [getStopsWithinRadius] (Map screen markers) so a physical station with
     * several platform-level stop_ids appears once, not once per platform.
     */
    fun getStopsWithLocation(): List<StopLocation> =
        db.rawQuery(
            "SELECT stop_id, stop_name, stop_lat, stop_lon, parent_station FROM stops " +
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
)

/**
 * Groups GTFS child platforms/entrances (`location_type=0` rows with a populated `parent_station`)
 * under their parent station (`location_type=1`) record, per the GTFS spec, so a single physical
 * station with several platform-level stop_ids is represented once instead of once per platform.
 * Only the `parent_station` linkage matters here, not `location_type` itself -- a row with no
 * `parent_station` is its own representative regardless of whether it's a true station or a simple
 * standalone stop, and either way any rows pointing at it via `parent_station` get folded into it.
 *
 * If a `parent_station` value doesn't resolve to any row with coordinates (a missing station record,
 * or one without lat/lon), the first child (by stop_id, for determinism) is promoted to represent
 * the group instead of silently dropping those stops from the results.
 */
internal fun groupStationsByParent(rows: List<RawStopRow>): List<StopLocation> {
    val (withParent, withoutParent) = rows.partition { !it.parentStation.isNullOrBlank() }
    val childrenByParent = withParent.groupBy { it.parentStation!! }

    val result = mutableListOf<StopLocation>()
    val claimedParentIds = mutableSetOf<String>()

    withoutParent.forEach { row ->
        val childIds = childrenByParent[row.stopId]?.map { it.stopId }?.sorted()
        result += StopLocation(
            stopId = row.stopId,
            stopName = row.stopName,
            lat = row.lat,
            lon = row.lon,
            memberStopIds = childIds ?: listOf(row.stopId),
        )
        claimedParentIds += row.stopId
    }

    childrenByParent.forEach { (parentId, children) ->
        if (parentId in claimedParentIds) return@forEach
        val representative = children.minBy { it.stopId }
        result += StopLocation(
            stopId = representative.stopId,
            stopName = representative.stopName,
            lat = representative.lat,
            lon = representative.lon,
            memberStopIds = children.map { it.stopId }.sorted(),
        )
    }

    return result
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
