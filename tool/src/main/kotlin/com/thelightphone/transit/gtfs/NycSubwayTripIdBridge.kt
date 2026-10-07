package com.thelightphone.transit.gtfs

/**
 * Decodes the origin time in NYC subway realtime trip_ids (see [RealtimeTripIdBridge]), e.g. 120050
 * to "20:00:30". The static schedule writes after-midnight times as 00:00 onward, so no service-day
 * adjustment is needed.
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
