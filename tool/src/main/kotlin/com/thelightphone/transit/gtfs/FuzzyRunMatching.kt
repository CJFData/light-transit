package com.thelightphone.transit.gtfs

/** One live fuzzy run's own real data, already reduced to the shape [matchFuzzyRunsOrdinally] needs
 * -- its own soonest predicted time (for ranking against other live runs and scheduled trips) and
 * its full ordered stop-time list (carried into the synthetic [GtfsRtTripUpdate] once matched). */
internal data class FuzzyLiveRun(
    val soonestPredictedEpochSeconds: Long,
    val stopTimeUpdates: List<GtfsRtStopTimeUpdate>,
)

/** One candidate real scheduled trip a live run could be matched to -- its own trip_id and its own
 * soonest remaining scheduled time, for the same rank-ordering [FuzzyLiveRun] uses. */
internal data class ScheduledTripCandidate(
    val tripId: String,
    val soonestScheduledEpochSeconds: Long,
)

/**
 * Ordinal rank-matching shared by every [FuzzyRunTrips] implementation -- see that interface's own
 * doc for why this exists instead of a synthetic trip. Sorts both lists by their own soonest time and
 * pairs them up by position: 1st live run with 1st scheduled trip, 2nd with 2nd, and so on. This is
 * deliberately rank-based, not a nearest-time-delta match -- a live run and a scheduled trip a few
 * minutes apart in absolute time can still be the same real run if everything ahead and behind them
 * lines up the same way, while a naive nearest-delta match could let two live runs both claim the
 * same closest scheduled trip and leave another scheduled trip unmatched despite a live run existing
 * for it.
 *
 * [liveRuns] and [scheduledTrips] must already be scoped to one (route_id, direction_id) group --
 * this function does no grouping itself, since each [FuzzyRunTrips] implementation's own API shape
 * naturally produces data already split by route (CTA's `ttpositions.aspx?rt=` is per-route; MBTA's
 * GTFS-RT entities are filtered to Green Line route_ids before reaching here).
 *
 * A caller with more live runs than scheduled trips (or vice versa) just gets fewer pairs -- the
 * extras are silently dropped rather than force-matched, the same "never force a link" rule every
 * other live source in this app follows.
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
