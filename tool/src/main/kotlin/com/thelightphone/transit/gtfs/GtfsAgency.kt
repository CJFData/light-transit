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

    // Most entries below are static-schedule-only ("(No Live)" in their own displayName) -- added
    // ahead of realtime on purpose so each one gets a real on-device ingest test at its actual size
    // before any live-feed work is layered on top. Realtime is only wired where it was hand-verified
    // to need no API key, resolve over plain HTTPS with no proxy needed, and either already fit
    // GtfsRealtime.kt's declared protobuf schema or needed just one small, verified addition (see
    // LIRR/METRO_NORTH below) -- every other entry's own comment records exactly what's blocking it
    // (no feed exists at all, a key/registration is required, the origin is HTTP-only and needs
    // proxying, or the feed's shape doesn't fit this app's one-URL-per-agency model), so wiring one
    // in later is a lookup, not new research. None of these reference 511.org's regional aggregator
    // by design (deliberately excluded), even where it's the only realtime source that exists for an
    // agency -- see each entry's own note for what that costs it.

    /** TripUpdates exists on BART's own domain but the origin is HTTP-only
     * (http://api.bart.gov/gtfsrt/tripupdate.aspx, 301-redirecting to HTTPS on the same host) --
     * same shape as RIPTA/LTC before their fix, so it needs routing through pico-transit-proxy
     * (or an equivalent HTTPS-only entry point) before it can be added here; Android blocks the
     * initial cleartext connection outright, before it would ever see the redirect. No key needed
     * either way. VehiclePositions does not exist at all -- confirmed against BART's own
     * GTFS-Realtime doc page and by exhausting every plausible URL guess -- same class of gap as
     * STM's Métro, live vehicle markers just aren't possible for BART. */
    BART(
        "bart",
        "BART (No Live)",
        "https://www.bart.gov/dev/schedules/google_transit.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    /** No realtime feed exists on any SFMTA-owned domain -- Muni's only live vehicle/ETA data is
     * distributed through 511's regional aggregator, which this app deliberately doesn't use.
     * Static schedule only, with no path to live data short of reversing that exclusion. ~1.9M
     * stop_times rows (~37% of STM's row count) -- real size, should be fine under the
     * streaming-download/batched-commit fixes already shipped, but worth a real device ingest test
     * before trusting it the way STM's own test was needed. */
    SFMTA_MUNI(
        "sfmta_muni",
        "SFMTA Muni (No Live)",
        "https://muni-gtfs.apps.sfmta.com/data/muni_gtfs-current.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    /** Full TripUpdates + VehiclePositions exist on AC Transit's own HTTPS domain
     * (api.actransit.org/gtfsrt/...) but require an individually-registered API token -- the static
     * feed URL below embeds a token AC Transit itself publishes as a public documentation example
     * (not a secret), but a realtime token needs its own registration and wasn't obtained here, so
     * field-compatibility against GtfsRealtime.kt's declared schema is unverified. timeZoneId is
     * `US/Pacific` exactly as declared in this feed's own agency.txt (a legacy tzdata alias --
     * resolves fine via ZoneId.of(), left as-is per this file's own "don't guess/correct, copy
     * straight from the feed" rule). */
    AC_TRANSIT(
        "ac_transit",
        "AC Transit (No Live)",
        "https://api.actransit.org/transit/gtfs/download?token=2512B81107A09D2DC44895CDDC650D47",
        null,
        null,
        timeZoneId = "US/Pacific",
    ),
    /** No independently-discoverable realtime feed exists outside 511/Swiftly -- static schedule
     * only. Feed is Trillium-hosted (data.trilliumtransit.com), same vendor already used for
     * Colorado's smaller agencies, not Caltrain's own domain -- it's the exact URL Caltrain's own
     * developer-resources page links to as its GTFS source. Tiny (5.5K stop_times rows), no size
     * concern. */
    CALTRAIN(
        "caltrain",
        "Caltrain (No Live)",
        "https://data.trilliumtransit.com/gtfs/caltrain-ca-us/caltrain-ca-us.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
    ),
    /** No realtime feed of VTA's own -- Transitland's own operator record lists VTA's only live
     * source as the 511 regional feed by Transitland's own tagging, not a VTA-owned one. Static
     * schedule only, same situation as Muni. */
    VTA(
        "vta",
        "VTA (No Live)",
        "https://gtfs.vta.org/gtfs_vta.zip",
        null,
        null,
        timeZoneId = "America/Los_Angeles",
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
    /** TripUpdates + VehiclePositions exist on CTA's own domain
     * (transitdata.transitchicago.com/GtfsRealtime/{TripUpdates,VehiclePositions}.pb?key=...) but
     * need a free registered API key
     * AND sit behind Cloudflare bot-protection that 403'd even a plain unauthenticated probe --
     * field-compatibility against GtfsRealtime.kt is unverified, and a real client User-Agent may be
     * needed to avoid being blocked as a bot, independent of the key. ~6.0M stop_times rows -- larger
     * than STM's 5.1M that already needed the streaming/batching fixes; same order of magnitude, not
     * UK-BODS-regional scale, but wants its own real device ingest test before being trusted. */
    CTA(
        "cta",
        "CTA (No Live)",
        "https://www.transitchicago.com/downloads/sch_data/google_transit.zip",
        null,
        null,
        timeZoneId = "America/Chicago",
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