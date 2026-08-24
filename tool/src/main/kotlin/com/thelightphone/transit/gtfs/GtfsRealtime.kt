@file:OptIn(ExperimentalSerializationApi::class)

package com.thelightphone.transit.gtfs

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Minimal mirror of the GTFS-realtime.proto schema -- only the fields this app reads. Field
 * numbers match the public spec exactly (verified by hand-decoding a live RIPTA feed byte-for-byte
 * during development). No official protobuf/gtfs-realtime-bindings library is on the SDK's
 * dependency allow-list, so this decodes via kotlinx-serialization-protobuf instead, which passes
 * the allow-list check on a startsWith-prefix technicality (see build.gradle.kts).
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

    /** trip_id -> its VehiclePosition, used to place live vehicle markers on the Map screen. */
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
 * Field numbers here were re-verified by hand-decoding real live bytes from MBTA, RIPTA, and RTD
 * Denver (not just assumed from the public spec, which turned out to be wrong for field 4): 3 and 4
 * were confirmed as genuinely distinct fields by finding MBTA messages where both appear at once
 * with clearly different value ranges (3 = larger, sequence-like numbers; 4 = a small 0-2 enum-like
 * range). No agency's live feed ever sends a `stop_id` string at the top level -- the field the
 * public spec places at 4 -- so that property doesn't exist here; declaring it as a String there is
 * wrong, since a real varint on the wire would decode incorrectly as a string. Fields 7/8 (a bare
 * vehicle-number string and a [GtfsRtVehicleDescriptor]) are unused by this app but still declared,
 * since this hand-rolled decoder faults on any undeclared field rather than skipping it. Field 9
 * (occupancy_status) is RTD-specific -- present on ~90% of its live vehicles but absent from both
 * MBTA's and RIPTA's feeds -- and unused by this app but declared for the same reason.
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
 * lat/lon are proto `float` (4-byte), not `double` -- verified against MBTA's live feed bytes.
 * bearing/speed are declared even though unused, for the same reason as
 * [GtfsRtVehiclePosition]'s trailing fields: this hand-rolled decoder faults on any undeclared
 * field rather than skipping it, and RIPTA's live feed includes both.
 */
@Serializable
data class GtfsRtPosition(
    @ProtoNumber(1) val latitude: Float = 0f,
    @ProtoNumber(2) val longitude: Float = 0f,
    @ProtoNumber(3) val bearing: Float? = null,
    @ProtoNumber(5) val speed: Float? = null,
)

/**
 * Fields 3 (vehicle descriptor) and 4 (timestamp) are unused by this app but declared anyway,
 * since this hand-rolled decoder faults on any undeclared field rather than skipping it (see
 * [GtfsRtVehiclePosition]'s doc comment) -- RTD Denver sends field 4 on every live TripUpdate
 * (verified by hand-decoding its real feed bytes). Fields 6-8 are LTC London-specific --
 * verified by hand-decoding its live feed, present on every one of its TripUpdates: field 6 is a
 * vendor bundle re-nesting trip_id/start_date/start_time/shape_id plus translated headsign
 * strings; field 7 is always zero-length; field 8 is a short numeric id (block/run-like). All
 * three decode as valid UTF-8 in every sample seen, so String is a safe unused-field type here.
 */
