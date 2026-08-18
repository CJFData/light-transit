package com.thelightphone.transit.gtfs

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.thelightphone.sdk.LightConnectivity
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.head
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.BufferedReader
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class GtfsIngestStatus {
    CheckingForUpdates, Downloading, Parsing, WaitingForWifi, Ready
}

class GtfsIngestException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val MAX_REDIRECTS = 5

/** Path to an agency's ingested SQLite database, shared by the ingestor and every query screen. */
fun gtfsDbFile(filesDir: File, agency: GtfsAgency): File =
    File(filesDir, "gtfs/${agency.id}/transit.db")

/** Deletes every agency's downloaded/ingested GTFS data (zip, database, and cached ETag/schema
 * metadata) -- the Settings screen's "Clear schedule cache" action. A no-op if nothing's been
 * downloaded yet. Bumps [GtfsCacheClearedSignal] so HomeScreenViewModel can react and re-ingest
 * whichever agency is currently selected, since deleting the files underneath it otherwise leaves
 * that agency's screens pointed at a database that no longer exists. */
fun clearAllCachedSchedules(filesDir: File) {
    File(filesDir, "gtfs").deleteRecursively()
    GtfsCacheClearedSignal.version.value++
}

/** Bumped by [clearAllCachedSchedules] -- a plain in-process counter (not a DataStore-backed
 * preference) since this only needs to signal other live screens for the remainder of this
 * process, never to persist across app restarts. Same "shared singleton Flow" pattern as
 * [HomeVisibility]. */
object GtfsCacheClearedSignal {
    val version = MutableStateFlow(0)
}

/** ETag/Last-Modified as last seen for a successfully-downloaded feed -- either may be blank if the
 * server didn't send that particular header. */
private data class FeedMeta(val etag: String, val lastModified: String) {
    fun isEmpty() = etag.isBlank() && lastModified.isBlank()
}

