package com.thelightphone.transit.gtfs

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Bump whenever [GtfsSchema.STATEMENTS] changes in a way an already-ingested database on a user's
 * device won't pick up on its own. `CREATE TABLE IF NOT EXISTS` only runs during a real ingest
 * (see [openGtfsDatabase]/[GtfsIngestor]), and ingest is normally skipped whenever the remote
 * feed's ETag/Last-Modified hasn't changed -- so a cached database from before a schema change
 * would otherwise keep silently missing the new table/column, until that agency's feed happens to
 * publish an update for unrelated reasons. [GtfsIngestor] persists this value alongside each
 * cached feed's metadata and forces one full re-ingest whenever it doesn't match, independent of
 * the feed's own ETag/Last-Modified.
 */
internal const val GTFS_SCHEMA_VERSION = 2

/**
 * Mirrors the subset of the GTFS static spec this app ingests. trip_id, route_id, stop_id, and
 * service_id are the join/filter keys every later screen uses, so each gets an explicit index
 * except where it's already the leading column of a table's primary key.
 *
 * Split into [TABLE_STATEMENTS] and [INDEX_STATEMENTS] (rather than one flat list) so
 * [openGtfsDatabase] can create just the tables up front and [createGtfsIndexes] can build every
 * index once, after [GtfsIngestor] has finished inserting all of a table's rows -- building an index
 * incrementally across tens of thousands of inserts is a well-known SQLite bulk-load slowdown
 * compared to building it once against data that's already there. Same statements, same order, as
 * before this split -- only *when* each group runs changed.
 */
