package com.thelightphone.transit.gtfs

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Agencies in the same region. The agency picker shows a region as one row, the Schedule button
 * offers region-mates' schedules, and Additional Schedules lists only region-mates. Each agency's
 * own feed setup stays in [GtfsAgency].
 *
 * An agency in no group gets null from [forAgency] and its own picker row.
 */
enum class RegionalGroup(val id: String, val displayName: String, val members: List<GtfsAgency>) {
    NYC(
        "nyc",
        "New York City",
        listOf(
            GtfsAgency.NYC_SUBWAY,
            GtfsAgency.LIRR,
            GtfsAgency.METRO_NORTH,
            GtfsAgency.NYC_BUS_BRONX,
            GtfsAgency.NYC_BUS_BROOKLYN,
            GtfsAgency.NYC_BUS_MANHATTAN,
            GtfsAgency.NYC_BUS_QUEENS,
            GtfsAgency.NYC_BUS_STATEN_ISLAND,
            GtfsAgency.NYC_BUS_COMPANY,
        ),
    ),
    /** The 511.org agencies. */
    SF_BAY_AREA(
        "sf_bay_area",
        "San Francisco Bay Area",
        listOf(
            GtfsAgency.BART,
            GtfsAgency.SFMTA_MUNI,
            GtfsAgency.AC_TRANSIT,
            GtfsAgency.CALTRAIN,
            GtfsAgency.VTA,
            GtfsAgency.COUNTY_CONNECTION,
            GtfsAgency.ACE,
            GtfsAgency.SANTA_CRUZ_METRO,
            GtfsAgency.CAPITOL_CORRIDOR,
            GtfsAgency.EMERY_GO_ROUND,
            GtfsAgency.GOLDEN_GATE_TRANSIT,
            GtfsAgency.MARIN_TRANSIT,
            GtfsAgency.MISSION_BAY_TMA,
            GtfsAgency.MOUNTAIN_VIEW_COMMUNITY_SHUTTLE,
            GtfsAgency.MVGO,
            GtfsAgency.PETALUMA_TRANSIT,
            GtfsAgency.RIO_VISTA_DELTA_BREEZE,
            GtfsAgency.SMART,
            GtfsAgency.SF_BAY_FERRY,
            GtfsAgency.SAN_LEANDRO_LINKS,
            GtfsAgency.SAMTRANS,
            GtfsAgency.SONOMA_COUNTY_TRANSIT,
            GtfsAgency.SANTA_ROSA_CITYBUS,
            GtfsAgency.SOLTRANS,
            GtfsAgency.WESTCAT,
            GtfsAgency.LAVTA_WHEELS,
            GtfsAgency.TRI_DELTA,
            GtfsAgency.ANGEL_ISLAND_TIBURON_FERRY,
            GtfsAgency.COMMUTE_ORG_SHUTTLES,
            GtfsAgency.DUMBARTON_EXPRESS,
            GtfsAgency.EMERY_EXPRESS,
            GtfsAgency.FAST_TRANSIT,
            GtfsAgency.GOLDEN_GATE_FERRY,
            GtfsAgency.PRESIDIO_GO,
            GtfsAgency.SFO_AIRPORT,
            GtfsAgency.SOUTH_SAN_FRANCISCO,
            GtfsAgency.TREASURE_ISLAND_FERRY,
            GtfsAgency.UNION_CITY_TRANSIT,
            GtfsAgency.VACAVILLE_CITY_COACH,
            GtfsAgency.VINE_TRANSIT,
        ),
    ),
    /**
     * Bustang's schedule is merged into RTD, but it's listed too so it can be picked on its own.
     */
    DENVER("denver", "Denver", listOf(GtfsAgency.RTD, GtfsAgency.BUSTANG)),
    /** Each agency has its own schedule; they share live data through OneBusAway. */
    PUGET_SOUND(
        "puget_sound",
        "Puget Sound",
        listOf(
            GtfsAgency.KING_COUNTY_METRO,
            GtfsAgency.SOUND_TRANSIT,
            GtfsAgency.PIERCE_TRANSIT,
            GtfsAgency.COMMUNITY_TRANSIT,
            GtfsAgency.KITSAP_TRANSIT,
            GtfsAgency.EVERETT_TRANSIT,
            GtfsAgency.INTERCITY_TRANSIT,
            GtfsAgency.WA_STATE_FERRIES,
            GtfsAgency.SEATTLE_MONORAIL,
        ),
    ),
    /** SEPTA's two schedules: buses, Metro, and trolleys in one, Regional Rail in the other. */
    PHILADELPHIA("philadelphia", "Philadelphia", listOf(GtfsAgency.SEPTA_BUS, GtfsAgency.SEPTA_RAIL)),
    /**
     * MBTA and RIPTA with Massachusetts' regional transit authorities and the area's ferries. MBTA
     * comes first, so the region takes its place at the top of the agency picker.
     */
    SOUTHERN_NEW_ENGLAND(
        "southern_new_england",
        "Southern New England",
        listOf(
            GtfsAgency.MBTA,
            GtfsAgency.RIPTA,
            GtfsAgency.SRTA,
            GtfsAgency.MERRIMACK_VALLEY_TRANSIT,
            GtfsAgency.BERKSHIRE_RTA,
            GtfsAgency.BROCKTON_AREA_TRANSIT,
            GtfsAgency.CAPE_ANN_TRANSPORTATION,
            GtfsAgency.CAPE_COD_RTA,
            GtfsAgency.FRANKLIN_RTA,
            GtfsAgency.LOWELL_RTA,
            GtfsAgency.METROWEST_RTA,
            GtfsAgency.MONTACHUSETT_RTA,
            GtfsAgency.NANTUCKET_WAVE,
            GtfsAgency.PIONEER_VALLEY_TRANSIT,
            GtfsAgency.BAY_STATE_CRUISE,
            GtfsAgency.CUTTYHUNK_FERRY,
            GtfsAgency.FREEDOM_CRUISE_LINE,
            GtfsAgency.HY_LINE_CRUISES,
            GtfsAgency.PATRIOT_PARTY_BOATS,
            GtfsAgency.SEASTREAK,
            GtfsAgency.VINEYARD_FAST_FERRY,
            GtfsAgency.BLOCK_ISLAND_FERRY,
        ),
    ),
    ;

