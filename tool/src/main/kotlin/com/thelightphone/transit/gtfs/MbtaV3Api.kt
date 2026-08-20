package com.thelightphone.transit.gtfs

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

private const val MBTA_V3_VEHICLES_URL = "https://pico-transit-proxy.data-32b.workers.dev/mbta/v3/vehicles"

private val mbtaV3Json = Json { ignoreUnknownKeys = true }

/**
 * MBTA's V3 API (https://api-v3.mbta.com) -- one of two current [LiveVehicleSource] implementations
 * (the other is [CtaBusTrackerSource], CTA's Bus Tracker). Commuter rail track assignments aren't
 * in GTFS-RT at all: MBTA's dispatch system doesn't decide
 * (or publish) a trip's track until roughly 10-15 minutes before departure, so most of the time a
 * Vehicle's `stop` relationship still points at its station's generic per-route placeholder
 * platform (e.g. South Station's "NEC-2287", whose own `platform_code` is null) rather than a real
 * numbered track (e.g. "NEC-2287-01", `platform_code` "1") -- confirmed by hand-querying the live
 * API during development. A trip with no assignment yet is the common case, not missing data --
 * [vehiclesByRoute]'s [LiveVehicleInfo.assignedStopId] is simply null for it.
 *
 * The same `/vehicles` request also carries the vehicle's own live position/status -- verified live
 * to be MORE current and (for commuter rail specifically) more reliable than GTFS-RT's separately-
 * published VehiclePositions.pb, so callers use this as commuter rail's PREFERRED position source,
 * falling back to GTFS-RT only when a trip is missing here (fetch failure, or genuinely not
 * reported yet) -- see MapScreen's own MapViewModel for that fallback. Position and status/sequence
 * are always read from the SAME source for a given vehicle, never mixed between GTFS-RT and V3, so
 * "is it arrived" can't disagree with itself across two feeds with different update cadences.
 *
 * Deliberately scoped to commuter rail only -- subway and Silver Line platforms are static/fixed
 * and already fully resolved via GTFS's own parent_station/child stop_id structure (see
 * [groupStationsByParent]); callers only ever pass commuter rail route_ids in here.
 *
 * Filters by route rather than trip or stop -- see [LiveVehicleSource]'s own doc for why (the API
 * doesn't support filtering `/vehicles` by stop at all, verified live). This returns EVERY vehicle
 * on the given routes, letting a caller discover a trip it has no prior schedule snapshot for, not
 * just ones it already knew to ask about.
 *
 * Requests are authenticated -- the worker injects `MBTA_API_KEY` as `api_key` on every
 * `/mbta/v3/vehicles` call (see pico-transit-proxy-worker.js's API_KEY_INJECTIONS), which raises
 * the rate limit to 1000/min (verified live via the API's own x-ratelimit-limit response header),
 * comfortably above this app's one batched call per poll cycle (LIVE_VEHICLE_POLL_INTERVAL_MS,
 * also 10s). Streaming (`Accept: text/event-stream`, which would push updates instead of polling)
 * is available now that a key is registered but not yet implemented here -- still a plain GET poll.
 *
 * JSON:API responses are decoded by hand for just the fields read here -- same "smallest subset
 * that reads the real wire format" approach GtfsRealtime.kt already takes for GTFS-RT's protobuf
 * feeds, rather than pulling in a full JSON:API client.
 */
object MbtaV3VehicleSource : LiveVehicleSource {
    override val coveredLineTypes: Set<LineType> = setOf(LineType.COMMUTER_RAIL)

    override suspend fun vehiclesByRoute(routeIds: Set<String>, repository: GtfsRepository): Map<String, LiveVehicleInfo> {
        if (routeIds.isEmpty()) return emptyMap()
        val client = HttpClient(OkHttp)
        try {
            val url = "$MBTA_V3_VEHICLES_URL" +
                "?filter%5Broute%5D=${routeIds.joinToString(",")}" +
                "&fields%5Bvehicle%5D=latitude,longitude,current_status,current_stop_sequence" +
                "&include=stop" +
                "&fields%5Bstop%5D=platform_code"
            val response = client.get(url)
            if (response.status.value !in 200..299) {
                Log.e("MbtaV3VehicleSource", "Vehicle fetch failed: HTTP ${response.status.value}")
                return emptyMap()
            }
            val document = mbtaV3Json.decodeFromString(MbtaJsonApiDocument.serializer(), response.bodyAsText())

            // Only stops with a real, non-null platform_code count as an actual assignment -- the
            // generic per-route placeholder's own platform_code is always null (see class doc).
            val assignedStopIds = document.included
                .asSequence()
                .filter { it.type == "stop" }
                .filter { it.attributes.stringOrNull("platform_code") != null }
                .mapTo(mutableSetOf()) { it.id }

            return document.data.mapNotNull { vehicle ->
                val tripId = vehicle.relationships.relationshipId("trip") ?: return@mapNotNull null
                val lat = vehicle.attributes.doubleOrNull("latitude") ?: return@mapNotNull null
                val lon = vehicle.attributes.doubleOrNull("longitude") ?: return@mapNotNull null
                val stopId = vehicle.relationships.relationshipId("stop")
                tripId to LiveVehicleInfo(
                    latitude = lat,
                    longitude = lon,
                    currentStatus = vehicle.attributes.stringOrNull("current_status")?.toGtfsRtVehicleStatus(),
                    currentStopSequence = vehicle.attributes.intOrNull("current_stop_sequence"),
                    assignedStopId = stopId?.takeIf { it in assignedStopIds },
                )
            }.toMap()
        } catch (e: Exception) {
            Log.e("MbtaV3VehicleSource", "Vehicle fetch failed", e)
            return emptyMap()
        } finally {
            client.close()
        }
    }
}

/** V3's `current_status` is the same three-value GTFS-RT enum, just spelled out as a string instead
 * of GTFS-RT's small int -- mapped to [GtfsRtVehicleStatus]'s own ints so downstream arrival/status
 * logic (isArrived, etc.) doesn't need a second parallel status representation. */
private fun String.toGtfsRtVehicleStatus(): Int? = when (this) {
    "INCOMING_AT" -> GtfsRtVehicleStatus.INCOMING_AT
    "STOPPED_AT" -> GtfsRtVehicleStatus.STOPPED_AT
    "IN_TRANSIT_TO" -> GtfsRtVehicleStatus.IN_TRANSIT_TO
    else -> null
}

@Serializable
private data class MbtaJsonApiDocument(
    val data: List<MbtaJsonApiResource> = emptyList(),
    val included: List<MbtaJsonApiResource> = emptyList(),
)

@Serializable
private data class MbtaJsonApiResource(
    val id: String,
    val type: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    val relationships: JsonObject = JsonObject(emptyMap()),
)

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

/** Digs out a JSON:API `relationships.<name>.data.id` -- null for a to-one relationship with no
 * linked resource (e.g. `"vehicle": {"data": null}`), same as an absent relationship entirely. */
private fun JsonObject.relationshipId(relationshipName: String): String? {
    val relationship = this[relationshipName] as? JsonObject ?: return null
    val data = relationship["data"] as? JsonObject ?: return null
    return data.stringOrNull("id")
}