private object GtfsSchema {
    val TABLE_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS routes (
            route_id TEXT PRIMARY KEY,
            agency_id TEXT,
            route_short_name TEXT,
            route_long_name TEXT,
            route_desc TEXT,
            route_type INTEGER,
            route_url TEXT,
            route_color TEXT,
            route_text_color TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS trips (
            trip_id TEXT PRIMARY KEY,
            route_id TEXT NOT NULL,
            service_id TEXT NOT NULL,
            trip_headsign TEXT,
            trip_short_name TEXT,
            direction_id INTEGER,
            block_id TEXT,
            shape_id TEXT,
            wheelchair_accessible INTEGER,
            bikes_allowed INTEGER
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS stops (
            stop_id TEXT PRIMARY KEY,
            stop_code TEXT,
            stop_name TEXT,
            stop_desc TEXT,
            stop_lat REAL,
            stop_lon REAL,
            zone_id TEXT,
            stop_url TEXT,
            location_type INTEGER,
            parent_station TEXT,
            wheelchair_boarding INTEGER
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS stop_times (
            trip_id TEXT NOT NULL,
            stop_sequence INTEGER NOT NULL,
            arrival_time TEXT,
            departure_time TEXT,
            stop_id TEXT NOT NULL,
            stop_headsign TEXT,
            pickup_type INTEGER,
            drop_off_type INTEGER,
            shape_dist_traveled REAL,
            PRIMARY KEY (trip_id, stop_sequence)
        ) WITHOUT ROWID
        """,
        """
        CREATE TABLE IF NOT EXISTS calendar (
            service_id TEXT PRIMARY KEY,
            monday INTEGER,
            tuesday INTEGER,
            wednesday INTEGER,
            thursday INTEGER,
            friday INTEGER,
            saturday INTEGER,
            sunday INTEGER,
            start_date TEXT,
            end_date TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS calendar_dates (
            service_id TEXT NOT NULL,
            date TEXT NOT NULL,
            exception_type INTEGER NOT NULL,
            PRIMARY KEY (service_id, date)
        ) WITHOUT ROWID
        """,
        // feed_info.txt is optional per the GTFS spec, so agency.txt (required) is kept as a fallback
        // attribution source -- see GtfsRepository.getFeedAttribution. Neither table has a natural
        // single-row key (feed_info.txt has none at all; a feed can list several agency.txt rows), so
        // both are re-populated wholesale on each ingest, same as every other table here, and read
        // back as "whichever row comes first".
        """
        CREATE TABLE IF NOT EXISTS feed_info (
            feed_publisher_name TEXT,
            feed_publisher_url TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS agency (
            agency_name TEXT,
            agency_url TEXT
        )
        """,
        // Optional GTFS extension (MBTA publishes it; most agencies don't) giving the curated
        // rider-facing word for each direction -- "Inbound"/"Outbound", "Northbound"/"Southbound",
        // etc. -- and destination, since direction_id itself has no fixed meaning across routes.
        // (route_id, direction_id) is a guaranteed-unique key per the file's own spec.
        """
        CREATE TABLE IF NOT EXISTS directions (
            route_id TEXT NOT NULL,
            direction_id INTEGER NOT NULL,
            direction TEXT,
            direction_destination TEXT,
            PRIMARY KEY (route_id, direction_id)
        ) WITHOUT ROWID
        """,
    )

    val INDEX_STATEMENTS = listOf(
        "CREATE INDEX IF NOT EXISTS idx_trips_route_id ON trips(route_id)",
        "CREATE INDEX IF NOT EXISTS idx_trips_service_id ON trips(service_id)",
        "CREATE INDEX IF NOT EXISTS idx_stop_times_stop_id ON stop_times(stop_id)",
        "CREATE INDEX IF NOT EXISTS idx_calendar_dates_service_id ON calendar_dates(service_id)",
    )
}

/**
 * Opens (creating if needed) the SQLite database backing one agency's ingested GTFS data, with just
 * its tables created -- see [createGtfsIndexes] for the other half of [GtfsSchema]'s own statements,
 * called separately by [GtfsIngestor] once every row is actually in place.
 *
 * Uses [SQLiteDatabase.openOrCreateDatabase]'s file-based entry point rather than
 * [android.database.sqlite.SQLiteOpenHelper] or Room, since both require an
 * android.content.Context, which isn't reachable from tool code (SealedLightContext keeps its
 * Context internal, and importing android.content.Context directly is blocked by the SDK build
 * plugin).
 *
 * [SQLiteDatabase.openOrCreateDatabase] is only ever called here, for [GtfsIngestor]'s own scratch
 * `transit.db.tmp` -- every other reader opens the finished, already-renamed database completely
 * separately ([GtfsRepository]'s own `OPEN_READONLY` connection), so `synchronous=NORMAL` below is
 * scoped to this one ingest-only connection and never reaches a reader at all: SQLite's `synchronous`
 * pragma lives on the connection, not the file, so a later connection just gets the normal default
 * back automatically. Safe against the real crash [COMMIT_BATCH_SIZE] (`GtfsCsv.kt`) exists to
 * prevent -- that fix bounds how much data one open transaction holds in memory; this pragma only
 * changes how aggressively each already-happening commit flushes to disk, a different axis
 * entirely. The only real cost is durability if the process dies mid-write, which this app already
 * tolerates by design: a crash mid-ingest just leaves `transit.db.tmp` to be discarded and rebuilt
 * from scratch next time, whether or not its writes were fsynced -- the real `transit.db` is only
 * ever replaced by the atomic rename in [GtfsIngestor] once ingestion fully succeeds.
 */
fun openGtfsDatabase(dbFile: File): SQLiteDatabase {
    dbFile.parentFile?.mkdirs()
    val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    db.execSQL("PRAGMA synchronous = NORMAL")
    GtfsSchema.TABLE_STATEMENTS.forEach { db.execSQL(it) }
    return db
}

/** The other half of [GtfsSchema]'s own statements -- called once by [GtfsIngestor], after every zip
 * file's rows are already inserted into [db], never before. */
fun createGtfsIndexes(db: SQLiteDatabase) {
    GtfsSchema.INDEX_STATEMENTS.forEach { db.execSQL(it) }
}
