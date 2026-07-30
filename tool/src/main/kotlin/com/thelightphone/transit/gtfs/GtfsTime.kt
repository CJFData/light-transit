package com.thelightphone.transit.gtfs

/**
 * Formats a raw GTFS "HH:MM:SS" time as a 12-hour "H:MM AM/PM" string for display.
 *
 * GTFS hours aren't clamped to 0-23 — a trip departing after midnight on its service day is
 * written as e.g. "25:15:00" rather than "01:15:00", so the hour is taken mod 24 before
 * converting to 12-hour form. Returns [raw] unchanged if it isn't well-formed.
 */
fun formatGtfsTime(raw: String?): String {
    if (raw == null) return "--:--"
    val parts = raw.split(":")
    val rawHour = parts.getOrNull(0)?.toIntOrNull() ?: return raw
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: return raw

    val hour24 = rawHour % 24
    val period = if (hour24 < 12) "AM" else "PM"
    val hour12 = when (val h = hour24 % 12) {
        0 -> 12
        else -> h
    }
    return "%d:%02d %s".format(hour12, minute, period)
}
