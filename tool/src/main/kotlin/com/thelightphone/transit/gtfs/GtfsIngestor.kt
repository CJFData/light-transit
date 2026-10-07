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
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class GtfsIngestStatus {
    CheckingForUpdates, Downloading, Parsing, WaitingForWifi, Ready
}

class GtfsIngestException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val MAX_REDIRECTS = 5

private const val MAX_ENTRY_READ_ATTEMPTS = 3

/**
 * One lock for the whole app, since more than one [GtfsIngestor] can exist at once and two writing
 * the same agency's files corrupts them.
 */
private val ingestMutex = Mutex()

/** Path to an agency's SQLite database. */
fun gtfsDbFile(filesDir: File, agency: GtfsAgency): File =
    File(filesDir, "gtfs/${agency.id}/transit.db")

/**
 * Path to an agency's primary schedule zip, kept after ingest so [TripShapeSource] can read
 * shapes.txt from it. Secondary feeds' zips aren't covered.
 */
fun gtfsZipFile(filesDir: File, agency: GtfsAgency): File =
    File(filesDir, "gtfs/${agency.id}/gtfs.zip")

/**
 * Deletes every agency's downloaded schedule, database, and cached metadata (Settings' "Clear
 * schedule cache"), then bumps [GtfsCacheClearedSignal] so Home re-downloads the selected agency.
 */
fun clearAllCachedSchedules(filesDir: File) {
    File(filesDir, "gtfs").deleteRecursively()
    GtfsCacheClearedSignal.version.value++
}

/**
 * Bumped by [clearAllCachedSchedules]. In memory only, since it just signals screens in this
 * process.
 */
object GtfsCacheClearedSignal {
    val version = MutableStateFlow(0)
}

/** The ETag and Last-Modified from the last successful download; either may be blank. */
private data class FeedMeta(val etag: String, val lastModified: String) {
    fun isEmpty() = etag.isBlank() && lastModified.isBlank()
}

