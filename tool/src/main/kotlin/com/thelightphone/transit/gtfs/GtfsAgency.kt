package com.thelightphone.transit.gtfs

import java.io.File
import java.time.ZoneId

/**
 * Every agency the app supports. Realtime URLs are null when an agency has no feed; screens treat a
 * null URL and a failed fetch the same way.
 *
 * To add an agency, append an entry with a unique [id], its [displayName], static [feedUrl], and
 * the [timeZoneId] from its agency.txt. Screens and preferences iterate [entries], so nothing else
 * needs to change. Check a live sample of any realtime feed first: the protobuf decoder fails on
 * undeclared fields (see GtfsRealtime.kt).
 */
enum class GtfsAgency(
    val id: String,
    val displayName: String,
    val feedUrl: String,
    val realtimeTripUpdatesUrl: String?,
    val realtimeVehiclePositionsUrl: String?,
    /** The agency's GTFS-RT alerts feed, if any. */
    val realtimeAlertsUrl: String? = null,
    /**
     * The agency's IANA time zone, from agency_timezone in its agency.txt. GTFS times are in the
     * agency's zone, not the device's, so [todayForGtfs], [currentGtfsTimeOfDay], and
     * [gtfsTimeToEpochSeconds] use this.
     */
    val timeZoneId: String,
    /**
     * Extra data sources beyond the feed URLs; see [AgencyComponent]. A [MultiGtfsFeed] merges in
     * another feed's schedule and realtime (see [RTD]), or adds only a realtime feed (see
     * [NYC_SUBWAY]).
     */
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
        // Subway trains running as ADDED trips get a closest match.
        components = listOf(MbtaV3VehicleSource, MbtaSubwayFuzzyRunSource),
    ),
    RIPTA(
        "ripta",
        "RIPTA",
        "https://ripta.com/RIPTA-GTFS.zip",
        "https://gtfs.picotransit.com/ripta/tripupdates",
        "https://gtfs.picotransit.com/ripta/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/ripta/alerts",
        timeZoneId = "America/New_York",
        // Reads shapes.txt from the downloaded zip for shape-based tracking.
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
    /**
     * Also merged into RTD Denver via [BustangSecondaryFeed], with its own entry for riders looking
     * it up directly.
     */
    BUSTANG(
        "bustang",
        "Bustang",
        "https://www.rtd-denver.com/files/gtfs/bustang-co-us.zip",
        "https://gtfs.picotransit.com/bustang/tripupdates",
        "https://gtfs.picotransit.com/bustang/vehiclepositions",
        timeZoneId = "America/Denver",
    ),
    /** The rest of Colorado's agencies, from colorado-gtfs.trilliumtransit.com. */
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
    /** A GTFS-Flex (demand-response) feed with little fixed-route data. */
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
    /** Same feed as [VIA_MOBILITY]; its agency.txt lists Boulder County and City of Boulder. */
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
    /** Redirects to evta.org. */
    CORE_TRANSIT(
        "core_transit",
        "Core Transit (No Live)",
        "https://gtfs.coretransit.org/",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /**
     * A GTFS-Flex feed with little fixed-route data. Its agency.txt uses `US/Mountain`, an alias
     * ZoneId accepts.
     */
    DOLORES_COUNTY(
        "dolores_county",
        "Dolores County (No Live)",
        "https://data.trilliumtransit.com/gtfs/dolorescounty-co-us/dolorescounty-co-us--flex-v2.zip",
        null,
        null,
        timeZoneId = "US/Mountain",
    ),
    /** A document-viewer URL, but it serves the zip itself. */
    DURANGO_TRANSIT(
        "durango_transit",
        "Durango Transit (No Live)",
        "https://durangogov.org/DocumentCenter/View/17688/Durango-Transit-GTFS-Data",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** A GTFS-Flex feed with little fixed-route data. */
    EASY_RIDE_TRANSPORTATION(
        "easy_ride_transportation",
        "Easy Ride Transportation (No Live)",
        "https://data.trilliumtransit.com/gtfs/broomfield-co-us/broomfield-co-us--flex-v2.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** A GTFS-Flex feed with little fixed-route data. */
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
    /** The URL says flex, but this feed has full fixed-route schedules. */
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
    /**
     * Same feed as [TOWN_OF_MOUNTAIN_VILLAGE] and [TOWN_OF_TELLURIDE]; its agency.txt lists SMART
     * and both towns.
     */
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
    /** Same feed as [SAN_MIGUEL_REGIONAL_TRANSPORTATION]. */
    TOWN_OF_MOUNTAIN_VILLAGE(
        "town_of_mountain_village",
        "Town of Mountain Village (No Live)",
        "https://data.trilliumtransit.com/gtfs/sanmiguelcounty-co-us/sanmiguelcounty-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
    /** Same feed as [SAN_MIGUEL_REGIONAL_TRANSPORTATION]. */
    TOWN_OF_TELLURIDE(
        "town_of_telluride",
        "Town of Telluride (No Live)",
        "https://data.trilliumtransit.com/gtfs/sanmiguelcounty-co-us/sanmiguelcounty-co-us.zip",
        null,
        null,
        timeZoneId = "America/Denver",
    ),
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
    /** Same feed as [BOULDER_COUNTY]. */
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
        realtimeAlertsUrl = "https://gtfs.picotransit.com/ltc/alerts",
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

    // SF Bay Area agencies, through the end of this group, get realtime from 511.org's regional
    // feed. The proxy fetches it once per cache window and serves each agency its own slice (see
    // [RegionalGtfsFeed]).
    //
    // Schedules also come from 511, since its realtime uses 511's own stop_ids. Each entry credits
    // 511.org alongside itself (see [AttributionPartner]).

    /**
     * No vehicle positions are used here, so vehicles don't move on the map. ETAs come from
     * TripUpdates, and Trip Detail's current stop from inferCurrentStopSequence().
     */
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
    /** A large feed, handled by the streaming download and batched ingest. */
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
    /** Uses `US/Pacific`, as in its agency.txt; ZoneId accepts the alias. */
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
    /**
     * The schedule comes from gtfs.vta.org (via the proxy) rather than 511, so realtime stop_ids
     * and route_ids go through [RegionalIdBridge].
     */
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
            RegionalIdBridge(stopIdPrefix = "6"),
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
    /** Bus only; the ferry is [GOLDEN_GATE_FERRY]. */
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
    /** Operated by WestCat, but a separate feed from [WESTCAT]. */
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
    /** A separate 511 operator from [EMERY_GO_ROUND]. */
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
    /** Separate from the [GOLDEN_GATE_TRANSIT] bus network. */
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

    /**
     * Bus, with Rail merged in from a second schedule ([LaMetroRailSecondaryFeed]). Schedules only.
     */
    LA_METRO(
        "la_metro",
        "LA Metro (No Live)",
        "https://gitlab.com/LACMTA/gtfs_bus/-/raw/master/gtfs_bus.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
        components = listOf(LaMetroRailSecondaryFeed),
    ),

    //cleveland
    GCRTA(
        "gcrta",
        "GCRTA",
        feedUrl = "https://www.riderta.com/sites/default/files/gtfs/latest/google_transit.zip",
        realtimeTripUpdatesUrl = "https://gtfs.picotransit.com/gcrta/tripupdates",
        realtimeVehiclePositionsUrl ="https://gtfs.picotransit.com/gcrta/vehiclepositions" ,
        realtimeAlertsUrl = "https://gtfs.picotransit.com/gcrta/alerts",
        timeZoneId = "America/New_York",
    ),
    /**
     * Realtime comes from CTA's own APIs rather than GTFS-RT: Bus Tracker matches buses to trips
     * ([RunAssociatedTripSource]), and Train Tracker gives 'L' trains a closest match
     * ([CtaTrainTrackerSource]). A large feed.
     */
    CTA(
        "cta",
        "CTA (Partial Live)",
        "https://www.transitchicago.com/downloads/sch_data/google_transit.zip",
        null,
        null,
        timeZoneId = "America/Chicago",
        // trips.txt has no trip_headsign, so its direction column is used instead. Train Tracker
        // covers the 'L' routes only.
        components = listOf(
            RunAssociatedTripSource,
            TripDirectionColumn("direction"),
            CtaTrainTrackerSource,
        ),
    ),
    /** Schedules only. */
    METRA(
        "metra",
        "Metra (No Live)",
        "https://schedules.metrarail.com/gtfs/schedule.zip",
        null,
        null,
        timeZoneId = "America/Chicago",
    ),
    /** Schedules only. */
    PACE(
        "pace",
        "Pace (No Live)",
        "https://www.pacebus.com/sites/default/files/2026-08/GTFS.zip",
        null,
        null,
        timeZoneId = "America/Chicago",
    ),
    /**
     * Realtime comes through the proxy, which merges the subway's line-group feeds into one at
     * `/nyc_subway/combined`. Those trip_ids encode a start time instead of the static trip_id, so
     * [NycSubwayTripIdBridge] maps them back.
     */
    NYC_SUBWAY(
        "nyc_subway",
        "NYC Subway",
        "https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip",
        "https://gtfs.picotransit.com/nyc_subway/combined",
        "https://gtfs.picotransit.com/nyc_subway/combined",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/nyc_subway/alerts",
        timeZoneId = "America/New_York",
        components = listOf(NycSubwayTripIdBridge),
    ),
    /**
     * Realtime: one combined TripUpdates and VehiclePositions feed. The schedule uses
     * calendar_dates.txt only, which the service-day query handles.
     */
    LIRR(
        "lirr",
        "LIRR",
        "https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/lirr%2Fgtfs-lirr",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/lirr%2Fgtfs-lirr",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/lirr/alerts",
        timeZoneId = "America/New_York",
    ),
    /** Same setup as [LIRR]. */
    METRO_NORTH(
        "metro_north",
        "Metro-North",
        "https://rrgtfsfeeds.s3.amazonaws.com/gtfsmnr.zip",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/mnr%2Fgtfs-mnr",
        "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/mnr%2Fgtfs-mnr",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/mnr/alerts",
        timeZoneId = "America/New_York",
    ),
    /**
     * NYC bus comes as 6 schedules: 5 NYCT borough divisions (this and the 4 below) and MTA Bus
     * Company. Each is its own agency, since merged they're too large to ingest on the phone. All 6
     * share one realtime feed; vehicles outside an agency's schedule don't match.
     */
    NYC_BUS_BRONX(
        "nyc_bus_bronx",
        "NYC Bus - Bronx",
        "https://web.mta.info/developers/data/nyct/bus/google_transit_bronx.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/nyc_bus/alerts",
        timeZoneId = "America/New_York",
    ),
    NYC_BUS_BROOKLYN(
        "nyc_bus_brooklyn",
        "NYC Bus - Brooklyn",
        "https://web.mta.info/developers/data/nyct/bus/google_transit_brooklyn.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/nyc_bus/alerts",
        timeZoneId = "America/New_York",
    ),
    NYC_BUS_MANHATTAN(
        "nyc_bus_manhattan",
        "NYC Bus - Manhattan",
        "https://web.mta.info/developers/data/nyct/bus/google_transit_manhattan.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/nyc_bus/alerts",
        timeZoneId = "America/New_York",
    ),
    NYC_BUS_QUEENS(
        "nyc_bus_queens",
        "NYC Bus - Queens",
        "https://web.mta.info/developers/data/nyct/bus/google_transit_queens.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/nyc_bus/alerts",
        timeZoneId = "America/New_York",
    ),
    NYC_BUS_STATEN_ISLAND(
        "nyc_bus_staten_island",
        "NYC Bus - Staten Island",
        "https://web.mta.info/developers/data/nyct/bus/google_transit_staten_island.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/nyc_bus/alerts",
        timeZoneId = "America/New_York",
    ),
    NYC_BUS_COMPANY(
        "nyc_bus_company",
        "NYC Bus - MTA Bus Company",
        "https://web.mta.info/developers/data/busco/google_transit.zip",
        "https://gtfs.picotransit.com/mta_bus/tripupdates",
        "https://gtfs.picotransit.com/mta_bus/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/nyc_bus/alerts",
        timeZoneId = "America/New_York",
    ),

    WEGO_TRANSIT(
        "wego_nashville",
        "Nashville - WeGo Public Transit",
        "https://www.wegotransit.com/GoogleExport/google_transit.zip",
        "https://gtfs.picotransit.com/wego_nashville/tripupdates",
        "https://gtfs.picotransit.com/wego_nashville/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/wego_nashville/alerts",
        timeZoneId = "America/Chicago",
    ),

    // Philadelphia region (see RegionalGroup.PHILADELPHIA). Buses, Metro lines, and trolleys come
    // in one schedule and Regional Rail in another. The live feeds match the bus schedule's trips.
    SEPTA_BUS(
        "septa_bus",
        "SEPTA Bus & Metro",
        "https://www3.septa.org/developer/google_bus.zip",
        "https://gtfs.picotransit.com/septa/tripupdates",
        "https://gtfs.picotransit.com/septa/vehiclepositions",
        realtimeAlertsUrl = "https://gtfs.picotransit.com/septa/alerts",
        timeZoneId = "America/New_York",
    ),
    SEPTA_RAIL(
        "septa_rail",
        "SEPTA Regional Rail (No Live)",
        "https://www3.septa.org/developer/google_rail.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),

    // Southern New England region (see RegionalGroup.SOUTHERN_NEW_ENGLAND), with MBTA and RIPTA.
    // Schedules only.
    SRTA(
        "srta",
        "SRTA (No Live)",
        "https://data.trilliumtransit.com/gtfs/srta-ma-us/srta-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    MERRIMACK_VALLEY_TRANSIT(
        "merrimack_valley_transit",
        "Merrimack Valley Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/merrimackvalley-ma-us/merrimackvalley-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    BERKSHIRE_RTA(
        "berkshire_rta",
        "Berkshire RTA (No Live)",
        "https://data.trilliumtransit.com/gtfs/berkshire-ma-us/berkshire-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    BROCKTON_AREA_TRANSIT(
        "brockton_area_transit",
        "Brockton Area Transit (No Live)",
        "https://data.trilliumtransit.com/gtfs/brockton-ma-us/brockton-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    CAPE_ANN_TRANSPORTATION(
        "cape_ann_transportation",
        "Cape Ann Transportation (No Live)",
        "https://data.trilliumtransit.com/gtfs/capeann-ma-us/capeann-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    CAPE_COD_RTA(
        "cape_cod_rta",
        "Cape Cod RTA (No Live)",
        "https://data.trilliumtransit.com/gtfs/capecod-ma-us/capecod-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    FRANKLIN_RTA(
        "franklin_rta",
        "Franklin RTA (No Live)",
        "https://data.trilliumtransit.com/gtfs/frta-ma-us/frta-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    LOWELL_RTA(
        "lowell_rta",
        "Lowell RTA (No Live)",
        "https://data.trilliumtransit.com/gtfs/lowell-ma-us/lowell-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    METROWEST_RTA(
        "metrowest_rta",
        "MetroWest RTA (No Live)",
        "https://vc.mwrta.com/gtfs/google_transit.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    MONTACHUSETT_RTA(
        "montachusett_rta",
        "Montachusett RTA (No Live)",
        "https://data.trilliumtransit.com/gtfs/montachusett-ma-us/montachusett-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    NANTUCKET_WAVE(
        "nantucket_wave",
        "Nantucket - The WAVE (No Live)",
        "https://data.trilliumtransit.com/gtfs/nantucket-ma-us/nantucket-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    PIONEER_VALLEY_TRANSIT(
        "pioneer_valley_transit",
        "Pioneer Valley Transit (No Live)",
        "https://www.pvta.com/g_trans/google_transit.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    BAY_STATE_CRUISE(
        "bay_state_cruise",
        "Bay State Cruise Company (No Live)",
        "https://data.trilliumtransit.com/gtfs/baystatecruisecompany-ma-us/baystatecruisecompany-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    CUTTYHUNK_FERRY(
        "cuttyhunk_ferry",
        "Cuttyhunk Ferry (No Live)",
        "https://data.trilliumtransit.com/gtfs/cuttyhunkferryco-ma-us/cuttyhunkferryco-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    FREEDOM_CRUISE_LINE(
        "freedom_cruise_line",
        "Freedom Cruise Line (No Live)",
        "https://data.trilliumtransit.com/gtfs/freedomcruiseline-ma-us/freedomcruiseline-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    HY_LINE_CRUISES(
        "hy_line_cruises",
        "Hy-Line Cruises (No Live)",
        "https://data.trilliumtransit.com/gtfs/hylinecruises-ma-us/hylinecruises-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    PATRIOT_PARTY_BOATS(
        "patriot_party_boats",
        "Patriot Party Boats (No Live)",
        "https://data.trilliumtransit.com/gtfs/patriotpartyboats-ma-us/patriotpartyboats-ma-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    SEASTREAK(
        "seastreak",
        "Seastreak (No Live)",
        "https://seastreak.com/api/transit/google_transit.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    VINEYARD_FAST_FERRY(
        "vineyard_fast_ferry",
        "Vineyard Fast Ferry (No Live)",
        "https://data.trilliumtransit.com/gtfs/vineyardfastferry-ri-us/vineyardfastferry-ri-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),
    BLOCK_ISLAND_FERRY(
        "block_island_ferry",
        "Block Island Ferry (No Live)",
        "https://data.trilliumtransit.com/gtfs/blockislandferry-ri-us/blockislandferry-ri-us.zip",
        null,
        null,
        timeZoneId = "America/New_York",
    ),

    // Puget Sound region (see RegionalGroup.PUGET_SOUND). Live data comes from OneBusAway's Puget
    // Sound API via the proxy's /puget_sound/ routes.
    //
    // King County Metro shows the legend its terms require (see [AttributionLegend]). The others
    // credit Sound Transit alongside themselves (see [AttributionPartner]).
    //
    // King County Metro, Pierce Transit, Community Transit, and Sound Transit download through the
    // proxy's /<id>/static routes, since their host's certificate chain ends at a root some devices
    // don't trust.
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

    /** Cached, so the zone's rules aren't looked up on every call. */
    val zoneId: ZoneId by lazy { ZoneId.of(timeZoneId) }

    /**
     * This agency's [AgencyComponent] of type [T], if it has one, e.g.
     * `agency.component<MbtaV3VehicleSource>()`.
     */
    inline fun <reified T : AgencyComponent> component(): T? = components.filterIsInstance<T>().firstOrNull()

    companion object {
        init {
            // [id] is also the cache directory name and the saved preference value, so a duplicate
            // would silently share another agency's data. Fail at class load instead.
            val duplicateIds = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
            check(duplicateIds.isEmpty()) {
                "GtfsAgency ids must be unique, got duplicates: $duplicateIds"
            }
        }

        /**
         * The agency a [dbFile] belongs to, from its "gtfs/{id}/transit.db" path, so screens don't
         * need the agency passed separately.
         */
        fun forDbFile(dbFile: File): GtfsAgency? = entries.find { it.id == dbFile.parentFile?.name }
    }
}