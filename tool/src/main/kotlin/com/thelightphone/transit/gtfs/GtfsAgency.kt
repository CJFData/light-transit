package com.thelightphone.transit.gtfs

import java.io.File

/**
 * [realtimeTripUpdatesUrl]/[realtimeVehiclePositionsUrl] are null when an agency has no realtime
 * feed reachable at all. Screens treat "null or fetch failed" identically, so adding/removing a
 * URL here is the only change a screen-level caller ever needs to make.
 *
 * RIPTA's realtime service is plain-HTTP-only with no HTTPS equivalent. Its two URLs below are
 * reachable through the narrowly scoped cleartext exception provided by the :netconfig module.
 */
enum class GtfsAgency(
    val id: String,
    val displayName: String,
    val feedUrl: String,
    val realtimeTripUpdatesUrl: String?,
    val realtimeVehiclePositionsUrl: String?,
    /** Optional extra data sources beyond the feed URLs above -- see [AgencyComponent]. Empty
     * for any agency that doesn't have one (e.g. RIPTA, today). */
    val components: List<AgencyComponent> = emptyList(),
    /** Additional static feeds merged into this agency's database, with IDs namespaced per feed. */
    val additionalStaticFeedUrls: List<String> = emptyList(),
) {
    MBTA(
        "mbta",
        "MBTA",
        "https://cdn.mbta.com/MBTA_GTFS.zip",
        "https://cdn.mbta.com/realtime/TripUpdates.pb",
        "https://cdn.mbta.com/realtime/VehiclePositions.pb",
        components = listOf(MbtaV3VehicleSource),
    ),
    RTD(
        "rtd",
        "RTD Denver",
        "https://www.rtd-denver.com/files/gtfs/google_transit.zip",
        "https://open-data.rtd-denver.com/files/gtfs-rt/rtd/TripUpdate.pb",
        "https://open-data.rtd-denver.com/files/gtfs-rt/rtd/VehiclePosition.pb",
        additionalStaticFeedUrls = listOf(
            "https://www.rtd-denver.com/files/gtfs/bustang-co-us.zip",
        ),
    ),
    RIPTA(
        "ripta",
        "RIPTA",
        "https://ripta.com/RIPTA-GTFS.zip",
        "http://realtime.ripta.com:81/api/tripupdates?format=gtfs.proto",
        "http://realtime.ripta.com:81/api/vehiclepositions?format=gtfs.proto",
    ),
    ;

    /** Fetches this agency's own instance of a given [AgencyComponent] type, if it has one -- e.g.
     * `agency.component<PlatformAssignmentSource>()`. Null for any agency/type combination that
     * isn't declared in [components], including every non-MBTA agency today. */
    inline fun <reified T : AgencyComponent> component(): T? = components.filterIsInstance<T>().firstOrNull()

    companion object {
        /**
         * Recovers which agency a screen's [dbFile] belongs to, from the same "gtfs/{id}/transit.db"
         * path convention [gtfsDbFile] builds it with — so a screen only needs to carry [dbFile] (which
         * it already does, to run any query at all) to know which agency's live feeds to poll, rather
         * than needing `agency` threaded through as a second, separate parameter everywhere. Driven
         * entirely by [id], so it stays correct with no changes needed if a third agency is added later.
         */
        fun forDbFile(dbFile: File): GtfsAgency? = entries.find { it.id == dbFile.parentFile?.name }
    }
}
