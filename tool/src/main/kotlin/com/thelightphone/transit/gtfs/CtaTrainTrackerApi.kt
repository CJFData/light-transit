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

private const val CTA_TRAIN_POSITIONS_URL = "https://gtfs.picotransit.com/cta/train/positions"

// A live train's predicted time can trail its scheduled match by a few minutes.
private const val CTA_MATCH_GRACE_SECONDS = 10 * 60

private val ctaTrainJson = Json { ignoreUnknownKeys = true }

/**
 * Closest-match runs for 'L' trains from Train Tracker, which identifies trains only by run number,
 * so each live train is paired with the scheduled trip it most likely is. Select Run
 * ([liveRunOptions], [tripUpdateForRun]) lets the rider pick a different train.
 *
 * One ttpositions call per poll returns every train on the requested routes, each with its next
 * stop and a predicted time there. Each train is matched at that next stop to the trip on its route
 * with the closest scheduled time, so a trip that has already passed the stop is never a candidate.
 * [usedTripIds] keeps two trains from claiming the same trip.
 *
 * [stickyRunByTripId] keeps a trip paired with the same run for the life of the process, so it
 * doesn't jump between trains from one poll to the next. The pairing ends when the run leaves the
 * feed.
 *
 * [propagatedTripUpdate] carries the train's one delay forward to the trip's remaining stops, so
 * the match also shows in Upcoming Arrivals.
 */
object CtaTrainTrackerSource : FuzzyRunTrips {
    override val routeIds: Set<String> = setOf("Red", "P", "Y", "Blue", "Pink", "G", "Org", "Brn")

    // One shared client, never closed.
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
            // The soonest trains pick first, with pinned runs ahead so they keep their trips.
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
        repository: GtfsRepository,
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
                // Train Tracker's own delay flag.
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

    /**
     * Shared by [matchedTripUpdates] and [tripUpdateForRun]. Falls back to a single-stop update
     * when the trip's schedule doesn't include the train's next stop. Null when the position data
     * is incomplete.
     */
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
        // ttpositions takes at most 8 routes per request.
        val rt = routeIds.take(8).joinToString(",")
        val response = client.get("$CTA_TRAIN_POSITIONS_URL?rt=$rt&outputType=JSON")
        if (response.status.value !in 200..299) {
            Log.e("CtaTrainTrackerSource", "Position fetch failed: HTTP ${response.status.value}")
            return emptyMap()
        }
        val document = ctaTrainJson.decodeFromString(CtattPositionsDocument.serializer(), response.bodyAsText())
        // Keyed by the requested route_id, since the response's route names are lowercase.
        return document.ctatt.route.orEmpty().mapNotNull { route ->
            val matchedRouteId = routeIds.find { it.equals(route.name, ignoreCase = true) } ?: return@mapNotNull null
            matchedRouteId to route.train.orEmpty()
        }.toMap()
    }
}

/** Local time with no offset, e.g. "2026-08-23T13:12:30". */
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
    /** "1" when the train is flagged as delayed. */
    val isDly: String? = null,
)
