package com.thelightphone.transit.gtfs

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Mirrors the subset of the GTFS static spec this app ingests. trip_id, route_id, stop_id, and
 * service_id are the join/filter keys every later screen uses, so each gets an explicit index
 * except where it's already the leading column of a table's primary key.
 */
private object GtfsSchema {
    val STATEMENTS = listOf(
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
        "CREATE INDEX IF NOT EXISTS idx_trips_route_id ON trips(route_id)",
        "CREATE INDEX IF NOT EXISTS idx_trips_service_id ON trips(service_id)",
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
        "CREATE INDEX IF NOT EXISTS idx_stop_times_stop_id ON stop_times(stop_id)",
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
        "CREATE INDEX IF NOT EXISTS idx_calendar_dates_service_id ON calendar_dates(service_id)",
    )
}

/**
 * Opens (creating if needed) the SQLite database backing one agency's ingested GTFS data.
 *
 * Uses [SQLiteDatabase.openOrCreateDatabase]'s file-based entry point rather than
 * [android.database.sqlite.SQLiteOpenHelper] or Room, because both of those require an
 * android.content.Context, which isn't reachable from tool code (SealedLightContext keeps
 * its Context internal, and importing android.content.Context directly is blocked by the SDK
 * build plugin).
 */
fun openGtfsDatabase(dbFile: File): SQLiteDatabase {
    dbFile.parentFile?.mkdirs()
    val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    GtfsSchema.STATEMENTS.forEach { db.execSQL(it) }
    return db
}
