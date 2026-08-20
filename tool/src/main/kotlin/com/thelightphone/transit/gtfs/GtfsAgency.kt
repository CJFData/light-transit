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

    // Every entry below (through the end of this SF Bay Area group) gets realtime through
    // pico-transit-proxy's shared regional-feed passthrough -- one TripUpdates + one
    // VehiclePositions fetch to 511.org per cache window, filtered server-side down to each
    // agency's own entities and re-served under that agency's own URL, so it behaves exactly like
    // a dedicated per-agency feed to every screen in this app (see pico-transit-proxy's own
    // REGIONAL_FEEDS/serveRegionalAgencyRoute, and [RegionalGtfsFeed], attached below). Entries not
    // marked "confirmed live" simply weren't seen with live entities in this project's own
    // one-time regional-feed sample -- a single snapshot, not proof an agency has no live data.
    // BART's VehiclePositions is the one confirmed exception (see that entry).
    //
    // Static feeds stay independent: 26 entries below have a real, independently-verified
    // direct-download static GTFS URL (agency's own domain or an aggregator it actually links to,
    // same as Caltrain's Trillium URL) -- cross-checked against MobilityData's own catalog
    // (github.com/MobilityData/mobility-database-catalogs) 2026-08-20, which turned up 4 agencies
    // (Union City Transit, VINE Transit, Commute.org Shuttles, FAST) that an earlier manual search
    // had missed and originally routed through 511 instead -- moved to direct here once
    // MobilityData's own listed URL was independently confirmed live (still physically grouped
    // with the 511-only entries below rather than moved up here, since grouping is cosmetic --
    // feedUrl is what actually governs behavior). That same pass found 3 agencies (ACE, Petaluma
    // Transit, SMART) whose then-current direct URL was one MobilityData's catalog marks
    // deprecated in favor of 511 -- moved to 511 rather than keep a deprecated source live-but-
    // unsupported (see each entry's own comment). The other 14 go through 511's datafeed API
    // instead -- the one place this group still uses a per-agency 511 route (that API has no
    // regional equivalent; still gets the proxy's normal 6h static-cache treatment) -- 10 of those
    // because no independently-discoverable static feed exists at all, SamTrans because its only
    // direct download is an unstable CMS media-asset link, and the 3 deprecated-direct agencies
    // above (see each entry's own comment). Every timezone was confirmed against the feed's own
    // agency.txt except those 14, which used the well-established America/Los_Angeles regional
    // default instead, since they can't be downloaded without the proxy's own server-side key.

    /** VehiclePositions always comes back empty here -- a real, freshly-timestamped 0-entity
     * FeedMessage, not a caching/rate-limit artifact (confirmed by cache-busting, by comparing
     * against Muni's VehiclePositions on the identical route shape returning real vehicles, and
     * by BART's own GTFS-RT page listing only tripupdate.aspx and alerts.aspx, no
     * vehiclepositions endpoint at all). BART simply doesn't publish vehicle position data
     * anywhere -- structurally absent, not a sampling-window gap. Costs only the
     * moving-vehicle-dot-on-map visualization; ETAs come from TripUpdates alone, and
     * TripDetailScreen's live current-stop indicator still works via inferCurrentStopSequence()
     * (the same fallback RIPTA's feed relies on). TripUpdates: 43/43 sampled trip_ids matched
     * exactly against this agency's own static trips.txt. */
    BART(
        "bart",
        "BART",
        "https://www.bart.gov/dev/schedules/google_transit.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFBA/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFBA/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "BA")),
    ),
    /** 589/589 sampled TripUpdates trip_ids and 269/269 sampled VehiclePositions trip_ids matched
     * exactly against Muni's own static trips.txt. ~1.9M stop_times rows (~37% of STM's row
     * count) -- real size, should be fine under the streaming-download/batched-commit fixes
     * already shipped, but worth a real device ingest test before trusting it the way STM's own
     * test was needed. */
    SFMTA_MUNI(
        "sfmta_muni",
        "SFMTA Muni",
        "https://muni-gtfs.apps.sfmta.com/data/muni_gtfs-current.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSF/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSF/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SF")),
    ),
    /** Static feed URL embeds a token AC Transit itself publishes as a public documentation
     * example (not a secret). Realtime comes from the shared regional feed rather than AC
     * Transit's own domain (needs an individually-registered token this project never obtained).
     * timeZoneId is `US/Pacific` exactly as declared in this feed's own agency.txt (a legacy
     * tzdata alias -- resolves fine via ZoneId.of(), left as-is per this file's own rule). */
    AC_TRANSIT(
        "ac_transit",
        "AC Transit",
        "https://api.actransit.org/transit/gtfs/download?token=2512B81107A09D2DC44895CDDC650D47",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFAC/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFAC/vehiclepositions",
        timeZoneId = "US/Pacific",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "AC")),
    ),
    /** Static feed is Trillium-hosted, not Caltrain's own domain -- it's the exact URL Caltrain's
     * own developer-resources page links to as its GTFS source. Tiny (5.5K stop_times rows), no
     * size concern. */
    CALTRAIN(
        "caltrain",
        "Caltrain",
        "https://data.trilliumtransit.com/gtfs/caltrain-ca-us/caltrain-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCT/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCT/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CT")),
    ),
    /** Transitland's own operator record already listed the 511 regional feed as VTA's only live
     * source, which is exactly what this group now uses. */
    VTA(
        "vta",
        "VTA",
        "https://gtfs.vta.org/gtfs_vta.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSC/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SC")),
    ),

    /** County Connection's own domain, confirmed live. */
    COUNTY_CONNECTION(
        "county_connection",
        "County Connection",
        "https://countyconnection.com/GTFS/google_transit.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCC/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CC")),
    ),
    /** ACE's own CDN URL still resolved live as of 2026-08-20, but MobilityData's catalog marks it
     * deprecated in favor of 511's datafeed API -- switched to that instead of keeping a
     * deprecated source live-but-unsupported. Tiny feed (~10-station commuter rail line). */
    ACE(
        "ace",
        "ACE",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCE/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCE/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCE/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CE")),
    ),
    /** Santa Cruz METRO's own developer portal, confirmed live (recently moved off their old
     * scmtd.com domain to developer.scmetro.org). */
    SANTA_CRUZ_METRO(
        "santa_cruz_metro",
        "Santa Cruz METRO",
        "https://developer.scmetro.org/gtfs.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCR/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCR/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CR")),
    ),
    /** Capitol Corridor's own domain, confirmed live. Intercity rail -- double-checked timezone
     * against agency.txt rather than assuming. */
    CAPITOL_CORRIDOR(
        "capitol_corridor",
        "Capitol Corridor",
        "https://www.capitolcorridor.org/googletransit/GTFS.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFAM/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFAM/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "AM")),
    ),
    /** Emery Go-Round's own domain, confirmed live -- but only with a real browser User-Agent (a
     * bare request gets 406), same pattern already seen with CTA's feed above. */
    EMERY_GO_ROUND(
        "emery_go_round",
        "Emery Go-Round",
        "https://emerygoround.com/data/emerygoround-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFEM/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFEM/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "EM")),
    ),
    /** Golden Gate Transit's own domain, confirmed live. This is the bus network specifically --
     * Golden Gate Ferry is a separate operator/feed ([GOLDEN_GATE_FERRY], 511 code GF). */
    GOLDEN_GATE_TRANSIT(
        "golden_gate_transit",
        "Golden Gate Transit",
        "https://realtime.goldengate.org/gtfsstatic/GTFSTransitData.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFGG/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFGG/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "GG")),
    ),
    /** Marin Transit's own domain, confirmed live. */
    MARIN_TRANSIT(
        "marin_transit",
        "Marin Transit",
        "https://marintransit.gov/data/google_transit.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFMA/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFMA/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MA")),
    ),
    /** Trillium-hosted, confirmed live -- no independent Mission Bay TMA domain found, but
     * Trillium is this agency's own registered source per the Mobility Database. */
    MISSION_BAY_TMA(
        "mission_bay_tma",
        "Mission Bay TMA",
        "https://data.trilliumtransit.com/gtfs/missionbaytma-ca-us/missionbaytma-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFMB/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFMB/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MB")),
    ),
    /** Mountain View Community Shuttle's own subdomain, confirmed live. This TMA also runs its
     * own GTFS-RT via TripShot (mtma.tripshot.com) -- not wired here, the shared 511 regional
     * feed is used instead. */
    MOUNTAIN_VIEW_COMMUNITY_SHUTTLE(
        "mountain_view_community_shuttle",
        "Mountain View Community Shuttle",
        "https://gtfs.mvcommunityshuttle.com/gtfs.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFMC/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFMC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MC")),
    ),
    /** MVgo's own subdomain, confirmed live -- same Mountain View TMA / TripShot situation as
     * [MOUNTAIN_VIEW_COMMUNITY_SHUTTLE] above. */
    MVGO(
        "mvgo",
        "MVgo",
        "https://gtfs.mvgo.org/gtfs.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFMV/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFMV/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MV")),
    ),
    /** The Trillium URL Petaluma Transit's own site links to still resolved live as of
     * 2026-08-20, but MobilityData's catalog marks it deprecated in favor of 511's datafeed API --
     * switched to that instead of keeping a deprecated source live-but-unsupported. */
    PETALUMA_TRANSIT(
        "petaluma_transit",
        "Petaluma Transit",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFPE/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFPE/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFPE/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "PE")),
    ),
    /** Trillium-hosted, confirmed live. Tiny feed. */
    RIO_VISTA_DELTA_BREEZE(
        "rio_vista_delta_breeze",
        "Rio Vista Delta Breeze",
        "https://data.trilliumtransit.com/gtfs/riovista-ca-us/riovista-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFRV/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFRV/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "RV")),
    ),
    /** sonomamarintrain.org's own site has no direct GTFS link -- Trillium was SMART's registered
     * source, and that URL still resolved live as of 2026-08-20, but MobilityData's catalog marks
     * it deprecated in favor of 511's datafeed API -- switched to that instead of keeping a
     * deprecated source live-but-unsupported. */
    SMART(
        "smart",
        "SMART",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSA/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSA/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSA/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SA")),
    ),
    /** San Francisco Bay Ferry's own domain (not Trillium), confirmed live. */
    SF_BAY_FERRY(
        "sf_bay_ferry",
        "SF Bay Ferry",
        "https://gtfs.sanfranciscobayferry.com/gtfs.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSB/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSB/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SB")),
    ),
    /** Trillium-hosted, confirmed live. Tiny feed. MobilityData's catalog flags this feed
     * "inactive" (re-checked 2026-08-20) -- the URL itself still returns a real, current-looking
     * GTFS zip, so this may just mean MobilityData isn't actively monitoring it rather than the
     * service having actually shut down; worth a periodic re-check rather than trusting either
     * signal alone. */
    SAN_LEANDRO_LINKS(
        "san_leandro_links",
        "San Leandro LINKS",
        "https://data.trilliumtransit.com/gtfs/sanleandro-ca-us/sanleandro-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSL/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSL/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SL")),
    ),
    /** Static feed routed through 511's datafeed API rather than SamTrans' own domain -- their
     * only direct download is a CMS media-asset link (samtrans.com/media/37384/download) with a
     * numeric ID that isn't guaranteed stable across their own file replacements, unlike every
     * other direct-download entry in this group. Confirmed live in this project's own
     * regional-feed sample. */
    SAMTRANS(
        "samtrans",
        "SamTrans",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSM/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSM/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSM/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SM")),
    ),
    /** Trillium-hosted, confirmed live -- sctransit.com's own developer-data page didn't resolve
     * when checked, but this URL is cited across multiple third-party catalogs and resolves fine
     * on its own. */
    SONOMA_COUNTY_TRANSIT(
        "sonoma_county_transit",
        "Sonoma County Transit",
        "https://data.trilliumtransit.com/gtfs/sonomacounty-ca-us/sonomacounty-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSO/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSO/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SO")),
    ),
    /** Santa Rosa CityBus' own Syncromatics vendor subdomain, confirmed live -- serves a real zip
     * (PK magic bytes, valid agency.txt/stops.txt entries) despite a misleading `text/plain`
     * response content-type, verified by inspecting the raw bytes directly rather than trusting
     * the header. Not in MobilityData's catalog at all (checked 2026-08-20; its own listed
     * current source is 511's datafeed API) -- an independently-discovered third source, same
     * situation as ACE's own CDN above. */
    SANTA_ROSA_CITYBUS(
        "santa_rosa_citybus",
        "Santa Rosa CityBus",
        "https://santarosa.syncromatics.com/gtfs",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSR/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSR/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SR")),
    ),
    /** SolTrans' own Connexionz vendor subdomain, confirmed live. */
    SOLTRANS(
        "soltrans",
        "SolTrans",
        "https://soltrans.connexionz.net/rtt/public/resource/gtfs.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFST/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFST/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "ST")),
    ),
    /** Trillium-hosted, confirmed live -- explicitly linked from WestCat's own "Data Request"
     * page as their most recent GTFS schedule data, not just a third-party guess. */
    WESTCAT(
        "westcat",
        "WestCat",
        "https://data.trilliumtransit.com/gtfs/westcat-ca-us/westcat-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFWC/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFWC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "WC")),
    ),
    /** Trillium-hosted, confirmed live -- LAVTA's own webwatch.lavta.org host (cited by
     * Transitland as the authoritative source) 404s on every path tried; this Trillium URL is
     * the one that actually resolves. */
    LAVTA_WHEELS(
        "lavta_wheels",
        "LAVTA Wheels",
        "https://data.trilliumtransit.com/gtfs/lavta-ca-us/lavta-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFWH/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFWH/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "WH")),
    ),

    // 10 of the entries below have no independently-discoverable static GTFS download at all --
    // 511's datafeed API is their only public source, so as an exception their feedUrl goes
    // through the proxy's 511 passthrough instead of a direct agency URL (still gets the normal 6h
    // static-cache treatment). SamTrans is here because its only direct download is an unstable
    // CMS media-asset link, and ACE/Petaluma Transit/SMART are here because MobilityData's catalog
    // marks their formerly-direct URL deprecated in favor of 511 (see this group's own
    // top-of-block comment, and each entry's own comment). Commute.org Shuttles/FAST/Union City
    // Transit/VINE Transit, further down, DO have a direct feedUrl now (see this group's own
    // top-of-block comment) -- left physically grouped here with the rest of this batch rather
    // than moved, since that's cosmetic and doesn't affect behavior.

    /** No independent static feed found (checked trideltatransit.com directly) -- 511-datafeed-
     * API only, via the proxy. Confirmed live in this project's own regional-feed sample. */
    TRI_DELTA(
        "tri_delta",
        "Tri Delta Transit",
        "https://pico-transit-proxy.data-32b.workers.dev/511SF3D/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SF3D/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SF3D/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "3D")),
    ),
    /** No independent static feed found (checked angelislandferry.com directly) -- 511-datafeed-
     * API only, via the proxy. */
    ANGEL_ISLAND_TIBURON_FERRY(
        "angel_island_tiburon_ferry",
        "Angel Island Tiburon Ferry",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFAF/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFAF/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFAF/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "AF")),
    ),
    /** The URL surfaced by search (commute.org/files/gtfs/Masterzip.zip) is dead -- but
     * MobilityData's catalog lists a separate, Trillium-hosted URL this project's own manual
     * search missed, confirmed live 2026-08-20. */
    COMMUTE_ORG_SHUTTLES(
        "commute_org_shuttles",
        "Commute.org Shuttles",
        "https://data.trilliumtransit.com/gtfs/commute-ca-us/commute-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCM/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFCM/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CM")),
    ),
    /** No independent static feed found -- operated by WestCat, but not folded into [WESTCAT]'s
     * own feed above (checked). 511-datafeed-API only, via the proxy. */
    DUMBARTON_EXPRESS(
        "dumbarton_express",
        "Dumbarton Express",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFDE/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFDE/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFDE/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "DE")),
    ),
    /** Genuinely ambiguous, not just unfound: 511 lists this as its own distinct code (separate
     * LastGenerated timestamp from Emery Go-Round's) but the only URL discoverable by hand
     * (emerygoround.com) turned out to serve [EMERY_GO_ROUND]'s own feed, with no way to confirm
     * whether "Emery Express" is genuinely separate data or the same TMA's feed under a second
     * name. Routing through 511's own operator-scoped datafeed API sidesteps the ambiguity --
     * whatever 511 itself considers distinct under this code is what gets served. */
    EMERY_EXPRESS(
        "emery_express",
        "Emery Express",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFEE/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFEE/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFEE/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "EE")),
    ),
    /** fasttransit.org itself has no GTFS link -- but MobilityData's catalog lists a separate,
     * Trillium-hosted URL this project's own manual search missed, confirmed live 2026-08-20. */
    FAST_TRANSIT(
        "fast_transit",
        "FAST",
        "https://data.trilliumtransit.com/gtfs/fairfield-ca-us/fairfield-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFFS/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFFS/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "FS")),
    ),
    /** No independent static feed found -- Transitland's own feed record lists 511's own
     * datafeed API as its "Current Static GTFS" source, confirming no agency-hosted feed exists.
     * Separate operator from [GOLDEN_GATE_TRANSIT] above (bus vs. ferry are distinct 511 codes).
     * 511-datafeed-API only, via the proxy. */
    GOLDEN_GATE_FERRY(
        "golden_gate_ferry",
        "Golden Gate Ferry",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFGF/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFGF/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFGF/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "GF")),
    ),
    /** No independent static feed found (checked presidio.gov directly) -- 511-datafeed-API
     * only, via the proxy. Confirmed live in this project's own regional-feed sample
     * (VehiclePositions). */
    PRESIDIO_GO(
        "presidio_go",
        "Presidio Go",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFPG/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFPG/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFPG/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "PG")),
    ),
    /** No independent static feed found (checked flysfo.com directly) -- 511-datafeed-API only,
     * via the proxy. Confirmed live in this project's own regional-feed sample. */
    SFO_AIRPORT(
        "sfo_airport",
        "SFO Airport",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSI/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSI/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSI/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SI")),
    ),
    /** No independent static feed found -- distributed only via commute.org/511's regional
     * aggregate feed, not as a standalone source. 511-datafeed-API only, via the proxy. */
    SOUTH_SAN_FRANCISCO(
        "south_san_francisco",
        "South San Francisco Shuttle",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSS/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSS/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFSS/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SS")),
    ),
    /** No independent static feed found (checked tisf.com directly) -- described everywhere as
     * "published by MTC" rather than the ferry operator itself. 511-datafeed-API only, via the
     * proxy. */
    TREASURE_ISLAND_FERRY(
        "treasure_island_ferry",
        "Treasure Island Ferry",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFTF/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFTF/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFTF/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "TF")),
    ),
    /** Transitland's own authoritative-source field points at 511's datafeed API, but
     * MobilityData's catalog separately lists a Trillium-hosted URL this project's own earlier
     * search missed, confirmed live 2026-08-20. */
    UNION_CITY_TRANSIT(
        "union_city_transit",
        "Union City Transit",
        "https://data.trilliumtransit.com/gtfs/unioncity-ca-us/unioncity-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFUC/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFUC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "UC")),
    ),
    /** No independent static feed found (checked citycoach.com directly; an old TransitFeeds
     * copy exists but is stale/deprecated). 511-datafeed-API only, via the proxy. */
    VACAVILLE_CITY_COACH(
        "vacaville_city_coach",
        "Vacaville City Coach",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFVC/static",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFVC/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFVC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "VC")),
    ),
    /** vinetransit.com itself has no GTFS/developer link -- but MobilityData's catalog lists a
     * separate, Trillium-hosted URL this project's own manual search missed, confirmed live
     * 2026-08-20. Confirmed live in this project's own regional-feed sample too
     * (VehiclePositions). */
    VINE_TRANSIT(
        "vine_transit",
        "VINE Transit",
        "https://data.trilliumtransit.com/gtfs/vinetransit-ca-us/vinetransit-ca-us.zip",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFVN/tripupdates",
        "https://pico-transit-proxy.data-32b.workers.dev/511SFVN/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "VN")),
    ),

    /** Bus (primary) + Rail ([LaMetroRailSecondaryFeed], see that file's own doc) -- LACMTA
     * publishes them as two separate static zips for the same real operator, merged the same way
     * Bustang merges into RTD. Realtime: Swiftly (API-key application, server-to-server per
     * Swiftly's own docs, not meant for individual client polling) or api.metro.net (a custom JSON
     * REST API despite its "GTFS-rt" branding -- not GTFS-RT protobuf, needs a bespoke adapter, not
     * a URL swap) -- neither wired here. */
    LA_METRO(
        "la_metro",
        "LA Metro (No Live)",
        "https://gitlab.com/LACMTA/gtfs_bus/-/raw/master/gtfs_bus.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
        components = listOf(LaMetroRailSecondaryFeed),
    ),
    /** No standard GTFS-RT feed used here -- CTA's own undocumented
     * transitdata.transitchicago.com/GtfsRealtime/{TripUpdates,VehiclePositions}.pb endpoint sits
     * behind Cloudflare bot-protection and is unverified against GtfsRealtime.kt's schema, so
     * realtime instead comes from
     * CTA's own proprietary, documented APIs, wired as [AgencyComponent]s rather than a
     * [realtimeTripUpdatesUrl]/[realtimeVehiclePositionsUrl] swap: [CtaBusTrackerSource] (Bus
     * Tracker) matches a live bus back to a real trip_id via its own scheduled-start-time fields
     * (see that class's own doc) and is fully wired below. CTA Train Tracker ('L' trains) is NOT
     * similarly trip-matched -- it identifies trains by run number, with no static-GTFS field that
     * bridges back to a trip_id (confirmed against CTA's own current export), so train positions
     * are shown as map markers only, never linked to a specific scheduled trip or trip detail
     * screen. ~6.0M stop_times rows -- larger than STM's 5.1M that already needed the streaming/
     * batching fixes; same order of magnitude, not UK-BODS-regional scale, but wants its own real
     * device ingest test before being trusted. */
    CTA(
        "cta",
        "CTA (Partial Live)",
        "https://www.transitchicago.com/downloads/sch_data/google_transit.zip",
        null,
        null,
        timeZoneId = "America/Chicago",
        components = listOf(CtaBusTrackerSource),
    ),
    /** Realtime exists (30s refresh, gtfspublic.metrarr.com) but requires submitting Metra's own
     * GTFS-RT license agreement request form before a key is issued -- not wired here,
     * field-compatibility unverified. Tiny static feed (76K stop_times rows), no size concern. */
    METRA(
        "metra",
        "Metra (No Live)",
        "https://schedules.metrarail.com/gtfs/schedule.zip",
        null,
        null,
        timeZoneId = "America/Chicago",
    ),
    /** No GTFS-RT feed exists for Pace at all -- confirmed, live predictions are only shown on
     * Pace's own Bus Tracker web page, never published as a downloadable feed. Static schedule only.
     * Pace's own GTFS itself only covers routes with their "Intelligent Bus System" equipment
     * installed, so even this static feed may not represent every Pace route. */
    PACE(
        "pace",
        "Pace (No Live)",
        "https://www.pacebus.com/sites/default/files/2026-08/GTFS.zip",
        null,
        null,
        timeZoneId = "America/Chicago",
    ),
    /** Realtime needs real work before it's safe to wire in, more than any agency added so far: it's
     * split across 8 separate live feeds with non-overlapping trip_id ranges (one per line group --
     * verified live), no API key needed but a real User-Agent header is required (HEAD requests get
     * a 403, likely a WAF rule). Every entity on every feed carries NYCT's own protobuf extension
     * (TripDescriptor field 1001 -- train_id/is_assigned/direction -- present on 100% of both
     * TripUpdates and VehiclePositions sampled) plus FeedEntity fields 2/5, VehiclePosition field 6,
     * and StopTimeUpdate fields 7 and 1001 (also 100% present), none of which GtfsRealtime.kt
     * declares today -- this isn't an edge case to shrug off the way some other agencies' unused
     * fields were, the whole feed would fault on decode exactly like RIPTA/LTC/RTD did before their
     * fixes. Static feed itself is small (565K stop_times rows), no size concern. */
    NYC_SUBWAY(
        "nyc_subway",
        "NYC Subway (No Live)",
        "https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    /** Realtime: no key needed, HTTPS, one combined TripUpdates+VehiclePositions feed -- wired in
     * below. GtfsRealtime.kt's schema needed one addition for this ([GtfsRtStopTimeUpdate]'s field
     * 1005, the MTA railroads' own scheduled/actual-track extension) -- hand-verified live against
     * this exact feed (wire type 2, decodes as valid UTF-8 track labels), see that field's own doc
     * comment. calendar_dates.txt-only (no calendar.txt) is fine -- verified
     * GtfsRepository's activeTodayClause already handles a service_id with zero `calendar` rows via
     * its independent calendar_dates-addition branch, same standard GTFS pattern many agencies use.
     * Tiny feed (24K stop_times rows). */
    LIRR(
        "lirr",
        "LIRR",
        "https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/lirr%2Fgtfs-lirr",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/lirr%2Fgtfs-lirr",
        timeZoneId = "America/New_York",
    ),
    /** Same situation as LIRR -- no key, HTTPS, one combined feed, wired in below. Shares
     * [GtfsRtStopTimeUpdate]'s field 1005 (hand-verified live against this feed too, same nested
     * track-label shape as LIRR's, though the sub-field contents differ slightly -- e.g. a
     * "Departed" status string where LIRR's was another track code). No calendar.txt in this feed
     * either (only calendar_dates.txt) -- confirmed fine for the same reason noted on [LIRR].
     * ~380K stop_times rows, no size concern. */
    METRO_NORTH(
        "metro_north",
        "Metro-North",
        "https://rrgtfsfeeds.s3.amazonaws.com/gtfsmnr.zip",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/mnr%2Fgtfs-mnr",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/mnr%2Fgtfs-mnr",
        timeZoneId = "America/New_York",
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