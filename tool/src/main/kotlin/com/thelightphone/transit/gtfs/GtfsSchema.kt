package com.thelightphone.transit.gtfs

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Bump when [GtfsSchema.STATEMENTS] changes in a way an existing database won't pick up. Ingest is
 * skipped while a feed is unchanged, so a mismatch here forces one full re-ingest.
 */
internal const val GTFS_SCHEMA_VERSION = 2

/**
 * The parts of the GTFS static spec this app ingests. trip_id, route_id, stop_id, and service_id
 * are indexed unless they already lead a primary key.
 *
 * Tables are created up front and indexes after loading, which is faster for bulk inserts.
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
        // feed_info.txt is optional, so agency.txt is a fallback for credits. Neither has a
        // single-row key, so the first row is used.
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
        // directions.txt is an optional extension giving each direction's rider-facing name
        // ("Inbound", "Northbound") and destination.
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
 * Opens or creates an agency's database with its tables; indexes come later from
 * [createGtfsIndexes]. Uses [SQLiteDatabase.openOrCreateDatabase] since tool code has no Context
 * for SQLiteOpenHelper or Room.
 *
 * Only the ingest's temp database is opened here, so `synchronous=NORMAL` applies only to that
 * connection. A crash mid-ingest just discards the temp file.
 */
fun openGtfsDatabase(dbFile: File): SQLiteDatabase {
    dbFile.parentFile?.mkdirs()
    val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    db.execSQL("PRAGMA synchronous = NORMAL")
    GtfsSchema.TABLE_STATEMENTS.forEach { db.execSQL(it) }
    return db
}

/** Builds the indexes once every row is loaded. */
fun createGtfsIndexes(db: SQLiteDatabase) {
    GtfsSchema.INDEX_STATEMENTS.forEach { db.execSQL(it) }
}
