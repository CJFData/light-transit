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
import java.util.zip.ZipInputStream

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

    /**
     * Re-downloads only when the feed has actually changed: a HEAD request's ETag/Last-Modified is
     * compared against what was stored alongside the cached database from the last successful
     * download. Unchanged -> the existing SQLite database is used as-is, no network transfer beyond
     * the HEAD request. Changed, or nothing cached yet, or the check itself is inconclusive -> falls
     * back to a full re-download, since that's always safe (just not always necessary).
     */
    suspend fun ingest(agency: GtfsAgency, onStatus: (GtfsIngestStatus) -> Unit) {
        val agencyDir = File(filesDir, "gtfs/${agency.id}")
        agencyDir.mkdirs()
        val zipFile = File(agencyDir, "gtfs.zip")
        val dbFile = gtfsDbFile(filesDir, agency)
        val metaFile = File(agencyDir, "feed_meta.txt")

        onStatus(GtfsIngestStatus.CheckingForUpdates)
        val cachedMeta = readFeedMeta(metaFile)
        val remoteMeta = try {
            checkForUpdate(agency.feedUrl)
        } catch (e: Exception) {
            Log.e("GtfsIngestor", "Feed update check failed for ${agency.displayName}, redownloading to be safe", e)
            null
        }

        // hasCurrentSchema also gates "up to date" -- a cached database from before a table like
        // feed_info/agency existed in the schema needs a genuine re-download to actually populate
        // that table's real data, not just an empty CREATE TABLE IF NOT EXISTS patched on top of
        // it (GtfsRepository opens read-only with no migration of its own, so this ingest path is
        // the only place a schema change ever gets applied at all). Once this fires and rewrites
        // feed_meta.txt below, later launches recognize the now-current cache as up to date again
        // and go back to skipping the network re-download as usual.
        val upToDate = dbFile.exists() && cachedMeta != null && remoteMeta != null &&
            !cachedMeta.isEmpty() && cachedMeta == remoteMeta && hasCurrentSchema(dbFile)
        if (upToDate) {
            onStatus(GtfsIngestStatus.Ready)
            return
        }

        onStatus(GtfsIngestStatus.Downloading)
        downloadZip(agency.feedUrl, zipFile)

        onStatus(GtfsIngestStatus.Parsing)
        dbFile.delete()
        val db = openGtfsDatabase(dbFile)
        try {
            parseAndLoad(zipFile, db)
        } finally {
            db.close()
        }

        remoteMeta?.let { writeFeedMeta(metaFile, it) }
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
                        currentUrl = if (location.startsWith("http://")) {
                            "https://" + location.removePrefix("http://")
                        } else {
                            location
                        }
                    }
                    else -> return null
                }
            }
            return null
        } finally {
            client.close()
        }
    }

    /** Whether [dbFile] already has every table the current schema expects -- specifically
     * feed_info, the newest addition (see GtfsSchema). A cached database from before that table
     * existed would otherwise look "up to date" by ETag/Last-Modified alone forever, since nothing
     * about the feed itself changed; this forces exactly one real re-download to catch it up. */
    private fun hasCurrentSchema(dbFile: File): Boolean {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'feed_info'", null,
            ).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.e("GtfsIngestor", "Schema check failed for $dbFile, redownloading to be safe", e)
            false
        } finally {
            db.close()
        }
    }

    private fun readFeedMeta(file: File): FeedMeta? {
        if (!file.exists()) return null
        val lines = file.readLines()
        if (lines.size < 2) return null
        return FeedMeta(etag = lines[0], lastModified = lines[1])
    }

    private fun writeFeedMeta(file: File, meta: FeedMeta) {
        file.writeText("${meta.etag}\n${meta.lastModified}\n")
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
                        currentUrl = if (location.startsWith("http://")) {
                            "https://" + location.removePrefix("http://")
                        } else {
                            location
                        }
                    }
                    else -> throw GtfsIngestException("GTFS download failed: HTTP $status")
                }
            }
            throw GtfsIngestException("GTFS download exceeded $MAX_REDIRECTS redirects")
        } finally {
            client.close()
        }
    }

    private fun parseAndLoad(zipFile: File, db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val loader = TABLE_LOADERS[entry.name.substringAfterLast('/')]
                    if (loader != null) {
                        val reader = BufferedReader(zis.reader(Charsets.UTF_8))
                        loader(db, reader)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private val TABLE_LOADERS: Map<String, (SQLiteDatabase, BufferedReader) -> Unit> = mapOf(
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

private fun loadRoutes(db: SQLiteDatabase, reader: BufferedReader) {
    db.delete("routes", null, null)
    val stmt = db.compileStatement(
        """
        INSERT INTO routes
            (route_id, agency_id, route_short_name, route_long_name, route_desc, route_type, route_url, route_color, route_text_color)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(reader) { header, row ->
        val routeId = header.get(row, "route_id") ?: return@readCsvEntry
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

private fun loadTrips(db: SQLiteDatabase, reader: BufferedReader) {
    db.delete("trips", null, null)
    val stmt = db.compileStatement(
        """
        INSERT INTO trips
            (trip_id, route_id, service_id, trip_headsign, trip_short_name, direction_id, block_id, shape_id, wheelchair_accessible, bikes_allowed)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(reader) { header, row ->
        val tripId = header.get(row, "trip_id") ?: return@readCsvEntry
        val routeId = header.get(row, "route_id") ?: return@readCsvEntry
        val serviceId = header.get(row, "service_id") ?: return@readCsvEntry
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

private fun loadStops(db: SQLiteDatabase, reader: BufferedReader) {
    db.delete("stops", null, null)
    val stmt = db.compileStatement(
        """
        INSERT INTO stops
            (stop_id, stop_code, stop_name, stop_desc, stop_lat, stop_lon, zone_id, stop_url, location_type, parent_station, wheelchair_boarding)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(reader) { header, row ->
        val stopId = header.get(row, "stop_id") ?: return@readCsvEntry
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
        stmt.bindStringOrNull(10, header.get(row, "parent_station"))
        stmt.bindLongOrNull(11, header.get(row, "wheelchair_boarding"))
        stmt.executeInsert()
    }
}

private fun loadStopTimes(db: SQLiteDatabase, reader: BufferedReader) {
    db.delete("stop_times", null, null)
    val stmt = db.compileStatement(
        """
        INSERT INTO stop_times
            (trip_id, stop_sequence, arrival_time, departure_time, stop_id, stop_headsign, pickup_type, drop_off_type, shape_dist_traveled)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(reader) { header, row ->
        val tripId = header.get(row, "trip_id") ?: return@readCsvEntry
        val stopSequence = header.get(row, "stop_sequence")?.toLongOrNull() ?: return@readCsvEntry
        val stopId = header.get(row, "stop_id") ?: return@readCsvEntry
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

private fun loadCalendar(db: SQLiteDatabase, reader: BufferedReader) {
    db.delete("calendar", null, null)
    val stmt = db.compileStatement(
        """
        INSERT INTO calendar
            (service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday, start_date, end_date)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    )
    readCsvEntry(reader) { header, row ->
        val serviceId = header.get(row, "service_id") ?: return@readCsvEntry
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

private fun loadFeedInfo(db: SQLiteDatabase, reader: BufferedReader) {
    db.delete("feed_info", null, null)
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

private fun loadAgency(db: SQLiteDatabase, reader: BufferedReader) {
    db.delete("agency", null, null)
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

private fun loadCalendarDates(db: SQLiteDatabase, reader: BufferedReader) {
    db.delete("calendar_dates", null, null)
    val stmt = db.compileStatement(
        """
        INSERT INTO calendar_dates (service_id, date, exception_type)
        VALUES (?, ?, ?)
        """
    )
    readCsvEntry(reader) { header, row ->
        val serviceId = header.get(row, "service_id") ?: return@readCsvEntry
        val date = header.get(row, "date") ?: return@readCsvEntry
        val exceptionType = header.get(row, "exception_type")?.toLongOrNull() ?: return@readCsvEntry
        stmt.clearBindings()
        stmt.bindString(1, serviceId)
        stmt.bindString(2, date)
        stmt.bindLong(3, exceptionType)
        stmt.executeInsert()
    }
}