    companion object {
        fun forAgency(agency: GtfsAgency): RegionalGroup? = entries.find { agency in it.members }
    }
}

/** One member's merged trip updates and vehicle positions. */
data class RegionalRealtimeFeed(
    val tripUpdates: MergedRealtimeFeed<GtfsRtTripUpdate>,
    val vehiclePositions: MergedRealtimeFeed<GtfsRtVehiclePosition>,
)

/**
 * Fetches realtime for every agency in [selectedMembers] at once, so they all reflect the same
 * moment. Callers pass only the agencies the rider has selected (the primary plus enabled
 * additional schedules in this region), each with its open [GtfsRepository]; nothing is filtered
 * here.
 */
suspend fun RegionalGroup.fetchRegionalRealtime(
    selectedMembers: Map<GtfsAgency, GtfsRepository>,
    logTag: String,
): Map<GtfsAgency, RegionalRealtimeFeed> = coroutineScope {
    selectedMembers.map { (agency, repository) ->
        async {
            // An agency's trip updates and vehicle positions are fetched at the same time too.
            val tripUpdatesDeferred = async { agency.fetchMergedTripUpdates(repository, logTag) }
            val vehiclePositionsDeferred = async { agency.fetchMergedVehiclePositions(repository, logTag) }
            agency to RegionalRealtimeFeed(tripUpdatesDeferred.await(), vehiclePositionsDeferred.await())
        }
    }.awaitAll().toMap()
}
