package com.thelightphone.transit.gtfs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** Web Mercator tile math, shared by the tile fetch and marker placement. */
private const val TILE_SIZE = 256.0
private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686
private const val METERS_PER_MILE = 1609.344

/** Fractional (x, y) tile coordinates for (lat, lon) at [zoom]. */
fun lonLatToTileFraction(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
    val n = 2.0.pow(zoom)
    val x = (lon + 180.0) / 360.0 * n
    val latRad = Math.toRadians(lat)
    val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
    return x to y
}

/** Meters per pixel at [lat] and [zoom]. */
fun metersPerPixel(lat: Double, zoom: Int): Double {
    val latRad = Math.toRadians(lat)
    return (EARTH_CIRCUMFERENCE_METERS * cos(latRad)) / (TILE_SIZE * 2.0.pow(zoom))
}

/**
 * The zoom that fits every point within [availableHalfExtentPx] of the center, clamped to
 * [minZoom]..[maxZoom]. Fits around the fixed center rather than the points' centroid, so the
 * selected stop stays centered.
 *
 * The box is at least [minBoundingBoxMiles] across so a tiny cluster doesn't zoom in too far.
 * [fallbackZoom] is used when there's nothing to fit.
 */
fun fitBoundsZoom(
    centerLat: Double,
    centerLon: Double,
    points: List<Pair<Double, Double>>,
    availableHalfExtentPx: Float,
    minZoom: Int,
    maxZoom: Int,
    fallbackZoom: Int,
    minBoundingBoxMiles: Double = 0.0,
): Int {
    // Offsets at zoom 0; at zoom z they're scaled by 2^z.
    val (centerFracX, centerFracY) = lonLatToTileFraction(centerLat, centerLon, 0)
    var maxDx = 0.0
    var maxDy = 0.0
    for ((lat, lon) in points) {
        val (fracX, fracY) = lonLatToTileFraction(lat, lon, 0)
        maxDx = max(maxDx, abs(fracX - centerFracX))
        maxDy = max(maxDy, abs(fracY - centerFracY))
    }

    // Converts the minimum size from miles into zoom-0 tile units.
    if (minBoundingBoxMiles > 0.0) {
        val minHalfExtentMeters = (minBoundingBoxMiles * METERS_PER_MILE) / 2.0
        val minHalfExtentFraction = minHalfExtentMeters / (EARTH_CIRCUMFERENCE_METERS * cos(Math.toRadians(centerLat)))
        maxDx = max(maxDx, minHalfExtentFraction)
        maxDy = max(maxDy, minHalfExtentFraction)
    }

    val epsilon = 1e-9
    if (maxDx < epsilon && maxDy < epsilon) {
        return fallbackZoom.coerceIn(minZoom, maxZoom)
    }

    val zForX = if (maxDx > epsilon) ln(availableHalfExtentPx / (maxDx * TILE_SIZE)) / ln(2.0) else Double.POSITIVE_INFINITY
    val zForY = if (maxDy > epsilon) ln(availableHalfExtentPx / (maxDy * TILE_SIZE)) / ln(2.0) else Double.POSITIVE_INFINITY
    return floor(min(zForX, zForY)).toInt().coerceIn(minZoom, maxZoom)
}

data class PixelPoint(val x: Float, val y: Float)

/** Pixel offset of (lat, lon) from the center at [zoom]. Doesn't depend on tiles loading. */
fun projectRelativeToCenter(centerLat: Double, centerLon: Double, lat: Double, lon: Double, zoom: Int): PixelPoint {
    val (centerX, centerY) = lonLatToTileFraction(centerLat, centerLon, zoom)
    val (pointX, pointY) = lonLatToTileFraction(lat, lon, zoom)
    return PixelPoint(
        x = ((pointX - centerX) * TILE_SIZE).toFloat(),
        y = ((pointY - centerY) * TILE_SIZE).toFloat(),
    )
}

/** One fetched tile and its tile coordinates. */
data class FetchedTile(val tileX: Int, val tileY: Int, val bitmap: Bitmap)

/**
 * The tiles covering an area around a center point. Each is drawn at its own offset, so a failed
 * tile just leaves a blank patch.
 */
data class MapTiles(
    val zoom: Int,
    val centerFracX: Double,
    val centerFracY: Double,
    val tiles: List<FetchedTile>,
) {
    /** This tile's pixel offset from the center point. */
    fun screenOffset(tileX: Int, tileY: Int): PixelPoint = PixelPoint(
        x = ((tileX - centerFracX) * TILE_SIZE).toFloat(),
        y = ((tileY - centerFracY) * TILE_SIZE).toFloat(),
    )
}

