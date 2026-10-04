package com.thelightphone.transit.gtfs

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * [FuzzyRunTrips] for MBTA subway lines, whose live feed can mark running trains as GTFS-RT `ADDED`
 * trips that were never in the static schedule (most Green Line trains, and other lines during
 * service changes). Unlike [CtaTrainTrackerSource], it needs no separate API: ADDED trips arrive in
 * the standard TripUpdates feed with full predicted `stop_time_update` lists. Scheduled trips with
 * their own live update are left out of the matching, so a real trip_id match always wins.
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

        // Same feed a caller's own primary live-status check already fetched this poll cycle -- see
        // FuzzyRunTrips.matchedTripUpdates's own doc for why this redundant-but-cached call is an
        // acceptable tradeoff for keeping the interface uniform across agencies.
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

    // MBTA has no run-number concept the way CTA does -- an ADDED entity's own trip.tripId (a
    // synthetic id MBTA itself assigns) is the closest stand-in: stable for as long as that vehicle
    // assignment stays on the feed, which is all Select Run needs it for. destinationLabel falls
    // back to the bare route_id (e.g. "Green-B", "Blue") since ADDED trips carry no clean destination field
    // to draw a real stop name from -- not worth an extra repository lookup for a first pass.
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
                    // MBTA's own live feed carries no native delay flag the way CTA's ttpositions
                    // does -- see FuzzyRunOption.isDelayed's own doc for why this stays null rather
                    // than a computed guess.
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
