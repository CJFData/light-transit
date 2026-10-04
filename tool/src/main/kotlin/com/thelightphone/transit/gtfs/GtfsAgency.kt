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
    /** This agency's GTFS-RT alerts feed, or null if it doesn't publish one. */
    val realtimeAlertsUrl: String? = null,
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
        realtimeAlertsUrl = "https://gtfs.picotransit.com/mbta/alerts",
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
        realtimeAlertsUrl = "https://gtfs.picotransit.com/rtd/alerts",
        timeZoneId = "America/Denver",
        components = listOf(BustangSecondaryFeed),
    ),
    /** Bustang (CDOT's intercity coach) also merges into RTD Denver via [BustangSecondaryFeed], but
     * gets its own selectable entry for riders looking it up directly. Same static feed and realtime
     * URLs as that component. */
    BUSTANG(
        "bustang",
        "Bustang",
        "https://www.rtd-denver.com/files/gtfs/bustang-co-us.zip",
        "https://gtfs.picotransit.com/bustang/tripupdates",
        "https://gtfs.picotransit.com/bustang/vehiclepositions",
        timeZoneId = "America/Denver",
    ),
    /**
     * The rest of Colorado's agencies from colorado-gtfs.trilliumtransit.com: static schedules only,
     * since that source publishes no GTFS-RT, so each gets a "(No Live)" suffix. Timezones come from
     * each feed's agency.txt (America/Denver unless noted). Some entries share identical feed content
     * under different rider-facing names (Boulder County/Via Mobility; San Miguel Authority/Mountain
     * Village/Telluride) and stay separate so riders find the name they know. COLT and Cripple Creek
     * Transportation are left out until a working feed URL is found.
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
    /** GTFS-Flex (demand-response) feed with sparse fixed-route data; the app handles routes with
     * little or no scheduled service. */
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
    /** Redirects cross-domain to evta.org, the operator's own domain. */
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
    /** A document-viewer URL rather than a bare .zip, but it serves the zip itself. */
    DURANGO_TRANSIT(
        "durango_transit",
        "Durango Transit (No Live)",
        "https://durangogov.org/DocumentCenter/View/17688/Durango-Transit-GTFS-Data",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** GTFS-Flex feed with sparse fixed-route data. */
    EASY_RIDE_TRANSPORTATION(
        "easy_ride_transportation",
        "Easy Ride Transportation (No Live)",
        "https://data.trilliumtransit.com/gtfs/broomfield-co-us/broomfield-co-us--flex-v2.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** GTFS-Flex feed with sparse fixed-route data. */
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
        //realtimeAlertsUrl = "https://gtfs.picotransit.com/stm/alerts",
        timeZoneId = "America/Montreal",
    ),

    // Every entry through the end of this SF Bay Area group gets realtime from 511.org's regional
    // feed via pico-transit-proxy, which fetches it once per cache window and serves each agency its
    // own filtered slice (see [RegionalGtfsFeed] and the worker's REGIONAL_FEEDS). BART's
    // VehiclePositions is an exception (see that entry).
    //
    // Static feeds also come from 511's datafeed API, because 511's realtime uses its own stop_id
    // catalog, which an agency's own static download wouldn't match. Feeds fetched through 511 use
    // the region's America/Los_Angeles default timezone, except AC Transit's `US/Pacific`. Each
    // entry credits 511.org alongside itself (see [AttributionPartner]).

    /** BART publishes no VehiclePositions, so its vehicles don't move on the map. ETAs come from
     * TripUpdates, and Trip Detail's current stop comes from inferCurrentStopSequence(). */
    BART(
        "bart",
        "BART",
        "https://gtfs.picotransit.com/511SFBA/static",
        "https://gtfs.picotransit.com/511SFBA/tripupdates",
        "https://gtfs.picotransit.com/511SFBA/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFBA/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "BA"), AttributionPartner("511.org")),
    ),
    /** A large feed, handled by the streaming download and batched ingest commits. */
    SFMTA_MUNI(
        "sfmta_muni",
        "SFMTA Muni",
        "https://gtfs.picotransit.com/511SFSF/static",
        "https://gtfs.picotransit.com/511SFSF/tripupdates",
        "https://gtfs.picotransit.com/511SFSF/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFSF/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SF"), AttributionPartner("511.org")),
    ),
    /** timeZoneId is `US/Pacific`, as declared in this feed's agency.txt (a legacy alias that
     * ZoneId.of() accepts). */
    AC_TRANSIT(
        "ac_transit",
        "AC Transit",
        "https://gtfs.picotransit.com/511SFAC/static",
        "https://gtfs.picotransit.com/511SFAC/tripupdates",
        "https://gtfs.picotransit.com/511SFAC/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFAC/alerts",
        timeZoneId = "US/Pacific",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "AC"), AttributionPartner("511.org")),
    ),

    CALTRAIN(
        "caltrain",
        "Caltrain",
        "https://gtfs.picotransit.com/511SFCT/static",
        "https://gtfs.picotransit.com/511SFCT/tripupdates",
        "https://gtfs.picotransit.com/511SFCT/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFCT/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CT"), AttributionPartner("511.org")),
    ),
    /** Static comes from gtfs.vta.org (via the proxy) rather than 511, so realtime stop_ids go through
     * [RegionalStopIdPrefixBridge]. */
    VTA(
        "vta",
        "VTA",
        "https://gtfs.picotransit.com/vta/static",
        "https://gtfs.picotransit.com/511SFSC/tripupdates",
        "https://gtfs.picotransit.com/511SFSC/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFSC/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(
            RegionalGtfsFeed("511.org SF Bay Area", "SC"),
            AttributionPartner("511.org"),
            RegionalStopIdPrefixBridge("6"),
        ),
    ),

    COUNTY_CONNECTION(
        "county_connection",
        "County Connection",
        "https://gtfs.picotransit.com/511SFCC/static",
        "https://gtfs.picotransit.com/511SFCC/tripupdates",
        "https://gtfs.picotransit.com/511SFCC/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFCC/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CC"), AttributionPartner("511.org")),
    ),
    ACE(
        "ace",
        "ACE",
        "https://gtfs.picotransit.com/511SFCE/static",
        "https://gtfs.picotransit.com/511SFCE/tripupdates",
        "https://gtfs.picotransit.com/511SFCE/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFCE/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CE"), AttributionPartner("511.org")),
    ),
    SANTA_CRUZ_METRO(
        "santa_cruz_metro",
        "Santa Cruz METRO",
        "https://gtfs.picotransit.com/511SFCR/static",
        "https://gtfs.picotransit.com/511SFCR/tripupdates",
        "https://gtfs.picotransit.com/511SFCR/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFCR/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CR"), AttributionPartner("511.org")),
    ),
    CAPITOL_CORRIDOR(
        "capitol_corridor",
        "Capitol Corridor",
        "https://gtfs.picotransit.com/511SFAM/static",
        "https://gtfs.picotransit.com/511SFAM/tripupdates",
        "https://gtfs.picotransit.com/511SFAM/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFAM/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "AM"), AttributionPartner("511.org")),
    ),
    EMERY_GO_ROUND(
        "emery_go_round",
        "Emery Go-Round",
        "https://gtfs.picotransit.com/511SFEM/static",
        "https://gtfs.picotransit.com/511SFEM/tripupdates",
        "https://gtfs.picotransit.com/511SFEM/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFEM/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "EM"), AttributionPartner("511.org")),
    ),
    /** Bus network only; Golden Gate Ferry is a separate operator ([GOLDEN_GATE_FERRY], 511 code GF). */
    GOLDEN_GATE_TRANSIT(
        "golden_gate_transit",
        "Golden Gate Transit",
        "https://gtfs.picotransit.com/511SFGG/static",
        "https://gtfs.picotransit.com/511SFGG/tripupdates",
        "https://gtfs.picotransit.com/511SFGG/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFGG/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "GG"), AttributionPartner("511.org")),
    ),
    MARIN_TRANSIT(
        "marin_transit",
        "Marin Transit",
        "https://gtfs.picotransit.com/511SFMA/static",
        "https://gtfs.picotransit.com/511SFMA/tripupdates",
        "https://gtfs.picotransit.com/511SFMA/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFMA/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MA"), AttributionPartner("511.org")),
    ),
    MISSION_BAY_TMA(
        "mission_bay_tma",
        "Mission Bay TMA",
        "https://gtfs.picotransit.com/511SFMB/static",
        "https://gtfs.picotransit.com/511SFMB/tripupdates",
        "https://gtfs.picotransit.com/511SFMB/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFMB/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MB"), AttributionPartner("511.org")),
    ),
    MOUNTAIN_VIEW_COMMUNITY_SHUTTLE(
        "mountain_view_community_shuttle",
        "Mountain View Community Shuttle",
        "https://gtfs.picotransit.com/511SFMC/static",
        "https://gtfs.picotransit.com/511SFMC/tripupdates",
        "https://gtfs.picotransit.com/511SFMC/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFMC/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MC"), AttributionPartner("511.org")),
    ),
    MVGO(
        "mvgo",
        "MVgo",
        "https://gtfs.picotransit.com/511SFMV/static",
        "https://gtfs.picotransit.com/511SFMV/tripupdates",
        "https://gtfs.picotransit.com/511SFMV/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFMV/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "MV"), AttributionPartner("511.org")),
    ),
    PETALUMA_TRANSIT(
        "petaluma_transit",
        "Petaluma Transit",
        "https://gtfs.picotransit.com/511SFPE/static",
        "https://gtfs.picotransit.com/511SFPE/tripupdates",
        "https://gtfs.picotransit.com/511SFPE/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFPE/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "PE"), AttributionPartner("511.org")),
    ),
    RIO_VISTA_DELTA_BREEZE(
        "rio_vista_delta_breeze",
        "Rio Vista Delta Breeze",
        "https://gtfs.picotransit.com/511SFRV/static",
        "https://gtfs.picotransit.com/511SFRV/tripupdates",
        "https://gtfs.picotransit.com/511SFRV/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFRV/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "RV"), AttributionPartner("511.org")),
    ),
    SMART(
        "smart",
        "SMART",
        "https://gtfs.picotransit.com/511SFSA/static",
        "https://gtfs.picotransit.com/511SFSA/tripupdates",
        "https://gtfs.picotransit.com/511SFSA/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFSA/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SA"), AttributionPartner("511.org")),
    ),
    SF_BAY_FERRY(
        "sf_bay_ferry",
        "SF Bay Ferry",
        "https://gtfs.picotransit.com/511SFSB/static",
        "https://gtfs.picotransit.com/511SFSB/tripupdates",
        "https://gtfs.picotransit.com/511SFSB/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFSB/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SB"), AttributionPartner("511.org")),
    ),
    SAN_LEANDRO_LINKS(
        "san_leandro_links",
        "San Leandro LINKS",
        "https://gtfs.picotransit.com/511SFSL/static",
        "https://gtfs.picotransit.com/511SFSL/tripupdates",
        "https://gtfs.picotransit.com/511SFSL/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFSL/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SL"), AttributionPartner("511.org")),
    ),
    SAMTRANS(
        "samtrans",
        "SamTrans",
        "https://gtfs.picotransit.com/511SFSM/static",
        "https://gtfs.picotransit.com/511SFSM/tripupdates",
        "https://gtfs.picotransit.com/511SFSM/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFSM/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SM"), AttributionPartner("511.org")),
    ),
    SONOMA_COUNTY_TRANSIT(
        "sonoma_county_transit",
        "Sonoma County Transit",
        "https://gtfs.picotransit.com/511SFSO/static",
        "https://gtfs.picotransit.com/511SFSO/tripupdates",
        "https://gtfs.picotransit.com/511SFSO/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFSO/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SO"), AttributionPartner("511.org")),
    ),
    SANTA_ROSA_CITYBUS(
        "santa_rosa_citybus",
        "Santa Rosa CityBus",
        "https://gtfs.picotransit.com/511SFSR/static",
        "https://gtfs.picotransit.com/511SFSR/tripupdates",
        "https://gtfs.picotransit.com/511SFSR/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFSR/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SR"), AttributionPartner("511.org")),
    ),
    SOLTRANS(
        "soltrans",
        "SolTrans",
        "https://gtfs.picotransit.com/511SFST/static",
        "https://gtfs.picotransit.com/511SFST/tripupdates",
        "https://gtfs.picotransit.com/511SFST/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFST/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "ST"), AttributionPartner("511.org")),
    ),
    /** This feed has duplicate directions.txt rows and blank times at non-timepoint stops. */
    WESTCAT(
        "westcat",
        "WestCat",
        "https://gtfs.picotransit.com/511SFWC/static",
        "https://gtfs.picotransit.com/511SFWC/tripupdates",
        "https://gtfs.picotransit.com/511SFWC/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFWC/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "WC"), AttributionPartner("511.org")),
    ),
    LAVTA_WHEELS(
        "lavta_wheels",
        "LAVTA Wheels",
        "https://gtfs.picotransit.com/511SFWH/static",
        "https://gtfs.picotransit.com/511SFWH/tripupdates",
        "https://gtfs.picotransit.com/511SFWH/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFWH/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "WH"), AttributionPartner("511.org")),
    ),


    TRI_DELTA(
        "tri_delta",
        "Tri Delta Transit",
        "https://gtfs.picotransit.com/511SF3D/static",
        "https://gtfs.picotransit.com/511SF3D/tripupdates",
        "https://gtfs.picotransit.com/511SF3D/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SF3D/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "3D"), AttributionPartner("511.org")),
    ),
    ANGEL_ISLAND_TIBURON_FERRY(
        "angel_island_tiburon_ferry",
        "Angel Island Tiburon Ferry",
        "https://gtfs.picotransit.com/511SFAF/static",
        "https://gtfs.picotransit.com/511SFAF/tripupdates",
        "https://gtfs.picotransit.com/511SFAF/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFAF/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "AF"), AttributionPartner("511.org")),
    ),
    COMMUTE_ORG_SHUTTLES(
        "commute_org_shuttles",
        "Commute.org Shuttles",
        "https://gtfs.picotransit.com/511SFCM/static",
        "https://gtfs.picotransit.com/511SFCM/tripupdates",
        "https://gtfs.picotransit.com/511SFCM/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFCM/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "CM"), AttributionPartner("511.org")),
    ),
    /** Operated by WestCat, but published as a separate feed from [WESTCAT]. */
    DUMBARTON_EXPRESS(
        "dumbarton_express",
        "Dumbarton Express",
        "https://gtfs.picotransit.com/511SFDE/static",
        "https://gtfs.picotransit.com/511SFDE/tripupdates",
        "https://gtfs.picotransit.com/511SFDE/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFDE/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "DE"), AttributionPartner("511.org")),
    ),
    /** A separate 511 operator code from [EMERY_GO_ROUND]; served as whatever 511 publishes under it. */
    EMERY_EXPRESS(
        "emery_express",
        "Emery Express",
        "https://gtfs.picotransit.com/511SFEE/static",
        "https://gtfs.picotransit.com/511SFEE/tripupdates",
        "https://gtfs.picotransit.com/511SFEE/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFEE/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "EE"), AttributionPartner("511.org")),
    ),
    FAST_TRANSIT(
        "fast_transit",
        "FAST",
        "https://gtfs.picotransit.com/511SFFS/static",
        "https://gtfs.picotransit.com/511SFFS/tripupdates",
        "https://gtfs.picotransit.com/511SFFS/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFFS/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "FS"), AttributionPartner("511.org")),
    ),
    /** Separate operator from [GOLDEN_GATE_TRANSIT] (bus and ferry are distinct 511 codes). */
    GOLDEN_GATE_FERRY(
        "golden_gate_ferry",
        "Golden Gate Ferry",
        "https://gtfs.picotransit.com/511SFGF/static",
        "https://gtfs.picotransit.com/511SFGF/tripupdates",
        "https://gtfs.picotransit.com/511SFGF/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFGF/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "GF"), AttributionPartner("511.org")),
    ),
    PRESIDIO_GO(
        "presidio_go",
        "Presidio Go",
        "https://gtfs.picotransit.com/511SFPG/static",
        "https://gtfs.picotransit.com/511SFPG/tripupdates",
        "https://gtfs.picotransit.com/511SFPG/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFPG/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "PG"), AttributionPartner("511.org")),
    ),
    SFO_AIRPORT(
        "sfo_airport",
        "SFO Airport",
        "https://gtfs.picotransit.com/511SFSI/static",
        "https://gtfs.picotransit.com/511SFSI/tripupdates",
        "https://gtfs.picotransit.com/511SFSI/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFSI/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SI"), AttributionPartner("511.org")),
    ),
    SOUTH_SAN_FRANCISCO(
        "south_san_francisco",
        "South San Francisco Shuttle",
        "https://gtfs.picotransit.com/511SFSS/static",
        "https://gtfs.picotransit.com/511SFSS/tripupdates",
        "https://gtfs.picotransit.com/511SFSS/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFSS/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "SS"), AttributionPartner("511.org")),
    ),
    TREASURE_ISLAND_FERRY(
        "treasure_island_ferry",
        "Treasure Island Ferry",
        "https://gtfs.picotransit.com/511SFTF/static",
        "https://gtfs.picotransit.com/511SFTF/tripupdates",
        "https://gtfs.picotransit.com/511SFTF/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFTF/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "TF"), AttributionPartner("511.org")),
    ),
    UNION_CITY_TRANSIT(
        "union_city_transit",
        "Union City Transit",
        "https://gtfs.picotransit.com/511SFUC/static",
        "https://gtfs.picotransit.com/511SFUC/tripupdates",
        "https://gtfs.picotransit.com/511SFUC/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFUC/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "UC"), AttributionPartner("511.org")),
    ),
    VACAVILLE_CITY_COACH(
        "vacaville_city_coach",
        "Vacaville City Coach",
        "https://gtfs.picotransit.com/511SFVC/static",
        "https://gtfs.picotransit.com/511SFVC/tripupdates",
        "https://gtfs.picotransit.com/511SFVC/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFVC/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "VC"), AttributionPartner("511.org")),
    ),
    VINE_TRANSIT(
        "vine_transit",
        "VINE Transit",
        "https://gtfs.picotransit.com/511SFVN/static",
        "https://gtfs.picotransit.com/511SFVN/tripupdates",
        "https://gtfs.picotransit.com/511SFVN/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/511SFVN/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(RegionalGtfsFeed("511.org SF Bay Area", "VN"), AttributionPartner("511.org")),
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
    /** Realtime: no key, HTTPS. MTA's WAF rejects requests with no User-Agent; the proxy sends one.
     * MTA splits subway realtime across 8 line-group feeds, which the proxy merges into one response
     * at `/nyc_subway/combined`, so this agency has a single realtime URL. Those feeds' trip_ids
     * encode a scheduled start time instead of the static trip_id; [NycSubwayTripIdBridge] maps them
     * back (see [RealtimeTripIdBridge]). Entities also carry NYCT-specific protobuf fields
     * (TripDescriptor 1001, FeedEntity 2/5, VehiclePosition 6, StopTimeUpdate 7 and 1001), declared
     * in GtfsRealtime.kt because this decoder fails on undeclared fields. */
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
     * MTA publishes NYC bus as 6 static feeds: 5 NYCT division zips (this entry and the 4 below), each
     * holding only its own service but sharing one citywide routes.txt, plus MTA Bus Company, a
     * separate operator with its own routes. Each is its own single-feed agency, since merging them
     * made a database too large to ingest on the phone. All 6 share MTA's one system-wide GTFS-RT
     * feed; live vehicles outside a division's own schedule simply don't match.
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

    // Puget Sound region, regionalized like NYC and the SF Bay Area (see RegionalGroup.PUGET_SOUND).
    // Every agency here gets live data through OneBusAway's Puget Sound API, via the proxy's
    // /puget_sound/ routes. Amtrak and Solid Ground EZ Loop are left out as not regional; Seattle
    // Streetcar is included in King County Metro's feed.
    //
    // King County Metro shows the legend King County's terms require (see [AttributionLegend]). The
    // others credit Sound Transit alongside themselves (see [AttributionPartner]), since their data
    // comes through Sound Transit.
    //
    // King County Metro, Pierce Transit, Community Transit, and Sound Transit are hosted on
    // soundtransit.org, whose cert chain ends at a root some device trust stores lack, so they
    // route through pico-transit-proxy's /<id>/static routes. The other five fetch directly.
    KING_COUNTY_METRO(
        "kcm",
        "King County Metro",
        "https://gtfs.picotransit.com/kcm/static",
        "https://gtfs.picotransit.com/puget_sound/kcm/tripupdates",
        "https://gtfs.picotransit.com/puget_sound/kcm/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/puget_sound/kcm/alerts",
        timeZoneId = "America/Los_Angeles",
        // Required by King County's Transit Data Terms of Use.
        components = listOf(
            AttributionLegend("Transit scheduling, geographic, and real-time data provided by permission of King County"),
        ),
    ),
    PIERCE_TRANSIT(
        "pierce_transit",
        "Pierce Transit",
        "https://gtfs.picotransit.com/pierce_transit/static",
        "https://gtfs.picotransit.com/puget_sound/pierce_transit/tripupdates",
        "https://gtfs.picotransit.com/puget_sound/pierce_transit/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/puget_sound/pierce_transit/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(AttributionPartner("Sound Transit")),
    ),
    INTERCITY_TRANSIT(
        "intercity_transit",
        "Intercity Transit",
        "https://gtfs.sound.obaweb.org/prod/19_gtfs.zip",
        "https://gtfs.picotransit.com/puget_sound/intercity_transit/tripupdates",
        "https://gtfs.picotransit.com/puget_sound/intercity_transit/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/puget_sound/intercity_transit/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(AttributionPartner("Sound Transit")),
    ),
    KITSAP_TRANSIT(
        "kitsap_transit",
        "Kitsap Transit",
        "https://gtfs.sound.obaweb.org/prod/20_gtfs.zip",
        "https://gtfs.picotransit.com/puget_sound/kitsap_transit/tripupdates",
        "https://gtfs.picotransit.com/puget_sound/kitsap_transit/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/puget_sound/kitsap_transit/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(AttributionPartner("Sound Transit")),
    ),
    COMMUNITY_TRANSIT(
        "community_transit",
        "Community Transit",
        "https://gtfs.picotransit.com/community_transit/static",
        "https://gtfs.picotransit.com/puget_sound/community_transit/tripupdates",
        "https://gtfs.picotransit.com/puget_sound/community_transit/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/puget_sound/community_transit/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(AttributionPartner("Sound Transit")),
    ),
    SOUND_TRANSIT(
        "sound_transit",
        "Sound Transit",
        "https://gtfs.picotransit.com/sound_transit/static",
        "https://gtfs.picotransit.com/puget_sound/sound_transit/tripupdates",
        "https://gtfs.picotransit.com/puget_sound/sound_transit/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/puget_sound/sound_transit/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(AttributionPartner("Sound Transit")),
    ),
    WA_STATE_FERRIES(
        "wa_state_ferries",
        "Washington State Ferries",
        "https://gtfs.sound.obaweb.org/prod/95_gtfs.zip",
        "https://gtfs.picotransit.com/puget_sound/wa_state_ferries/tripupdates",
        "https://gtfs.picotransit.com/puget_sound/wa_state_ferries/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/puget_sound/wa_state_ferries/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(AttributionPartner("Sound Transit")),
    ),
    SEATTLE_MONORAIL(
        "seattle_monorail",
        "Seattle Center Monorail",
        "https://gtfs.sound.obaweb.org/prod/96_gtfs.zip",
        "https://gtfs.picotransit.com/puget_sound/seattle_monorail/tripupdates",
        "https://gtfs.picotransit.com/puget_sound/seattle_monorail/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/puget_sound/seattle_monorail/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(AttributionPartner("Sound Transit")),
    ),
    EVERETT_TRANSIT(
        "everett_transit",
        "Everett Transit",
        "https://gtfs.sound.obaweb.org/prod/97_gtfs.zip",
        "https://gtfs.picotransit.com/puget_sound/everett_transit/tripupdates",
        "https://gtfs.picotransit.com/puget_sound/everett_transit/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/puget_sound/everett_transit/alerts",
        timeZoneId = "America/Los_Angeles",
        components = listOf(AttributionPartner("Sound Transit")),
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