/**
 * An in-memory LRU of decoded tiles, keyed by "style/z/x/y" and shared across screens. Sized by
 * tile count, since every tile is the same size.
 */
private object TileCache {
    private const val MAX_ENTRIES = 300
    private val entries = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): Bitmap? = entries[key]

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        entries[key] = bitmap
    }
}

/**
 * Fetches CARTO raster tiles (OpenStreetMap data) through the proxy, which adds the API key.
 * Voyager is the default for legibility; Dark Matter is optional (see MapPreferences). The map must
 * show "© OpenStreetMap contributors © CARTO".
 */
class MapTileClient {
    private val client = HttpClient(OkHttp)

    companion object {
        private const val VOYAGER_BASE_URL = "https://gtfs.picotransit.com/carto/voyager"
        private const val DARK_BASE_URL = "https://gtfs.picotransit.com/carto/dark"
        private const val USER_AGENT = "LightTransitTool/1.0 (+https://github.com/lightphone)"
        // Fetch a bit more than the target radius, since the canvas size isn't known yet.
        private const val COVERAGE_MARGIN = 1.3
    }

    /**
     * The tiles covering [targetRadiusMeters] around (lat, lon), fetched concurrently. Failed tiles
     * are logged and left out. [darkMode] picks Dark Matter; each style is cached separately.
     */
    suspend fun fetchTilesAround(
        lat: Double,
        lon: Double,
        zoom: Int,
        targetRadiusMeters: Double,
        darkMode: Boolean = false,
    ): MapTiles = coroutineScope {
        val (centerFracX, centerFracY) = lonLatToTileFraction(lat, lon, zoom)
        val radiusPixels = targetRadiusMeters / metersPerPixel(lat, zoom) * COVERAGE_MARGIN
        val radiusTiles = ceil(radiusPixels / TILE_SIZE).toInt().coerceAtLeast(1)
        val centerTileX = floor(centerFracX).toInt()
        val centerTileY = floor(centerFracY).toInt()

        val tileCoords = buildList {
            for (tileX in (centerTileX - radiusTiles)..(centerTileX + radiusTiles)) {
                for (tileY in (centerTileY - radiusTiles)..(centerTileY + radiusTiles)) {
                    add(tileX to tileY)
                }
            }
        }
        val tiles = tileCoords
            .groupBy { (tileX, tileY) -> Math.floorDiv(tileX, 2) to Math.floorDiv(tileY, 2) }
            .map { (parent, children) -> async { fetchFromParentTile(parent.first, parent.second, children, zoom, darkMode) } }
            .flatMap { it.await() }

        MapTiles(zoom, centerFracX, centerFracY, tiles)
    }

    /**
     * Cuts [children] (tiles at [zoom]) from their parent's 512px @2x tile at zoom - 1, so one
     * request covers four tiles and street labels render larger. Each quadrant is a drop-in 256px
     * tile.
     */
    private suspend fun fetchFromParentTile(
        parentX: Int,
        parentY: Int,
        children: List<Pair<Int, Int>>,
        zoom: Int,
        darkMode: Boolean,
    ): List<FetchedTile> {
        val style = if (darkMode) "dark" else "voyager"
        fun keyFor(tileX: Int, tileY: Int) = "$style/$zoom/$tileX/$tileY"

        val cached = children.mapNotNull { (tileX, tileY) -> TileCache.get(keyFor(tileX, tileY))?.let { FetchedTile(tileX, tileY, it) } }
        if (cached.size == children.size) return cached

        val baseUrl = if (darkMode) DARK_BASE_URL else VOYAGER_BASE_URL
        val parent = fetchBitmap("$baseUrl/${zoom - 1}/$parentX/$parentY@2x.png", "$style@2x/${zoom - 1}/$parentX/$parentY")
            ?: return emptyList()
        val half = parent.width / 2
        return children.map { (tileX, tileY) ->
            val quadrant = Bitmap.createBitmap(parent, (tileX - parentX * 2) * half, (tileY - parentY * 2) * half, half, half)
            val tile = if (half == TILE_SIZE.toInt()) quadrant else Bitmap.createScaledBitmap(quadrant, TILE_SIZE.toInt(), TILE_SIZE.toInt(), true)
            TileCache.put(keyFor(tileX, tileY), tile)
            FetchedTile(tileX, tileY, tile)
        }
    }

    /** One decoded tile, or null on failure ([label] is for the log). */
    private suspend fun fetchBitmap(url: String, label: String): Bitmap? =
        try {
            val response = client.get(url) {
                header("User-Agent", USER_AGENT)
            }
            if (!response.status.isSuccess()) {
                null
            } else {
                val bytes: ByteArray = response.body()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("MapTileClient", "Tile fetch failed for $label", e)
            null
        }

    fun close() {
        client.close()
    }
}
