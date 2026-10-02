package com.thelightphone.transit.gtfs

/**
 * Bustang (CDOT's intercity coach) publishes its own static GTFS, re-hosted by RTD Denver, and is
 * merged into RTD's database via [MultiGtfsFeed] (see [GtfsAgency.RTD]) so one "RTD Denver"
 * selection covers both. Its GTFS-RT feeds live on RTD's open-data host under a "cdot/Bustang_"
 * path, separate from RTD's own, and decode with the existing schema.
 */
val BustangSecondaryFeed = MultiGtfsFeed(
    name = "Bustang",
    feedUrl = "https://www.rtd-denver.com/files/gtfs/bustang-co-us.zip",
    realtimeTripUpdatesUrl = "https://gtfs.picotransit.com/bustang/tripupdates",
    realtimeVehiclePositionsUrl = "https://gtfs.picotransit.com/bustang/vehiclepositions",
)

/**
 * LA Metro (LACMTA) publishes bus and rail as two separate static GTFS zips rather than one
 * combined feed -- merged here the same way Bustang merges into RTD, since it's the same real
 * operator just split for publishing convenience. Realtime: no [MultiGtfsFeed] URLs set below
 * since none exists in a form this app can use yet -- LA Metro's only live vehicle/trip data is
 * Swiftly (a third-party platform, gated behind an API-key application, and documented by Swiftly
 * itself as server-to-server, not meant for individual client polling) or api.metro.net (a custom
 * JSON REST API rather than actual GTFS-RT protobuf, so wiring it in would need custom translation
 * code, not just a URL swap).
 */
val LaMetroRailSecondaryFeed = MultiGtfsFeed(
    name = "Rail",
    feedUrl = "https://gitlab.com/LACMTA/gtfs_rail/raw/master/gtfs_rail.zip",
)

// NYC Subway no longer needs a MultiGtfsFeed per line group here -- pico-transit-proxy now merges
// all 8 of MTA's own line-group feeds server-side into one combined URL (see
// GtfsAgency.NYC_SUBWAY's own doc and the worker's serveNycSubwayCombinedRoute), so it's just a
// plain single-realtime-URL agency like LIRR/Metro-North, plus NycSubwayTripIdBridge for its
// trip_id decoding.
