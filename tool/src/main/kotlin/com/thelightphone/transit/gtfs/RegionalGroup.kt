package com.thelightphone.transit.gtfs

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Groups agencies that belong to the same real-world region -- not merely a display label:
 * [forAgency] already gates a real feature (HomeScreen's Schedule button decides whether to show
 * `ScheduleAgencyPickerScreen` or go straight to one agency's own schedule based on membership
 * here, and Settings' Additional Schedules list is scoped to region-mates the same way -- see
 * [AgencyPreferences.additionalDownloadsFlow]'s own doc), and is meant to grow into the join key
 * for further region-scoped features still to come (a combined view of every region agency's live
 * vehicles, stops grouped across agencies within a region). Each region's own realtime/static
 * data-sharing mechanics stay wherever they already live per-agency (511's server-side regional
 * aggregator for SF Bay Area, [MultiGtfsFeed]'s merge for RTD/Bustang, NYC's various agencies
 * already sharing single realtime URLs where applicable) -- this enum doesn't own or duplicate any
 * of that itself, it's the membership list those region-scoped behaviors, present and future, key
 * off of.
 *
 * An agency not listed under any group here (MBTA, RIPTA, every Colorado agency, etc.) has no
 * [RegionalGroup] at all -- [forAgency] returns null, and the picker just lists it individually.
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
    /** Every 511-integrated agency already in [GtfsAgency] (each carries its own
     * `RegionalGtfsFeed("511.org SF Bay Area", ...)` component) -- membership here doesn't
     * introduce a separate realtime path of its own, since 511 already covers every one of these
     * agencies individually; it's still the real membership list [forAgency]'s callers key off
     * of. */
    SF_BAY_AREA(
        "sf_bay_area",
        "SF Bay Area",
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
    /** Bustang already merges into RTD's own database via [BustangSecondaryFeed] (so RTD alone
     * already carries Bustang's schedule) -- [GtfsAgency.BUSTANG] is listed as its own member too
     * since a rider who wants Bustang specifically without the rest of RTD's own network should
     * still be able to pick it individually, same reasoning as it getting its own top-level
     * agency entry in the first place. */
    DENVER("denver", "Denver", listOf(GtfsAgency.RTD, GtfsAgency.BUSTANG)),
    /** Unlike SF Bay Area, no shared regional aggregator here -- each agency has its own independent
     * static feed (and, once wired, its own OneBusAway realtime by agency_id), so this membership
     * list is the only region-scoped mechanism these nine currently share. See each agency's own
     * doc comment in [GtfsAgency] for why Amtrak/Solid Ground EZ Loop/Seattle Streetcar aren't
     * included. */
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
    ;

    companion object {
        fun forAgency(agency: GtfsAgency): RegionalGroup? = entries.find { agency in it.members }
    }
}

/** One member agency's own realtime, both merged feeds -- exactly what
 * [GtfsAgency.fetchMergedTripUpdates]/[GtfsAgency.fetchMergedVehiclePositions] already return
 * per-agency; [fetchRegionalRealtime] just runs several of these concurrently. */
data class RegionalRealtimeFeed(
    val tripUpdates: MergedRealtimeFeed<GtfsRtTripUpdate>,
    val vehiclePositions: MergedRealtimeFeed<GtfsRtVehiclePosition>,
)

/**
 * Fetches every one of [selectedMembers]' own realtime concurrently, never one agency after
 * another -- a rider who has more than one of this region's agencies active at once (their primary
 * plus whichever [AgencyPreferences.additionalDownloadsFlow] extras belong to this same
 * [RegionalGroup] -- the existing "Additional Schedules" toggle already IS the selection this reads
 * from, nothing new to pick) gets every selected agency's live data at the same real-world moment,
 * not staggered by however many agencies came before it in some arbitrary fetch order. Only ever
 * checks as many feeds in parallel as [selectedMembers] actually holds -- a rider with just their
 * primary selected still makes exactly one agency's own fetch, same cost as today.
 *
 * Deliberately a [RegionalGroup]-scoped concern, not a generic "fetch N agencies in parallel"
 * utility -- it only ever makes sense for agencies already known to belong together (this enum's
 * own [members]), never an arbitrary unrelated set. Each member's own multi-feed internals (NYC
 * Subway's own line-group feeds, merged server-side by pico-transit-proxy into one URL; a
 * 511-integrated agency's own regional-aggregator fetch) stay entirely that agency's own concern --
 * this only concerns itself with fetching different AGENCIES' feeds concurrently, never how any
 * single agency's own feed(s) get fetched internally. That split is intentional: consolidating
 * NYC Subway's own line-group feeds server-side (one URL, shared across every rider) is a genuine
 * efficiency win, but merging feeds ACROSS agencies server-side would mean the worker fetching data
 * for agency combinations most riders never select at all -- this instead only ever fetches exactly
 * the agencies one specific rider has actually chosen, client-side, once per rider.
 *
 * [selectedMembers] maps each agency the rider actually wants to its own already-open
 * [GtfsRepository] -- callers are expected to have already filtered [members] down to the primary
 * plus enabled [AgencyPreferences.additionalDownloadsFlow] extras (and to only pass an agency whose
 * static schedule is actually downloaded on-device); this function does no filtering of its own.
 */
suspend fun RegionalGroup.fetchRegionalRealtime(
    selectedMembers: Map<GtfsAgency, GtfsRepository>,
    logTag: String,
): Map<GtfsAgency, RegionalRealtimeFeed> = coroutineScope {
    selectedMembers.map { (agency, repository) ->
        async {
            // Each agency's own two feed kinds are also fetched concurrently with each other, not
            // just concurrently with other agencies -- same reasoning as UpcomingArrivalsScreen's
            // existing StopPredictionSource/LiveVehicleSource pattern (two independent network
            // round trips, awaiting one after the other bought nothing).
            val tripUpdatesDeferred = async { agency.fetchMergedTripUpdates(repository, logTag) }
            val vehiclePositionsDeferred = async { agency.fetchMergedVehiclePositions(repository, logTag) }
            agency to RegionalRealtimeFeed(tripUpdatesDeferred.await(), vehiclePositionsDeferred.await())
        }
    }.awaitAll().toMap()
}
