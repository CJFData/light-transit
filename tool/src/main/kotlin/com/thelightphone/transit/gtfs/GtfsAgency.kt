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
 * To add a new agency: append an entry below with a unique [id] (enforced at class-load time, see
 * the companion `init` block), its [displayName], its static [feedUrl], and its [timeZoneId]
 * (copy it straight from that feed's own agency.txt `agency_timezone` column -- don't guess from
 * the city name). Leave either realtime URL null if that feed doesn't exist. Nothing else needs a
 * matching change -- every screen and preference store iterates [entries] rather than switching on
 * individual agencies. Three things worth checking against the agency's live feed first: (1) all
 * three URLs should resolve to plain HTTPS, same as RIPTA/LTC above; (2) GtfsRealtime.kt's
 * hand-rolled protobuf schema only declares field numbers seen in agencies added so far -- an
 * undeclared field on a new feed can fault the whole GTFS-RT decode (see that file's doc
 * comments), so hand-verify a live sample; (3) [timeZoneId] only matters once it differs from
 * every agency added before it -- verify it against the feed's own agency.txt regardless, since a
 * wrong value fails silently rather than loudly.
 */
enum class GtfsAgency(
    val id: String,
    val displayName: String,
    val feedUrl: String,
    val realtimeTripUpdatesUrl: String?,
    val realtimeVehiclePositionsUrl: String?,
    /** This agency's own IANA timezone, exactly as declared in its GTFS feed's agency.txt
     * `agency_timezone` column (verified against each agency's real feed, not assumed) -- every
     * GTFS scheduled time is only meaningful relative to the agency's own clock, not the rider's
     * device's, so this (not `ZoneId.systemDefault()`) is what [todayForGtfs]/
     * [currentGtfsTimeOfDay]/[gtfsTimeToEpochSeconds] must be anchored to. Only differs from the
     * device's own zone when the phone isn't physically in the agency's timezone -- MBTA/RIPTA/LTC
     * all happen to share Eastern with this project's test devices, which is why RTD (the first
     * Mountain-zone agency) was the first to expose this having been wrong. */
    val timeZoneId: String,
    /** Optional extra data sources beyond the feed URLs above -- see [AgencyComponent]. Empty for
     * any agency that doesn't have one. A [MultiGtfsFeed] entry here is how an agency merges in
     * another feed's static (and, if it ever publishes one, realtime) data -- see
     * [GtfsAgency.RTD]'s Bustang entry -- or, with no static feed of its own, just an extra
     * realtime feed layered onto this agency's own already-ingested schedule -- see
     * [GtfsAgency.NYC_SUBWAY]. */
    val components: List<AgencyComponent> = emptyList(),
) {
    MBTA(
        "mbta",
        "MBTA",
        "https://cdn.mbta.com/MBTA_GTFS.zip",
        "https://gtfs.picotransit.com/mbta/tripupdates",
        "https://gtfs.picotransit.com/mbta/vehiclepositions",
        timeZoneId = "America/New_York",
        // See MbtaGreenLineFuzzyRunSource's own doc -- Green Line's own live feed marks the vast
        // majority of its running vehicles as ADDED trips with no real static trip to match to;
        // Orange/Red/Blue aren't affected and keep using ordinary trip_id matching.
        components = listOf(MbtaV3VehicleSource, MbtaGreenLineFuzzyRunSource),
    ),
    RIPTA(
        "ripta",
        "RIPTA",
        "https://ripta.com/RIPTA-GTFS.zip",
        "https://gtfs.picotransit.com/ripta/tripupdates",
        "https://gtfs.picotransit.com/ripta/vehiclepositions",
        timeZoneId = "America/New_York",
        // Pilot agency for TripShapeSource -- see StaticGtfsShapeSource's own doc for why this reads
        // shapes.txt on demand from the already-downloaded zip rather than through ingestion.
        components = listOf(StaticGtfsShapeSource),
    ),
    RTD(
        "rtd",
        "RTD Denver",
        "https://www.rtd-denver.com/files/gtfs/google_transit.zip",
        "https://gtfs.picotransit.com/rtd/tripupdates",
        "https://gtfs.picotransit.com/rtd/vehiclepositions",
        timeZoneId = "America/Denver",
        components = listOf(BustangSecondaryFeed),
    ),
    /** Bustang (CDOT's intercity coach service) already merges into RTD Denver via
     * [BustangSecondaryFeed] above, but a rider looking it up directly (not through RTD) wants
     * their own selectable entry too -- same static feed and same already-verified realtime URLs
     * as that component (its own feedUrl redirects through RTD's nodejs-prod API host, confirmed
     * live again this session). */
    BUSTANG(
        "bustang",
        "Bustang",
        "https://www.rtd-denver.com/files/gtfs/bustang-co-us.zip",
        "https://gtfs.picotransit.com/bustang/tripupdates",
        "https://gtfs.picotransit.com/bustang/vehiclepositions",
        timeZoneId = "America/Denver",
    ),
    /**
     * The rest of Colorado's ~40 agencies listed at colorado-gtfs.trilliumtransit.com (see the
     * "Colorado rollout" plan) -- static schedule data only, per this project's established
     * per-agency verification discipline: no GTFS-RT feed is published for any agency on that
     * aggregator page (confirmed again this session), so every entry below gets a "(No Live)"
     * displayName suffix, same convention as LA Metro/Metra/Pace elsewhere in this file. Each
     * feed's own agency.txt was hand-checked live this session for its real `agency_timezone`
     * rather than assumed from the city name -- every one declares `America/Denver` except where
     * individually noted below. Two small clusters here share byte-identical zip content across
     * multiple rider-facing names (Boulder County/Via Mobility, and San Miguel Authority/Town of
     * Mountain Village/Town of Telluride) -- kept as separate selectable entries anyway, same
     * reasoning as Bustang above: a rider searching by the name they actually know shouldn't need
     * to already know it's the same underlying feed as some other entry. Two other agencies from
     * the same source list (COLT, Cripple Creek Transportation) are left out entirely -- both
     * URLs 403'd with an HTML page instead of a real feed link, and need a corrected source before
     * they can be added.
     */
    ALL_POINTS_TRANSIT(
        "all_points_transit",
        "All Points Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/allpointstransit-co-us/allpointstransit-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    AVON_TRANSIT(
        "avon_transit",
        "Avon Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/avon-co-us/avon-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** GTFS-Flex (demand-response) feed -- real but sparse fixed-route data (426B stop_times.txt),
     * confirmed live rather than broken; the app already tolerates a route with little/no
     * scheduled service (see FuzzyRunTrips' NoTrips handling). */
    BACA_AREA_TRANSPORTATION(
        "baca_area_transportation",
        "Baca Area Transportation (No Live)",
        "https://data.trilliumtransit.com/gtfs/bacacounty-co-us/bacacounty-co-us--flex-v2.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    BENT_COUNTY_TRANSPORTATION(
        "bent_county_transportation",
        "Bent County Transportation (No Live)",
        "https://data.trilliumtransit.com/gtfs/bentcounty-co-us/bentcounty-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    BLACKHAWK_CENTRAL_CITY_TRAMWAY(
        "blackhawk_central_city_tramway",
        "Blackhawk and Central City Tramway (No Live)",
        "https://data.trilliumtransit.com/gtfs/blackhawktramway-co-us/blackhawktramway-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** See this cluster's shared doc note above -- byte-identical feed to VIA_MOBILITY below (the
     * zip's own agency.txt lists "Boulder County"/"City of Boulder", not "Via Mobility", as its
     * two agency_name rows). */
    BOULDER_COUNTY(
        "boulder_county",
        "Boulder County (No Live)",
        "https://data.trilliumtransit.com/gtfs/viamobilityservices-co-us/viamobilityservices-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    BRECKENRIDGE_FREE_RIDE(
        "breckenridge_free_ride",
        "Breckenridge Free Ride (No Live)",
        "https://data.trilliumtransit.com/gtfs/breckenridgefreeride-co-us/breckenridgefreeride-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    BUSTANG_OUTRIDER(
        "bustang_outrider",
        "Bustang Outrider (No Live)",
        "https://data.trilliumtransit.com/gtfs/outrider-co-us/outrider-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    CITY_OF_FOUNTAIN_TRANSIT(
        "city_of_fountain_transit",
        "City of Fountain Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/cityoffountaintransit-co-us/cityoffountaintransit-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    CLEAR_CREEK_COUNTY_TRANSIT(
        "clear_creek_county_transit",
        "Clear Creek County Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/clearcreek-co-us/clearcreek-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** Confirmed live this session -- redirects cross-domain to evta.org (the real operator's own
     * domain), not a coretransit.org-hosted file directly. */
    CORE_TRANSIT(
        "core_transit",
        "Core Transit (No Live)",
        "https://gtfs.coretransit.org/",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** GTFS-Flex feed, sparse (292B stop_times.txt). Its own agency.txt declares `US/Mountain`,
     * not `America/Denver` like every other Colorado feed here -- a legacy IANA alias for the same
     * zone (same UTC offset, same DST rules), used verbatim rather than normalized, since
     * java.time.ZoneId resolves it correctly as-is. */
    DOLORES_COUNTY(
        "dolores_county",
        "Dolores County (No Live)",
        "https://data.trilliumtransit.com/gtfs/dolorescounty-co-us/dolorescounty-co-us--flex-v2.zip",
        null,
        null,
        timeZoneId = "US/Mountain",
    ),
    /** URL is a document-viewer page, not a bare .zip -- still resolves to a real zip body
     * (`application/octet-stream` content-type, valid PK bytes), confirmed live this session. */
    DURANGO_TRANSIT(
        "durango_transit",
        "Durango Transit (No Live)",
        "https://durangogov.org/DocumentCenter/View/17688/Durango-Transit-GTFS-Data",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** GTFS-Flex feed, sparse (356B stop_times.txt) -- confirmed live, not broken. */
    EASY_RIDE_TRANSPORTATION(
        "easy_ride_transportation",
        "Easy Ride Transportation (No Live)",
        "https://data.trilliumtransit.com/gtfs/broomfield-co-us/broomfield-co-us--flex-v2.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** GTFS-Flex feed, sparse (416B stop_times.txt) -- confirmed live, not broken. */
    EL_PASO_FOUNTAIN_VALLEY_SENIORS(
        "el_paso_fountain_valley_seniors",
        "El Paso Fountain Valley Senior Citizens Program Inc. (No Live)",
        "https://data.trilliumtransit.com/gtfs/elpaso-co-us/elpaso-co-us--flex-v2.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    ENVIDA(
        "envida",
        "Envida (No Live)",
        "https://data.trilliumtransit.com/gtfs/dsi-co-us/dsi-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    EPIC_MOUNTAIN_EXPRESS(
        "epic_mountain_express",
        "Epic Mountain Express (No Live)",
        "https://data.trilliumtransit.com/gtfs/cme-co-us/cme-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    ESTES_TRANSIT(
        "estes_transit",
        "Estes Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/estestransit-co-us/estestransit-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    GARDEN_OF_THE_GODS(
        "garden_of_the_gods",
        "Garden of the Gods (No Live)",
        "https://data.trilliumtransit.com/gtfs/gardenofthegods-co-us/gardenofthegods-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    GREELEY_EVANS_TRANSIT(
        "greeley_evans_transit",
        "Greeley-Evans Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/greeleyevans-co-us/greeleyevans-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    GUNNISON_VALLEY_RTA(
        "gunnison_valley_rta",
        "Gunnison Valley RTA (No Live)",
        "https://mjcaction.com/MJC_GTFS_Public/gunnisonrta_google_transit.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    HOME_JAMES_TRANSPORTATION(
        "home_james_transportation",
        "Home James Transportation (No Live)",
        "https://data.trilliumtransit.com/gtfs/homejames-co-us/homejames-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    MOUNTAIN_METROPOLITAN_TRANSIT(
        "mountain_metropolitan_transit",
        "Mountain Metropolitan Transit (No Live)",
        "https://coloradosprings.gov/document/googletransit.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    PARACHUTE_AREA_TRANSIT_SYSTEM(
        "parachute_area_transit_system",
        "Parachute Area Transit System (No Live)",
        "https://data.trilliumtransit.com/gtfs/pats-co-us/pats-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    PRAIRIE_EXPRESS_TRANSIT(
        "prairie_express_transit",
        "Prairie Express Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/prairieexpress-co-us/prairieexpress-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** URL says flex-v2 but this is substantial fixed-route data too (50,150B stop_times.txt), not
     * a sparse demand-response-only feed like the other flex entries above. */
    PUEBLO_TRANSIT(
        "pueblo_transit",
        "Pueblo Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/pueblo-co-us/pueblo-co-us--flex-v2.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** Large feed (~1.4MB stop_times.txt) -- no size concern given STM Montreal's own feed is
     * already handled at multi-hundred-MB scale (see GtfsIngestor's streaming-download doc). */
    RFTA(
        "rfta",
        "RFTA (No Live)",
        "https://data.trilliumtransit.com/gtfs/rfta-co-us/rfta-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    ROAD_RUNNER_TRANSIT(
        "road_runner_transit",
        "Road Runner Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/roadrunnertransit-co-us/roadrunnertransit-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    ROCKY_MOUNTAIN_NP_SHUTTLES(
        "rocky_mountain_np_shuttles",
        "Rocky Mountain National Park Shuttles (No Live)",
        "https://data.trilliumtransit.com/gtfs/rockymountainnationalpark-co-us/rockymountainnationalpark-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** See this cluster's shared doc note above -- byte-identical feed to TOWN_OF_MOUNTAIN_VILLAGE
     * and TOWN_OF_TELLURIDE below (the zip's own agency.txt lists "SMART" -- the actual regional
     * operator's real name -- plus both towns' own agency_name rows). */
    SAN_MIGUEL_REGIONAL_TRANSPORTATION(
        "san_miguel_regional_transportation",
        "San Miguel Authority for Regional Transportation (No Live)",
        "https://data.trilliumtransit.com/gtfs/sanmiguelcounty-co-us/sanmiguelcounty-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    SNOWMASS_VILLAGE_TRANSPORTATION(
        "snowmass_village_transportation",
        "Snowmass Village Transportation (No Live)",
        "https://data.trilliumtransit.com/gtfs/snowmassvillagetransportation-co-us/snowmassvillagetransportation-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    STEAMBOAT_SPRINGS_TRANSIT(
        "steamboat_springs_transit",
        "Steamboat Springs Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/steamboatsprings-co-us/steamboatsprings-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    SUMMIT_STAGE(
        "summit_stage",
        "Summit Stage (No Live)",
        "https://data.trilliumtransit.com/gtfs/summitstage-co-us/summitstage-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** See SAN_MIGUEL_REGIONAL_TRANSPORTATION's own doc -- same byte-identical feed. */
    TOWN_OF_MOUNTAIN_VILLAGE(
        "town_of_mountain_village",
        "Town of Mountain Village (No Live)",
        "https://data.trilliumtransit.com/gtfs/sanmiguelcounty-co-us/sanmiguelcounty-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** See SAN_MIGUEL_REGIONAL_TRANSPORTATION's own doc -- same byte-identical feed. */
    TOWN_OF_TELLURIDE(
        "town_of_telluride",
        "Town of Telluride (No Live)",
        "https://data.trilliumtransit.com/gtfs/sanmiguelcounty-co-us/sanmiguelcounty-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** Large feed (~586K stop_times.txt). */
    TRANSFORT(
        "transfort",
        "Transfort (No Live)",
        "https://ridetransfort.com/wp-content/uploads/transfort_gtfs.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    TSC_TRANSIT(
        "tsc_transit",
        "TSC Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/tsctransit-co-us/tsctransit-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    UNIVERSITY_OF_COLORADO_BOULDER(
        "university_of_colorado_boulder",
        "University of Colorado Boulder (No Live)",
        "https://data.trilliumtransit.com/gtfs/universitycoloradoboulder-co-us/universitycoloradoboulder-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    VAIL_TRANSIT(
        "vail_transit",
        "Vail Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/vailtransit-co-us/vailtransit-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** See BOULDER_COUNTY's own doc -- same byte-identical feed. */
    VIA_MOBILITY(
        "via_mobility",
        "Via Mobility (No Live)",
        "https://data.trilliumtransit.com/gtfs/viamobilityservices-co-us/viamobilityservices-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),

    WINTER_PARK_TRANSIT(
        "winter_park_transit",
        "Winter Park Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/winterpark-co-us/winterpark-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    LTC(
        "ltc",
        "LTC Ontario",
        "https://gtfs.picotransit.com/ltc/static",
        "https://gtfs.picotransit.com/ltc/tripupdates",
        "https://gtfs.picotransit.com/ltc/vehiclepositions",
        timeZoneId = "America/Toronto",
    ),
    STM(
        "stm",
        "STM Montréal",
        "https://www.stm.info/sites/default/files/gtfs/gtfs_stm.zip",
        "https://gtfs.picotransit.com/stm/tripupdates",
        "https://gtfs.picotransit.com/stm/vehiclepositions",
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
    // Every entry's static feed also routes through 511's datafeed API now, regardless of whether
    // an independent agency-domain download exists -- 511's own regional TripUpdates/
    // VehiclePositions feed above is built against 511's own unified regional stop_id catalog, not
    // each operator's native GTFS, so a static feed downloaded straight from the agency's own domain
    // has stop_ids that never match that agency's own realtime feed at all (confirmed on BART: live
    // trip_ids matched its own bart.gov-sourced trips.txt 71/71, but live stop_ids matched its own
    // stops.txt 0/1018 -- UpcomingArrivalsScreen's per-row match needs both, so this silently
    // produced zero live rows, i.e. permanently "Offline", for every agency sourced this way).
    // Routing both feeds through 511 guarantees one shared stop_id namespace by construction. Each
    // entry below still names whatever independent source was found/considered, for provenance, but
    // the feedUrl itself is always the 511 route. Agencies whose static feed goes through 511
    // generally use the region's America/Los_Angeles timezone default rather than a per-feed
    // agency.txt lookup, since they can't be downloaded without the proxy's own server-side key --
    // AC Transit is the one exception, its `US/Pacific` value confirmed directly from the feed
    // before it moved to 511.

    /** VehiclePositions always comes back empty here -- a real, freshly-timestamped 0-entity
     * FeedMessage, not a caching/rate-limit artifact (confirmed by cache-busting, by comparing
     * against Muni's VehiclePositions on the identical route shape returning real vehicles, and
     * by BART's own GTFS-RT page listing only tripupdate.aspx and alerts.aspx, no
     * vehiclepositions endpoint at all). BART simply doesn't publish vehicle position data
     * anywhere -- structurally absent, not a sampling-window gap. Costs only the
     * moving-vehicle-dot-on-map visualization; ETAs come from TripUpdates alone, and
     * TripDetailScreen's live current-stop indicator still works via inferCurrentStopSequence()
     * (the same fallback RIPTA's feed relies on). TripUpdates trip_ids matched cleanly against
     * bart.gov's own trips.txt (71/71), but its stop_ids didn't (0/1018) -- the agency this
     * group's own top-of-block comment cites as the confirming case for switching every static
     * feed here to 511. */
    BART(
        "bart",
        "BART",
        "https://gtfs.picotransit.com/511SFBA/static",
        "https://gtfs.picotransit.com/511SFBA/tripupdates",
        "https://gtfs.picotransit.com/511SFBA/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "BA")),
    ),
    /** A large feed (~1.9M stop_times rows) -- already covered by the streaming-download/batched-
     * commit fixes shipped for STM Montreal's similarly large feed, no size concern. Direct source
     * was muni-gtfs.apps.sfmta.com; switched to 511 for stop_id parity with this agency's own
     * realtime feed (see this group's own top-of-block comment). */
    SFMTA_MUNI(
        "sfmta_muni",
        "SFMTA Muni",
        "https://gtfs.picotransit.com/511SFSF/static",
        "https://gtfs.picotransit.com/511SFSF/tripupdates",
        "https://gtfs.picotransit.com/511SFSF/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SF")),
    ),
    /** Static feed routed through 511's datafeed API rather than a direct URL. timeZoneId is
     * `US/Pacific` exactly as declared in this feed's own agency.txt (a legacy tzdata alias --
     * resolves fine via ZoneId.of(), left as-is per this file's own rule). */
    AC_TRANSIT(
        "ac_transit",
        "AC Transit",
        "https://gtfs.picotransit.com/511SFAC/static",
        "https://gtfs.picotransit.com/511SFAC/tripupdates",
        "https://gtfs.picotransit.com/511SFAC/vehiclepositions",
        timeZoneId = "US/Pacific",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "AC")),
    ),

    CALTRAIN(
        "caltrain",
        "Caltrain",
        "https://gtfs.picotransit.com/511SFCT/static",
        "https://gtfs.picotransit.com/511SFCT/tripupdates",
        "https://gtfs.picotransit.com/511SFCT/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CT")),
    ),
    /** Transitland's own operator record already listed the 511 regional feed as VTA's only live
     * source. Static is sourced directly from VTA's own gtfs.vta.org (routed through the proxy for
     * a trust-anchor fix, same as /ltc/static) rather than 511's own static -- see
     * RegionalStopIdPrefixBridge's own doc for the stop_id renumbering that pairing needs bridging
     * for. */
    VTA(
        "vta",
        "VTA",
        "https://gtfs.picotransit.com/vta/static",
        "https://gtfs.picotransit.com/511SFSC/tripupdates",
        "https://gtfs.picotransit.com/511SFSC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SC"), RegionalStopIdPrefixBridge("6")),
    ),

    COUNTY_CONNECTION(
        "county_connection",
        "County Connection",
        "https://gtfs.picotransit.com/511SFCC/static",
        "https://gtfs.picotransit.com/511SFCC/tripupdates",
        "https://gtfs.picotransit.com/511SFCC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CC")),
    ),
    /** ACE's own CDN URL still resolves live, but is no longer current -- switched to 511's
     * datafeed API instead of keeping a deprecated source live-but-unsupported. Tiny feed
     * (~10-station commuter rail line). */
    ACE(
        "ace",
        "ACE",
        "https://gtfs.picotransit.com/511SFCE/static",
        "https://gtfs.picotransit.com/511SFCE/tripupdates",
        "https://gtfs.picotransit.com/511SFCE/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CE")),
    ),
    /** Direct source was developer.scmetro.org (recently moved off scmtd.com), confirmed live.
     * Switched to 511 for stop_id parity with this agency's own realtime feed (see this group's
     * own top-of-block comment). */
    SANTA_CRUZ_METRO(
        "santa_cruz_metro",
        "Santa Cruz METRO",
        "https://gtfs.picotransit.com/511SFCR/static",
        "https://gtfs.picotransit.com/511SFCR/tripupdates",
        "https://gtfs.picotransit.com/511SFCR/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CR")),
    ),
    /** Direct source was capitolcorridor.org, confirmed live. Intercity rail -- double-checked
     * timezone against agency.txt rather than assuming. Switched to 511 for stop_id parity with
     * this agency's own realtime feed (see this group's own top-of-block comment). */
    CAPITOL_CORRIDOR(
        "capitol_corridor",
        "Capitol Corridor",
        "https://gtfs.picotransit.com/511SFAM/static",
        "https://gtfs.picotransit.com/511SFAM/tripupdates",
        "https://gtfs.picotransit.com/511SFAM/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "AM")),
    ),
    /** Direct source was emerygoround.com, confirmed live -- but only with a real browser
     * User-Agent (a bare request gets 406), same pattern already seen with CTA's feed above.
     * Switched to 511 for stop_id parity with this agency's own realtime feed (see this group's
     * own top-of-block comment) -- sidesteps the User-Agent requirement too. */
    EMERY_GO_ROUND(
        "emery_go_round",
        "Emery Go-Round",
        "https://gtfs.picotransit.com/511SFEM/static",
        "https://gtfs.picotransit.com/511SFEM/tripupdates",
        "https://gtfs.picotransit.com/511SFEM/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "EM")),
    ),
    /** Direct source was realtime.goldengate.org, confirmed live. This is the bus network
     * specifically -- Golden Gate Ferry is a separate operator/feed ([GOLDEN_GATE_FERRY], 511 code
     * GF). Switched to 511 for stop_id parity with this agency's own realtime feed (see this
     * group's own top-of-block comment). */
    GOLDEN_GATE_TRANSIT(
        "golden_gate_transit",
        "Golden Gate Transit",
        "https://gtfs.picotransit.com/511SFGG/static",
        "https://gtfs.picotransit.com/511SFGG/tripupdates",
        "https://gtfs.picotransit.com/511SFGG/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "GG")),
    ),
    /** Direct source was marintransit.gov, confirmed live. Switched to 511 for stop_id parity
     * with this agency's own realtime feed (see this group's own top-of-block comment). */
    MARIN_TRANSIT(
        "marin_transit",
        "Marin Transit",
        "https://gtfs.picotransit.com/511SFMA/static",
        "https://gtfs.picotransit.com/511SFMA/tripupdates",
        "https://gtfs.picotransit.com/511SFMA/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MA")),
    ),
    /** Direct source was Trillium's missionbaytma-ca-us feed, confirmed live -- no independent
     * Mission Bay TMA domain found, but Trillium is this agency's own registered source per the
     * Mobility Database. Switched to 511 for stop_id parity with this agency's own realtime feed
     * (see this group's own top-of-block comment). */
    MISSION_BAY_TMA(
        "mission_bay_tma",
        "Mission Bay TMA",
        "https://gtfs.picotransit.com/511SFMB/static",
        "https://gtfs.picotransit.com/511SFMB/tripupdates",
        "https://gtfs.picotransit.com/511SFMB/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MB")),
    ),
    /** Direct source was gtfs.mvcommunityshuttle.com, confirmed live. This TMA also runs its own
     * GTFS-RT via TripShot (mtma.tripshot.com) -- not wired here, the shared 511 regional feed is
     * used instead. Switched to 511 for stop_id parity with that realtime feed (see this group's
     * own top-of-block comment). */
    MOUNTAIN_VIEW_COMMUNITY_SHUTTLE(
        "mountain_view_community_shuttle",
        "Mountain View Community Shuttle",
        "https://gtfs.picotransit.com/511SFMC/static",
        "https://gtfs.picotransit.com/511SFMC/tripupdates",
        "https://gtfs.picotransit.com/511SFMC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MC")),
    ),
    /** Direct source was gtfs.mvgo.org, confirmed live -- same Mountain View TMA / TripShot
     * situation as [MOUNTAIN_VIEW_COMMUNITY_SHUTTLE] above. Switched to 511 for stop_id parity
     * with this agency's own realtime feed (see this group's own top-of-block comment). */
    MVGO(
        "mvgo",
        "MVgo",
        "https://gtfs.picotransit.com/511SFMV/static",
        "https://gtfs.picotransit.com/511SFMV/tripupdates",
        "https://gtfs.picotransit.com/511SFMV/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MV")),
    ),
    /** The Trillium URL Petaluma Transit's own site links to still resolves live, but is no
     * longer current -- switched to 511's datafeed API instead of keeping a deprecated source
     * live-but-unsupported. */
    PETALUMA_TRANSIT(
        "petaluma_transit",
        "Petaluma Transit",
        "https://gtfs.picotransit.com/511SFPE/static",
        "https://gtfs.picotransit.com/511SFPE/tripupdates",
        "https://gtfs.picotransit.com/511SFPE/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "PE")),
    ),
    /** Direct source was Trillium's riovista-ca-us feed, confirmed live. Tiny feed. Switched to
     * 511 for stop_id parity with this agency's own realtime feed (see this group's own
     * top-of-block comment). */
    RIO_VISTA_DELTA_BREEZE(
        "rio_vista_delta_breeze",
        "Rio Vista Delta Breeze",
        "https://gtfs.picotransit.com/511SFRV/static",
        "https://gtfs.picotransit.com/511SFRV/tripupdates",
        "https://gtfs.picotransit.com/511SFRV/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "RV")),
    ),
    /** sonomamarintrain.org's own site has no direct GTFS link -- Trillium was SMART's registered
     * source, and that URL still resolves live, but is no longer current -- switched to 511's
     * datafeed API instead of keeping a deprecated source live-but-unsupported. */
    SMART(
        "smart",
        "SMART",
        "https://gtfs.picotransit.com/511SFSA/static",
        "https://gtfs.picotransit.com/511SFSA/tripupdates",
        "https://gtfs.picotransit.com/511SFSA/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SA")),
    ),
    /** Direct source was gtfs.sanfranciscobayferry.com (its own domain, not Trillium), confirmed
     * live. Switched to 511 for stop_id parity with this agency's own realtime feed (see this
     * group's own top-of-block comment). */
    SF_BAY_FERRY(
        "sf_bay_ferry",
        "SF Bay Ferry",
        "https://gtfs.picotransit.com/511SFSB/static",
        "https://gtfs.picotransit.com/511SFSB/tripupdates",
        "https://gtfs.picotransit.com/511SFSB/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SB")),
    ),
    /** Direct source was Trillium's sanleandro-ca-us feed, confirmed live. Flagged inactive
     * elsewhere, but the URL itself still returned a real, current-looking GTFS zip -- worth a
     * periodic re-check rather than trusting that flag alone. Switched to 511 for stop_id parity
     * with this agency's own realtime feed (see this group's own top-of-block comment). */
    SAN_LEANDRO_LINKS(
        "san_leandro_links",
        "San Leandro LINKS",
        "https://gtfs.picotransit.com/511SFSL/static",
        "https://gtfs.picotransit.com/511SFSL/tripupdates",
        "https://gtfs.picotransit.com/511SFSL/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SL")),
    ),
    /** Static feed routed through 511's datafeed API rather than a direct URL -- SamTrans' only
     * public download is a CMS media-asset link with no independently-stable identifier to build
     * a permanent URL around, so 511 is the more durable source here. Confirmed live in this
     * project's own regional-feed sample. */
    SAMTRANS(
        "samtrans",
        "SamTrans",
        "https://gtfs.picotransit.com/511SFSM/static",
        "https://gtfs.picotransit.com/511SFSM/tripupdates",
        "https://gtfs.picotransit.com/511SFSM/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SM")),
    ),
    /** Direct source was Trillium's sonomacounty-ca-us feed, confirmed live -- sctransit.com's own
     * developer-data page didn't resolve when checked, but this URL is cited across multiple
     * third-party catalogs and resolved fine on its own. Switched to 511 for stop_id parity with
     * this agency's own realtime feed (see this group's own top-of-block comment). */
    SONOMA_COUNTY_TRANSIT(
        "sonoma_county_transit",
        "Sonoma County Transit",
        "https://gtfs.picotransit.com/511SFSO/static",
        "https://gtfs.picotransit.com/511SFSO/tripupdates",
        "https://gtfs.picotransit.com/511SFSO/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SO")),
    ),
    /** Direct source was Santa Rosa CityBus' own Syncromatics vendor subdomain, confirmed live --
     * served a real zip (PK magic bytes, valid agency.txt/stops.txt entries) despite a misleading
     * `text/plain` response content-type, verified by inspecting the raw bytes directly rather than
     * trusting the header. An independently-discovered source, same situation as ACE's own CDN
     * above. Switched to 511 for stop_id parity with this agency's own realtime feed (see this
     * group's own top-of-block comment). */
    SANTA_ROSA_CITYBUS(
        "santa_rosa_citybus",
        "Santa Rosa CityBus",
        "https://gtfs.picotransit.com/511SFSR/static",
        "https://gtfs.picotransit.com/511SFSR/tripupdates",
        "https://gtfs.picotransit.com/511SFSR/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SR")),
    ),
    /** Direct source was SolTrans' own Connexionz vendor subdomain, confirmed live. Switched to
     * 511 for stop_id parity with this agency's own realtime feed (see this group's own
     * top-of-block comment). */
    SOLTRANS(
        "soltrans",
        "SolTrans",
        "https://gtfs.picotransit.com/511SFST/static",
        "https://gtfs.picotransit.com/511SFST/tripupdates",
        "https://gtfs.picotransit.com/511SFST/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "ST")),
    ),
    /** Direct source was Trillium's westcat-ca-us feed, confirmed live -- explicitly linked from
     * WestCat's own "Data Request" page as their most recent GTFS schedule data, not just a
     * third-party guess. Switched to 511 for stop_id parity with this agency's own realtime feed
     * (see this group's own top-of-block comment) -- the directions.txt duplicate-row quirk and
     * the non-timepoint blank-time stops noted elsewhere in this codebase were both confirmed in
     * this same underlying feed, so still apply after the switch. */
    WESTCAT(
        "westcat",
        "WestCat",
        "https://gtfs.picotransit.com/511SFWC/static",
        "https://gtfs.picotransit.com/511SFWC/tripupdates",
        "https://gtfs.picotransit.com/511SFWC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "WC")),
    ),
    /** Direct source was Trillium's lavta-ca-us feed, confirmed live -- LAVTA's own
     * webwatch.lavta.org host (cited by Transitland as the authoritative source) 404s on every
     * path tried; this Trillium URL was the one that actually resolved. Switched to 511 for
     * stop_id parity with this agency's own realtime feed (see this group's own top-of-block
     * comment). */
    LAVTA_WHEELS(
        "lavta_wheels",
        "LAVTA Wheels",
        "https://gtfs.picotransit.com/511SFWH/static",
        "https://gtfs.picotransit.com/511SFWH/tripupdates",
        "https://gtfs.picotransit.com/511SFWH/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "WH")),
    ),

    // 10 of the entries below have no independently-discoverable static GTFS download at all --
    // 511's datafeed API is their only public source, so as an exception their feedUrl goes
    // through the proxy's 511 passthrough instead of a direct agency URL (still gets the normal 6h
    // static-cache treatment). SamTrans is here because its only public download has no
    // independently-stable identifier to build a permanent URL around, ACE/Petaluma Transit/SMART
    // are here because MobilityData's catalog marks their formerly-direct URL deprecated in favor
    // of 511 (see this group's own top-of-block comment, and each entry's own comment), and AC
    // Transit is here because its prior direct URL relied on a token with no confirmed source,
    // dropped rather than kept unverified (see that entry's own comment). Commute.org
    // Shuttles/FAST/Union City Transit/VINE Transit, further down, DO have a direct feedUrl now
    // (see this group's own top-of-block comment) -- left physically grouped here with the rest of
    // this batch rather than moved, since that's cosmetic and doesn't affect behavior.

    /** No independent static feed found (checked trideltatransit.com directly) -- 511-datafeed-
     * API only, via the proxy. Confirmed live in this project's own regional-feed sample. */
    TRI_DELTA(
        "tri_delta",
        "Tri Delta Transit",
        "https://gtfs.picotransit.com/511SF3D/static",
        "https://gtfs.picotransit.com/511SF3D/tripupdates",
        "https://gtfs.picotransit.com/511SF3D/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "3D")),
    ),
    /** No independent static feed found (checked angelislandferry.com directly) -- 511-datafeed-
     * API only, via the proxy. */
    ANGEL_ISLAND_TIBURON_FERRY(
        "angel_island_tiburon_ferry",
        "Angel Island Tiburon Ferry",
        "https://gtfs.picotransit.com/511SFAF/static",
        "https://gtfs.picotransit.com/511SFAF/tripupdates",
        "https://gtfs.picotransit.com/511SFAF/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "AF")),
    ),
    /** The URL surfaced by search (commute.org/files/gtfs/Masterzip.zip) is dead -- a separate,
     * Trillium-hosted URL was the direct source found; switched to 511 for stop_id parity with
     * this agency's own realtime feed (see this group's own top-of-block comment). */
    COMMUTE_ORG_SHUTTLES(
        "commute_org_shuttles",
        "Commute.org Shuttles",
        "https://gtfs.picotransit.com/511SFCM/static",
        "https://gtfs.picotransit.com/511SFCM/tripupdates",
        "https://gtfs.picotransit.com/511SFCM/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CM")),
    ),
    /** No independent static feed found -- operated by WestCat, but not folded into [WESTCAT]'s
     * own feed above (checked). 511-datafeed-API only, via the proxy. */
    DUMBARTON_EXPRESS(
        "dumbarton_express",
        "Dumbarton Express",
        "https://gtfs.picotransit.com/511SFDE/static",
        "https://gtfs.picotransit.com/511SFDE/tripupdates",
        "https://gtfs.picotransit.com/511SFDE/vehiclepositions",
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
        "https://gtfs.picotransit.com/511SFEE/static",
        "https://gtfs.picotransit.com/511SFEE/tripupdates",
        "https://gtfs.picotransit.com/511SFEE/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "EE")),
    ),
    /** fasttransit.org itself has no GTFS link -- a separate, Trillium-hosted URL was the direct
     * source found; switched to 511 for stop_id parity with this agency's own realtime feed (see
     * this group's own top-of-block comment). */
    FAST_TRANSIT(
        "fast_transit",
        "FAST",
        "https://gtfs.picotransit.com/511SFFS/static",
        "https://gtfs.picotransit.com/511SFFS/tripupdates",
        "https://gtfs.picotransit.com/511SFFS/vehiclepositions",
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
        "https://gtfs.picotransit.com/511SFGF/static",
        "https://gtfs.picotransit.com/511SFGF/tripupdates",
        "https://gtfs.picotransit.com/511SFGF/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "GF")),
    ),
    /** No independent static feed found (checked presidio.gov directly) -- 511-datafeed-API
     * only, via the proxy. Confirmed live in this project's own regional-feed sample
     * (VehiclePositions). */
    PRESIDIO_GO(
        "presidio_go",
        "Presidio Go",
        "https://gtfs.picotransit.com/511SFPG/static",
        "https://gtfs.picotransit.com/511SFPG/tripupdates",
        "https://gtfs.picotransit.com/511SFPG/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "PG")),
    ),
    /** No independent static feed found (checked flysfo.com directly) -- 511-datafeed-API only,
     * via the proxy. Confirmed live in this project's own regional-feed sample. */
    SFO_AIRPORT(
        "sfo_airport",
        "SFO Airport",
        "https://gtfs.picotransit.com/511SFSI/static",
        "https://gtfs.picotransit.com/511SFSI/tripupdates",
        "https://gtfs.picotransit.com/511SFSI/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SI")),
    ),
    /** No independent static feed found -- distributed only via commute.org/511's regional
     * aggregate feed, not as a standalone source. 511-datafeed-API only, via the proxy. */
    SOUTH_SAN_FRANCISCO(
        "south_san_francisco",
        "South San Francisco Shuttle",
        "https://gtfs.picotransit.com/511SFSS/static",
        "https://gtfs.picotransit.com/511SFSS/tripupdates",
        "https://gtfs.picotransit.com/511SFSS/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SS")),
    ),
    /** No independent static feed found (checked tisf.com directly) -- described everywhere as
     * "published by MTC" rather than the ferry operator itself. 511-datafeed-API only, via the
     * proxy. */
    TREASURE_ISLAND_FERRY(
        "treasure_island_ferry",
        "Treasure Island Ferry",
        "https://gtfs.picotransit.com/511SFTF/static",
        "https://gtfs.picotransit.com/511SFTF/tripupdates",
        "https://gtfs.picotransit.com/511SFTF/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "TF")),
    ),
    /** Transitland's own authoritative-source field already points at 511's datafeed API; a
     * separate Trillium-hosted URL also resolves live, but 511 is used directly for stop_id
     * parity with this agency's own realtime feed (see this group's own top-of-block comment). */
    UNION_CITY_TRANSIT(
        "union_city_transit",
        "Union City Transit",
        "https://gtfs.picotransit.com/511SFUC/static",
        "https://gtfs.picotransit.com/511SFUC/tripupdates",
        "https://gtfs.picotransit.com/511SFUC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "UC")),
    ),
    /** No independent static feed found (checked citycoach.com directly; an old TransitFeeds
     * copy exists but is stale/deprecated). 511-datafeed-API only, via the proxy. */
    VACAVILLE_CITY_COACH(
        "vacaville_city_coach",
        "Vacaville City Coach",
        "https://gtfs.picotransit.com/511SFVC/static",
        "https://gtfs.picotransit.com/511SFVC/tripupdates",
        "https://gtfs.picotransit.com/511SFVC/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "VC")),
    ),
    /** vinetransit.com itself has no GTFS/developer link -- a separate, Trillium-hosted URL was
     * the direct source found; switched to 511 for stop_id parity with this agency's own
     * realtime feed (see this group's own top-of-block comment). Confirmed live in this project's
     * own regional-feed sample too (VehiclePositions). */
    VINE_TRANSIT(
        "vine_transit",
        "VINE Transit",
        "https://gtfs.picotransit.com/511SFVN/static",
        "https://gtfs.picotransit.com/511SFVN/tripupdates",
        "https://gtfs.picotransit.com/511SFVN/vehiclepositions",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "VN")),
    ),

    /** Bus (primary) + Rail ([LaMetroRailSecondaryFeed], see that file's own doc) -- LACMTA publishes
     * them as two separate static zips for the same real operator, merged the same way Bustang merges
     * into RTD. Realtime isn't wired: Swiftly requires an API-key application and is server-to-server
     * per its own docs, not meant for individual client polling; api.metro.net is a custom JSON REST
     * API rather than actual GTFS-RT protobuf, so wiring it in would need custom translation code,
     * not just a URL swap. */
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
     * [realtimeTripUpdatesUrl]/[realtimeVehiclePositionsUrl] swap: [RunAssociatedTripSource] (Bus
     * Tracker) matches a live bus back to a real trip_id via its own scheduled-start-time fields
     * (see that class's own doc), and [CtaTrainTrackerSource] (Train Tracker, for 'L' trains) is a
     * [FuzzyRunTrips] implementation instead -- Train Tracker identifies a train only by run
     * number, with no static-GTFS field that bridges back to a trip_id, so it ranks live trains
     * against scheduled trips ordinally rather than matching one with certainty (see
     * [FuzzyRunTrips]'s own doc), surfaced as "Closest match." Both are fully wired below. ~6.0M
     * stop_times rows --
     * larger than STM's 5.1M that already needed the streaming/batching fixes; same order of
     * magnitude, not UK-BODS-regional scale, but wants its own real device ingest test before being
     * trusted. */
    CTA(
        "cta",
        "CTA (Partial Live)",
        "https://www.transitchicago.com/downloads/sch_data/google_transit.zip",
        null,
        null,
        timeZoneId = "America/Chicago",
        // See TripDirectionColumn's own doc -- CTA's trips.txt has no trip_headsign at all, but its
        // non-standard "direction" column (verified consistent per route_id+direction_id across the
        // whole feed) stands in for it. See CtaTrainTrackerSource's own doc -- it's scoped to 'L'
        // route_ids (verified against CTA's own routes.txt) -- Bus Tracker's routes are unaffected,
        // already run-associated.
        components = listOf(
            RunAssociatedTripSource,
            TripDirectionColumn("direction"),
            CtaTrainTrackerSource,
        ),
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
    /** No GTFS-RT feed exists for Pace at all -- confirmed, live predictions are only shown on Pace's
     * own Bus Tracker web page, never published as a downloadable feed. Static schedule only, scoped
     * to routes with their "Intelligent Bus System" equipment installed. */
    PACE(
        "pace",
        "Pace (No Live)",
        "https://www.pacebus.com/sites/default/files/2026-08/GTFS.zip",
        null,
        null,
        timeZoneId = "America/Chicago",
    ),
    /** Realtime: no key needed, HTTPS (pico-transit-proxy's own default User-Agent satisfies MTA's
     * WAF, which 403s a request with no real UA at all). Unlike LIRR/Metro-North's single combined
     * feed, MTA itself splits NYC Subway's realtime across 8 line-group feeds (ACE, BDFM, G, JZ,
     * NQRW, L, numbered lines/1234567S, SIR -- verified live, non-overlapping trip_id ranges) --
     * pico-transit-proxy merges all 8 server-side into one combined response at
     * `/nyc_subway/combined` (fetched/cached once per rider hitting that route, shared by everyone,
     * a pure efficiency win since it's genuinely the same schedule split only for MTA's own
     * publishing convenience -- see the worker's own `serveNycSubwayCombinedRoute`), so this agency
     * has a single realtime URL like any other, no [MultiGtfsFeed] components needed for it. Every
     * one of those 8 feeds' own trip_ids isn't the real static trip_id verbatim, though -- it packs
     * a scheduled start time in NYCT's own encoding (e.g. "119000_L..S"), which
     * [NycSubwayTripIdBridge] bridges back to the real trip_id (see [RealtimeTripIdBridge]'s own doc
     * for why, and the verified decode) -- applied to the combined feed's entities regardless of
     * which of the 8 original upstream feeds each one came from. Every entity also carries several
     * NYCT-specific protobuf fields (TripDescriptor field 1001, FeedEntity fields 2/5,
     * VehiclePosition field 6, StopTimeUpdate fields 7 and 1001) declared in GtfsRealtime.kt --
     * this hand-rolled decoder faults on any undeclared field rather than skipping it. */
    NYC_SUBWAY(
        "nyc_subway",
        "NYC Subway",
        "https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip",
        "https://gtfs.picotransit.com/nyc_subway/combined",
        "https://gtfs.picotransit.com/nyc_subway/combined",
        timeZoneId = "America/New_York",
        components = listOf(NycSubwayTripIdBridge),
    ),
    /** Realtime: no key needed, HTTPS, one combined TripUpdates+VehiclePositions feed -- wired in
     * below. Shares [GtfsRtStopTimeUpdate]'s field 1005 (see that field's own doc for verification
     * detail). calendar_dates.txt-only (no calendar.txt) is fine -- verified GtfsRepository's
     * activeTodayClause already handles a service_id with zero `calendar` rows via its independent
     * calendar_dates-addition branch, same pattern many agencies use. */
    LIRR(
        "lirr",
        "LIRR",
        "https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/lirr%2Fgtfs-lirr",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/lirr%2Fgtfs-lirr",
        timeZoneId = "America/New_York",
    ),
    /** Same situation as LIRR -- no key, HTTPS, one combined feed, wired in below. Shares
     * [GtfsRtStopTimeUpdate]'s field 1005 (see that field's own doc) -- this feed's sub-field
     * contents differ slightly from LIRR's, e.g. a "Departed" status string where LIRR's was a
     * track code. No calendar.txt in this feed either (only calendar_dates.txt) -- confirmed fine
     * for the same reason noted on [LIRR]. */
    METRO_NORTH(
        "metro_north",
        "Metro-North",
        "https://rrgtfsfeeds.s3.amazonaws.com/gtfsmnr.zip",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/mnr%2Fgtfs-mnr",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/mnr%2Fgtfs-mnr",
        timeZoneId = "America/New_York",
    ),
    /**
     * MTA doesn't publish NYC bus as one static feed -- confirmed live by downloading and
     * inspecting all 6 real files: 5 "division" zips (this entry plus the other 4 below) that
     * together form ONE citywide NYCT bus network (each division's own trips.txt/stop_times.txt
     * holds only its own real service, zero trip_id/service_id overlap across any of the 10
     * pairs, routes.txt is a byte-identical full 306-route catalog copied into all 5 purely so
     * foreign keys resolve), plus MTA Bus Company -- a real separate legal operator (own
     * agency_id "MTABC", its own distinct 92-route catalog, zero route/trip overlap with NYCT).
     *
     * Originally merged into one combined agency (4 divisions flat-merged, Bus Company
     * id-prefixed) -- reverted after a real device test: the combined database reached
     * 600MB+ and was still growing after 10+ minutes of ingest, an unacceptable cost for a
     * rider who may only ever care about their own borough. Each division (and Bus Company) is
     * instead its own plain single-feed agency here, same shape as MBTA/RIPTA -- no merging, no
     * `components` needed at all. All 6 share the exact same realtime URLs below: MTA publishes
     * one combined GTFS-RT feed for the whole system (NYCT + Bus Company vehicles together), so
     * a rider on any one of these 6 selections still sees real live buses -- the plain
     * unprefixed trip_id lookup every single-feed agency already does simply finds no match for
     * a live vehicle outside that division's own static schedule, same as "not currently live"
     * anywhere else in this app. All 6 static URLs confirmed live this session: HTTPS throughout
     * (each 301s through web.mta.info to an S3-hosted zip, still HTTPS), real zips,
     * agency_timezone = America/New_York on every one.
     */
    NYC_BUS_BRONX(
        "nyc_bus_bronx",
        "NYC Bus - Bronx",
        "https://web.mta.info/developers/data/nyct/bus/google_transit_bronx.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        timeZoneId = "America/New_York",
    ),
    NYC_BUS_BROOKLYN(
        "nyc_bus_brooklyn",
        "NYC Bus - Brooklyn",
        "https://web.mta.info/developers/data/nyct/bus/google_transit_brooklyn.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        timeZoneId = "America/New_York",
    ),
    NYC_BUS_MANHATTAN(
        "nyc_bus_manhattan",
        "NYC Bus - Manhattan",
        "https://web.mta.info/developers/data/nyct/bus/google_transit_manhattan.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        timeZoneId = "America/New_York",
    ),
    NYC_BUS_QUEENS(
        "nyc_bus_queens",
        "NYC Bus - Queens",
        "https://web.mta.info/developers/data/nyct/bus/google_transit_queens.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        timeZoneId = "America/New_York",
    ),
    NYC_BUS_STATEN_ISLAND(
        "nyc_bus_staten_island",
        "NYC Bus - Staten Island",
        "https://web.mta.info/developers/data/nyct/bus/google_transit_staten_island.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        timeZoneId = "America/New_York",
    ),
    NYC_BUS_COMPANY(
        "nyc_bus_company",
        "NYC Bus - MTA Bus Company",
        "https://web.mta.info/developers/data/busco/google_transit.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        timeZoneId = "America/New_York",
    ),

    WEGO_TRANSIT(
        "wego_nashville",
        "Nashville - WeGo Public Transit",
        "https://www.wegotransit.com/GoogleExport/google_transit.zip",
        "https://gtfs.picotransit.com/wego_nashville/tripupdates",
        "https://gtfs.picotransit.com/wego_nashville/vehiclepositions",
        timeZoneId = "America/Chicago",
    ),

    // Puget Sound region -- regionalized like NYC/SF Bay Area (see RegionalGroup.PUGET_SOUND), each
    // agency independently ingested from its own per-agency static feed, not the 261MB consolidated
    // zip covering all regional operators at once (confirmed via direct inspection: shapes.txt alone
    // is ~50MB, stop_times.txt ~196MB -- far more than a Light Phone III should download for a rider
    // who only wants one of these agencies). Realtime is available per agency_id (in parens below)
    // via OneBusAway's Puget Sound API (trip-updates-for-agency/<id>.pb,
    // vehicle-positions-for-agency/<id>.pb), same static/realtime namespace, but requires a real API
    // key (a TEST key exists but isn't for production) and per-agency coverage isn't confirmed yet --
    // not wired here, "(No Live)" pending that. Amtrak, Solid Ground EZ Loop, and Seattle Streetcar
    // (City of Seattle) are deliberately not included: the first two aren't really "Puget Sound
    // regional" the way these are, and Seattle Streetcar has no standalone per-agency feed of its own
    // -- it only exists inside the consolidated zip.
    //
    // King County Metro/Pierce Transit/Community Transit/Sound Transit are all hosted on
    // soundtransit.org, whose cert chain terminates at a Let's Encrypt root not yet present in this
    // device's trust store (confirmed live: SSLHandshakeException, "Trust anchor for certification
    // path not found") -- routed through pico-transit-proxy's own /<id>/static route instead of
    // fetched directly, the same fix already proven out for LTC's identical problem (see that
    // worker's own comment on this). The other five Puget Sound agencies are hosted on
    // gtfs.sound.obaweb.org (a standard Amazon root, confirmed fine) and fetch directly, same as
    // every other non-511 agency in this file.
    KING_COUNTY_METRO(
        "kcm",
        "King County Metro (No Live)",
        "https://gtfs.picotransit.com/kcm/static",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    PIERCE_TRANSIT(
        "pierce_transit",
        "Pierce Transit (No Live)",
        "https://gtfs.picotransit.com/pierce_transit/static",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    INTERCITY_TRANSIT(
        "intercity_transit",
        "Intercity Transit (No Live)",
        "https://gtfs.sound.obaweb.org/prod/19_gtfs.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    KITSAP_TRANSIT(
        "kitsap_transit",
        "Kitsap Transit (No Live)",
        "https://gtfs.sound.obaweb.org/prod/20_gtfs.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    COMMUNITY_TRANSIT(
        "community_transit",
        "Community Transit (No Live)",
        "https://gtfs.picotransit.com/community_transit/static",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    SOUND_TRANSIT(
        "sound_transit",
        "Sound Transit (No Live)",
        "https://gtfs.picotransit.com/sound_transit/static",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    WA_STATE_FERRIES(
        "wa_state_ferries",
        "Washington State Ferries (No Live)",
        "https://gtfs.sound.obaweb.org/prod/95_gtfs.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    SEATTLE_MONORAIL(
        "seattle_monorail",
        "Seattle Center Monorail (No Live)",
        "https://gtfs.sound.obaweb.org/prod/96_gtfs.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    EVERETT_TRANSIT(
        "everett_transit",
        "Everett Transit (No Live)",
        "https://gtfs.sound.obaweb.org/prod/97_gtfs.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),

    ;

    /** Cached lookup -- [ZoneId.of] parses/interns the zone's rules, no need to redo that on every
     * "what time is it right now for this agency" call. */
    val zoneId: ZoneId by lazy { ZoneId.of(timeZoneId) }

    /** Fetches this agency's own instance of a given [AgencyComponent] type, if it has one, e.g.
     * `agency.component<MbtaV3VehicleSource>()`. Null for any agency/type combination not
     * declared in [components]. */
    inline fun <reified T : AgencyComponent> component(): T? = components.filterIsInstance<T>().firstOrNull()

    companion object {
        init {
            // [id] doubles as the "gtfs/{id}/" cache directory name (see [forDbFile]/[gtfsDbFile]) and the
            // DEFAULT_AGENCY/BOARDED_AGENCY preference value -- a copy-pasted entry with an unchanged id
            // silently merges its cache and preferences with whichever other agency already owns that
            // id, rather than failing loudly. Catching it here, at class-load time, means a bad
            // copy-paste fails immediately instead of surfacing as "why is agency X showing agency Y's
            // data."
            val duplicateIds = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
            check(duplicateIds.isEmpty()) {
                "GtfsAgency ids must be unique, got duplicates: $duplicateIds"
            }
        }

        /**
         * Recovers which agency a screen's [dbFile] belongs to, from the same "gtfs/{id}/transit.db"
         * path convention [gtfsDbFile] builds it with -- so a screen only needs [dbFile] (already
         * required to run any query) to know which agency's live feeds to poll, rather than
         * threading `agency` through as a second parameter everywhere. Driven entirely by [id], so
         * it stays correct with no changes if a third agency is added later.
         */
        fun forDbFile(dbFile: File): GtfsAgency? = entries.find { it.id == dbFile.parentFile?.name }
    }
}