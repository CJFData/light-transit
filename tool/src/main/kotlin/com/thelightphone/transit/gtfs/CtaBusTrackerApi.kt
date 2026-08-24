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

private const val CTA_BUS_VEHICLES_URL = "https://pico-transit-proxy.data-32b.workers.dev/cta/bus/vehicles"
private const val CTA_BUS_PREDICTIONS_URL = "https://pico-transit-proxy.data-32b.workers.dev/cta/bus/predictions"

private val ctaBusJson = Json { ignoreUnknownKeys = true }

/**
 * A **run-associated trip** is a live vehicle that corresponds to a real, exact trip already in the
 * static schedule -- the live source just doesn't hand back that trip_id directly, so a
 * [LiveVehicleSource] implementation bridges to it definitively via some other agency-specific key.
 * The match is never a guess: either it resolves to the one real static trip it belongs to, or it's
 * dropped for that poll, same as every other live source's "never force a link" contract.
 *
 * Contrast with a **fuzzy-run trip** (see [FuzzyRunTrips]) -- a live vehicle/run with no real static
 * trip to resolve to at all (e.g. MBTA Green Line's ADDED trips), where any pairing against the
 * schedule is necessarily an approximation, not a genuine match.
 *
 * CTA Bus Tracker (ctabustracker.com/bustime/api/v3) is this app's first run-associated source --
 * proprietary REST/JSON, not GTFS-RT, but every one of its buses IS a real scheduled trip, just not
 * identified by trip_id in the live payload. Its own bridge: getvehicles hands back `stsd`/`stst` --
 * the trip's own scheduled start date and start time in seconds-past-midnight -- the same (route,
 * start_date, start_time) triple GTFS-RT itself uses to identify a trip when its trip_id isn't
 * directly known. [GtfsRepository.tripIdForScheduledStart] looks up the one static trip whose first
 * stop_time matches that triple; ambiguous or unmatched vehicles are simply dropped for that poll.
 *
 * getvehicles has no next-stop/current-sequence field at all, so
 * [LiveVehicleInfo.currentStatus]/[currentStopSequence] are always null -- a caller already
 * treats a null sequence as "fall back to schedule-time" for every other source with the same gap.
 *
 * `rt` accepts at most 10 comma-delimited route designators per the API's own limit; a caller
 * asking for more only gets the first 10.
 */
object RunAssociatedTripSource : LiveVehicleSource, StopPredictionSource {
    override val coveredLineTypes: Set<LineType> = setOf(LineType.BUS)

    // Shared across every call (both vehiclesByRoute and predictionsByStop), never closed -- this is
    // a singleton object living for the app's whole lifetime, so there's no owning screen to close it
    // from. A fresh HttpClient(OkHttp) per call was paying full TLS/TCP handshake cost on every single
    // poll instead of reusing a pooled connection -- confirmed live via on-device timing logs showing
    // ~5-6s per call (vehiclesByRoute) that a direct curl from a dev machine did in well under 1s, the
    // real cause behind Trip Detail's "takes 10 seconds to load/update" report (two such calls run
    // sequentially there -- see predictionsByStop's own doc for why they can't run concurrently).
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
     * See [StopPredictionSource]'s own doc for why this is the preferred source for Upcoming
     * Arrivals over [vehiclesByRoute] -- `getpredictions` hands back a real predicted arrival time
     * (`prdtm`) per stop, not just a raw vehicle position, so an ETA computed from it can reflect an
     * actual delay instead of just trusting the static schedule. `stpid` accepts multiple
     * comma-delimited stop ids (verified live); `stst`/`stsd` on each prediction are the same
     * scheduled-start fields `getvehicles` publishes, resolved to a trip_id the identical way.
     *
     * Returns the raw predicted instant only, NOT a diffed status -- `stst`/`stsd` here are the
     * trip's own *first*-stop scheduled time (needed for the trip_id bridge), not the scheduled time
     * at the specific stop being predicted for, so this has no correct basis to compute a delay
     * itself. See [StopPredictionSource]'s own doc for why that distinction matters.
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
     * See [StopPredictionSource.nextStopForVehicle]'s own doc for why this exists (fixes the
     * looping-route failure mode GPS-proximity matching has). `getpredictions?vid=` returns the
     * requested vehicle's entire remaining trip, one entry per upcoming stop, real-world confirmed
     * to already come back correctly ordered (predictions are documented as always ascending by
     * `prdtm`, verified live 2026-08-23 against a real CTA route-22 bus: 40+ stops, each with its
     * own `stpid` and real predicted time) -- only the first entry (the vehicle's immediate next
     * stop) is used here; no trip_id resolution needed, since the caller already knows which trip
     * this vehicle is on.
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

/** `prdtm` is "yyyyMMdd HH:mm" in the agency's own local time (confirmed live against CTA's real
 * feed), no timezone info of its own -- same "must anchor to the agency's zone, not the device's"
 * rule as every other GTFS time value in this codebase. */
private fun parsePredictionTimestamp(raw: String, zoneId: ZoneId): Long? =
    runCatching {
        LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd HH:mm")).atZone(zoneId).toEpochSecond()
    }.getOrNull()

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
    /** The stop this specific prediction is for -- only needed by [RunAssociatedTripSource.nextStopForVehicle],
     * which (unlike [RunAssociatedTripSource.predictionsByStop]) queries by vid rather than stpid, so the
     * response itself is the only place the stop_id comes from. */
    val stpid: String? = null,
    /** "yyyyMMdd HH:mm", the agency's own predicted arrival/departure time -- see
     * [parsePredictionTimestamp]'s own doc. */
    val prdtm: String? = null,
    val stst: Int? = null,
    val stsd: String? = null,
)
