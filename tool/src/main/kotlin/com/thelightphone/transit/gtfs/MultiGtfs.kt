package com.thelightphone.transit.gtfs

/**
 * Bustang's schedule, merged into RTD Denver (see [GtfsAgency.RTD]) so one selection covers both.
 * Its realtime feeds are separate from RTD's.
 */
val BustangSecondaryFeed = MultiGtfsFeed(
    name = "Bustang",
    feedUrl = "https://www.rtd-denver.com/files/gtfs/bustang-co-us.zip",
    realtimeTripUpdatesUrl = "https://gtfs.picotransit.com/bustang/tripupdates",
    realtimeVehiclePositionsUrl = "https://gtfs.picotransit.com/bustang/vehiclepositions",
)

/**
 * LA Metro Rail, published separately from the bus schedule and merged into [GtfsAgency.LA_METRO].
 * Schedule only.
 */
val LaMetroRailSecondaryFeed = MultiGtfsFeed(
    name = "Rail",
    feedUrl = "https://gitlab.com/LACMTA/gtfs_rail/raw/master/gtfs_rail.zip",
)

