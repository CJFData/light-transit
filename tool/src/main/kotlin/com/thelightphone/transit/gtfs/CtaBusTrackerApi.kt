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

private const val CTA_BUS_VEHICLES_URL = "https://gtfs.picotransit.com/cta/bus/vehicles"
private const val CTA_BUS_PREDICTIONS_URL = "https://gtfs.picotransit.com/cta/bus/predictions"

private val ctaBusJson = Json { ignoreUnknownKeys = true }

/**
 * A live source whose vehicles each belong to a real scheduled trip but don't carry its trip_id.
 * Unlike [FuzzyRunTrips], the match is exact or the vehicle is dropped for that poll.
 *
 * Bus Tracker's getvehicles gives each bus's scheduled start date and time (`stsd`/`stst`), which
 * [GtfsRepository.tripIdForScheduledStart] resolves to a trip. It has no next stop, so
 * [LiveVehicleInfo.currentStatus] and [currentStopSequence] are null. `rt` takes at most 10 routes.
 */
object RunAssociatedTripSource : LiveVehicleSource, StopPredictionSource {
    override val coveredLineTypes: Set<LineType> = setOf(LineType.BUS)

    // One shared client, never closed, so polls reuse connections.
    private val client = HttpClient(OkHttp)

    override suspend fun vehiclesByRoute(routeIds: Set<String>, repository: GtfsRepository): Map<String, LiveVehicleInfo> {
        if (routeIds.isEmpty()) return emptyMap()
        try {
            val rt = routeIds.take(10).joinToString(",")
            val response = client.get("$CTA_BUS_VEHICLES_URL?rt=$rt&tmres=s&format=json")
            if (response.status.value !in 200..299) {
                Log.e("RunAssociatedTripSource", "Vehicle fetch failed: HTTP ${response.status.value}")
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
                    vehicleId = vehicle.vid,
                )
            }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("RunAssociatedTripSource", "Vehicle fetch failed", e)
            return emptyMap()
        }
    }

    /**
     * Predicted arrival times (`prdtm`) for one or more stops, preferred over [vehiclesByRoute] for
     * ETAs. Each prediction's `stst`/`stsd` resolve to a trip_id the same way as in
     * [vehiclesByRoute]. Returns the predicted time only, not a delay: `stst` is the trip's
     * first-stop time, not this stop's scheduled time.
     */
    override suspend fun predictionsByStop(stopIds: Set<String>, repository: GtfsRepository, zoneId: ZoneId): Map<String, Long> {
        if (stopIds.isEmpty()) return emptyMap()
        try {
            val stpid = stopIds.take(10).joinToString(",")
            val response = client.get("$CTA_BUS_PREDICTIONS_URL?stpid=$stpid&format=json")
            if (response.status.value !in 200..299) {
                Log.e("RunAssociatedTripSource", "Prediction fetch failed: HTTP ${response.status.value}")
                return emptyMap()
            }
            val document = ctaBusJson.decodeFromString(BustimePredictionsDocument.serializer(), response.bodyAsText())

            return document.busTimeResponse.prd.orEmpty().mapNotNull { prediction ->
                val routeId = prediction.rt ?: return@mapNotNull null
                val startTime = prediction.stst?.let { secondsToGtfsTime(it) } ?: return@mapNotNull null
                val startDate = prediction.stsd?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val predictedTime = prediction.prdtm?.let { parsePredictionTimestamp(it, zoneId) } ?: return@mapNotNull null
                val tripId = repository.tripIdForScheduledStart(routeId, startTime, startDate) ?: return@mapNotNull null
                tripId to predictedTime
            }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("RunAssociatedTripSource", "Prediction fetch failed", e)
            return emptyMap()
        }
    }

    /**
     * `getpredictions?vid=` returns the vehicle's remaining stops in ascending `prdtm` order; the
     * first is its next stop. No trip_id resolution needed, since the caller already knows the
     * trip.
     */
    override suspend fun nextStopForVehicle(vehicleId: String, repository: GtfsRepository, zoneId: ZoneId): VehicleNextStop? {
        try {
            val response = client.get("$CTA_BUS_PREDICTIONS_URL?vid=$vehicleId&format=json")
            if (response.status.value !in 200..299) {
                Log.e("RunAssociatedTripSource", "Vehicle prediction fetch failed: HTTP ${response.status.value}")
                return null
            }
            val document = ctaBusJson.decodeFromString(BustimePredictionsDocument.serializer(), response.bodyAsText())
            val next = document.busTimeResponse.prd.orEmpty().firstOrNull() ?: return null
            val stopId = next.stpid ?: return null
            val predictedTime = next.prdtm?.let { parsePredictionTimestamp(it, zoneId) } ?: return null
            return VehicleNextStop(stopId, predictedTime)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("RunAssociatedTripSource", "Vehicle prediction fetch failed", e)
            return null
        }
    }
}

/**
 * `prdtm` is "yyyyMMdd HH:mm" in agency-local time with no zone, so it's parsed in the agency's
 * zone.
 */
private fun parsePredictionTimestamp(raw: String, zoneId: ZoneId): Long? =
    runCatching {
        LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd HH:mm")).atZone(zoneId).toEpochSecond()
    }.getOrNull()

/** Seconds past midnight to "HH:MM:SS", without wrapping at 24:00 for after-midnight trips. */
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
    val vid: String? = null,
    val rt: String? = null,
    val lat: String? = null,
    val lon: String? = null,
    val stst: Int? = null,
    val stsd: String? = null,
)

@Serializable
private data class BustimePredictionsDocument(
    @SerialName("bustime-response") val busTimeResponse: BustimePredictionsBody = BustimePredictionsBody(),
)

@Serializable
private data class BustimePredictionsBody(
    val prd: List<BustimePrediction>? = null,
)

@Serializable
private data class BustimePrediction(
    val rt: String? = null,
    /** The stop this prediction is for; needed when querying by vehicle. */
    val stpid: String? = null,
    /** Predicted time, "yyyyMMdd HH:mm". */
    val prdtm: String? = null,
    val stst: Int? = null,
    val stsd: String? = null,
)
