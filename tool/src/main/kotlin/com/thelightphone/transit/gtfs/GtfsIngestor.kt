package com.thelightphone.transit.gtfs

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import java.io.BufferedReader
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class GtfsIngestStatus {
    CheckingForUpdates, Downloading, Parsing, Ready
}

class GtfsIngestException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val MAX_REDIRECTS = 5

/** Path to an agency's ingested SQLite database, shared by the ingestor and every query screen. */
fun gtfsDbFile(filesDir: File, agency: GtfsAgency): File =
    File(filesDir, "gtfs/${agency.id}/transit.db")

/** ETag/Last-Modified as last seen for a successfully-downloaded feed -- either may be blank if the
 * server didn't send that particular header. */
private data class FeedMeta(val etag: String, val lastModified: String) {
    fun isEmpty() = etag.isBlank() && lastModified.isBlank()
}

/** Downloads, unzips, and bulk-loads an agency's GTFS static feed into a local SQLite database. */
class GtfsIngestor(private val filesDir: File) {
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
        val feedUrls = listOf(agency.feedUrl) + agency.additionalStaticFeedUrls
        val zipFiles = feedUrls.mapIndexed { index, _ ->
            File(agencyDir, if (index == 0) "gtfs.zip" else "gtfs-$index.zip")
        }
        val dbFile = gtfsDbFile(filesDir, agency)
        val metaFile = File(agencyDir, "feed_meta.txt")

        onStatus(GtfsIngestStatus.CheckingForUpdates)
        val cachedMeta = readFeedMeta(metaFile, feedUrls)
        val remoteMeta = try {
            feedUrls.map { checkForUpdate(it) }.takeIf { metas -> metas.all { it != null } }?.map { it!! }
        } catch (e: Exception) {
            Log.e("GtfsIngestor", "Feed update check failed for ${agency.displayName}, redownloading to be safe", e)
            null
        }

        val upToDate = dbFile.exists() && cachedMeta != null && remoteMeta != null &&
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
                parseAndLoad(zipFile, db, if (index == 0) "" else "feed$index:")
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
     * Some feeds (e.g. RIPTA) redirect through a plain-HTTP hop before landing on HTTPS.
     * Ktor's HttpRedirect plugin refuses to follow an HTTPS->HTTP downgrade by default (and
     * Android blocks cleartext traffic anyway), so it hands back the original redirect
     * response instead of an error. Redirects are followed manually here, upgrading any
     * http:// hop to https:// rather than ever actually connecting over plain HTTP.
     */
    private suspend fun downloadZip(url: String, destination: File) {
        val client = HttpClient(OkHttp) {
            followRedirects = false
        }
        try {
            var currentUrl = url
            repeat(MAX_REDIRECTS + 1) {
                val response = client.get(currentUrl)
                val status = response.status.value
                when {
                    status in 200..299 -> {
                        val bytes: ByteArray = response.body()
                        destination.writeBytes(bytes)
                        return
                    }
                    status in 300..399 -> {
                        val location = response.headers[HttpHeaders.Location]
                            ?: throw GtfsIngestException("GTFS download redirected without a Location header")
                        currentUrl = secureRedirectUrl(currentUrl, location)
                    }
                    else -> throw GtfsIngestException("GTFS download failed: HTTP $status")
                }
            }
            throw GtfsIngestException("GTFS download exceeded $MAX_REDIRECTS redirects")
        } finally {
            client.close()
        }
    }

    private fun parseAndLoad(zipFile: File, db: SQLiteDatabase, idPrefix: String) {
        db.beginTransaction()
        try {
            ZipFile(zipFile).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val loader = TABLE_LOADERS[entry.name.substringAfterLast('/')]
                    if (loader != null) {
                        archive.getInputStream(entry).reader(Charsets.UTF_8).buffered().use { reader ->
                            loader(db, reader, idPrefix)
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private val TABLE_LOADERS: Map<String, (SQLiteDatabase, BufferedReader, String) -> Unit> = mapOf(
            "routes.txt" to ::loadRoutes,
            "trips.txt" to ::loadTrips,
            "stops.txt" to ::loadStops,
            "stop_times.txt" to ::loadStopTimes,
            "calendar.txt" to ::loadCalendar,
            "calendar_dates.txt" to ::loadCalendarDates,
            "feed_info.txt" to ::loadFeedInfo,
            "agency.txt" to ::loadAgency,
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
}

private fun prefixedId(prefix: String, id: String?): String? = id?.takeIf { it.isNotEmpty() }?.let { prefix + it }

private fun loadRoutes(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String) {
    val stmt = db.compileStatement(
        """
        INSERT INTO routes
            (route_id, agency_id, route_short_name, route_long_name, route_desc, route_type, route_url, route_color, route_text_color)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(reader) { header, row ->
        val routeId = prefixedId(idPrefix, header.get(row, "route_id")) ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, routeId)
        stmt.bindStringOrNull(2, header.get(row, "agency_id"))
        stmt.bindStringOrNull(3, header.get(row, "route_short_name"))
        stmt.bindStringOrNull(4, header.get(row, "route_long_name"))
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
    readCsvEntry(reader) { header, row ->
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

private fun loadStops(db: SQLiteDatabase, reader: BufferedReader, idPrefix: String) {
    val stmt = db.compileStatement(
        """
        INSERT INTO stops
            (stop_id, stop_code, stop_name, stop_desc, stop_lat, stop_lon, zone_id, stop_url, location_type, parent_station, wheelchair_boarding)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(reader) { header, row ->
        val stopId = prefixedId(idPrefix, header.get(row, "stop_id")) ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, stopId)
        stmt.bindStringOrNull(2, header.get(row, "stop_code"))
        stmt.bindStringOrNull(3, header.get(row, "stop_name"))
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
    readCsvEntry(reader) { header, row ->
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
    readCsvEntry(reader) { header, row ->
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
    readCsvEntry(reader) { header, row ->
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
    readCsvEntry(reader) { header, row ->
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
    readCsvEntry(reader) { header, row ->
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