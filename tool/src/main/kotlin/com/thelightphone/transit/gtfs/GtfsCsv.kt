package com.thelightphone.transit.gtfs

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.BufferedReader

/**
 * A minimal RFC 4180 line splitter: quoted fields, embedded commas, and "" escapes. Internal so the
 * shapes.txt reader can use it too.
 */
internal fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuotes -> when {
                c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> inQuotes = false
                else -> field.append(c)
            }
            c == '"' -> inQuotes = true
            c == ',' -> {
                fields.add(field.toString())
                field.clear()
            }
            else -> field.append(c)
        }
        i++
    }
    fields.add(field.toString())
    return fields
}

/** Looks up a row's values by column name, since GTFS doesn't fix column order. */
internal class GtfsCsvHeader(header: List<String>) {
    private val columnIndex: Map<String, Int> = header
        .mapIndexed { index, name -> name.trim().removePrefix("\uFEFF") to index }
        .toMap()

    fun get(row: List<String>, column: String): String? {
        val index = columnIndex[column] ?: return null
        return row.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Rows committed per transaction while reading a table. */
private const val COMMIT_BATCH_SIZE = 50_000

/**
 * Reads the header, then calls [onRow] for each row, committing every [COMMIT_BATCH_SIZE] rows. One
 * transaction for a very large table ran out of memory on the phone.
 *
 * [reader] isn't closed here, since that would close the shared zip stream.
 */
internal inline fun readCsvEntry(db: SQLiteDatabase, reader: BufferedReader, onRow: (GtfsCsvHeader, List<String>) -> Unit) {
    val headerLine = reader.readLine() ?: return
    val header = GtfsCsvHeader(parseCsvLine(headerLine))
    var rowsInBatch = 0
    db.beginTransaction()
    try {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            onRow(header, parseCsvLine(line))
            rowsInBatch++
            if (rowsInBatch >= COMMIT_BATCH_SIZE) {
                db.setTransactionSuccessful()
                db.endTransaction()
                db.beginTransaction()
                rowsInBatch = 0
            }
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

internal fun SQLiteStatement.bindStringOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindString(index, value)
}

internal fun SQLiteStatement.bindLongOrNull(index: Int, value: String?) {
    val parsed = value?.toLongOrNull()
    if (parsed == null) bindNull(index) else bindLong(index, parsed)
}

internal fun SQLiteStatement.bindDoubleOrNull(index: Int, value: String?) {
    val parsed = value?.toDoubleOrNull()
    if (parsed == null) bindNull(index) else bindDouble(index, parsed)
}