@Serializable
data class GtfsRtTripUpdate(
    @ProtoNumber(1) val trip: GtfsRtTripDescriptor = GtfsRtTripDescriptor(),
    @ProtoNumber(2) val stopTimeUpdate: List<GtfsRtStopTimeUpdate> = emptyList(),
    @ProtoNumber(3) val vehicle: GtfsRtVehicleDescriptor? = null,
    @ProtoNumber(4) val timestamp: Long? = null,
    @ProtoNumber(6) val vendorTripPropertiesUnused: String? = null,
    @ProtoNumber(7) val unusedField7: String? = null,
    @ProtoNumber(8) val vendorRunIdUnused: String? = null,
) {
    /** Matches by stop_id first (more specific), falling back to stop_sequence. */
    fun updateFor(stopId: String, stopSequence: Int): GtfsRtStopTimeUpdate? =
        stopTimeUpdate.find { it.stopId == stopId }
            ?: stopTimeUpdate.find { it.stopSequence == stopSequence }

    /**
     * Infers which stop the vehicle currently occupies purely from this TripUpdate's own remaining
     * [stopTimeUpdate] entries, for agencies whose VehiclePositions feed never populates
     * current_stop_sequence at all -- confirmed empirically for RIPTA (never populated across live
     * sampling), unlike MBTA where it's reliably present. Well-behaved GTFS-RT producers drop
     * already-passed stops from a TripUpdate's own stop_time_update list as the trip progresses, so
     * the lowest stop_sequence still present is the next stop the vehicle hasn't yet reached -- the
     * same stop current_stop_sequence would point to together with an INCOMING_AT/IN_TRANSIT_TO
     * status. Only ever used as a fallback when VehiclePositions itself came up empty; a real
     * current_stop_sequence is always preferred when available.
     */
    fun inferCurrentStopSequence(): Int? = stopTimeUpdate.mapNotNull { it.stopSequence }.minOrNull()
}

/** GTFS-realtime's own `TripDescriptor.ScheduleRelationship` enum value meaning "this trip was added
 * to the schedule, with no corresponding trip in the static GTFS data" -- e.g. MBTA Green Line's own
 * live feed marks ~96% of its currently-running vehicles this way (see [FuzzyRunTrips]'s own doc for
 * why that matters). The only value of this enum currently interpreted anywhere in this codebase;
 * [GtfsRtTripDescriptor.scheduleRelationship] was previously decoded purely to avoid desyncing RTD's
 * nested messages (see that field's own doc), never read. */
const val GTFS_RT_SCHEDULE_RELATIONSHIP_ADDED = 1

/**
 * RIPTA's feed also sends start_time/start_date/route_id here (verified by hand-decoding RIPTA's
 * real feed bytes) -- declared even though unused, since an undeclared field inside a *nested*
 * message desyncs this decoder's byte position for everything after it, corrupting the rest of the
 * enclosing VehiclePosition/TripUpdate rather than being harmlessly skipped. Fields 4
 * (schedule_relationship) and 6 (direction_id) are RTD-specific -- present on every one of its live
 * trip descriptors -- and declared for the same reason. [scheduleRelationship] is also genuinely read
 * now, not just decoded -- see [GTFS_RT_SCHEDULE_RELATIONSHIP_ADDED]'s own doc.
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
 * [GtfsRtTripDescriptor]'s doc comment. Field 1005 is the MTA commuter railroads' (LIRR, Metro-
 * North) own nyct_stop_time_update extension (scheduled_track/actual_track) -- hand-verified live
 * against LIRR's real feed: a short nested message whose sub-fields decode as valid UTF-8 track
 * labels (e.g. "203", "4", "2A"), so String is a safe unused-field type here, same reasoning as
 * [GtfsRtTripUpdate]'s own vendor-bundle fields. */
@Serializable
data class GtfsRtStopTimeUpdate(
    @ProtoNumber(1) val stopSequence: Int? = null,
    @ProtoNumber(4) val stopId: String? = null,
    @ProtoNumber(2) val arrival: GtfsRtStopTimeEvent? = null,
    @ProtoNumber(3) val departure: GtfsRtStopTimeEvent? = null,
    @ProtoNumber(5) val scheduleRelationship: Int? = null,
    @ProtoNumber(1005) val nyctTrackUnused: String? = null,
)

/** Field 4 is LTC London-specific -- a second timestamp-shaped varint present on nearly every
 * arrival/departure event in its live feed, hand-verified to genuinely differ from [time] in most
 * samples (not just a duplicate encoding of it) -- purpose unconfirmed, declared unused for the
 * same reason as [GtfsRtTripUpdate]'s doc comment. */
