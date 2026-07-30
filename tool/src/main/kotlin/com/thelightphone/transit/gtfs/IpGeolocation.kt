package com.thelightphone.transit.gtfs

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val IP_GEOLOCATION_URL = "https://ipwho.is/"

@Serializable
private data class IpWhoIsResponse(
    val success: Boolean = false,
    val city: String? = null,
    val region: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

class IpGeolocationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Approximates the device's location from its public IP address — no location permission or GPS
 * access needed, since it's a plain network request, unlike device GPS which tool code can't
 * reach at all (see GtfsRepository/GtfsAgency notes on that). Accuracy is typically city/metro
 * level on a mobile carrier network, not street-level, so this is meant to pre-fill a starting
 * point for the user to confirm or edit, not to stand in for a real address.
 */
class IpGeolocator {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun locate(): GeocodeResult {
        val response = client.get(IP_GEOLOCATION_URL)
        if (!response.status.isSuccess()) {
            throw IpGeolocationException("IP geolocation failed: HTTP ${response.status.value}")
        }
        val body: IpWhoIsResponse = response.body()
        val lat = body.latitude
        val lon = body.longitude
        if (!body.success || lat == null || lon == null) {
            throw IpGeolocationException("IP geolocation did not return a location")
        }
        val displayName = listOfNotNull(body.city, body.region)
            .joinToString(", ")
            .ifBlank { "Approximate location" }
        return GeocodeResult(displayName = displayName, lat = lat, lon = lon)
    }

    fun close() {
        client.close()
    }
}
