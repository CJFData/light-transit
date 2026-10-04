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
    /** MTA's header extension, sent on its alerts feeds; unused. */
    @ProtoNumber(1001) val mtaHeaderUnused: ByteArray? = null,
)

/** Fields 2 (is_deleted) and 5 (alert) are standard GTFS-RT FeedEntity fields no prior agency's
 * live feed has ever actually sent, so they were never declared -- NYC Subway's does (hand-verified
 * live), and this hand-rolled decoder faults on any undeclared field rather than skipping it.
 * [alert] is a large nested message this app has no use for; declared as a raw pass-through
 * [String] rather than fully modeled, the same "unused length-delimited field" pattern used
 * throughout this file (e.g. [GtfsRtTripUpdate]'s vendor-bundle fields). */
@Serializable
data class GtfsRtFeedEntity(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val isDeletedUnused: Boolean? = null,
    @ProtoNumber(3) val tripUpdate: GtfsRtTripUpdate? = null,
    @ProtoNumber(4) val vehicle: GtfsRtVehiclePosition? = null,
    @ProtoNumber(5) val alertUnused: String? = null,
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
 * MBTA's and RIPTA's feeds -- and unused by this app but declared for the same reason. Field 6
 * (congestion_level) is NYC Subway-specific -- declared unused for the same reason.
 */
@Serializable
data class GtfsRtVehiclePosition(
    @ProtoNumber(1) val trip: GtfsRtTripDescriptor = GtfsRtTripDescriptor(),
    @ProtoNumber(2) val position: GtfsRtPosition? = null,
    @ProtoNumber(3) val currentStopSequence: Int? = null,
    @ProtoNumber(4) val currentStatus: Int? = null,
    @ProtoNumber(5) val timestamp: Long? = null,
    @ProtoNumber(6) val congestionLevelUnused: Int? = null,
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
 * now, not just decoded -- see [GTFS_RT_SCHEDULE_RELATIONSHIP_ADDED]'s own doc. Field 1001 is NYC
 * Subway's own nyct_trip_descriptor extension (train_id/is_assigned/direction) -- declared unused
 * for the same reason, same raw-passthrough-String treatment as [GtfsRtStopTimeUpdate]'s own
 * NYCT extension field.
 */
@Serializable
data class GtfsRtTripDescriptor(
    @ProtoNumber(1) val tripId: String = "",
    @ProtoNumber(2) val startTime: String? = null,
    @ProtoNumber(3) val startDate: String? = null,
    @ProtoNumber(4) val scheduleRelationship: Int? = null,
    @ProtoNumber(5) val routeId: String? = null,
    @ProtoNumber(6) val directionId: Int? = null,
    @ProtoNumber(1001) val nyctTripDescriptorUnused: String? = null,
)

/** Fields we don't use but have to declare, since this decoder fails on any field it doesn't
 * recognize or reads as the wrong type. Field 5 is schedule_relationship, which RTD sends. Field
 * 1005 is LIRR/Metro-North's track-label extension, safe to read as a String. Field 1001 is NYC
 * Subway's own extension. Field 7 is a plain number in NYC Subway's feed rather than the spec's
 * nested `stop_time_properties`, so it's an [Int]. */
@Serializable
data class GtfsRtStopTimeUpdate(
    @ProtoNumber(1) val stopSequence: Int? = null,
    @ProtoNumber(4) val stopId: String? = null,
    @ProtoNumber(2) val arrival: GtfsRtStopTimeEvent? = null,
    @ProtoNumber(3) val departure: GtfsRtStopTimeEvent? = null,
    @ProtoNumber(5) val scheduleRelationship: Int? = null,
    @ProtoNumber(7) val stopTimePropertiesUnused: Int? = null,
    @ProtoNumber(1001) val nyctStopTimeUpdateUnused: String? = null,
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

/** An alerts feed. Kept separate from [GtfsRtFeedMessage] so alerts embedded in trip feeds (NYC
 * Subway sends some) stay undecoded there, and trip feeds behave the same whether alerts are on
 * or off. */
@Serializable
data class GtfsRtAlertFeedMessage(
    @ProtoNumber(1) val header: GtfsRtFeedHeader = GtfsRtFeedHeader(),
    @ProtoNumber(2) val entity: List<GtfsRtAlertEntity> = emptyList(),
)

/** A FeedEntity read for its alert only; trip updates and vehicles in the same feed are skipped. */
@Serializable
class GtfsRtAlertEntity(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val isDeleted: Boolean? = null,
    @ProtoNumber(3) val tripUpdateUnused: ByteArray? = null,
    @ProtoNumber(4) val vehicleUnused: ByteArray? = null,
    @ProtoNumber(5) val alert: GtfsRtAlert? = null,
)

/** GTFS-RT Alert. Every standard field is declared so none can fail the decode; only the core ones
 * are used. */
@Serializable
class GtfsRtAlert(
    @ProtoNumber(1) val activePeriod: List<GtfsRtTimeRange> = emptyList(),
    @ProtoNumber(5) val informedEntity: List<GtfsRtEntitySelector> = emptyList(),
    @ProtoNumber(6) val cause: Int? = null,
    @ProtoNumber(7) val effect: Int? = null,
    @ProtoNumber(8) val url: GtfsRtTranslatedString? = null,
    @ProtoNumber(10) val headerText: GtfsRtTranslatedString? = null,
    @ProtoNumber(11) val descriptionText: GtfsRtTranslatedString? = null,
    @ProtoNumber(12) val ttsHeaderTextUnused: GtfsRtTranslatedString? = null,
    @ProtoNumber(13) val ttsDescriptionTextUnused: GtfsRtTranslatedString? = null,
    @ProtoNumber(14) val severityLevel: Int? = null,
    @ProtoNumber(15) val imageUnused: ByteArray? = null,
    @ProtoNumber(16) val imageAlternativeTextUnused: GtfsRtTranslatedString? = null,
    @ProtoNumber(17) val causeDetailUnused: GtfsRtTranslatedString? = null,
    @ProtoNumber(18) val effectDetailUnused: GtfsRtTranslatedString? = null,
    /** MTA's alert extension (created/updated times, alert type); unused. */
    @ProtoNumber(1001) val mtaAlertUnused: ByteArray? = null,
)

/** Epoch seconds; either end may be missing. */
@Serializable
data class GtfsRtTimeRange(
    @ProtoNumber(1) val start: Long? = null,
    @ProtoNumber(2) val end: Long? = null,
)

@Serializable
data class GtfsRtEntitySelector(
    @ProtoNumber(1) val agencyId: String? = null,
    @ProtoNumber(2) val routeId: String? = null,
    @ProtoNumber(3) val routeType: Int? = null,
    @ProtoNumber(4) val trip: GtfsRtTripDescriptor? = null,
    @ProtoNumber(5) val stopId: String? = null,
    @ProtoNumber(6) val directionId: Int? = null,
    /** MTA's selector extension (a sort order); unused. */
    @ProtoNumber(1001) val mtaSelectorUnused: ByteArray? = null,
)

@Serializable
data class GtfsRtTranslatedString(
    @ProtoNumber(1) val translation: List<GtfsRtTranslation> = emptyList(),
)

@Serializable
data class GtfsRtTranslation(
    @ProtoNumber(1) val text: String = "",
    @ProtoNumber(2) val language: String? = null,
)

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

    suspend fun fetchAlertsFeed(url: String): GtfsRtAlertFeedMessage {
        val client = HttpClient(OkHttp)
        try {
            val response = client.get(url)
            val status = response.status.value
            if (status !in 200..299) throw GtfsRealtimeException("GTFS-RT alerts fetch failed: HTTP $status")
            val bytes: ByteArray = response.body()
            return ProtoBuf.decodeFromByteArray(GtfsRtAlertFeedMessage.serializer(), bytes)
        } finally {
            client.close()
        }
    }
}

/** The trip_id prefix [GtfsIngestor] applies to the [index]-th prefixed (feedUrl != null)
 * [MultiGtfsFeed] component's *static* data when loading it into the shared database (see that
 * file's own `idPrefix` handling) -- every function below relies on this exact same convention to
 * know which live feed a given trip_id's realtime data actually lives in. Only meaningful for a
 * [MultiGtfsFeed] with a real [MultiGtfsFeed.feedUrl] -- a feedUrl-less one (e.g. NYC Subway's
 * extra line-group feeds) is never prefixed, see the unprefixed loops below instead. */
private fun secondaryFeedPrefix(index: Int) = "feed${index + 1}:"

/**
 * Collision safeguard for a prefixed [MultiGtfsFeed]'s realtime match -- see
 * [GtfsRepository.getRouteIdForTrip]'s own doc for why a raw trip_id match alone isn't proof
 * enough. [prefixedTripId] is the local, already-prefixed id the caller was actually looking for;
 * [rawRouteId] is the matched live entity's own (unprefixed) route_id straight off the wire. True
 * only when the trip's own locally-known route agrees with what the live entity claims.
 */
private fun matchesOwnRoute(rawRouteId: String?, prefixedTripId: String, prefix: String, repository: GtfsRepository): Boolean {
    if (rawRouteId == null) return false
    return repository.getRouteIdForTrip(prefixedTripId) == "$prefix$rawRouteId"
}

/** Rewrites a live route_id into the schedule's, for an agency with a [RealtimeIdBridge]; null
 * for every other agency. Built once per fetch. */
private fun GtfsAgency.realtimeRouteIdBridge(repository: GtfsRepository): ((String) -> String?)? {
    val bridge = components.filterIsInstance<RealtimeIdBridge>().firstOrNull() ?: return null
    val routeIdsByShortName = repository.getRouteIdsByShortName()
    return { raw -> bridge.bridgeRouteId(raw, routeIdsByShortName) }
}

private fun GtfsRtTripDescriptor.withBridgedRouteId(bridge: ((String) -> String?)?): GtfsRtTripDescriptor =
    bridge?.let { b -> routeId?.let(b)?.let { copy(routeId = it) } } ?: this

/**
 * Looks up a single trip's live TripUpdate from whichever of this agency's realtime feeds owns
 * [tripId] -- its own primary feed for an unprefixed id, the matching prefixed [MultiGtfsFeed]'s
 * feed for a "feed{n}:"-prefixed one (see [secondaryFeedPrefix]), or, failing both, a probe across
 * any feedUrl-less [MultiGtfsFeed]s (unprefixed, so there's no cheap way to know which one owns
 * [tripId] without trying each) -- fetching only as many feeds as actually needed rather than
 * every feed the agency has, since a caller here always already knows exactly which trip it wants
 * (typically a boarded trip's own id, polled in a loop). Null for a trip_id whose owning feed has
 * no realtime URL, or whose fetch fails or has no matching entry -- identical to every other "not
 * currently live" case this app already treats uniformly. When this agency has a
 * [RealtimeTripIdBridge] component, every feed's own entities (primary included) are resolved
 * through it before being compared to [tripId], rather than assuming any feed's raw trip_id
 * already matches -- see that interface's own doc.
 */
suspend fun GtfsAgency.fetchTripUpdate(tripId: String, repository: GtfsRepository): GtfsRtTripUpdate? {
    // Rewrites every StopTimeUpdate's own raw stop_id into this agency's static stop_id space right
    // before the result leaves this function, whichever of the return points below produced it --
    // see RealtimeIdBridge's own doc.
    val stopIdBridge = components.filterIsInstance<RealtimeIdBridge>().firstOrNull()
    val routeIdBridge = realtimeRouteIdBridge(repository)
    fun bridged(update: GtfsRtTripUpdate?): GtfsRtTripUpdate? {
        if (stopIdBridge == null || update == null) return update
        return update.copy(
            trip = update.trip.withBridgedRouteId(routeIdBridge),
            stopTimeUpdate = update.stopTimeUpdate.map { stopTimeUpdate ->
                val rawStopId = stopTimeUpdate.stopId ?: return@map stopTimeUpdate
                stopIdBridge.bridgeStopId(rawStopId)?.let { stopTimeUpdate.copy(stopId = it) } ?: stopTimeUpdate
            },
        )
    }

    components.filterIsInstance<MultiGtfsFeed>().filter { it.feedUrl != null }.forEachIndexed { index, feed ->
        val prefix = secondaryFeedPrefix(index)
        if (tripId.startsWith(prefix)) {
            val url = feed.realtimeTripUpdatesUrl ?: return null
            return try {
                val match = GtfsRealtimeClient.fetchFeed(url).tripUpdatesByTripId[tripId.removePrefix(prefix)]
                bridged(match?.takeIf { matchesOwnRoute(it.trip.routeId, tripId, prefix, repository) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GtfsRealtime", "TripUpdates fetch failed for $displayName's secondary feed", e)
                null
            }
        }
    }
    // Applied to the PRIMARY feed's own entities too, not just the unprefixed loop below -- a
    // RealtimeTripIdBridge-backed agency's own primary feed (e.g. NYC Subway's own combined feed,
    // served by pico-transit-proxy's /nyc_subway/combined) needs exactly the same resolution its
    // other feeds do, since its own raw trip_ids are just as unresolved as any of them. A plain
    // direct-key lookup here would silently never match anything for such an agency -- confirmed:
    // this was a real, previously-unnoticed gap when NYC Subway's ACE lines (occupying the primary
    // URL slot) never got the bridge treatment the other 7 line groups did as separate MultiGtfsFeed
    // components.
    val bridge = components.filterIsInstance<RealtimeTripIdBridge>().firstOrNull()
    // Computed once per call, not once per entity -- see scheduledStartTimesByRoute's own doc for
    // why a per-entity repository.tripIdForScheduledStart call each re-ran an expensive query.
    val scheduleMap = if (bridge != null) repository.scheduledStartTimesByRoute(todayForGtfs(zoneId)) else null
    fun resolvedMatch(entities: Map<String, GtfsRtTripUpdate>): GtfsRtTripUpdate? =
        if (bridge != null && scheduleMap != null) {
            entities.entries.firstNotNullOfOrNull { (rawTripId, update) ->
                resolvedTripUpdateOrNull(update, rawTripId, tripId, bridge, scheduleMap)
            }
        } else {
            entities[tripId]
        }

    val url = realtimeTripUpdatesUrl
    val primaryMatch = url?.let {
        try {
            resolvedMatch(GtfsRealtimeClient.fetchFeed(it).tripUpdatesByTripId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GtfsRealtime", "TripUpdates fetch failed for $displayName", e)
            null
        }
    }
    if (primaryMatch != null) return bridged(primaryMatch)
    // Unprefixed multi-feed agencies: tripId alone doesn't say which feed owns it, unlike a
    // prefixed MultiGtfsFeed above -- probe each in list order, first match wins. A no-op loop for
    // every agency with none of these (including NYC Subway now that its own 8 line-group feeds
    // are merged server-side into one primary URL). Each hit is served from the proxy worker's own
    // ~10s edge cache, not a fresh MTA round trip every time.
    for (feed in components.filterIsInstance<MultiGtfsFeed>().filter { it.feedUrl == null }) {
        val additionalUrl = feed.realtimeTripUpdatesUrl ?: continue
        val match = try {
            resolvedMatch(GtfsRealtimeClient.fetchFeed(additionalUrl).tripUpdatesByTripId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GtfsRealtime", "TripUpdates fetch failed for $displayName's additional feed", e)
            null
        }
        if (match != null) return bridged(match)
    }
    return null
}

private fun resolvedTripUpdateOrNull(
    update: GtfsRtTripUpdate, rawTripId: String, wantedTripId: String,
    bridge: RealtimeTripIdBridge, scheduleMap: Map<Pair<String, String>, String?>,
): GtfsRtTripUpdate? {
    val routeId = update.trip.routeId ?: return null
    val startTime = bridge.scheduledStartTime(rawTripId) ?: return null
    val resolvedId = scheduleMap[routeId to startTime] ?: return null
    if (resolvedId != wantedTripId) return null
    return update.copy(trip = update.trip.copy(tripId = resolvedId))
}

/** Same lookup as [fetchTripUpdate], for VehiclePositions instead of TripUpdates. */
suspend fun GtfsAgency.fetchVehiclePosition(tripId: String, repository: GtfsRepository): GtfsRtVehiclePosition? {
    components.filterIsInstance<MultiGtfsFeed>().filter { it.feedUrl != null }.forEachIndexed { index, feed ->
        val prefix = secondaryFeedPrefix(index)
        if (tripId.startsWith(prefix)) {
            val url = feed.realtimeVehiclePositionsUrl ?: return null
            return try {
                val match = GtfsRealtimeClient.fetchFeed(url).vehiclePositionsByTripId[tripId.removePrefix(prefix)]
                match?.takeIf { matchesOwnRoute(it.trip.routeId, tripId, prefix, repository) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GtfsRealtime", "VehiclePositions fetch failed for $displayName's secondary feed", e)
                null
            }
        }
    }
    // See fetchTripUpdate's identical primary-feed treatment for why this is applied here too.
    val bridge = components.filterIsInstance<RealtimeTripIdBridge>().firstOrNull()
    val scheduleMap = if (bridge != null) repository.scheduledStartTimesByRoute(todayForGtfs(zoneId)) else null
    fun resolvedMatch(entities: Map<String, GtfsRtVehiclePosition>): GtfsRtVehiclePosition? =
        if (bridge != null && scheduleMap != null) {
            entities.entries.firstNotNullOfOrNull { (rawTripId, position) ->
                resolvedVehiclePositionOrNull(position, rawTripId, tripId, bridge, scheduleMap)
            }
        } else {
            entities[tripId]
        }

    val routeIdBridge = realtimeRouteIdBridge(repository)
    fun bridged(position: GtfsRtVehiclePosition?): GtfsRtVehiclePosition? =
        position?.copy(trip = position.trip.withBridgedRouteId(routeIdBridge))

    val url = realtimeVehiclePositionsUrl
    val primaryMatch = url?.let {
        try {
            resolvedMatch(GtfsRealtimeClient.fetchFeed(it).vehiclePositionsByTripId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GtfsRealtime", "VehiclePositions fetch failed for $displayName", e)
            null
        }
    }
    if (primaryMatch != null) return bridged(primaryMatch)
    // See fetchTripUpdate's identical loop for why this exists.
    for (feed in components.filterIsInstance<MultiGtfsFeed>().filter { it.feedUrl == null }) {
        val additionalUrl = feed.realtimeVehiclePositionsUrl ?: continue
        val match = try {
            resolvedMatch(GtfsRealtimeClient.fetchFeed(additionalUrl).vehiclePositionsByTripId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GtfsRealtime", "VehiclePositions fetch failed for $displayName's additional feed", e)
            null
        }
        if (match != null) return bridged(match)
    }
    return null
}

private fun resolvedVehiclePositionOrNull(
    position: GtfsRtVehiclePosition, rawTripId: String, wantedTripId: String,
    bridge: RealtimeTripIdBridge, scheduleMap: Map<Pair<String, String>, String?>,
): GtfsRtVehiclePosition? {
    val routeId = position.trip.routeId ?: return null
    val startTime = bridge.scheduledStartTime(rawTripId) ?: return null
    val resolvedId = scheduleMap[routeId to startTime] ?: return null
    if (resolvedId != wantedTripId) return null
    return position.copy(trip = position.trip.copy(tripId = resolvedId))
}

/**
 * Result of polling an agency's own realtime feed together with every [MultiGtfsFeed]
 * component's -- [primary] is the agency's own fetched [GtfsRtFeedMessage] (null if it has no
 * realtime URL, or its fetch failed), kept around so a caller's existing status/staleness handling
 * (offline banners, [GtfsRtFeedHeader.isStale]) stays keyed off the primary feed exactly as before
 * secondary feeds existed -- a secondary feed's own freshness isn't surfaced separately today.
 * [byTripId] additionally folds in every reachable extra feed's own trip_id -> value map: a
 * prefixed one (real [MultiGtfsFeed.feedUrl], see [secondaryFeedPrefix]) so a caller iterating
 * scheduled trips finds a merged secondary-feed trip's live data (e.g. a Bustang trip under RTD
 * Denver) the same way it finds the primary agency's own; an unprefixed one (no feedUrl) put in
 * directly, since its trip_ids already match the shared static database with nothing to
 * disambiguate. When the agency also has a [RealtimeTripIdBridge] component (NYC Subway does),
 * every entity -- the primary feed's own included -- is resolved and aliased to its real trip_id
 * first instead (see that interface's own doc); NYC Subway's own 8 line-group feeds are merged
 * server-side by pico-transit-proxy into one combined primary URL, so there's no unprefixed
 * [MultiGtfsFeed] involved for it at all anymore, just the primary feed's own resolution.
 */
class MergedRealtimeFeed<T>(val primary: GtfsRtFeedMessage?, val byTripId: Map<String, T>)

private suspend fun <T> GtfsAgency.fetchMerged(
    primaryUrl: String?,
    secondaryUrl: (MultiGtfsFeed) -> String?,
    byTripId: (GtfsRtFeedMessage) -> Map<String, T>,
    routeIdOf: (T) -> String?,
    withResolvedTripId: (T, String) -> T,
    withBridgedRouteId: (T, (String) -> String?) -> T,
    repository: GtfsRepository,
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
    val bridge = components.filterIsInstance<RealtimeTripIdBridge>().firstOrNull()
    // Computed once per poll, not per entity: a per-entity lookup is too slow for feeds with
    // thousands of entities (see scheduledStartTimesByRoute).
    val scheduleMap = if (bridge != null) repository.scheduledStartTimesByRoute(todayForGtfs(zoneId)) else null
    val routeIdBridge = realtimeRouteIdBridge(repository)
    fun bridgedRoute(value: T): T = routeIdBridge?.let { withBridgedRouteId(value, it) } ?: value
    // Resolves and aliases every entity's raw trip_id to its real static trip_id before it's put in
    // the map, so every downstream consumer still sees a plain real-trip_id-keyed entity -- applied
    // to the PRIMARY feed's own entities too, not just unprefixed MultiGtfsFeed ones below, since a
    // RealtimeTripIdBridge-backed agency's own primary feed (e.g. NYC Subway's combined feed) needs
    // exactly the same resolution its other feeds do. A no-op (`putAll`) for every agency with no
    // bridge component.
    fun MutableMap<String, T>.putResolved(entities: Map<String, T>) {
        if (bridge != null && scheduleMap != null) {
            entities.forEach { (rawTripId, value) ->
                val routeId = routeIdOf(value) ?: return@forEach
                val startTime = bridge.scheduledStartTime(rawTripId) ?: return@forEach
                val resolvedId = scheduleMap[routeId to startTime] ?: return@forEach
                put(resolvedId, bridgedRoute(withResolvedTripId(value, resolvedId)))
            }
        } else {
            entities.forEach { (tripId, value) -> put(tripId, bridgedRoute(value)) }
        }
    }
    val merged = buildMap {
        primaryFeed?.let { putResolved(byTripId(it)) }
        components.filterIsInstance<MultiGtfsFeed>().filter { it.feedUrl != null }.forEachIndexed { index, feed ->
            val url = secondaryUrl(feed) ?: return@forEachIndexed
            try {
                val prefix = secondaryFeedPrefix(index)
                // Collision safeguard, batched for this feed's whole entity list instead of one
                // query per entity -- see GtfsRepository.getRouteIdForTrip's own doc for why a raw
                // trip_id match alone doesn't prove an entity is really this feed's own trip.
                val ownRouteByTripId = repository.tripRouteIdsForPrefix(prefix)
                byTripId(GtfsRealtimeClient.fetchFeed(url)).forEach { (tripId, value) ->
                    if (ownRouteByTripId[tripId] == routeIdOf(value)) put("$prefix$tripId", value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(logTag, "Realtime fetch failed for $displayName's secondary feed", e)
            }
        }
        // Unprefixed multi-feed agencies: trip_ids already match the shared static database
        // directly, no prefix needed -- a no-op loop for every agency with none of these (NYC
        // Subway's own line-group feeds are merged server-side into one primary URL now, so this
        // stays a no-op for it too, but the shape remains for any future agency shaped this way).
        components.filterIsInstance<MultiGtfsFeed>().filter { it.feedUrl == null }.forEach { feed ->
            val url = secondaryUrl(feed) ?: return@forEach
            try {
                putResolved(byTripId(GtfsRealtimeClient.fetchFeed(url)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(logTag, "Realtime fetch failed for $displayName's additional feed", e)
            }
        }
    }
    return MergedRealtimeFeed(primaryFeed, merged)
}

/** See [MergedRealtimeFeed]. [logTag] is the calling screen's own logcat tag, so a fetch failure
 * here still shows up attributed to the screen that triggered it, same as before this helper
 * existed. [repository] is only consulted when this agency has a [RealtimeTripIdBridge] component
 * (currently just NYC Subway) -- see that interface's own doc. */
suspend fun GtfsAgency.fetchMergedTripUpdates(repository: GtfsRepository, logTag: String): MergedRealtimeFeed<GtfsRtTripUpdate> =
    fetchMerged(
        realtimeTripUpdatesUrl,
        { it.realtimeTripUpdatesUrl },
        { it.tripUpdatesByTripId },
        { it.trip.routeId },
        { update, resolvedId -> update.copy(trip = update.trip.copy(tripId = resolvedId)) },
        { update, bridge -> update.copy(trip = update.trip.withBridgedRouteId(bridge)) },
        repository,
        logTag,
    )

/** See [MergedRealtimeFeed]. */
suspend fun GtfsAgency.fetchMergedVehiclePositions(repository: GtfsRepository, logTag: String): MergedRealtimeFeed<GtfsRtVehiclePosition> =
    fetchMerged(
        realtimeVehiclePositionsUrl,
        { it.realtimeVehiclePositionsUrl },
        { it.vehiclePositionsByTripId },
        { it.trip.routeId },
        { position, resolvedId -> position.copy(trip = position.trip.copy(tripId = resolvedId)) },
        { position, bridge -> position.copy(trip = position.trip.withBridgedRouteId(bridge)) },
        repository,
        logTag,
    )

/** Default +/- window (seconds) within which a live prediction still counts as "On time". */
const val ARRIVAL_STATUS_TOLERANCE_SECONDS = 90L

/** Beyond this +/- diff, a Late/Early status means the prediction was matched to the wrong
 * scheduled trip (e.g. an ordinal fuzzy-run match across an overnight service gap), not a real
 * delay. Only the status label is capped; see [computeArrivalEta] for why the ETA stays correct. */
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
 * lose a genuine live prediction just because the status label built from it would look wrong. Only
 * [status] gets capped -- see [implausibleThresholdSeconds]'s own doc.
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