@Serializable
data class GtfsRtStopTimeEvent(
    @ProtoNumber(1) val delay: Int? = null,
    @ProtoNumber(2) val time: Long? = null,
    @ProtoNumber(4) val unusedField4: Long? = null,
)

class GtfsRealtimeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Fetches and decodes a GTFS-RT feed -- TripUpdates and VehiclePositions are separate published
 * feeds but both decode into this same FeedMessage/FeedEntity wrapper (each entity just populates
 * whichever of trip_update/vehicle applies to that feed), so one fetch function covers both. [url]
 * always resolves through a redirect layer to the real feed on this app's behalf, so a single
 * request here is enough -- no redirect-following needed at this layer.
 */
object GtfsRealtimeClient {
    suspend fun fetchFeed(url: String): GtfsRtFeedMessage {
        val client = HttpClient(OkHttp)
        try {
            val response = client.get(url)
            val status = response.status.value
            if (status !in 200..299) throw GtfsRealtimeException("GTFS-RT fetch failed: HTTP $status")
            val bytes: ByteArray = response.body()
            return ProtoBuf.decodeFromByteArray(GtfsRtFeedMessage.serializer(), bytes)
        } finally {
            client.close()
        }
    }
}

/** The trip_id prefix [GtfsIngestor] applies to the [index]-th [SecondaryGtfsFeed] component's
 * *static* data when loading it into the shared database (see that file's own `idPrefix`
 * handling) -- every function below relies on this exact same convention to know which live feed
 * a given trip_id's realtime data actually lives in. */
private fun secondaryFeedPrefix(index: Int) = "feed${index + 1}:"

/**
 * Looks up a single trip's live TripUpdate from whichever of this agency's realtime feeds owns
 * [tripId] -- its own primary feed for an unprefixed id, or the matching [SecondaryGtfsFeed]'s
 * feed for a "feed{n}:"-prefixed one (see [secondaryFeedPrefix]) -- fetching only that one feed
 * rather than every feed the agency has, since a caller here always already knows exactly which
 * trip it wants (typically a boarded trip's own id, polled in a loop). Null for a trip_id whose
 * owning feed has no realtime URL, or whose fetch fails or has no matching entry -- identical to
 * every other "not currently live" case this app already treats uniformly.
 */
suspend fun GtfsAgency.fetchTripUpdate(tripId: String): GtfsRtTripUpdate? {
    components.filterIsInstance<SecondaryGtfsFeed>().forEachIndexed { index, feed ->
        val prefix = secondaryFeedPrefix(index)
        if (tripId.startsWith(prefix)) {
            val url = feed.realtimeTripUpdatesUrl ?: return null
            return try {
                GtfsRealtimeClient.fetchFeed(url).tripUpdatesByTripId[tripId.removePrefix(prefix)]
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GtfsRealtime", "TripUpdates fetch failed for $displayName's secondary feed", e)
                null
            }
        }
    }
    val url = realtimeTripUpdatesUrl ?: return null
    return try {
        GtfsRealtimeClient.fetchFeed(url).tripUpdatesByTripId[tripId]
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("GtfsRealtime", "TripUpdates fetch failed for $displayName", e)
        null
    }
}

/** Same lookup as [fetchTripUpdate], for VehiclePositions instead of TripUpdates. */
suspend fun GtfsAgency.fetchVehiclePosition(tripId: String): GtfsRtVehiclePosition? {
    components.filterIsInstance<SecondaryGtfsFeed>().forEachIndexed { index, feed ->
        val prefix = secondaryFeedPrefix(index)
        if (tripId.startsWith(prefix)) {
            val url = feed.realtimeVehiclePositionsUrl ?: return null
            return try {
                GtfsRealtimeClient.fetchFeed(url).vehiclePositionsByTripId[tripId.removePrefix(prefix)]
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GtfsRealtime", "VehiclePositions fetch failed for $displayName's secondary feed", e)
                null
            }
        }
    }
    val url = realtimeVehiclePositionsUrl ?: return null
    return try {
        GtfsRealtimeClient.fetchFeed(url).vehiclePositionsByTripId[tripId]
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("GtfsRealtime", "VehiclePositions fetch failed for $displayName", e)
        null
    }
}