/** Downloads, unzips, and bulk-loads an agency's GTFS static feed into a local SQLite database. */
class GtfsIngestor(
    private val filesDir: File,
    private val connectivity: LightConnectivity,
    private val networkPreferences: NetworkPreferences,
) {
    private val ingestMutex = Mutex()

    /**
     * Re-downloads only when the feed has actually changed: a HEAD request's ETag/Last-Modified is
     * compared against what was stored alongside the cached database from the last successful
     * download. Unchanged -> the existing SQLite database is used as-is, no network transfer beyond
     * the HEAD request. Changed, or nothing cached yet, or the check itself is inconclusive -> falls
     * back to a full re-download, since that's always safe (just not always necessary).
     */
    suspend fun ingest(agency: GtfsAgency, onStatus: (GtfsIngestStatus) -> Unit) = ingestMutex.withLock {
        ingestInternal(agency, onStatus)
    }

    private suspend fun ingestInternal(agency: GtfsAgency, onStatus: (GtfsIngestStatus) -> Unit) {
        val agencyDir = File(filesDir, "gtfs/${agency.id}")
        agencyDir.mkdirs()
        val secondaryFeeds = agency.components.filterIsInstance<SecondaryGtfsFeed>()
        val feedUrls = listOf(agency.feedUrl) + secondaryFeeds.map { it.feedUrl }
        val zipFiles = feedUrls.mapIndexed { index, _ ->
            File(agencyDir, if (index == 0) "gtfs.zip" else "gtfs-$index.zip")
        }
        val dbFile = gtfsDbFile(filesDir, agency)
        val metaFile = File(agencyDir, "feed_meta.txt")
        // See GTFS_SCHEMA_VERSION's own doc -- forces one full re-ingest whenever the app's own
        // schema has moved on, independent of whether the remote feed itself changed.
        val schemaVersionFile = File(agencyDir, "schema_version.txt")
        val cachedSchemaVersion = schemaVersionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()

        // Checked before any network activity at all (not just before the zip download) -- the
        // "Only download over Wi-Fi" setting's whole point is that a rider on cellular never pays
        // for a GTFS refresh, including the small HEAD-only update check below. Whatever's already
        // on disk (possibly stale, possibly nothing at all) is left exactly as it is; the caller
        // (HomeScreenViewModel) is what decides whether a stale-but-present database is still
        // usable, and retries once Wi-Fi comes back.
        if (networkPreferences.wifiOnlyDownloadsEnabledFlow.first() && !connectivity.currentStatus.isWifi) {
            onStatus(GtfsIngestStatus.WaitingForWifi)
            return
        }

        onStatus(GtfsIngestStatus.CheckingForUpdates)
        val cachedMeta = readFeedMeta(metaFile, feedUrls)
        val remoteMeta = try {
            feedUrls.map { checkForUpdate(it) }.takeIf { metas -> metas.all { it != null } }?.map { it!! }
        } catch (e: Exception) {
            Log.e("GtfsIngestor", "Feed update check failed for ${agency.displayName}, redownloading to be safe", e)
            null
        }

        val upToDate = dbFile.exists() && cachedMeta != null && remoteMeta != null &&
            cachedSchemaVersion == GTFS_SCHEMA_VERSION &&
            cachedMeta.size == remoteMeta.size && cachedMeta.zip(remoteMeta).all { (cached, remote) ->
                !cached.isEmpty() && cached == remote
            }
        if (upToDate) {
            onStatus(GtfsIngestStatus.Ready)
            return
        }

        onStatus(GtfsIngestStatus.Downloading)
        feedUrls.zip(zipFiles).forEach { (url, zipFile) -> downloadZip(url, zipFile) }

        onStatus(GtfsIngestStatus.Parsing)
        val tempDbFile = File(agencyDir, "transit.db.tmp")
        tempDbFile.delete()
        val db = openGtfsDatabase(tempDbFile)
        try {
            clearGtfsTables(db)
            zipFiles.forEachIndexed { index, zipFile ->
                val secondaryFeedName = if (index == 0) null else secondaryFeeds[index - 1].name
                parseAndLoad(zipFile, db, if (index == 0) "" else "feed$index:", secondaryFeedName)
            }
        } finally {
            db.close()
        }
        try {
            Files.move(
                tempDbFile.toPath(),
                dbFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                tempDbFile.toPath(),
                dbFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        remoteMeta?.let { metas -> writeFeedMeta(metaFile, feedUrls, metas) }
        schemaVersionFile.writeText(GTFS_SCHEMA_VERSION.toString())
        onStatus(GtfsIngestStatus.Ready)
    }

    /** A HEAD request's ETag/Last-Modified, or null if the check couldn't be completed -- follows
     * the same manual redirect handling as [downloadZip], since a HEAD to the same URL can hit the
     * same http hop. */
    private suspend fun checkForUpdate(url: String): FeedMeta? {
        val client = HttpClient(OkHttp) {
            followRedirects = false
        }
        try {
            var currentUrl = url
            repeat(MAX_REDIRECTS + 1) {
                val response = client.head(currentUrl)
                val status = response.status.value
                when {
                    status in 200..299 -> {
                        return FeedMeta(
                            etag = response.headers[HttpHeaders.ETag].orEmpty(),
                            lastModified = response.headers[HttpHeaders.LastModified].orEmpty(),
                        )
                    }
                    status in 300..399 -> {
                        val location = response.headers[HttpHeaders.Location] ?: return null
                        currentUrl = secureRedirectUrl(currentUrl, location)
                    }
                    else -> return null
                }
            }
            return null
        } finally {
            client.close()
        }
    }

    private fun readFeedMeta(file: File, feedUrls: List<String>): List<FeedMeta>? {
        if (!file.exists()) return null
        val lines = file.readLines()
        if (lines.size < feedUrls.size * 3) return null
        return feedUrls.mapIndexed { index, url ->
            val offset = index * 3
            if (lines[offset] != url) return null
            FeedMeta(etag = lines[offset + 1], lastModified = lines[offset + 2])
        }
    }

    private fun writeFeedMeta(file: File, feedUrls: List<String>, metas: List<FeedMeta>) {
        file.writeText(feedUrls.zip(metas).joinToString("\n") { (url, meta) ->
            "$url\n${meta.etag}\n${meta.lastModified}"
        } + "\n")
    }

    /**
     * Some feeds (e.g. RTD Denver's static GTFS feed) redirect through a hop that needs resolving
     * relative to the URL that produced it. Ktor's HttpRedirect plugin refuses to follow an
     * HTTPS->HTTP downgrade by default (and Android blocks cleartext traffic anyway), so it hands
     * back the original redirect response instead of an error. Redirects are followed manually
     * here, upgrading any http:// hop to https:// rather than ever actually connecting over plain
     * HTTP.
     *
     * Streams the response body straight to [destination] via [prepareGet]/[bodyAsChannel] rather
     * than `client.get(url)` + `response.body<ByteArray>()` -- the latter (via Ktor's SavedCall)
     * buffers the *entire* response into one in-memory byte array before it's ever written to disk,
     * which OOM'd on a real Light Phone III for STM Montreal's multi-hundred-MB zip (confirmed via
     * on-device logcat: `OutOfMemoryError` inside `SavedCallKt.save`/`SourcesKt.readByteArray`,
     * against a ~128MB heap growth limit). Streaming keeps memory use bounded regardless of feed
     * size. This is separate from [COMMIT_BATCH_SIZE]'s fix, which addresses OOM risk during
     * parsing/loading, not downloading.
     */
    private suspend fun downloadZip(url: String, destination: File) {
        val client = HttpClient(OkHttp) {
            followRedirects = false
        }
        try {
            var currentUrl = url
            repeat(MAX_REDIRECTS + 1) {
                val redirectLocation = client.prepareGet(currentUrl).execute { response ->
                    val status = response.status.value
                    when {
                        status in 200..299 -> {
                            destination.outputStream().use { out -> response.bodyAsChannel().copyTo(out) }
                            null
                        }
                        status in 300..399 -> {
                            response.headers[HttpHeaders.Location]
                                ?: throw GtfsIngestException("GTFS download redirected without a Location header")
                        }
                        else -> throw GtfsIngestException("GTFS download failed: HTTP $status")
                    }
                }
                if (redirectLocation == null) return
                currentUrl = secureRedirectUrl(currentUrl, redirectLocation)
            }
            throw GtfsIngestException("GTFS download exceeded $MAX_REDIRECTS redirects")
        } finally {
            client.close()
        }
    }

    /** No outer transaction wraps the whole zip here -- each table's own [readCsvEntry] call
     * commits itself in batches instead (see that function's own doc for why a single transaction
     * spanning a table the size of STM Montreal's stop_times.txt crashed on real hardware). The
     * ingest pipeline's actual safety net is [ingestInternal]'s temp-file-then-atomic-move, not
     * this function's own atomicity -- a failure partway through here just leaves the temp database
     * mid-load and never reaches the move, so the previous good database (if any) is untouched
     * either way. */
    private fun parseAndLoad(zipFile: File, db: SQLiteDatabase, idPrefix: String, secondaryFeedName: String?) {
        ZipFile(zipFile).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val loader = TABLE_LOADERS[entry.name.substringAfterLast('/')]
                if (loader != null) {
                    archive.getInputStream(entry).reader(Charsets.UTF_8).buffered().use { reader ->
                        loader(db, reader, idPrefix, secondaryFeedName)
                    }
                }
            }
        }
    }

    companion object {
        /** [secondaryFeedName] (see [SecondaryGtfsFeed.name]) is only consulted by [loadRoutes] and
         * [loadStops] -- every other loader here ignores its fourth parameter entirely, it just
         * needs to accept one so all eight can share one map's function type. */
        private val TABLE_LOADERS: Map<String, (SQLiteDatabase, BufferedReader, String, String?) -> Unit> = mapOf(
            "routes.txt" to ::loadRoutes,
            "trips.txt" to { db, reader, idPrefix, _ -> loadTrips(db, reader, idPrefix) },
            "stops.txt" to ::loadStops,
            "stop_times.txt" to { db, reader, idPrefix, _ -> loadStopTimes(db, reader, idPrefix) },
            "calendar.txt" to { db, reader, idPrefix, _ -> loadCalendar(db, reader, idPrefix) },
            "calendar_dates.txt" to { db, reader, idPrefix, _ -> loadCalendarDates(db, reader, idPrefix) },
            "feed_info.txt" to { db, reader, idPrefix, _ -> loadFeedInfo(db, reader, idPrefix) },
            "agency.txt" to { db, reader, idPrefix, _ -> loadAgency(db, reader, idPrefix) },
            "directions.txt" to { db, reader, idPrefix, _ -> loadDirections(db, reader, idPrefix) },
        )
    }
}

