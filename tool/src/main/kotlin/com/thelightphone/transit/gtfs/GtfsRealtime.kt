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
 * The parts of the GTFS-realtime schema this app reads, decoded with
 * kotlinx-serialization-protobuf. The decoder fails on any field it doesn't know, so fields sent
 * by real feeds are declared even when unused.
 */
@Serializable
data class GtfsRtFeedMessage(
    @ProtoNumber(1) val header: GtfsRtFeedHeader = GtfsRtFeedHeader(),
    @ProtoNumber(2) val entity: List<GtfsRtFeedEntity> = emptyList(),
) {
    val tripUpdatesByTripId: Map<String, GtfsRtTripUpdate> by lazy {
        entity.mapNotNull { it.tripUpdate }.associateBy { it.trip.tripId }
    }

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

/**
 * Fields 2 and 5 are declared so feeds that send them decode; the alert is kept as an unused raw
 * value.
 */
@Serializable
data class GtfsRtFeedEntity(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val isDeletedUnused: Boolean? = null,
    @ProtoNumber(3) val tripUpdate: GtfsRtTripUpdate? = null,
    @ProtoNumber(4) val vehicle: GtfsRtVehiclePosition? = null,
    @ProtoNumber(5) val alertUnused: String? = null,
)

object GtfsRtVehicleStatus {
    const val INCOMING_AT = 0
    const val STOPPED_AT = 1
    const val IN_TRANSIT_TO = 2
}

/**
 * Field 4 is a small status value (not the spec's stop_id string), separate from field 3. Fields
 * 6-9 are unused but declared because some feeds send them.
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
 * lat/lon are 4-byte floats. bearing/speed are unused but declared because some feeds send them.
 */
@Serializable
data class GtfsRtPosition(
    @ProtoNumber(1) val latitude: Float = 0f,
    @ProtoNumber(2) val longitude: Float = 0f,
    @ProtoNumber(3) val bearing: Float? = null,
    @ProtoNumber(5) val speed: Float? = null,
)

/** Fields 3, 4, and 6-8 are unused but declared because some feeds send them. */
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
    fun updateFor(stopId: String, stopSequence: Int): GtfsRtStopTimeUpdate? =
        stopTimeUpdate.find { it.stopId == stopId }
            ?: stopTimeUpdate.find { it.stopSequence == stopSequence }

    /**
     * The next stop the vehicle hasn't reached, inferred from the lowest stop_sequence still in
     * this update. A fallback for feeds that don't send current_stop_sequence in vehicle positions.
     */
    fun inferCurrentStopSequence(): Int? = stopTimeUpdate.mapNotNull { it.stopSequence }.minOrNull()
}

/** TripDescriptor schedule_relationship ADDED: a trip that isn't in the static schedule. */
const val GTFS_RT_SCHEDULE_RELATIONSHIP_ADDED = 1

/**
 * Fields 2-4, 6, and 1001 are declared because some feeds send them; an undeclared field in a
 * nested message would corrupt the rest of the decode.
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

/** Fields 5, 7, 1001, and 1005 are unused but declared because some feeds send them. */
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

/** Field 4 is unused but declared because some feeds send it. */
@Serializable
data class GtfsRtStopTimeEvent(
    @ProtoNumber(1) val delay: Int? = null,
    @ProtoNumber(2) val time: Long? = null,
    @ProtoNumber(4) val unusedField4: Long? = null,
)

class GtfsRealtimeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * An alerts feed, kept separate from [GtfsRtFeedMessage] so alerts embedded in trip feeds stay
 * undecoded there.
 */
@Serializable
data class GtfsRtAlertFeedMessage(
    @ProtoNumber(1) val header: GtfsRtFeedHeader = GtfsRtFeedHeader(),
    @ProtoNumber(2) val entity: List<GtfsRtAlertEntity> = emptyList(),
)

/** A feed entity read for its alert only. */
@Serializable
class GtfsRtAlertEntity(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val isDeleted: Boolean? = null,
    @ProtoNumber(3) val tripUpdateUnused: ByteArray? = null,
    @ProtoNumber(4) val vehicleUnused: ByteArray? = null,
    @ProtoNumber(5) val alert: GtfsRtAlert? = null,
)

/** Every standard alert field is declared so none can fail the decode. */
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
    /** An alert extension (an id, a flag, and an update time); unused. */
    @ProtoNumber(9000) val alertExtensionUnused: ByteArray? = null,
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
 * Fetches and decodes a GTFS-RT feed. Trip updates and vehicle positions share the same message
 * shape.
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

/**
 * The trip_id prefix given to the [index]-th prefixed [MultiGtfsFeed]'s data when it's merged into
 * the agency's database.
 */
