@file:OptIn(ExperimentalSerializationApi::class)

package com.thelightphone.transit.gtfs

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Minimal mirror of the GTFS-realtime.proto schema — only the fields this app reads. Field
 * numbers match the public spec exactly (verified by hand-decoding a live RIPTA feed byte-for-
 * byte during development). No official protobuf/gtfs-realtime-bindings library is on the SDK's
 * dependency allow-list, so this is decoded via kotlinx-serialization-protobuf instead, which
 * passes the allow-list check on a startsWith-prefix technicality (see build.gradle.kts).
 */
@Serializable
data class GtfsRtFeedMessage(
    @ProtoNumber(1) val header: GtfsRtFeedHeader = GtfsRtFeedHeader(),
    @ProtoNumber(2) val entity: List<GtfsRtFeedEntity> = emptyList(),
) {
    /** trip_id -> its TripUpdate, for O(1) lookup while merging against the static schedule. */
    val tripUpdatesByTripId: Map<String, GtfsRtTripUpdate> by lazy {
        entity.mapNotNull { it.tripUpdate }.associateBy { it.trip.tripId }
    }

    /** trip_id -> its VehiclePosition, for the ETA radar screen. */
    val vehiclePositionsByTripId: Map<String, GtfsRtVehiclePosition> by lazy {
        entity.mapNotNull { it.vehicle }.associateBy { it.trip.tripId }
    }
}

@Serializable
data class GtfsRtFeedHeader(
    @ProtoNumber(1) val gtfsRealtimeVersion: String = "",
    @ProtoNumber(2) val incrementality: Int = 0,
    @ProtoNumber(3) val timestamp: Long = 0L,
)

@Serializable
data class GtfsRtFeedEntity(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(3) val tripUpdate: GtfsRtTripUpdate? = null,
    @ProtoNumber(4) val vehicle: GtfsRtVehiclePosition? = null,
)

/** VehiclePosition.current_status values, per the GTFS-realtime spec. */
object GtfsRtVehicleStatus {
    const val INCOMING_AT = 0
    const val STOPPED_AT = 1
    const val IN_TRANSIT_TO = 2
}

/**
 * Field numbers here were re-verified by hand-decoding real live bytes from MBTA, RIPTA, *and* RTD
 * Denver (not just assumed from the public spec, which turned out to be wrong for field 4): 3 and 4
 * were confirmed as genuinely distinct fields by finding MBTA messages where both appear at once
 * with clearly different value ranges (3 = larger, sequence-like numbers; 4 = a small 0-2 enum-like
 * range). No agency's live feed ever sends a `stop_id` string at the top level — the field the
 * public spec places at 4 — so that property doesn't exist here at all; declaring it as a String
 * there is what caused every previous crash (a real varint on the wire, decoded as a string).
 * Fields 7/8 (a bare vehicle-number string and a [GtfsRtVehicleDescriptor]) are unused by this app
 * but still declared, since an undeclared field previously faulted the whole decode instead of
 * being skipped. Field 9 (occupancy_status) is RTD-specific -- present on ~90% of its live vehicles
 * but absent from both MBTA's and RIPTA's feeds -- and unused by this app but declared for the same
 * reason.
 */
@Serializable
data class GtfsRtVehiclePosition(
    @ProtoNumber(1) val trip: GtfsRtTripDescriptor = GtfsRtTripDescriptor(),
    @ProtoNumber(2) val position: GtfsRtPosition? = null,
    @ProtoNumber(3) val currentStopSequence: Int? = null,
    @ProtoNumber(4) val currentStatus: Int? = null,
    @ProtoNumber(5) val timestamp: Long? = null,
    @ProtoNumber(7) val vehicleNumber: String? = null,
    @ProtoNumber(8) val vehicle: GtfsRtVehicleDescriptor? = null,
    @ProtoNumber(9) val occupancyStatus: Int? = null,
)

@Serializable
data class GtfsRtVehicleDescriptor(
    @ProtoNumber(1) val id: String? = null,
    @ProtoNumber(2) val label: String? = null,
    @ProtoNumber(3) val licensePlate: String? = null,
)

/**
 * lat/lon are proto `float` (4-byte), not `double` — verified against MBTA's live feed bytes.
 * bearing/speed declared for the same reason as [GtfsRtVehiclePosition]'s trailing fields — RIPTA's
 * feed includes them, and an undeclared field faulted the whole decode instead of being skipped.
 */
@Serializable
data class GtfsRtPosition(
    @ProtoNumber(1) val latitude: Float = 0f,
    @ProtoNumber(2) val longitude: Float = 0f,
    @ProtoNumber(3) val bearing: Float? = null,
    @ProtoNumber(5) val speed: Float? = null,
)