/**
 * Resolves absolute and relative redirects while never following a redirect back to plain HTTP.
 * Needed because some feeds (e.g. RTD Denver's static GTFS feed, which 308s every request to a bare
 * "/api/download?..." path) send a *relative* Location -- a plain `startsWith("http://")` check let
 * that through unresolved, straight to the HTTP client, which parsed the bare path as a request to
 * https://localhost/... and failed with a cleartext error.
 */
private fun secureRedirectUrl(currentUrl: String, location: String): String {
    val resolved = URI(currentUrl).resolve(location).toString()
    return if (resolved.startsWith("http://")) {
        "https://" + resolved.removePrefix("http://")
    } else {
        resolved
    }
}

private fun clearGtfsTables(db: SQLiteDatabase) {
    db.delete("stop_times", null, null)
    db.delete("trips", null, null)
    db.delete("routes", null, null)
    db.delete("stops", null, null)
    db.delete("calendar_dates", null, null)
    db.delete("calendar", null, null)
    db.delete("feed_info", null, null)
    db.delete("agency", null, null)
    db.delete("directions", null, null)
}

private fun prefixedId(prefix: String, id: String?): String? = id?.takeIf { it.isNotEmpty() }?.let { prefix + it }

/** Disambiguates a secondary feed's own route/stop name against the parent agency's -- e.g.
 * Bustang's "West Line" merged under RTD Denver becomes "West Line - Bustang" so a rider can tell
 * it's not one of RTD's own, unless [secondaryFeedName] is already part of [name] (some of
 * Bustang's own text already spells this out, e.g. trip_headsigns like "West - Bustang West
 * Line"), in which case appending it again would just be redundant. [secondaryFeedName] is null
 * for the primary feed itself -- nothing is ever appended to an agency's own routes/stops. */
