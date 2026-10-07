package com.thelightphone.transit.gtfs

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Reads a trip's shape from the agency's downloaded schedule zip (see [gtfsZipFile]) instead of
 * ingesting shapes.txt, so agencies that don't use shapes pay nothing and no schema change is
 * needed. Works for any agency that adds it.
 */
object StaticGtfsShapeSource : TripShapeSource {

    /**
     * Caches one trip's shape, since it doesn't change while boarded. [cachedStopDistances] is
     * cached separately because it also depends on the caller's stop locations.
     */
    @Volatile private var cachedTripId: String? = null
    @Volatile private var cachedPoints: List<ShapePoint>? = null
    @Volatile private var cachedStopDistancesTripId: String? = null
    @Volatile private var cachedStopDistances: Map<Int, Double>? = null

    override suspend fun shapePoints(tripId: String, repository: GtfsRepository, gtfsZipFile: File): List<ShapePoint>? {
        synchronized(this) {
            if (cachedTripId == tripId) return cachedPoints
        }
        val shapeId = repository.getShapeIdForTrip(tripId)
        val points = shapeId?.let { readShapePoints(gtfsZipFile, it) }
        synchronized(this) {
            cachedTripId = tripId
            cachedPoints = points
        }
        return points
    }

    /**
     * Each stop's distance along [tripId]'s shape (stopSequence to meters), found once per trip by
     * projecting the stop onto the whole shape. Null when the trip has no shape; stops without a
     * location are left out.
     */
    override suspend fun stopDistancesAlongShape(
        tripId: String,
        repository: GtfsRepository,
        gtfsZipFile: File,
        stops: List<TripStopRow>,
        stopLocations: Map<String, Pair<Double, Double>>,
    ): Map<Int, Double>? {
        synchronized(this) {
            if (cachedStopDistancesTripId == tripId) return cachedStopDistances
        }
        val points = shapePoints(tripId, repository, gtfsZipFile) ?: return null
        val distances = stops.mapNotNull { stop ->
            val (lat, lon) = stopLocations[stop.stopId] ?: return@mapNotNull null
            projectOntoShape(lat, lon, points)?.let { stop.stopSequence to it.distanceAlongShapeMeters }
        }.toMap()
        synchronized(this) {
            cachedStopDistancesTripId = tripId
            cachedStopDistances = distances
        }
        return distances
    }

    /** Reads one shape from the zip. A failure just means no shape this time. */
    private fun readShapePoints(zipFile: File, shapeId: String): List<ShapePoint>? {
        if (!zipFile.exists()) return null
        return try {
            ZipFile(zipFile).use { archive ->
                val entry = archive.getEntry("shapes.txt") ?: return null
                archive.getInputStream(entry).reader(Charsets.UTF_8).buffered().use { reader ->
                    val headerLine = reader.readLine() ?: return null
                    val header = GtfsCsvHeader(parseCsvLine(headerLine))
                    data class RawPoint(val lat: Double, val lon: Double, val sequence: Int)
                    val rawPoints = mutableListOf<RawPoint>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val row = parseCsvLine(line)
                        if (header.get(row, "shape_id") != shapeId) continue
                        val lat = header.get(row, "shape_pt_lat")?.toDoubleOrNull() ?: continue
                        val lon = header.get(row, "shape_pt_lon")?.toDoubleOrNull() ?: continue
                        val sequence = header.get(row, "shape_pt_sequence")?.toIntOrNull() ?: continue
                        rawPoints += RawPoint(lat, lon, sequence)
                    }
                    val sorted = rawPoints.sortedBy { it.sequence }
                    if (sorted.isEmpty()) return null
                    // Cumulative distance is computed here, since shape_dist_traveled is optional
                    // and not every feed has it.
                    var cumulative = 0.0
                    val points = mutableListOf<ShapePoint>()
                    sorted.forEachIndexed { index, raw ->
                        if (index > 0) {
                            val prev = sorted[index - 1]
                            cumulative += haversineMeters(prev.lat, prev.lon, raw.lat, raw.lon)
                        }
                        points += ShapePoint(raw.lat, raw.lon, raw.sequence, cumulative)
                    }
                    points
                }
            }
        } catch (e: IOException) {
            Log.e("StaticGtfsShapeSource", "Failed to read shapes.txt for shape $shapeId", e)
            null
        }
    }
}