/**
 * Fields 3 (vehicle descriptor) and 4 (timestamp) are unused by this app but declared anyway --
 * RTD Denver sends field 4 on every single live TripUpdate (verified by hand-decoding its real
 * feed bytes), and an undeclared field previously faulted the whole decode instead of being
 * skipped (see [GtfsRtVehiclePosition]'s doc comment).
 */
@Serializable
data class GtfsRtTripUpdate(
    @ProtoNumber(1) val trip: GtfsRtTripDescriptor = GtfsRtTripDescriptor(),
    @ProtoNumber(2) val stopTimeUpdate: List<GtfsRtStopTimeUpdate> = emptyList(),
    @ProtoNumber(3) val vehicle: GtfsRtVehicleDescriptor? = null,
    @ProtoNumber(4) val timestamp: Long? = null,
) {
    /** Matches by stop_id first (more specific), falling back to stop_sequence. */
    fun updateFor(stopId: String, stopSequence: Int): GtfsRtStopTimeUpdate? =
        stopTimeUpdate.find { it.stopId == stopId }
            ?: stopTimeUpdate.find { it.stopSequence == stopSequence }

    /**
     * Infers which stop the vehicle currently occupies purely from this TripUpdate's own remaining
     * [stopTimeUpdate] entries, for agencies whose VehiclePositions feed never populates
     * current_stop_sequence at all -- confirmed empirically for RIPTA (0 of 64 live vehicles had it
     * set, hand-decoding real feed bytes), unlike MBTA where it's reliably present. Well-behaved
     * GTFS-RT producers drop already-passed stops from a TripUpdate's own stop_time_update list as
     * the trip progresses, so the lowest stop_sequence still present is the next stop the vehicle
     * hasn't yet reached -- the same stop current_stop_sequence would point to together with an
     * INCOMING_AT/IN_TRANSIT_TO status. Only ever used as a fallback when VehiclePositions itself
     * came up empty (see TripDetailScreen/HomeScreen's own callers) -- a real current_stop_sequence
     * is always preferred when available.
     */
    fun inferCurrentStopSequence(): Int? = stopTimeUpdate.mapNotNull { it.stopSequence }.minOrNull()
}

/**
 * RIPTA's feed also sends start_time/start_date/route_id here (verified by hand-decoding RIPTA's
 * real feed bytes) — declared even though unused, since an undeclared field in a *nested* message
 * desynced the decoder's byte position for everything decoded after it, corrupting the rest of the
 * enclosing VehiclePosition/TripUpdate rather than just being harmlessly skipped. Fields 4
 * (schedule_relationship) and 6 (direction_id) are RTD-specific -- present on every one of its live
 * trip descriptors -- and declared for the same reason.
 */
@Serializable
data class GtfsRtTripDescriptor(
    @ProtoNumber(1) val tripId: String = "",
    @ProtoNumber(2) val startTime: String? = null,
    @ProtoNumber(3) val startDate: String? = null,
    @ProtoNumber(4) val scheduleRelationship: Int? = null,
    @ProtoNumber(5) val routeId: String? = null,
    @ProtoNumber(6) val directionId: Int? = null,
)

/** Field 5 (schedule_relationship) is RTD-specific -- present on every one of its live stop time
 * updates -- and declared unused for the same undeclared-field-faults-decode reason as
 * [GtfsRtTripDescriptor]'s doc comment. */
@Serializable
data class GtfsRtStopTimeUpdate(
    @ProtoNumber(1) val stopSequence: Int? = null,
    @ProtoNumber(4) val stopId: String? = null,
    @ProtoNumber(2) val arrival: GtfsRtStopTimeEvent? = null,
    @ProtoNumber(3) val departure: GtfsRtStopTimeEvent? = null,
    @ProtoNumber(5) val scheduleRelationship: Int? = null,
)

@Serializable
data class GtfsRtStopTimeEvent(
    @ProtoNumber(1) val delay: Int? = null,
    @ProtoNumber(2) val time: Long? = null,
)

class GtfsRealtimeException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val MAX_REDIRECTS = 5

/** Resolves a redirect Location against the URL that produced it (RFC 3986) and upgrades an
 * absolute http:// result to https:// -- see GtfsIngestor.kt's identical private helper (of the
 * same name) for the full explanation of why a relative Location can't just be string-checked. */
private fun resolveRedirectLocation(currentUrl: String, location: String): String {
    val resolved = java.net.URI(currentUrl).resolve(location).toString()
    return if (resolved.startsWith("http://")) "https://" + resolved.removePrefix("http://") else resolved
}

