package com.thelightphone.transit.gtfs

/**
 * See [RealtimeTripIdBridge]'s own doc for the origin-time encoding this decodes -- verified this
 * session against the real static schedule: raw origin_time 120050 (from trip_id "120050_L..N01R")
 * decodes to "20:00:30", matching an actual Weekday L-route [GtfsRepository] departure_time
 * verbatim, along with 120450 -> "20:04:30" and 120850 -> "20:08:30". NYCT's own static feed also
 * already uses plain 00:00-23:59 wraparound for post-midnight departures (confirmed: real rows like
 * "00:06:30" exist), not the extended->24:00:00 notation some agencies use, so no service-day
 * boundary adjustment is needed here.
 */
object NycSubwayTripIdBridge : RealtimeTripIdBridge {
    override fun scheduledStartTime(rawTripId: String): String? {
        val originTime = rawTripId.substringBefore('_').toIntOrNull() ?: return null
        val totalSeconds = (originTime / 100) * 60 + Math.round((originTime % 100) * 0.6)
        val hour = totalSeconds / 3600
        val minute = (totalSeconds % 3600) / 60
        val second = totalSeconds % 60
        return "%02d:%02d:%02d".format(hour, minute, second)
    }
}
