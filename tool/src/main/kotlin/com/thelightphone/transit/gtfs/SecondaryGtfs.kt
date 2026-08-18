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
    realtimeTripUpdatesUrl = "https://pico-transit-proxy.data-32b.workers.dev/bustang/tripupdates",
    realtimeVehiclePositionsUrl = "https://pico-transit-proxy.data-32b.workers.dev/bustang/vehiclepositions",
)

/**
 * LA Metro (LACMTA) publishes bus and rail as two separate static GTFS zips rather than one
 * combined feed -- merged here the same way Bustang merges into RTD, since it's the same real
 * operator just split for publishing convenience, not two agencies a rider would think of
 * separately. Realtime: no [SecondaryGtfsFeed] URLs set below since none exists in a form this app
 * can use yet -- LA Metro's only live vehicle/trip data is Swiftly (a third-party platform, gated
 * behind an API-key application process, and documented by Swiftly itself as server-to-server, not
 * meant for individual client polling) or api.metro.net (a custom JSON REST API despite its
 * "GTFS-rt" branding -- not GTFS-RT protobuf at all, so it wouldn't decode via
 * [GtfsRealtimeClient.fetchFeed] without a bespoke adapter, not a URL swap).
 */
val LaMetroRailSecondaryFeed = SecondaryGtfsFeed(
    name = "Rail",
    feedUrl = "https://gitlab.com/LACMTA/gtfs_rail/raw/master/gtfs_rail.zip",
)