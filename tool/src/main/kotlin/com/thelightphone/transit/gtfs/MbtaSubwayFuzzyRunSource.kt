package com.thelightphone.transit.gtfs

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * Closest-match runs for MBTA subway lines, whose live feed can carry running trains as `ADDED`
 * trips that aren't in the schedule. They come through the standard TripUpdates feed with full stop
 * predictions. Scheduled trips with their own live update are left out, so a real trip_id match
 * always wins.
 */
object MbtaSubwayFuzzyRunSource : FuzzyRunTrips {
    override val routeIds: Set<String> =
        setOf("Red", "Mattapan", "Orange", "Blue", "Green-B", "Green-C", "Green-D", "Green-E")

    override suspend fun matchedTripUpdates(
        requestedRouteIds: Set<String>,
        repository: GtfsRepository,
        agency: GtfsAgency,
        zoneId: ZoneId,
    ): Map<String, GtfsRtTripUpdate> {
        val scopedRouteIds = requestedRouteIds.intersect(routeIds)
        if (scopedRouteIds.isEmpty()) return emptyMap()

        // Usually already cached from this poll's other fetch.
        val feed = try {
            agency.fetchMergedTripUpdates(repository, "MbtaSubwayFuzzyRunSource")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MbtaSubwayFuzzyRunSource", "TripUpdates fetch failed", e)
            return emptyMap()
        }
        val primary = feed.primary ?: return emptyMap()

        val scopedUpdates = primary.entity.mapNotNull { it.tripUpdate }.filter { it.trip.routeId in scopedRouteIds }
        val addedByRouteAndDirection = scopedUpdates
            .filter { it.trip.scheduleRelationship == GTFS_RT_SCHEDULE_RELATIONSHIP_ADDED }
            .groupBy { (it.trip.routeId ?: "") to (it.trip.directionId ?: -1) }
        if (addedByRouteAndDirection.isEmpty()) return emptyMap()
        // Trips already tracked by their own trip_id aren't offered to ADDED runs.
        val trackedTripIds = scopedUpdates
            .filter { it.trip.scheduleRelationship != GTFS_RT_SCHEDULE_RELATIONSHIP_ADDED }
            .mapTo(HashSet()) { it.trip.tripId }

        val today = todayForGtfs(zoneId)
        val nowGtfsTime = currentGtfsTimeOfDay(zoneId)
        val result = mutableMapOf<String, GtfsRtTripUpdate>()
        for ((key, addedTrips) in addedByRouteAndDirection) {
            val (routeId, directionId) = key
            if (directionId < 0) continue
            val candidates = repository.getScheduledTripCandidates(routeId, directionId, nowGtfsTime, today)
                .filter { (tripId, _) -> tripId !in trackedTripIds }
                .mapNotNull { (tripId, timeStr) ->
                    gtfsTimeToEpochSeconds(timeStr, today, zoneId)?.let { ScheduledTripCandidate(tripId, it) }
                }
            if (candidates.isEmpty()) continue

            val liveRuns = addedTrips.mapNotNull { tripUpdate ->
                val soonest = tripUpdate.stopTimeUpdate.firstOrNull()?.let { it.arrival?.time ?: it.departure?.time }
                    ?: return@mapNotNull null
                FuzzyLiveRun(soonestPredictedEpochSeconds = soonest, stopTimeUpdates = tripUpdate.stopTimeUpdate)
            }
            result.putAll(matchFuzzyRunsOrdinally(liveRuns, candidates))
        }
        return result
    }

    // The ADDED trip's id stands in for a run number, stable while it's in the feed. The
    // destination falls back to the route_id.
    override suspend fun liveRunOptions(
        routeId: String,
        agency: GtfsAgency,
        repository: GtfsRepository,
        zoneId: ZoneId,
    ): List<FuzzyRunOption> {
        if (routeId !in routeIds) return emptyList()
        val feed = try {
            agency.fetchMergedTripUpdates(repository, "MbtaSubwayFuzzyRunSource")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MbtaSubwayFuzzyRunSource", "TripUpdates fetch failed", e)
            return emptyList()
        }
        val primary = feed.primary ?: return emptyList()
        return primary.entity.mapNotNull { it.tripUpdate }
            .filter { it.trip.routeId == routeId && it.trip.scheduleRelationship == GTFS_RT_SCHEDULE_RELATIONSHIP_ADDED }
            .mapNotNull { tripUpdate ->
                val runId = tripUpdate.trip.tripId.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val nextStopId = tripUpdate.stopTimeUpdate.firstOrNull()?.stopId ?: return@mapNotNull null
                val time = tripUpdate.stopTimeUpdate.firstOrNull()?.let { it.arrival?.time ?: it.departure?.time }
                    ?: return@mapNotNull null
                FuzzyRunOption(
                    runId = runId,
                    destinationLabel = routeId,
                    soonestPredictedEpochSeconds = time,
                    nextStopId = nextStopId,
                    // No delay flag in this feed.
                    isDelayed = null,
                )
            }
            .sortedBy { it.soonestPredictedEpochSeconds }
    }

    override suspend fun tripUpdateForRun(
        runId: String,
        tripId: String,
        routeId: String,
        repository: GtfsRepository,
        agency: GtfsAgency,
        zoneId: ZoneId,
    ): GtfsRtTripUpdate? {
        if (routeId !in routeIds) return null
        val feed = try {
            agency.fetchMergedTripUpdates(repository, "MbtaSubwayFuzzyRunSource")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MbtaSubwayFuzzyRunSource", "TripUpdates fetch failed", e)
            return null
        }
        val primary = feed.primary ?: return null
        val addedTrip = primary.entity.mapNotNull { it.tripUpdate }
            .find { it.trip.routeId == routeId && it.trip.tripId == runId } ?: return null
        return GtfsRtTripUpdate(
            trip = GtfsRtTripDescriptor(tripId = tripId),
            stopTimeUpdate = addedTrip.stopTimeUpdate,
        )
    }
}
