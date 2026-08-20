package com.thelightphone.transit.gtfs

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

private const val CTA_BUS_VEHICLES_URL = "https://pico-transit-proxy.data-32b.workers.dev/cta/bus/vehicles"

private val ctaBusJson = Json { ignoreUnknownKeys = true }

/**
 * CTA Bus Tracker (ctabustracker.com/bustime/api/v3) -- proprietary REST/JSON, not GTFS-RT, but
 * still resolves a real GTFS trip_id per vehicle (see below), so this implements [LiveVehicleSource]
 * itself rather than needing its own separate shape, and shares MapScreen's single generic
 * live-vehicle merge with MBTA.
 *
 * getvehicles hands back `stsd`/`stst` -- the trip's own scheduled start date and start time in
 * seconds-past-midnight -- which is the same (route, start_date, start_time) triple the GTFS-RT
 * spec itself uses to identify a trip when its trip_id isn't directly known.
 * [GtfsRepository.tripIdForScheduledStart] looks up the one static trip whose first stop_time
 * matches that triple; ambiguous (more than one match, vanishingly rare in practice) or unmatched
 * vehicles are simply dropped from the returned map for that poll -- same "never force a link"
 * contract as every other live source.
 *
 * getvehicles has no next-stop/current-sequence field at all (only `pdist`, a raw distance-into-
 * pattern figure that isn't resolvable to a stop_sequence without also parsing getpatterns' own
 * geometry -- not fetched here), so [LiveVehicleInfo.currentStatus]/[currentStopSequence] are
 * always null. A caller already treats a null sequence as "no live status opinion, fall back to
 * schedule-time" for every other source with the same gap -- nothing new needed here.
 *
 * `rt` accepts at most 10 comma-delimited route designators per the API's own limit; a caller
 * asking for more than that only gets the first 10.
 */
object CtaBusTrackerSource : LiveVehicleSource {
    override val coveredLineTypes: Set<LineType> = setOf(LineType.BUS)

    override suspend fun vehiclesByRoute(routeIds: Set<String>, repository: GtfsRepository): Map<String, LiveVehicleInfo> {
        if (routeIds.isEmpty()) return emptyMap()
        val client = HttpClient(OkHttp)
        try {
            val rt = routeIds.take(10).joinToString(",")
            val response = client.get("$CTA_BUS_VEHICLES_URL?rt=$rt&tmres=s&format=json")
            if (response.status.value !in 200..299) {
                Log.e("CtaBusTrackerSource", "Vehicle fetch failed: HTTP ${response.status.value}")
                return emptyMap()
            }
            val document = ctaBusJson.decodeFromString(BustimeVehiclesDocument.serializer(), response.bodyAsText())

            return document.busTimeResponse.vehicle.orEmpty().mapNotNull { vehicle ->
                val routeId = vehicle.rt ?: return@mapNotNull null
                val lat = vehicle.lat?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = vehicle.lon?.toDoubleOrNull() ?: return@mapNotNull null
                val startTime = vehicle.stst?.let { secondsToGtfsTime(it) } ?: return@mapNotNull null
                val startDate = vehicle.stsd?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val tripId = repository.tripIdForScheduledStart(routeId, startTime, startDate) ?: return@mapNotNull null
                tripId to LiveVehicleInfo(
                    latitude = lat,
                    longitude = lon,
                    currentStatus = null,
                    currentStopSequence = null,
                    assignedStopId = null,
                )
            }.toMap()
        } catch (e: Exception) {
            Log.e("CtaBusTrackerSource", "Vehicle fetch failed", e)
            return emptyMap()
        } finally {
            client.close()
        }
    }
}

/** getvehicles' `stst` is seconds-past-midnight as a plain int; GTFS stop_times.departure_time
 * wants "HH:MM:SS" (and, same as GTFS, doesn't wrap at 24:00:00 for a post-midnight trip, so this
 * doesn't clamp the hour either). */
private fun secondsToGtfsTime(totalSeconds: Int): String =
    "%02d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)

@Serializable
private data class BustimeVehiclesDocument(
    @SerialName("bustime-response") val busTimeResponse: BustimeResponseBody = BustimeResponseBody(),
)

@Serializable
private data class BustimeResponseBody(
    val vehicle: List<BustimeVehicle>? = null,
)

@Serializable
private data class BustimeVehicle(
    val rt: String? = null,
    val lat: String? = null,
    val lon: String? = null,
    val stst: Int? = null,
    val stsd: String? = null,
)
