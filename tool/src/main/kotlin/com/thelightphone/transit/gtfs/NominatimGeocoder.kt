package com.thelightphone.transit.gtfs

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search"
private const val NOMINATIM_REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"
private const val RESULT_LIMIT = 5

/**
 * Nominatim's usage policy requires a real identifying User-Agent (generic/default client agents
 * get blocked) and asks for no more than ~1 request/second — both trivially satisfied by a single
 * user-triggered search. Results are OpenStreetMap data (ODbL) and must be attributed in the UI.
 */
private const val NOMINATIM_USER_AGENT = "LightTransitTool/1.0 (+https://github.com/lightphone)"

@Serializable
private data class NominatimResult(
    val lat: String,
    val lon: String,
    @kotlinx.serialization.SerialName("display_name") val displayName: String,
)

@Serializable
private data class NominatimAddress(val road: String? = null)

@Serializable
private data class NominatimReverseResult(val address: NominatimAddress? = null)

data class GeocodeResult(val displayName: String, val lat: Double, val lon: Double)

class GeocodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NominatimGeocoder {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun search(query: String): List<GeocodeResult> {
        val response = client.get(NOMINATIM_SEARCH_URL) {
            header("User-Agent", NOMINATIM_USER_AGENT)
            parameter("q", query)
            parameter("format", "jsonv2")
            parameter("limit", RESULT_LIMIT)
        }
        if (!response.status.isSuccess()) {
            throw GeocodeException("Location search failed: HTTP ${response.status.value}")
        }
        val results: List<NominatimResult> = response.body()
        return results.mapNotNull { result ->
            val lat = result.lat.toDoubleOrNull() ?: return@mapNotNull null
            val lon = result.lon.toDoubleOrNull() ?: return@mapNotNull null
            GeocodeResult(displayName = result.displayName, lat = lat, lon = lon)
        }
    }

    /** Nearby street name for a point, e.g. for labeling a stop pin with its street context. */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? {
        val response = client.get(NOMINATIM_REVERSE_URL) {
            header("User-Agent", NOMINATIM_USER_AGENT)
            parameter("lat", lat)
            parameter("lon", lon)
            parameter("format", "jsonv2")
        }
        if (!response.status.isSuccess()) return null
        val result: NominatimReverseResult = response.body()
        return result.address?.road
    }

    fun close() {
        client.close()
    }
}