private fun disambiguatedName(name: String?, secondaryFeedName: String?): String? {
    if (name == null || secondaryFeedName == null || name.contains(secondaryFeedName, ignoreCase = true)) return name
    return "$name - $secondaryFeedName"
}

private fun loadRoutes(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String, secondaryFeedName: String?) {
    val stmt = db.compileStatement(
        """
        INSERT INTO routes
            (route_id, agency_id, route_short_name, route_long_name, route_desc, route_type, route_url, route_color, route_text_color)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(db, reader) { header, row ->
        val routeId = prefixedId(idPrefix, header.get(row, "route_id")) ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, routeId)
        stmt.bindStringOrNull(2, header.get(row, "agency_id"))
        stmt.bindStringOrNull(3, header.get(row, "route_short_name"))
        stmt.bindStringOrNull(4, disambiguatedName(header.get(row, "route_long_name"), secondaryFeedName))
        stmt.bindStringOrNull(5, header.get(row, "route_desc"))
        stmt.bindLongOrNull(6, header.get(row, "route_type"))
        stmt.bindStringOrNull(7, header.get(row, "route_url"))
        stmt.bindStringOrNull(8, header.get(row, "route_color"))
        stmt.bindStringOrNull(9, header.get(row, "route_text_color"))
        stmt.executeInsert()
    }
}

private fun loadTrips(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String) {
    val stmt = db.compileStatement(
        """
        INSERT INTO trips
            (trip_id, route_id, service_id, trip_headsign, trip_short_name, direction_id, block_id, shape_id, wheelchair_accessible, bikes_allowed)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(db, reader) { header, row ->
        val tripId = prefixedId(idPrefix, header.get(row, "trip_id")) ?: return@readCsvEntry
        val routeId = prefixedId(idPrefix, header.get(row, "route_id")) ?: return@readCsvEntry
        val serviceId = prefixedId(idPrefix, header.get(row, "service_id")) ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, tripId)
        stmt.bindString(2, routeId)
        stmt.bindString(3, serviceId)
        stmt.bindStringOrNull(4, header.get(row, "trip_headsign"))
        stmt.bindStringOrNull(5, header.get(row, "trip_short_name"))
        stmt.bindLongOrNull(6, header.get(row, "direction_id"))
        stmt.bindStringOrNull(7, header.get(row, "block_id"))
        stmt.bindStringOrNull(8, header.get(row, "shape_id"))
        stmt.bindLongOrNull(9, header.get(row, "wheelchair_accessible"))
        stmt.bindLongOrNull(10, header.get(row, "bikes_allowed"))
        stmt.executeInsert()
    }
}

