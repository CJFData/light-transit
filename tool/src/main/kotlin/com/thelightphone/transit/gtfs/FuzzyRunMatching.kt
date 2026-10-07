package com.thelightphone.transit.gtfs

/** A live run reduced to what matching needs: its soonest predicted time and its stop times. */
internal data class FuzzyLiveRun(
    val soonestPredictedEpochSeconds: Long,
    val stopTimeUpdates: List<GtfsRtStopTimeUpdate>,
)

/** A scheduled trip a live run could match, with its soonest remaining time. */
internal data class ScheduledTripCandidate(
    val tripId: String,
    val soonestScheduledEpochSeconds: Long,
)

/**
 * Pairs live runs with scheduled trips by rank: both sorted by soonest time, 1st with 1st, 2nd with
 * 2nd. Ranking keeps two runs from claiming the same nearest trip. Both lists must already be one
 * route and direction. Extras on either side go unmatched.
 */
internal fun matchFuzzyRunsOrdinally(
    liveRuns: List<FuzzyLiveRun>,
    scheduledTrips: List<ScheduledTripCandidate>,
): Map<String, GtfsRtTripUpdate> {
    val sortedRuns = liveRuns.sortedBy { it.soonestPredictedEpochSeconds }
    val sortedTrips = scheduledTrips.sortedBy { it.soonestScheduledEpochSeconds }
    return sortedRuns.zip(sortedTrips).associate { (run, trip) ->
        trip.tripId to GtfsRtTripUpdate(
            trip = GtfsRtTripDescriptor(tripId = trip.tripId),
            stopTimeUpdate = run.stopTimeUpdates,
        )
    }
}