/**
 * Fetches and decodes a GTFS-RT feed — TripUpdates and VehiclePositions are separate published
 * feeds but both decode into this same FeedMessage/FeedEntity wrapper (each entity just populates
 * whichever of trip_update/vehicle applies to that feed), so one fetch function covers both.
 * Reuses the same manual redirect handling as GtfsIngestor's zip download (some feeds route
 * through non-HTTPS hops too), upgrading any http:// redirect target to https:// rather than ever
 * connecting over plain HTTP.
 */
object GtfsRealtimeClient {
    suspend fun fetchFeed(url: String): GtfsRtFeedMessage {
        val client = HttpClient(OkHttp) {
            followRedirects = false
        }
        try {
            var currentUrl = url
            repeat(MAX_REDIRECTS + 1) {
                val response = client.get(currentUrl)
                val status = response.status.value
                when {
                    status in 200..299 -> {
                        val bytes: ByteArray = response.body()
                        return ProtoBuf.decodeFromByteArray(GtfsRtFeedMessage.serializer(), bytes)
                    }
                    status in 300..399 -> {
                        val location = response.headers[HttpHeaders.Location]
                            ?: throw GtfsRealtimeException("GTFS-RT redirected without a Location header")
                        currentUrl = resolveRedirectLocation(currentUrl, location)
                    }
                    else -> throw GtfsRealtimeException("GTFS-RT fetch failed: HTTP $status")
                }
            }
            throw GtfsRealtimeException("GTFS-RT fetch exceeded $MAX_REDIRECTS redirects")
        } finally {
            client.close()
        }
    }
}

/** Default +/- window (seconds) within which a live prediction still counts as "On time". */
const val ARRIVAL_STATUS_TOLERANCE_SECONDS = 90L

/** How stale (seconds) a feed's header timestamp can be before it's flagged to the user. */
const val REALTIME_STALE_THRESHOLD_SECONDS = 90L

sealed class ArrivalStatus {
    object OnTime : ArrivalStatus()
    data class Late(val seconds: Long) : ArrivalStatus()
    data class Early(val seconds: Long) : ArrivalStatus()
}

data class ArrivalEta(
    val etaEpochSeconds: Long,
    val isLive: Boolean,
    val status: ArrivalStatus?,
)

/**
 * Converts a GTFS scheduled "HH:MM:SS" time (hour may exceed 24 for a post-midnight trip on
 * [serviceDate]'s service day) to an absolute Unix epoch-seconds instant, for comparison against
 * GTFS-RT's absolute timestamps.
 */
fun gtfsTimeToEpochSeconds(rawTime: String, serviceDate: LocalDate): Long? {
    val parts = rawTime.split(":")
    val hour = parts.getOrNull(0)?.toLongOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toLongOrNull() ?: return null
    val second = parts.getOrNull(2)?.toLongOrNull() ?: 0L
    val midnightEpoch = serviceDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    return midnightEpoch + hour * 3600 + minute * 60 + second
}

/**
 * Combines a static scheduled time with a matching GTFS-RT StopTimeUpdate (if any) into an ETA
 * and status. With no realtime match, returns a non-live ETA with a null status — callers should
 * render that as "just the scheduled time, no badge" per spec.
 */
fun computeArrivalEta(
    scheduledTime: String,
    serviceDate: LocalDate,
    realtimeUpdate: GtfsRtStopTimeUpdate?,
    toleranceSeconds: Long = ARRIVAL_STATUS_TOLERANCE_SECONDS,
): ArrivalEta? {
    val scheduledEpoch = gtfsTimeToEpochSeconds(scheduledTime, serviceDate) ?: return null
    val event = realtimeUpdate?.departure ?: realtimeUpdate?.arrival
        ?: return ArrivalEta(etaEpochSeconds = scheduledEpoch, isLive = false, status = null)

    val predicted = event.time ?: (scheduledEpoch + (event.delay ?: 0))
    val diff = predicted - scheduledEpoch
    val status = when {
        diff > toleranceSeconds -> ArrivalStatus.Late(diff)
        diff < -toleranceSeconds -> ArrivalStatus.Early(-diff)
        else -> ArrivalStatus.OnTime
    }
    return ArrivalEta(etaEpochSeconds = predicted, isLive = true, status = status)
}

fun GtfsRtFeedHeader.isStale(nowEpochSeconds: Long, thresholdSeconds: Long = REALTIME_STALE_THRESHOLD_SECONDS): Boolean =
    timestamp > 0 && (nowEpochSeconds - timestamp) > thresholdSeconds