private fun loadStops(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String, secondaryFeedName: String?) {
    val stmt = db.compileStatement(
        """
        INSERT INTO stops
            (stop_id, stop_code, stop_name, stop_desc, stop_lat, stop_lon, zone_id, stop_url, location_type, parent_station, wheelchair_boarding)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(db, reader) { header, row ->
        val stopId = prefixedId(idPrefix, header.get(row, "stop_id")) ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, stopId)
        stmt.bindStringOrNull(2, header.get(row, "stop_code"))
        stmt.bindStringOrNull(3, disambiguatedName(header.get(row, "stop_name"), secondaryFeedName))
        stmt.bindStringOrNull(4, header.get(row, "stop_desc"))
        stmt.bindDoubleOrNull(5, header.get(row, "stop_lat"))
        stmt.bindDoubleOrNull(6, header.get(row, "stop_lon"))
        stmt.bindStringOrNull(7, header.get(row, "zone_id"))
        stmt.bindStringOrNull(8, header.get(row, "stop_url"))
        stmt.bindLongOrNull(9, header.get(row, "location_type"))
        stmt.bindStringOrNull(10, prefixedId(idPrefix, header.get(row, "parent_station")))
        stmt.bindLongOrNull(11, header.get(row, "wheelchair_boarding"))
        stmt.executeInsert()
    }
}

private fun loadStopTimes(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String) {
    val stmt = db.compileStatement(
        """
        INSERT INTO stop_times
            (trip_id, stop_sequence, arrival_time, departure_time, stop_id, stop_headsign, pickup_type, drop_off_type, shape_dist_traveled)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(db, reader) { header, row ->
        val tripId = prefixedId(idPrefix, header.get(row, "trip_id")) ?: return@readCsvEntry
        val stopSequence = header.get(row, "stop_sequence")?.toLongOrNull() ?: return@readCsvEntry
        val stopId = prefixedId(idPrefix, header.get(row, "stop_id")) ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, tripId)
        stmt.bindLong(2, stopSequence)
        stmt.bindStringOrNull(3, header.get(row, "arrival_time"))
        stmt.bindStringOrNull(4, header.get(row, "departure_time"))
        stmt.bindString(5, stopId)
        stmt.bindStringOrNull(6, header.get(row, "stop_headsign"))
        stmt.bindLongOrNull(7, header.get(row, "pickup_type"))
        stmt.bindLongOrNull(8, header.get(row, "drop_off_type"))
        stmt.bindDoubleOrNull(9, header.get(row, "shape_dist_traveled"))
        stmt.executeInsert()
    }
}

