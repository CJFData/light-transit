package com.thelightphone.transit.gtfs

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

private const val MBTA_V3_VEHICLES_URL = "https://gtfs.picotransit.com/mbta/v3/vehicles"

private val mbtaV3Json = Json { ignoreUnknownKeys = true }

/**
 * MBTA's V3 API (https://api-v3.mbta.com), used for commuter rail. Track assignments aren't in
 * GTFS-RT: MBTA assigns a track only shortly before departure, so until then a Vehicle's `stop`
 * points at the station's generic placeholder platform (whose `platform_code` is null) and
 * [LiveVehicleInfo.assignedStopId] is null. That's the common case, not missing data.
 *
 * The same `/vehicles` response also gives each train's position and status, which we prefer over
 * GTFS-RT VehiclePositions for commuter rail. Both always come from the same source for a given
 * vehicle, so they can't disagree. Subway and Silver Line platforms are already fixed in GTFS, so
 * this is only used for commuter rail. Requests filter by route, since `/vehicles` can't filter by
 * stop.
 *
 * The worker injects `MBTA_API_KEY` on every call for a higher rate limit. JSON:API responses are
 * decoded by hand for just the fields used here, like GtfsRealtime.kt does for protobuf.
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
        } catch (e: CancellationException) {
            throw e
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
