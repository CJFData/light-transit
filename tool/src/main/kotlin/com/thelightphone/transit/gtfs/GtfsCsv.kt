package com.thelightphone.transit.gtfs

import android.database.sqlite.SQLiteStatement
import java.io.BufferedReader

/**
 * Minimal RFC 4180 line splitter: handles quoted fields, embedded commas, and "" as an escaped
 * quote. No CSV library is in the SDK's allowed-dependency list, and GTFS fields like
 * route_long_name/stop_name routinely contain commas, so a plain split(",") would corrupt rows.
 */
private fun parseCsvLine(line: String): List<String> {
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

/**
 * Maps a GTFS CSV row to column values by header name. GTFS doesn't guarantee column order or
 * that optional columns are present, so lookups go by name rather than fixed index.
 */
internal class GtfsCsvHeader(header: List<String>) {
    private val columnIndex: Map<String, Int> = header
        .mapIndexed { index, name -> name.trim().removePrefix("\uFEFF") to index }
        .toMap()

    fun get(row: List<String>, column: String): String? {
        val index = columnIndex[column] ?: return null
        return row.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Reads the header line from [reader] then invokes [onRow] for each data row until the current
 * zip entry ends. [reader] is never closed here — closing it would close the shared
 * ZipInputStream it wraps.
 */
internal inline fun readCsvEntry(reader: BufferedReader, onRow: (GtfsCsvHeader, List<String>) -> Unit) {
    val headerLine = reader.readLine() ?: return
    val header = GtfsCsvHeader(parseCsvLine(headerLine))
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) continue
        onRow(header, parseCsvLine(line))
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