private fun loadCalendar(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String) {
    val stmt = db.compileStatement(
        """
        INSERT INTO calendar
            (service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday, start_date, end_date)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(db, reader) { header, row ->
        val serviceId = prefixedId(idPrefix, header.get(row, "service_id")) ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, serviceId)
        stmt.bindLongOrNull(2, header.get(row, "monday"))
        stmt.bindLongOrNull(3, header.get(row, "tuesday"))
        stmt.bindLongOrNull(4, header.get(row, "wednesday"))
        stmt.bindLongOrNull(5, header.get(row, "thursday"))
        stmt.bindLongOrNull(6, header.get(row, "friday"))
        stmt.bindLongOrNull(7, header.get(row, "saturday"))
        stmt.bindLongOrNull(8, header.get(row, "sunday"))
        stmt.bindStringOrNull(9, header.get(row, "start_date"))
        stmt.bindStringOrNull(10, header.get(row, "end_date"))
        stmt.executeInsert()
    }
}

/** Attribution text is only ever shown for the agency's *primary* feed (idPrefix == "") -- a
 * secondary merged feed (e.g. RTD's own Bustang addition) never overwrites the primary agency's own
 * feed_info/agency row, since [GtfsRepository.getFeedAttribution] always wants "whose feed is this
 * screen showing", not whichever feed happened to parse last. See [clearGtfsTables] for why this
 * doesn't also need its own delete call here. */
private fun loadFeedInfo(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String) {
    if (idPrefix.isNotEmpty()) return
    val stmt = db.compileStatement(
        "INSERT INTO feed_info (feed_publisher_name, feed_publisher_url) VALUES (?, ?)"
    )
    readCsvEntry(db, reader) { header, row ->
        val name = header.get(row, "feed_publisher_name") ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, name)
        stmt.bindStringOrNull(2, header.get(row, "feed_publisher_url"))
        stmt.executeInsert()
    }
}

/** See [loadFeedInfo] -- same primary-feed-only rule, for the same reason. */
private fun loadAgency(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String) {
    if (idPrefix.isNotEmpty()) return
    val stmt = db.compileStatement(
        "INSERT INTO agency (agency_name, agency_url) VALUES (?, ?)"
    )
    readCsvEntry(db, reader) { header, row ->
        val name = header.get(row, "agency_name") ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, name)
        stmt.bindStringOrNull(2, header.get(row, "agency_url"))
        stmt.executeInsert()
    }
}

private fun loadCalendarDates(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String) {
    val stmt = db.compileStatement(
        """
        INSERT INTO calendar_dates (service_id, date, exception_type)
        VALUES (?, ?, ?)
        """
    )
    readCsvEntry(db, reader) { header, row ->
        val serviceId = prefixedId(idPrefix, header.get(row, "service_id")) ?: return@readCsvEntry
        val date = header.get(row, "date") ?: return@readCsvEntry
        val exceptionType = header.get(row, "exception_type")?.toLongOrNull() ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, serviceId)
        stmt.bindString(2, date)
        stmt.bindLong(3, exceptionType)
        stmt.executeInsert()
    }
}

/** directions.txt is an optional GTFS extension -- absent for most agencies, in which case this
 * table simply stays empty and GtfsRepository.getDirections falls back to a headsign-derived
 * label. Plain INSERT (not OR REPLACE) is safe since (route_id, direction_id) is documented as a
 * guaranteed-unique key within the file itself. */
private fun loadDirections(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String) {
    val stmt = db.compileStatement(
        "INSERT INTO directions (route_id, direction_id, direction, direction_destination) VALUES (?, ?, ?, ?)"
    )
    readCsvEntry(db, reader) { header, row ->
        val routeId = prefixedId(idPrefix, header.get(row, "route_id")) ?: return@readCsvEntry
        val directionId = header.get(row, "direction_id")?.toLongOrNull() ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, routeId)
        stmt.bindLong(2, directionId)
        stmt.bindStringOrNull(3, header.get(row, "direction"))
        stmt.bindStringOrNull(4, header.get(row, "direction_destination"))
        stmt.executeInsert()
    }
}