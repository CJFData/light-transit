package com.thelightphone.transit.gtfs

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Reads a trip's shape points directly out of its agency's already-downloaded static feed zip (see
 * [gtfsZipFile]), entirely separate from [GtfsIngestor]'s bulk SQLite pipeline -- deliberately so.
 * Adding a `shapes` table there would mean parsing shapes.txt (some agencies' are comparable in row
 * count to stop_times.txt) for every agency's ordinary ingest, and bumping [GTFS_SCHEMA_VERSION]
 * would force a full re-ingest for every already-cached agency, not just whichever one this is
 * piloted on. Reading on demand from the zip already sitting on disk, scoped to one shape_id at a
 * time, costs nothing for any agency that doesn't attach [TripShapeSource] at all, and no ingestion
 * or schema change for the one that does.
 *
 * Same shape as [MbtaV3VehicleSource]/CTA's `RunAssociatedTripSource` (see [AgencyComponent]'s own
 * doc) -- a component doing its own independent data access rather than hooking into the shared
 * ingestion pipeline. Generic (not RIPTA-specific): shapes.txt's format is standard GTFS, so this one
 * object is meant to be reused by any agency that opts in, not reimplemented per agency.
 */
object StaticGtfsShapeSource : TripShapeSource {

    /** One trip's worth of cache -- a trip's shape never changes mid-boarding, so re-reading the zip
     * on every poll would be pure waste. Keyed by tripId (not shapeId) since every caller already has
     * tripId on hand; @Volatile/@Synchronized mirrors [TripPositionAnchor]'s own single-trip-at-a-time
     * shape, appropriate for the same reason -- a rider only ever tracks one boarded trip at a time.
     * [cachedStopDistances] is a second, separate cache (see [stopDistancesAlongShape]'s own doc) --
     * kept alongside rather than merged into one object since it depends on stop locations the caller
     * supplies, not just the trip id. */
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

    /** Each of [stops]' own position along [tripId]'s shape (stopSequence -> distanceAlongShapeMeters),
     * found by projecting each stop's own coordinates (from [stopLocations], the same map every caller
     * already builds via [GtfsRepository.getTripStopLocations] for the point-radius fallback -- reused
     * here rather than re-queried) onto the *whole* shape once, unconstrained (a stop's position never
     * changes, so this global search only ever needs to run once per boarded trip, not once per poll,
     * unlike [projectOntoShape]'s own windowed steady-state calls). Null when [shapePoints] has no
     * shape for this trip at all; a stop whose own coordinates are missing from [stopLocations] is
     * simply absent from the returned map (matches every other "missing data, not an error" case in
     * this app), not a reason to fail the whole lookup. */
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

    /** Mirrors [GtfsIngestor.loadEntryWithRetry]'s own `ZipFile.getEntry`/`getInputStream` idiom, minus
     * its retry-on-`ZipException` behavior -- this is a one-shot on-demand read, not a bulk ingest a
     * rider is actively waiting on, so a transient failure just means no shape data this time, the same
     * "never force a link" fallback every other component in this app already follows. */
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
                    // Cumulative distance computed here, once, rather than by a caller on every poll --
                    // RIPTA's real feed confirmed to have no shape_dist_traveled column in either
                    // shapes.txt or stop_times.txt (checked directly against its cached zip), so this
                    // can't just be read off the feed the way that GTFS-optional column is meant to.
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
