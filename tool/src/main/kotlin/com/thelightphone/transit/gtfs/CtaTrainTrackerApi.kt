package com.thelightphone.transit.gtfs

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val CTA_TRAIN_POSITIONS_URL = "https://pico-transit-proxy.data-32b.workers.dev/cta/train/positions"

// Same width as MapScreen's own SCHEDULED_ARRIVALS_GRACE_PERIOD_SECONDS -- a live train's own real
// predicted time can legitimately trail its closest scheduled candidate's static time by a few
// minutes without that candidate being the wrong match.
private const val CTA_MATCH_GRACE_SECONDS = 10 * 60

private val ctaTrainJson = Json { ignoreUnknownKeys = true }

/**
 * See [FuzzyRunTrips]'s own doc for why CTA 'L' trains need closest-matching rather than a real
 * trip_id bridge -- Train Tracker identifies a train only by a daily-changing run number, with no
 * schedule-time-equivalent field the way Bus Tracker's `stst`/`stsd` provide for buses.
 *
 * One call per poll: `ttpositions.aspx?rt=` gives every live train on a route in one shot (run
 * number, and each train's own immediate next stop_id + real predicted time). An earlier version
 * also called `ttfollow.aspx?runnumber=` per live train for its full remaining trip -- confirmed
 * live 2026-08-23 that this meant one HTTP call per live train per 10s poll, directly the
 * over-fetching CTA's own developer guidance warns against, so it's gone; only a matched trip's own
 * immediate next stop ever gets a real live time now, every stop further downstream just falls back
 * to schedule-only, an acceptable trade for an already-approximate "closest match" (see
 * [FuzzyRunTrips]'s own doc).
 *
 * Matching is stop-anchored, not route-wide: each live train's own next stop_id (train stop_ids are
 * confirmed the same numbering space as this app's own GTFS `stop_id` per CTA's own Train Tracker
 * docs) is looked up directly via [GtfsRepository.getScheduledArrivals], and whichever real trip on
 * this route has the closest static departure time at that exact stop wins. An earlier version
 * instead built one shared route+direction-wide candidate pool (ranked without knowing which stop a
 * rider was actually viewing) and paired it ordinally against live runs -- confirmed live 2026-08-23
 * this let a candidate that had already passed the rider's own stop rank as "soonest" (it still had
 * stops left elsewhere on the route), which silently zeroed out every match at that stop. Matching
 * directly at each live train's own real next stop can't have that failure mode: the candidate pool
 * for a given train is only ever real trips still scheduled at the one stop that train is truly
 * heading to next. [usedTripIds] still guards against two different live trains claiming the same
 * real trip_id when their candidate windows overlap.
 *
 * [stickyRunByTripId] pins a real trip_id to whichever run number first matched it, for this
 * object's whole process lifetime (not just one poll) -- confirmed live 2026-08-23: recomputing the
 * closest match fresh every poll let the SAME rider-viewed trip_id silently flip to a DIFFERENT
 * physical train between polls (or across leaving and returning to Upcoming Arrivals/Trip Detail,
 * which re-polls from scratch) whenever two trains' candidate windows happened to be close. Once a
 * run is pinned, every later poll skips ranking entirely and just refreshes that same run's current
 * next-stop/time against the same trip_id, so a rider always sees one consistent train's position
 * for as long as it keeps appearing in ttpositions -- only unpinned implicitly, by that run no
 * longer appearing at all (see the sticky-first ordering below for why that's still race-safe).
 *
 * [liveRunOptions]/[tripUpdateForRun] are the direct, unranked counterpart to all of the above --
 * see their own doc on [FuzzyRunTrips] for why boarding needs a rider's own explicit pick (Select
 * Run) rather than trusting even the sticky automatic match.
 *
 * [propagatedTripUpdate] is what actually builds every [GtfsRtTripUpdate] this source returns --
 * confirmed live 2026-08-23 that a single-entry update (real time at [CtattTrain.nextStpId] only,
 * the original shape here right after `ttfollow` was removed) meant a matched trip's "closest
 * match" was visible on Trip Detail (which only ever cares about that one live stop) but silently
 * never showed up on Upcoming Arrivals for any OTHER stop on that same trip -- which is nearly
 * always the case, since a rider is essentially never viewing the exact stop a live train happens
 * to be approaching at that instant. Rather than reintroducing a second per-run API call (the
 * over-fetching `ttfollow` was removed to fix), the one real data point already in hand -- the
 * difference between [CtattTrain.arrT] and this trip's own scheduled time at that same stop -- is
 * propagated as a constant delay across every remaining stop on the matched trip's own static
 * schedule (one local DB query, no network), the same "hold a delay forward" approximation a real
 * GTFS-RT producer commonly uses when it hasn't got a fresher per-stop prediction either.
 */
object CtaTrainTrackerSource : FuzzyRunTrips {
    override val routeIds: Set<String> = setOf("Red", "P", "Y", "Blue", "Pink", "G", "Org", "Brn")

