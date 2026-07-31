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

/**
 * Standard Web Mercator "slippy map" tile math (see the OSM wiki's "Slippy map tilenames" page) —
 * shared by both the background tile fetch and marker placement so everything drawn on the map
 * screen comes from one consistent, real-world-accurate coordinate system.
 */
private const val TILE_SIZE = 256.0
private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686

/** Fractional (x, y) tile coordinates for (lat, lon) at the given integer zoom. */
fun lonLatToTileFraction(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
    val n = 2.0.pow(zoom)
    val x = (lon + 180.0) / 360.0 * n
    val latRad = Math.toRadians(lat)
    val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
    return x to y
}

/** Ground resolution (meters/pixel) at [lat] and integer [zoom]. */
fun metersPerPixel(lat: Double, zoom: Int): Double {
    val latRad = Math.toRadians(lat)
    return (EARTH_CIRCUMFERENCE_METERS * cos(latRad)) / (TILE_SIZE * 2.0.pow(zoom))
}

/**
 * The integer zoom that fits every (lat, lon) in [points] within [availableHalfExtentPx] of
 * (centerLat, centerLon) in both axes, clamped to [minZoom]..[maxZoom]. Unlike a textbook
 * "fitBounds" (which re-centers on the bounding box's own centroid), this fits the box around a
 * *fixed* center point — the map screen's selected stop must always render at dead center, so each
 * point's offset is measured from (centerLat, centerLon) directly, not from the box's own middle.
 * A dense cluster of nearby points naturally yields a high (zoomed-in) result; a lone distant point
 * naturally yields a low one — no per-agency density special-casing needed.
 *
 * Falls back to [fallbackZoom] when [points] is empty or every point is ~coincident with the
 * center, since the fit-to-bounds formula is undefined for a zero-size box.
 */
fun fitBoundsZoom(
    centerLat: Double,
    centerLon: Double,
    points: List<Pair<Double, Double>>,
    availableHalfExtentPx: Float,
    minZoom: Int,
    maxZoom: Int,
    fallbackZoom: Int,
): Int {
    // Computed at zoom 0 (n = 1) as a zoom-independent baseline -- offsets at any real zoom z are
    // this baseline scaled by 2^z, since both x and the Mercator-projected y are linear in n.
    val (centerFracX, centerFracY) = lonLatToTileFraction(centerLat, centerLon, 0)
    var maxDx = 0.0
    var maxDy = 0.0
    for ((lat, lon) in points) {
        val (fracX, fracY) = lonLatToTileFraction(lat, lon, 0)
        maxDx = max(maxDx, abs(fracX - centerFracX))
        maxDy = max(maxDy, abs(fracY - centerFracY))
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

/**
 * Pixel offset of (lat, lon) relative to (centerLat, centerLon) at the given zoom — the same
 * projection tile placement uses, but usable on its own for marker placement even when a tile
 * failed to fetch, so bearing/distance-accurate placement never depends on the tile network call
 * succeeding.
 */
fun projectRelativeToCenter(centerLat: Double, centerLon: Double, lat: Double, lon: Double, zoom: Int): PixelPoint {
    val (centerX, centerY) = lonLatToTileFraction(centerLat, centerLon, zoom)
    val (pointX, pointY) = lonLatToTileFraction(lat, lon, zoom)
    return PixelPoint(
        x = ((pointX - centerX) * TILE_SIZE).toFloat(),
        y = ((pointY - centerY) * TILE_SIZE).toFloat(),
    )
}

/** One fetched map tile and its integer tile coordinates at the map's zoom level. */
data class FetchedTile(val tileX: Int, val tileY: Int, val bitmap: Bitmap)

/**
 * Every tile fetched to cover the requested area around one center point, plus the fractional tile
 * coordinates of that center point. Each tile is drawn independently at its own screen offset via
 * [screenOffset] — there's no pre-stitched composite bitmap, so a handful of individually-failed
 * tiles just leave that one patch blank instead of requiring a grid size guessed to be "big enough"
 * up front.
 */
data class MapTiles(
    val zoom: Int,
    val centerFracX: Double,
    val centerFracY: Double,
    val tiles: List<FetchedTile>,
) {
    /** This tile's pixel offset relative to the center point, at the map's zoom level. */
    fun screenOffset(tileX: Int, tileY: Int): PixelPoint = PixelPoint(
        x = ((tileX - centerFracX) * TILE_SIZE).toFloat(),
        y = ((tileY - centerFracY) * TILE_SIZE).toFloat(),
    )
}

/**
 * A small in-process LRU of decoded tile bitmaps, keyed by "z/x/y", shared by every [MapTileClient]
 * instance and screen visit in this app session — revisiting the Map screen for the same or a
 * nearby stop reuses tiles already downloaded instead of refetching them. Capacity is a tile count,
 * not a byte budget, since every raster tile is the same 256x256 size.
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
 * Fetches individual raster tiles from one of CARTO's free basemaps (built on OpenStreetMap data),
 * caching decoded bitmaps in [TileCache]. Voyager is the default -- chosen specifically for
 * street-name legibility, since its labels and road contours stay readable at the small sizes this
 * screen renders at, which the darker "Dark Matter" style didn't -- but Dark Matter remains
 * available as an opt-in (see MapPreferences) for anyone who prefers it. A real, descriptive
 * User-Agent is sent on every request, and on-screen "© OpenStreetMap contributors © CARTO"
 * attribution is required wherever these tiles are displayed — see MapScreen's Content().
 */
class MapTileClient {
    private val client = HttpClient(OkHttp)

    companion object {
        private const val VOYAGER_BASE_URL = "https://a.basemaps.cartocdn.com/rastertiles/voyager"
        private const val DARK_BASE_URL = "https://a.basemaps.cartocdn.com/dark_all"
        private const val USER_AGENT = "LightTransitTool/1.0 (+https://github.com/lightphone)"
        // Fetched area is this much larger than the target radius, so the real device canvas (whose
        // exact size isn't known yet when tiles are requested) ends up comfortably inside the
        // fetched area rather than right at its edge.
        private const val COVERAGE_MARGIN = 1.3
    }

    /**
     * Every tile needed to cover a [targetRadiusMeters] circle around (lat, lon) at [zoom], fetched
     * concurrently. Individual tile failures are logged and simply omitted from the result — never
     * fail the whole map for one bad tile. [darkMode] selects Dark Matter over the default Voyager
     * style; both are cached independently (see [fetchTile]) so switching styles never serves a
     * stale tile from the other one.
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
            .map { (tileX, tileY) -> async { fetchTile(tileX, tileY, zoom, darkMode)?.let { FetchedTile(tileX, tileY, it) } } }
            .mapNotNull { it.await() }

        MapTiles(zoom, centerFracX, centerFracY, tiles)
    }

    private suspend fun fetchTile(x: Int, y: Int, zoom: Int, darkMode: Boolean): Bitmap? {
        val style = if (darkMode) "dark" else "voyager"
        val key = "$style/$zoom/$x/$y"
        TileCache.get(key)?.let { return it }

        val baseUrl = if (darkMode) DARK_BASE_URL else VOYAGER_BASE_URL
        return try {
            val response = client.get("$baseUrl/$zoom/$x/$y.png") {
                header("User-Agent", USER_AGENT)
            }
            if (!response.status.isSuccess()) return null
            val bytes: ByteArray = response.body()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { TileCache.put(key, it) }
        } catch (e: Exception) {
            Log.e("MapTileClient", "Tile fetch failed for $key", e)
            null
        }
    }

    fun close() {
        client.close()
    }
}
