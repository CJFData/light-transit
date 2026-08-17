package com.thelightphone.transit.gtfs

import java.io.File
import java.time.ZoneId

/**
 * [realtimeTripUpdatesUrl]/[realtimeVehiclePositionsUrl] are null when an agency has no realtime
 * feed reachable at all. Screens treat "null or fetch failed" identically, so adding/removing a
 * URL here is the only change a screen-level caller ever needs to make.
 *
 * RIPTA's and LTC London's realtime feeds are HTTP-only at the origin with no HTTPS equivalent of
 * their own; every URL below is now a redirect that resolves to HTTPS, so no cleartext exception
 * is needed for any agency here (the old `:netconfig` module is gone).
 *
 * To add a new agency: append an entry below with a unique [id] (uniqueness is enforced at
 * class-load time, see the companion `init` block), its [displayName], its static [feedUrl], and
 * its [timeZoneId] (copy it straight from that feed's own agency.txt `agency_timezone` column --
 * don't guess from the city name). Leave either realtime URL null if that feed doesn't exist.
 * Nothing else needs a matching change -- every screen and preference store iterates [entries]
 * rather than switching on individual agencies. Three things worth checking against the agency's
 * *live* feed before trusting a new entry: (1) all three URLs should resolve to plain HTTPS, same
 * as RIPTA/LTC above; (2) GtfsRealtime.kt's hand-rolled protobuf schema only
 * declares the specific field numbers seen in MBTA/RIPTA/RTD's real feeds so far -- an undeclared
 * field on a new agency's feed can fault the whole GTFS-RT decode (see that file's doc comments),
 * so hand-verify a live sample against it; (3) [timeZoneId] only matters once it differs from
 * every agency added before it -- verify it against the feed's own agency.txt regardless, since a
 * wrong value fails silently (no crash, just wrong ETAs) rather than loudly.
 */
enum class GtfsAgency(
    val id: String,
    val displayName: String,
    val feedUrl: String,
    val realtimeTripUpdatesUrl: String?,
    val realtimeVehiclePositionsUrl: String?,
    /** This agency's own IANA timezone, exactly as declared in its GTFS feed's agency.txt
     * `agency_timezone` column (verified against each agency's real feed, not assumed) -- every
     * GTFS scheduled time is only meaningful relative to the agency's OWN clock, not the rider's
     * device's, so this (not `ZoneId.systemDefault()`) is what [todayForGtfs]/
     * [currentGtfsTimeOfDay]/[gtfsTimeToEpochSeconds] must be anchored to. This only differs from
     * the device's own zone when the rider's phone isn't physically in the agency's own timezone
     * (checking a schedule remotely, or a manually-overridden clock) -- MBTA/RIPTA/LTC all happen
     * to share Eastern with this project's own test devices, which is why RTD (the first
     * Mountain-zone agency added) was the first to expose this having been wrong. */
    val timeZoneId: String,
    /** Optional extra data sources beyond the feed URLs above -- see [AgencyComponent]. Empty
     * for any agency that doesn't have one (e.g. RIPTA, today). A [SecondaryGtfsFeed] entry here
     * is how an agency merges in another feed's static (and, if it ever publishes one, realtime)
     * data -- see [GtfsAgency.RTD]'s Bustang entry. */
    val components: List<AgencyComponent> = emptyList(),
) {
    MBTA(
        "mbta",
        "MBTA",
        "https://cdn.mbta.com/MBTA_GTFS.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/mbta/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/mbta/vehiclepositions",
        timeZoneId = "America/New_York",
        components = listOf(MbtaV3VehicleSource),
    ),
    RIPTA(
        "ripta",
        "RIPTA",
        "https://ripta.com/RIPTA-GTFS.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/ripta/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/ripta/vehiclepositions",
        timeZoneId = "America/New_York",
    ),
    RTD(
        "rtd",
        "RTD Denver",
        "https://www.rtd-denver.com/files/gtfs/google_transit.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/rtd/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/rtd/vehiclepositions",
        timeZoneId = "America/Denver",
        components = listOf(BustangSecondaryFeed),
    ),
    LTC(
        "ltc",
        "LTC Ontario",
        "https://pico-transit-proxy.data-32b.workers.dev/ltc/static",
        "https://pico-transit-proxy.data-32b.workers.dev/ltc/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/ltc/vehiclepositions",
        timeZoneId = "America/Toronto",
    ),
    STM(
        "stm",
        "STM Montréal",
        "https://www.stm.info/sites/default/files/gtfs/gtfs_stm.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/stm/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/stm/vehiclepositions",
        timeZoneId = "America/Montreal",
    ),

    ;

    /** Cached lookup -- [ZoneId.of] parses/interns the zone's rules, no need to redo that on every
     * "what time is it right now for this agency" call. */
    val zoneId: ZoneId by lazy { ZoneId.of(timeZoneId) }

    /** Fetches this agency's own instance of a given [AgencyComponent] type, if it has one -- e.g.
     * `agency.component<PlatformAssignmentSource>()`. Null for any agency/type combination that
     * isn't declared in [components], including every non-MBTA agency today. */
    inline fun <reified T : AgencyComponent> component(): T? = components.filterIsInstance<T>().firstOrNull()

    companion object {
        init {
            // [id] doubles as the "gtfs/{id}/" cache directory name (see [forDbFile]/[gtfsDbFile]) and
            // the DEFAULT_AGENCY/BOARDED_AGENCY preference value -- a copy-pasted entry with an
            // unchanged id silently merges its cache and preferences with whichever other agency
            // already owns that id, rather than failing loudly. Catching it here, at class-load time,
            // means a bad copy-paste fails immediately instead of surfacing as "why is agency X showing
            // agency Y's data."
            val duplicateIds = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
            check(duplicateIds.isEmpty()) {
                "GtfsAgency ids must be unique, got duplicates: $duplicateIds"
            }
        }

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