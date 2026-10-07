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
 * MBTA's V3 API, used for commuter rail track assignments and positions. Until a track is assigned,
 * a vehicle's stop is the station's generic platform (null platform_code) and
 * [LiveVehicleInfo.assignedStopId] is null; that's normal.
 *
 * Position and status come from the same `/vehicles` response, so they can't disagree. Requests
 * filter by route, since `/vehicles` can't filter by stop. The proxy adds the API key. JSON:API
 * fields are decoded by hand.
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

            // Only a stop with a platform_code is a real assignment.
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

/** Maps the spelled-out `current_status` to [GtfsRtVehicleStatus]'s ints. */
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

/** A JSON:API `relationships.<name>.data.id`, or null when unlinked or absent. */
private fun JsonObject.relationshipId(relationshipName: String): String? {
    val relationship = this[relationshipName] as? JsonObject ?: return null
    val data = relationship["data"] as? JsonObject ?: return null
    return data.stringOrNull("id")
}