/** Downloads, unzips, and loads an agency's GTFS schedule into a local SQLite database. */
class GtfsIngestor(
    private val filesDir: File,
    private val connectivity: LightConnectivity,
    private val networkPreferences: NetworkPreferences,
) {
    /**
     * Re-downloads only when a HEAD request's ETag or Last-Modified differs from the last download.
     * A changed feed, nothing cached, or a failed check all mean a full download.
     */
    suspend fun ingest(agency: GtfsAgency, onStatus: (GtfsIngestStatus) -> Unit) = ingestMutex.withLock {
        ingestInternal(agency, onStatus)
    }

    private suspend fun ingestInternal(agency: GtfsAgency, onStatus: (GtfsIngestStatus) -> Unit) {
        val agencyDir = File(filesDir, "gtfs/${agency.id}")
        agencyDir.mkdirs()
        // Only extra feeds with their own schedule are ingested; realtime-only ones have nothing to
        // download.
        val secondaryFeeds = agency.components.filterIsInstance<MultiGtfsFeed>().filter { it.feedUrl != null }
        val feedUrls = listOf(agency.feedUrl) + secondaryFeeds.map { it.feedUrl!! }
        val zipFiles = feedUrls.mapIndexed { index, _ ->
            File(agencyDir, if (index == 0) "gtfs.zip" else "gtfs-$index.zip")
        }
        val dbFile = gtfsDbFile(filesDir, agency)
        val metaFile = File(agencyDir, "feed_meta.txt")
        // A new app schema forces one full re-ingest, even if the feed hasn't changed.
        val schemaVersionFile = File(agencyDir, "schema_version.txt")
        val cachedSchemaVersion = schemaVersionFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()

        // Checked before any network use, including the update check. What's on disk stays; Home
        // decides whether it's usable and retries once Wi-Fi is back.
        if (networkPreferences.wifiOnlyDownloadsEnabledFlow.first() && !connectivity.currentStatus.isWifi) {
            onStatus(GtfsIngestStatus.WaitingForWifi)
            return
        }

        onStatus(GtfsIngestStatus.CheckingForUpdates)
        val cachedMeta = readFeedMeta(metaFile, feedUrls)
        val remoteMeta = try {
            feedUrls.map { checkForUpdate(it) }.takeIf { metas -> metas.all { it != null } }?.map { it!! }
        } catch (e: CancellationException) {
            throw e
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
            val tripDirectionColumn = agency.component<TripDirectionColumn>()?.columnName
            zipFiles.forEachIndexed { index, zipFile ->
                val secondaryFeedName = if (index == 0) null else secondaryFeeds[index - 1].name
                parseAndLoad(zipFile, db, if (index == 0) "" else "feed$index:", secondaryFeedName, tripDirectionColumn)
            }
            // Built after loading, which is faster than updating indexes on every insert.
            createGtfsIndexes(db)
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

    /**
     * A HEAD request's ETag and Last-Modified, or null if the check failed. Follows redirects like
     * [downloadZip].
     */
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
     * Follows redirects by hand, upgrading any http:// hop to https://, since Ktor won't follow an
     * HTTPS-to-HTTP redirect.
     *
     * Streams the body straight to [destination] so a very large feed doesn't have to fit in
     * memory.
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

    /**
     * Each table commits in batches rather than one transaction for the whole zip. A failure
     * partway leaves only the temp database half-loaded, so the previous good one is untouched.
     */
    private fun parseAndLoad(
        zipFile: File, db: SQLiteDatabase, idPrefix: String, secondaryFeedName: String?, tripDirectionColumn: String?,
    ) {
        ZipFile(zipFile).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val loader = TABLE_LOADERS[entry.name.substringAfterLast('/')]
                if (loader != null) {
                    loadEntryWithRetry(zipFile, archive, entry.name, db, idPrefix, secondaryFeedName, tripDirectionColumn, loader)
                }
            }
        }
    }

    /**
     * Retries a failed entry read up to [MAX_ENTRY_READ_ATTEMPTS] times. Later attempts open a
     * fresh [ZipFile], and the table is cleared first since a failed attempt may have committed
     * rows.
     */
    private fun loadEntryWithRetry(
        zipFile: File, firstArchive: ZipFile, entryName: String, db: SQLiteDatabase,
        idPrefix: String, secondaryFeedName: String?, tripDirectionColumn: String?,
        loader: (SQLiteDatabase, BufferedReader, String, String?, String?) -> Unit,
    ) {
        val tableName = entryName.substringAfterLast('/').removeSuffix(".txt")
        for (attempt in 1..MAX_ENTRY_READ_ATTEMPTS) {
            try {
                if (attempt == 1) {
                    val entry = firstArchive.getEntry(entryName) ?: return
                    firstArchive.getInputStream(entry).reader(Charsets.UTF_8).buffered().use { reader ->
                        loader(db, reader, idPrefix, secondaryFeedName, tripDirectionColumn)
                    }
                } else {
                    ZipFile(zipFile).use { retryArchive ->
                        val entry = retryArchive.getEntry(entryName) ?: return
                        retryArchive.getInputStream(entry).reader(Charsets.UTF_8).buffered().use { reader ->
                            loader(db, reader, idPrefix, secondaryFeedName, tripDirectionColumn)
                        }
                    }
                }
                return
            } catch (e: ZipException) {
                if (attempt == MAX_ENTRY_READ_ATTEMPTS) throw e
                Log.e("GtfsIngestor", "Zip read failed for $entryName on attempt $attempt, retrying with a fresh archive handle", e)
                db.delete(tableName, null, null)
            }
        }
    }

    companion object {
        /**
         * Every loader takes both [secondaryFeedName] and [tripDirectionColumn] so they share one
         * function type, though most ignore them.
         */
        private val TABLE_LOADERS: Map<String, (SQLiteDatabase, BufferedReader, String, String?, String?) -> Unit> = mapOf(
            "routes.txt" to { db, reader, idPrefix, secondaryFeedName, _ -> loadRoutes(db, reader, idPrefix, secondaryFeedName) },
            "trips.txt" to { db, reader, idPrefix, _, tripDirectionColumn -> loadTrips(db, reader, idPrefix, tripDirectionColumn) },
            "stops.txt" to { db, reader, idPrefix, secondaryFeedName, _ -> loadStops(db, reader, idPrefix, secondaryFeedName) },
            "stop_times.txt" to { db, reader, idPrefix, _, _ -> loadStopTimes(db, reader, idPrefix) },
            "calendar.txt" to { db, reader, idPrefix, _, _ -> loadCalendar(db, reader, idPrefix) },
            "calendar_dates.txt" to { db, reader, idPrefix, _, _ -> loadCalendarDates(db, reader, idPrefix) },
            "feed_info.txt" to { db, reader, idPrefix, _, _ -> loadFeedInfo(db, reader, idPrefix) },
            "agency.txt" to { db, reader, idPrefix, _, _ -> loadAgency(db, reader, idPrefix) },
            "directions.txt" to { db, reader, idPrefix, _, _ -> loadDirections(db, reader, idPrefix) },
        )
    }
}

