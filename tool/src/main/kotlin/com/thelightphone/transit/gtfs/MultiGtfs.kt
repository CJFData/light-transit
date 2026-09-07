package com.thelightphone.transit.gtfs

/**
 * Bustang (CDOT's intercity coach service) publishes its own static GTFS feed, re-hosted at this
 * URL by RTD Denver alongside RTD's own -- merged into RTD's on-device database via
 * [MultiGtfsFeed] (see [GtfsAgency.RTD]) rather than being its own separate agency entry, so a
 * rider gets both services' schedules from a single "RTD Denver" selection.
 *
 * Its own GTFS-RT TripUpdates/VehiclePositions feeds live on RTD's open-data host under a
 * "cdot/Bustang_" prefixed path, distinct from RTD's own "rtd/" feeds -- verified live and
 * hand-decoded byte-for-byte, field-by-field, via a raw wire-format Python decode script, same
 * approach used for every other agency here. Every field on the wire already matches
 * GtfsRealtime.kt's existing schema, with nothing undeclared -- no schema changes were needed for
 * this one.
 */
val BustangSecondaryFeed = MultiGtfsFeed(
    name = "Bustang",
    feedUrl = "https://www.rtd-denver.com/files/gtfs/bustang-co-us.zip",
    realtimeTripUpdatesUrl = "https://pico-transit-proxy.data-32b.workers.dev/bustang/tripupdates",
    realtimeVehiclePositionsUrl = "https://pico-transit-proxy.data-32b.workers.dev/bustang/vehiclepositions",
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

/** name -> the matching pico-transit-proxy path segment, same codes as `worker.js`'s own
 * `NYC_SUBWAY_LINE_GROUPS` list (see that file's own doc for how it uses these). */
private val NYC_SUBWAY_LINE_GROUPS = mapOf(
    "BDFM" to "bdfm",
    "G" to "g",
    "JZ" to "jz",
    "NQRW" to "nqrw",
    "L" to "l",
    "Numbered" to "numbered",
    "SIR" to "si",
)

/**
 * The 7 NYC Subway line-group realtime feeds beyond ACE (the primary, see
 * [GtfsAgency.NYC_SUBWAY]) -- unlike LIRR/Metro-North's single combined feed, MTA splits subway
 * realtime across 8 line-group feeds (ACE, BDFM, G, JZ, NQRW, L, numbered lines/1234567S, SIR --
 * verified live, non-overlapping trip_id ranges), each still a single URL combining
 * TripUpdates+VehiclePositions the same way LIRR/Metro-North's is. No real second network here,
 * unlike every other secondary feed in this file -- these 7 have no `feedUrl` of their own (see
 * [MultiGtfsFeed]'s own doc for what that means): every one of their trip_ids already matches
 * trips loaded from NYC Subway's single static feed directly, so they're just 7 more realtime
 * sources layered onto that same already-ingested schedule.
 */
val NycSubwaySecondaryFeeds: List<MultiGtfsFeed> = NYC_SUBWAY_LINE_GROUPS.map { (name, path) ->
    MultiGtfsFeed(
        name,
        realtimeTripUpdatesUrl = "https://pico-transit-proxy.data-32b.workers.dev/nyc_subway/$path",
        realtimeVehiclePositionsUrl = "https://pico-transit-proxy.data-32b.workers.dev/nyc_subway/$path",
    )
}