private fun secondaryFeedPrefix(index: Int) = "feed${index + 1}:"

/**
 * True when the live entity's route matches the prefixed trip's own route, so a raw trip_id that
 * happens to collide isn't mistaken for this feed's trip.
 */
private fun matchesOwnRoute(rawRouteId: String?, prefixedTripId: String, prefix: String, repository: GtfsRepository): Boolean {
    if (rawRouteId == null) return false
    return repository.getRouteIdForTrip(prefixedTripId) == "$prefix$rawRouteId"
}

/**
 * Rewrites a live route_id into the schedule's for an agency with a [RealtimeIdBridge]; null
 * otherwise.
 */
private fun GtfsAgency.realtimeRouteIdBridge(repository: GtfsRepository): ((String) -> String?)? {
    val bridge = components.filterIsInstance<RealtimeIdBridge>().firstOrNull() ?: return null
    val routeIdsByShortName = repository.getRouteIdsByShortName()
    return { raw -> bridge.bridgeRouteId(raw, routeIdsByShortName) }
}

private fun GtfsRtTripDescriptor.withBridgedRouteId(bridge: ((String) -> String?)?): GtfsRtTripDescriptor =
    bridge?.let { b -> routeId?.let(b)?.let { copy(routeId = it) } } ?: this

/**
 * One trip's live trip update, fetched only from the feed that owns [tripId]: the primary feed, a
 * prefixed [MultiGtfsFeed] for a prefixed id, or each unprefixed one in turn. Null when the trip
 * isn't live.
 */
suspend fun GtfsAgency.fetchTripUpdate(tripId: String, repository: GtfsRepository): GtfsRtTripUpdate? {
    // Bridged ids apply to every result returned below.
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
    // The primary feed's trip_ids need resolving too.
    val bridge = components.filterIsInstance<RealtimeTripIdBridge>().firstOrNull()
    // Built once per call; looking it up per entity is too slow.
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
    // Unprefixed extra feeds don't say which one owns a trip, so each is tried in order.
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

/** Same lookup as [fetchTripUpdate], for vehicle positions. */
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
 * An agency's primary realtime feed plus every extra feed, merged by trip_id. [primary] stays
 * separate for feed-level checks like staleness. Prefixed feeds' trips keep their prefix, and
 * bridged trip_ids are resolved first.
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
    // Built once per poll; looking it up per entity is too slow.
    val scheduleMap = if (bridge != null) repository.scheduledStartTimesByRoute(todayForGtfs(zoneId)) else null
    val routeIdBridge = realtimeRouteIdBridge(repository)
    fun bridgedRoute(value: T): T = routeIdBridge?.let { withBridgedRouteId(value, it) } ?: value
    // Resolves each raw trip_id to its scheduled trip before adding it.
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
                // Keeps only entities whose route matches the prefixed trip's own, checked in one
                // query per feed.
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
        // Unprefixed extra feeds already match the schedule's trip_ids.
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

/** See [MergedRealtimeFeed]. Fetch failures are logged under [logTag]. */
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

/** How far, in seconds, a prediction can be from schedule and still count as on time. */
const val ARRIVAL_STATUS_TOLERANCE_SECONDS = 90L

/**
 * Beyond this, a late/early status means the prediction was matched to the wrong trip, so the
 * status is dropped. The ETA itself is kept.
 */
const val ARRIVAL_STATUS_IMPLAUSIBLE_THRESHOLD_SECONDS = 90 * 60L

/** How old, in seconds, a feed's timestamp can be before it's flagged as stale. */
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
 * Converts a GTFS "HH:MM:SS" time (hours can exceed 24) on [serviceDate] to epoch seconds in the
 * agency's [zoneId].
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
 * Combines a scheduled time with a matching live update into an ETA and status. Without a live
 * update, the ETA is the scheduled time with no status. A live prediction is always kept; only an
 * implausible status is dropped.
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
        // Too far off to be a real delay, so drop the status but keep the live ETA.
        diff > implausibleThresholdSeconds || diff < -implausibleThresholdSeconds -> null
        diff > toleranceSeconds -> ArrivalStatus.Late(diff)
        diff < -toleranceSeconds -> ArrivalStatus.Early(-diff)
        else -> ArrivalStatus.OnTime
    }
    return ArrivalEta(etaEpochSeconds = predicted, isLive = true, status = status)
}

fun GtfsRtFeedHeader.isStale(nowEpochSeconds: Long, thresholdSeconds: Long = REALTIME_STALE_THRESHOLD_SECONDS): Boolean =
    timestamp > 0 && (nowEpochSeconds - timestamp) > thresholdSeconds
