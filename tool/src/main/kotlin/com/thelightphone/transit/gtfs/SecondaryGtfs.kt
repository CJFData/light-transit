package com.thelightphone.transit.gtfs

/**
 * Bustang (CDOT's intercity coach service) publishes its own static GTFS feed, re-hosted at this
 * URL by RTD Denver alongside RTD's own -- merged into RTD's on-device database via
 * [SecondaryGtfsFeed] (see [GtfsAgency.RTD]) rather than being its own separate agency entry, so a
 * rider gets both services' schedules from a single "RTD Denver" selection.
 *
 * Its own GTFS-RT TripUpdates/VehiclePositions feeds live on RTD's open-data host under a
 * "cdot/Bustang_" prefixed path, distinct from RTD's own "rtd/" feeds -- verified live and
 * hand-decoded byte-for-byte on 2026-08-07 (field-by-field, via a raw wire-format Python decode
 * script, same approach as every other agency added so far): every field on the wire already
 * matches GtfsRealtime.kt's existing schema (populated by MBTA/RIPTA/RTD/LTC's own feeds), with
 * nothing undeclared -- unlike every other agency added so far, this one needed no schema changes
 * at all.
 */
val BustangSecondaryFeed = SecondaryGtfsFeed(
    name = "Bustang",
    feedUrl = "https://www.rtd-denver.com/files/gtfs/bustang-co-us.zip",
    realtimeTripUpdatesUrl = "https://open-data.rtd-denver.com/files/gtfs-rt/cdot/Bustang_TripUpdate.pb",
    realtimeVehiclePositionsUrl = "https://open-data.rtd-denver.com/files/gtfs-rt/cdot/Bustang_VehiclePosition.pb",
)