    // Shared across every call, never closed -- same reasoning as RunAssociatedTripSource's own
    // client: a fresh HttpClient per call was a real, confirmed source of added latency there.
    private val client = HttpClient(OkHttp)

    private val stickyRunByTripId = mutableMapOf<String, String>()

    override suspend fun matchedTripUpdates(
        requestedRouteIds: Set<String>,
        repository: GtfsRepository,
        agency: GtfsAgency,
        zoneId: ZoneId,
    ): Map<String, GtfsRtTripUpdate> {
        val scopedRouteIds = requestedRouteIds.intersect(routeIds)
        if (scopedRouteIds.isEmpty()) return emptyMap()
        val today = todayForGtfs(zoneId)
        val nowGtfsTime = currentGtfsTimeOfDay(zoneId)

        val trainsByRoute = try {
            fetchPositions(scopedRouteIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("CtaTrainTrackerSource", "Position fetch failed", e)
            return emptyMap()
        }

        val result = mutableMapOf<String, GtfsRtTripUpdate>()
        for ((routeId, trains) in trainsByRoute) {
            // Claimed first-come, soonest-arriving train first -- the more time-critical match gets
            // first pick when two trains' candidate windows happen to overlap. Already-pinned runs
            // go first within that ordering, so a run that's sticky to trip_id X always reclaims X
            // before any other train can freshly claim it this same poll.
            val usedTripIds = mutableSetOf<String>()
            val sortedTrains = trains
                .sortedBy { it.arrT?.let { t -> parseTrainTimestamp(t, zoneId) } ?: Long.MAX_VALUE }
                .sortedByDescending { it.rn != null && stickyRunByTripId.containsValue(it.rn) }
            for (train in sortedTrains) {
                val stopId = train.nextStpId ?: continue
                val targetTime = train.arrT?.let { parseTrainTimestamp(it, zoneId) } ?: continue
                val rn = train.rn ?: continue

                val stickyTripId = stickyRunByTripId.entries.find { it.value == rn }?.key
                val tripId = stickyTripId ?: repository.getScheduledArrivals(stopId, nowGtfsTime, today, CTA_MATCH_GRACE_SECONDS)
                    .asSequence()
                    .filter { it.route.routeId == routeId && it.tripId !in usedTripIds }
                    .mapNotNull { arrival ->
                        gtfsTimeToEpochSeconds(arrival.departureTime, today, zoneId)?.let { arrival.tripId to it }
                    }
                    .minByOrNull { (_, epoch) -> kotlin.math.abs(epoch - targetTime) }
                    ?.first
                    ?: continue

                stickyRunByTripId[tripId] = rn
                usedTripIds += tripId
                propagatedTripUpdate(train, tripId, repository, today, zoneId)?.let { result[tripId] = it }
            }
        }
        return result
    }

    override suspend fun liveRunOptions(
        routeId: String,
        agency: GtfsAgency,
        zoneId: ZoneId,
    ): List<FuzzyRunOption> {
        if (routeId !in routeIds) return emptyList()
        val trains = try {
            fetchPositions(setOf(routeId))[routeId].orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("CtaTrainTrackerSource", "Position fetch failed", e)
            return emptyList()
        }
        return trains.mapNotNull { train ->
            val rn = train.rn ?: return@mapNotNull null
            val nextStopId = train.nextStpId ?: return@mapNotNull null
            val time = train.arrT?.let { parseTrainTimestamp(it, zoneId) } ?: return@mapNotNull null
            FuzzyRunOption(
                runId = rn,
                destinationLabel = train.destNm ?: "Unknown",
                soonestPredictedEpochSeconds = time,
                nextStopId = nextStopId,
                // CTA's own delay flag, straight from ttpositions -- not diffed against any
                // schedule of ours (see FuzzyRunOption.isDelayed's own doc for why).
                isDelayed = train.isDly == "1",
            )
        }.sortedBy { it.soonestPredictedEpochSeconds }
    }

    override suspend fun tripUpdateForRun(
        runId: String,
        tripId: String,
        routeId: String,
        repository: GtfsRepository,
        agency: GtfsAgency,
        zoneId: ZoneId,
    ): GtfsRtTripUpdate? {
        if (routeId !in routeIds) return null
        val trains = try {
            fetchPositions(setOf(routeId))[routeId].orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("CtaTrainTrackerSource", "Position fetch failed", e)
            return null
        }
        val train = trains.find { it.rn == runId } ?: return null
        return propagatedTripUpdate(train, tripId, repository, todayForGtfs(zoneId), zoneId)
    }

    /** Shared by [matchedTripUpdates] (ranked match) and [tripUpdateForRun] (direct, rider-selected
     * lookup) -- see this object's own doc on why a single-stop update alone isn't enough. Falls
     * back to that original single-stop shape (real time at [CtattTrain.nextStpId] only, no
     * propagation) whenever [tripId]'s own static schedule doesn't have a row at that exact stop_id
     * to anchor a delay against -- confirmed live this can happen on a short-turn/interlined trip
     * whose actual stop_times don't cover every physical stop the live train reports. Null only when
     * this train's own position data is incomplete (no next stop reported yet, or its predicted time
     * didn't parse) -- that case can't even build the fallback. */
    private fun propagatedTripUpdate(
        train: CtattTrain,
        tripId: String,
        repository: GtfsRepository,
        today: LocalDate,
        zoneId: ZoneId,
    ): GtfsRtTripUpdate? {
        val stopId = train.nextStpId ?: return null
        val targetTime = train.arrT?.let { parseTrainTimestamp(it, zoneId) } ?: return null
        val rn = train.rn ?: return null
        val singleStopUpdate = GtfsRtTripUpdate(
            trip = GtfsRtTripDescriptor(tripId = tripId),
            vehicle = GtfsRtVehicleDescriptor(id = rn),
            stopTimeUpdate = listOf(GtfsRtStopTimeUpdate(stopId = stopId, arrival = GtfsRtStopTimeEvent(time = targetTime))),
        )

        val tripStops = repository.getTripStops(tripId, 0)
        val anchor = tripStops.find { it.stopId == stopId } ?: return singleStopUpdate
        val anchorScheduledTime = anchor.departureTime ?: anchor.arrivalTime ?: return singleStopUpdate
        val anchorScheduledEpoch = gtfsTimeToEpochSeconds(anchorScheduledTime, today, zoneId) ?: return singleStopUpdate
        val delaySeconds = (targetTime - anchorScheduledEpoch).toInt()

        val stopTimeUpdate = tripStops
            .filter { it.stopSequence >= anchor.stopSequence }
            .map { row ->
                GtfsRtStopTimeUpdate(
                    stopSequence = row.stopSequence,
                    stopId = row.stopId,
                    arrival = GtfsRtStopTimeEvent(delay = delaySeconds),
                    departure = GtfsRtStopTimeEvent(delay = delaySeconds),
                )
            }
        return GtfsRtTripUpdate(
            trip = GtfsRtTripDescriptor(tripId = tripId),
            vehicle = GtfsRtVehicleDescriptor(id = rn),
            stopTimeUpdate = stopTimeUpdate,
        )
    }

    private suspend fun fetchPositions(routeIds: Set<String>): Map<String, List<CtattTrain>> {
        // CTA's own cap on comma-delimited rt= values for ttpositions (Train Locations API Error Codes,
        // error 107: "Maximum number of rt's per request is 8") -- matches this component's own full
        // routeIds set exactly, so a caller asking for all 8 lines at once still fits one call.
        val rt = routeIds.take(8).joinToString(",")
        val response = client.get("$CTA_TRAIN_POSITIONS_URL?rt=$rt&outputType=JSON")
        if (response.status.value !in 200..299) {
            Log.e("CtaTrainTrackerSource", "Position fetch failed: HTTP ${response.status.value}")
            return emptyMap()
        }
        val document = ctaTrainJson.decodeFromString(CtattPositionsDocument.serializer(), response.bodyAsText())
        // Keyed back to the caller's own requested route_id casing, not CTA's -- confirmed live
        // 2026-08-23: ttpositions' own "@name" always comes back lowercase ("blue", "red", "g")
        // regardless of the case sent in rt=, but GTFS route_id is "Blue"/"Red"/"G". Silently keying
        // by CTA's own casing here made every downstream route_id-scoped lookup miss.
        return document.ctatt.route.orEmpty().mapNotNull { route ->
            val matchedRouteId = routeIds.find { it.equals(route.name, ignoreCase = true) } ?: return@mapNotNull null
            matchedRouteId to route.train.orEmpty()
        }.toMap()
    }
}

/** ISO-8601 local time with no offset (e.g. "2026-08-23T13:12:30") -- confirmed live 2026-08-23
 * against real ttpositions responses. NOT the older "yyyyMMdd HH:mm:ss" format CTA's own developer
 * docs describe for this endpoint -- that mismatch silently failed to parse (runCatching swallowed
 * every DateTimeParseException), which is why every FuzzyRunTrips match for CTA trains previously
 * came back empty despite positions fetching fine. */
private fun parseTrainTimestamp(raw: String, zoneId: ZoneId): Long? =
    runCatching {
        LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zoneId).toEpochSecond()
    }.getOrNull()

@Serializable
private data class CtattPositionsDocument(
    val ctatt: CtattPositionsBody = CtattPositionsBody(),
)

@Serializable
private data class CtattPositionsBody(
    val route: List<CtattRoute>? = null,
)

@Serializable
private data class CtattRoute(
    @SerialName("@name") val name: String? = null,
    val train: List<CtattTrain>? = null,
)

@Serializable
private data class CtattTrain(
    val rn: String? = null,
    val destNm: String? = null,
    val nextStpId: String? = null,
    val arrT: String? = null,
    /** "1" when CTA itself flags this train as delayed, "0" otherwise -- see
     * [FuzzyRunOption.isDelayed]'s own doc for why this is used as-is rather than diffed against
     * our own schedule. */
    val isDly: String? = null,
)