/** Resolves absolute and relative redirects, never following one back to plain HTTP. */
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

/**
 * Appends a secondary feed's name to its routes and stops, e.g. "West Line - Bustang" under RTD
 * Denver, unless the name already includes it. Null for the primary feed.
 */
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

private fun loadTrips(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String, tripDirectionColumn: String?) {
    val stmt = db.compileStatement(
        """
        INSERT INTO trips
            (trip_id, route_id, service_id, trip_headsign, trip_short_name, direction_id, block_id, shape_id, wheelchair_accessible, bikes_allowed)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    // Builds the directions table from a trips.txt column when the feed has one instead of
    // directions.txt. INSERT OR IGNORE keeps the first trip's value for each route and direction.
    val directionStmt = tripDirectionColumn?.let {
        db.compileStatement(
            "INSERT OR IGNORE INTO directions (route_id, direction_id, direction, direction_destination) VALUES (?, ?, ?, NULL)"
        )
    }
    readCsvEntry(db, reader) { header, row ->
        val tripId = prefixedId(idPrefix, header.get(row, "trip_id")) ?: return@readCsvEntry
        val routeId = prefixedId(idPrefix, header.get(row, "route_id")) ?: return@readCsvEntry
        val serviceId = prefixedId(idPrefix, header.get(row, "service_id")) ?: return@readCsvEntry
        val directionId = header.get(row, "direction_id")
        stmt.clearBindings()
        stmt.bindString(1, tripId)
        stmt.bindString(2, routeId)
        stmt.bindString(3, serviceId)
        stmt.bindStringOrNull(4, header.get(row, "trip_headsign"))
        stmt.bindStringOrNull(5, header.get(row, "trip_short_name"))
        stmt.bindLongOrNull(6, directionId)
        stmt.bindStringOrNull(7, header.get(row, "block_id"))
        stmt.bindStringOrNull(8, header.get(row, "shape_id"))
        stmt.bindLongOrNull(9, header.get(row, "wheelchair_accessible"))
        stmt.bindLongOrNull(10, header.get(row, "bikes_allowed"))
        stmt.executeInsert()

        if (directionStmt != null) {
            val directionValue = tripDirectionColumn.let { header.get(row, it) }
            val directionIdLong = directionId?.toLongOrNull()
            if (directionValue != null && directionIdLong != null) {
                directionStmt.clearBindings()
                directionStmt.bindString(1, routeId)
                directionStmt.bindLong(2, directionIdLong)
                directionStmt.bindString(3, directionValue)
                directionStmt.executeInsert()
            }
        }
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

/**
 * Zero-pads each "HH:MM:SS" segment, since times are compared as strings and "6:30:00" would sort
 * after "17:40:00". Blank values and other formats pass through unchanged.
 */
private fun normalizeGtfsTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return raw
    val parts = raw.split(":")
    if (parts.size != 3) return raw
    return try {
        parts.joinToString(":") { it.toInt().toString().padStart(2, '0') }
    } catch (e: NumberFormatException) {
        raw
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
        stmt.bindStringOrNull(3, normalizeGtfsTime(header.get(row, "arrival_time")))
        stmt.bindStringOrNull(4, normalizeGtfsTime(header.get(row, "departure_time")))
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

/** Only the primary feed's credits are stored; a merged feed doesn't overwrite them. */
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

/** Primary feed only, like [loadFeedInfo]. */
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

/**
 * directions.txt is optional; without it, labels come from headsigns. INSERT OR IGNORE because some
 * feeds repeat a route and direction, and the first row wins.
 */
private fun loadDirections(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String) {
    val stmt = db.compileStatement(
        "INSERT OR IGNORE INTO directions (route_id, direction_id, direction, direction_destination) VALUES (?, ?, ?, ?)"
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