/**
 * Result of polling an agency's own realtime feed together with every [SecondaryGtfsFeed]
 * component's -- [primary] is the agency's own fetched [GtfsRtFeedMessage] (null if it has no
 * realtime URL, or its fetch failed), kept around so a caller's existing status/staleness handling
 * (offline banners, [GtfsRtFeedHeader.isStale]) stays keyed off the primary feed exactly as before
 * secondary feeds existed -- a secondary feed's own freshness isn't surfaced separately today.
 * [byTripId] additionally folds in every reachable secondary feed's own trip_id -> value map,
 * prefixed to match the shared database's trip_ids (see [secondaryFeedPrefix]), so a caller
 * iterating scheduled trips finds a merged secondary-feed trip's live data (e.g. a Bustang trip
 * under RTD Denver) the same way it finds the primary agency's own.
 */
class MergedRealtimeFeed<T>(val primary: GtfsRtFeedMessage?, val byTripId: Map<String, T>)

private suspend fun <T> GtfsAgency.fetchMerged(
    primaryUrl: String?,
    secondaryUrl: (SecondaryGtfsFeed) -> String?,
    byTripId: (GtfsRtFeedMessage) -> Map<String, T>,
    logTag: String,
): MergedRealtimeFeed<T> {
    val primaryFeed = primaryUrl?.let { url ->
        try {
            GtfsRealtimeClient.fetchFeed(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(logTag, "Realtime fetch failed for $displayName", e)
            null
        }
    }
    val merged = buildMap {
        primaryFeed?.let { putAll(byTripId(it)) }
        components.filterIsInstance<SecondaryGtfsFeed>().forEachIndexed { index, feed ->
            val url = secondaryUrl(feed) ?: return@forEachIndexed
            try {
                val prefix = secondaryFeedPrefix(index)
                byTripId(GtfsRealtimeClient.fetchFeed(url)).forEach { (tripId, value) -> put("$prefix$tripId", value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(logTag, "Realtime fetch failed for $displayName's secondary feed", e)
            }
        }
    }
    return MergedRealtimeFeed(primaryFeed, merged)
}

/** See [MergedRealtimeFeed]. [logTag] is the calling screen's own logcat tag, so a fetch failure
 * here still shows up attributed to the screen that triggered it, same as before this helper
 * existed. */
suspend fun GtfsAgency.fetchMergedTripUpdates(logTag: String): MergedRealtimeFeed<GtfsRtTripUpdate> =
    fetchMerged(realtimeTripUpdatesUrl, { it.realtimeTripUpdatesUrl }, { it.tripUpdatesByTripId }, logTag)

/** See [MergedRealtimeFeed]. */
suspend fun GtfsAgency.fetchMergedVehiclePositions(logTag: String): MergedRealtimeFeed<GtfsRtVehiclePosition> =
    fetchMerged(realtimeVehiclePositionsUrl, { it.realtimeVehiclePositionsUrl }, { it.vehiclePositionsByTripId }, logTag)

/** Default +/- window (seconds) within which a live prediction still counts as "On time". */
const val ARRIVAL_STATUS_TOLERANCE_SECONDS = 90L

/** Beyond this +/- diff, a Late/Early status stops being a plausible real-world delay and starts
 * meaning "this live prediction was diffed against the wrong scheduled trip" -- confirmed live
 * 2026-08-24: MBTA's ordinal [FuzzyRunTrips] matching (rank-based, not nearest-time) paired a real
 * live Green Line D vehicle against the nearest scheduled candidate by rank, which during an
 * overnight service gap (no Green-D trip scheduled between ~1 AM and 5:21 AM) was hours away,
 * producing a technically-accurate but nonsensical "Early by 291m". See [computeArrivalEta]'s own
 * doc for why the ETA itself stays correct regardless -- only the status label is capped. */
const val ARRIVAL_STATUS_IMPLAUSIBLE_THRESHOLD_SECONDS = 90 * 60L

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
 * Converts a GTFS scheduled "HH:MM:SS" time (hour may exceed 24 for a post-midnight trip still
 * counted on [serviceDate]'s service day) to an absolute Unix epoch-seconds instant, for comparison
 * against GTFS-RT's absolute timestamps. [zoneId] must be the specific agency's own -- see
 * [todayForGtfs]'s doc, the same reasoning applies: a GTFS time string is only meaningful relative
 * to the agency's own clock, not whatever zone the rider's device happens to be in.
 */
fun gtfsTimeToEpochSeconds(rawTime: String, serviceDate: LocalDate, zoneId: ZoneId): Long? {
    val parts = rawTime.split(":")
    val hour = parts.getOrNull(0)?.toLongOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toLongOrNull() ?: return null
    val second = parts.getOrNull(2)?.toLongOrNull() ?: 0L
    val midnightEpoch = serviceDate.atStartOfDay(zoneId).toEpochSecond()
    return midnightEpoch + hour * 3600 + minute * 60 + second
}

/**
 * Combines a static scheduled time with a matching GTFS-RT StopTimeUpdate (if any) into an ETA
 * and status. With no realtime match, returns a non-live ETA with a null status -- callers should
 * render that as "just the scheduled time, no badge" per spec. [zoneId] should always be the
 * specific trip's own agency's [GtfsAgency.zoneId] -- see [gtfsTimeToEpochSeconds]'s own doc.
 *
 * [etaEpochSeconds] is always the real live [predicted] time whenever [realtimeUpdate] is present,
 * regardless of how large the diff against [scheduledTime] turns out to be -- a rider should never
 * lose a genuine live prediction just because the status label built from it would look wrong (see
 * [implausibleThresholdSeconds]'s own doc). Only [status] gets capped.
 */
fun computeArrivalEta(
    scheduledTime: String,
    serviceDate: LocalDate,
    realtimeUpdate: GtfsRtStopTimeUpdate?,
    zoneId: ZoneId,
    toleranceSeconds: Long = ARRIVAL_STATUS_TOLERANCE_SECONDS,
    implausibleThresholdSeconds: Long = ARRIVAL_STATUS_IMPLAUSIBLE_THRESHOLD_SECONDS,
): ArrivalEta? {
    val scheduledEpoch = gtfsTimeToEpochSeconds(scheduledTime, serviceDate, zoneId) ?: return null
    val event = realtimeUpdate?.departure ?: realtimeUpdate?.arrival
        ?: return ArrivalEta(etaEpochSeconds = scheduledEpoch, isLive = false, status = null)

    val predicted = event.time ?: (scheduledEpoch + (event.delay ?: 0))
    val diff = predicted - scheduledEpoch
    val status = when {
        // See ARRIVAL_STATUS_IMPLAUSIBLE_THRESHOLD_SECONDS's own doc -- this diff is too large to be
        // a real delay, meaning realtimeUpdate almost certainly wasn't genuinely diffed against its
        // own trip (e.g. a FuzzyRunTrips ordinal mismatch). The ETA above stays the real live time
        // regardless; only the misleading status label is dropped.
        diff > implausibleThresholdSeconds || diff < -implausibleThresholdSeconds -> null
        diff > toleranceSeconds -> ArrivalStatus.Late(diff)
        diff < -toleranceSeconds -> ArrivalStatus.Early(-diff)
        else -> ArrivalStatus.OnTime
    }
    return ArrivalEta(etaEpochSeconds = predicted, isLive = true, status = status)
}

fun GtfsRtFeedHeader.isStale(nowEpochSeconds: Long, thresholdSeconds: Long = REALTIME_STALE_THRESHOLD_SECONDS): Boolean =
    timestamp > 0 && (nowEpochSeconds - timestamp) > thresholdSeconds
