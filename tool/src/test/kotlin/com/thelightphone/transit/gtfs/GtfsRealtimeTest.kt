@file:OptIn(ExperimentalSerializationApi::class)

package com.thelightphone.transit.gtfs

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Regression coverage for the fields RTD Denver's live GTFS-RT feed sends that MBTA/RIPTA never
 * did -- verified by hand-decoding RTD's real TripUpdate.pb/VehiclePosition.pb byte-for-byte during
 * development, the same way MBTA/RIPTA's own field numbers were originally verified (see
 * GtfsRealtime.kt's doc comments). Bytes here are hand-built to reproduce exactly that shape rather
 * than embedding a live feed snapshot, since raw feed content (trip ids, times) goes stale in
 * minutes. If any of these fields get un-declared again, this fails with a decode exception instead
 * of the failure only showing up as "RTD's live arrivals are silently missing."
 */
class GtfsRealtimeTest {

    @Test
    fun decodesTripUpdateWithRtdOnlyFields() {
        val tripDescriptor = ByteArrayOutputStream().apply {
            writeStringField(1, "115873037")
            writeVarintField(4, 0) // schedule_relationship -- undeclared before this fix
            writeStringField(5, "0")
            writeVarintField(6, 1) // direction_id -- undeclared before this fix
        }.toByteArray()

        val vehicleDescriptor = ByteArrayOutputStream().apply {
            writeStringField(1, "V123")
        }.toByteArray()

        val stopTimeUpdate = ByteArrayOutputStream().apply {
            writeVarintField(1, 10)
            writeStringField(4, "12535")
            writeVarintField(5, 0) // schedule_relationship -- undeclared before this fix
        }.toByteArray()

        val tripUpdate = ByteArrayOutputStream().apply {
            writeMessageField(1, tripDescriptor)
            writeMessageField(2, stopTimeUpdate)
            writeMessageField(3, vehicleDescriptor) // undeclared before this fix
            writeVarintField(4, 1786067248L) // timestamp -- undeclared before this fix
        }.toByteArray()

        val entity = ByteArrayOutputStream().apply {
            writeStringField(1, "test-entity")
            writeMessageField(3, tripUpdate)
        }.toByteArray()

        val feedMessage = ByteArrayOutputStream().apply {
            writeMessageField(1, minimalHeader())
            writeMessageField(2, entity)
        }.toByteArray()

        val decoded = ProtoBuf.decodeFromByteArray(GtfsRtFeedMessage.serializer(), feedMessage)

        val decodedTripUpdate = decoded.tripUpdatesByTripId["115873037"]
        assertNotNull(decodedTripUpdate, "TripUpdate with RTD-only fields must still decode")
        assertEquals(0, decodedTripUpdate.trip.scheduleRelationship)
        assertEquals(1, decodedTripUpdate.trip.directionId)
        assertEquals(1786067248L, decodedTripUpdate.timestamp)
        assertEquals("V123", decodedTripUpdate.vehicle?.id)
        assertEquals(1, decodedTripUpdate.stopTimeUpdate.size)
        assertEquals(0, decodedTripUpdate.stopTimeUpdate[0].scheduleRelationship)
    }

    @Test
    fun decodesVehiclePositionWithOccupancyStatus() {
        val tripDescriptor = ByteArrayOutputStream().apply {
            writeStringField(1, "115873037")
        }.toByteArray()

        val position = ByteArrayOutputStream().apply {
            writeFloatField(1, 39.7392f)
            writeFloatField(2, -104.9903f)
        }.toByteArray()

        val vehiclePosition = ByteArrayOutputStream().apply {
            writeMessageField(1, tripDescriptor)
            writeMessageField(2, position)
            writeVarintField(4, 2)
            writeVarintField(5, 1786067248L)
            writeVarintField(9, 1) // occupancy_status -- undeclared before this fix
        }.toByteArray()

        val entity = ByteArrayOutputStream().apply {
            writeStringField(1, "vp-entity")
            writeMessageField(4, vehiclePosition)
        }.toByteArray()

        val feedMessage = ByteArrayOutputStream().apply {
            writeMessageField(1, minimalHeader())
            writeMessageField(2, entity)
        }.toByteArray()

        val decoded = ProtoBuf.decodeFromByteArray(GtfsRtFeedMessage.serializer(), feedMessage)

        val decodedPosition = decoded.vehiclePositionsByTripId["115873037"]
        assertNotNull(decodedPosition, "VehiclePosition with occupancy_status must still decode")
        assertEquals(1, decodedPosition.occupancyStatus)
    }

    @Test
    fun decodesTripUpdateWithLtcOnlyFields() {
        val tripDescriptor = ByteArrayOutputStream().apply {
            writeStringField(1, "2331159")
        }.toByteArray()

        val arrival = ByteArrayOutputStream().apply {
            writeVarintField(2, 1786080480L)
            writeVarintField(4, 1786080123L) // second timestamp -- undeclared before this fix
        }.toByteArray()

        val stopTimeUpdate = ByteArrayOutputStream().apply {
            writeVarintField(1, 11)
            writeMessageField(2, arrival)
            writeStringField(4, "12535")
        }.toByteArray()

        // LTC's vendor bundle nests its own trip_id/start_date/start_time/shape_id -- content is
        // never read by this app, only its presence at TripUpdate field 6 matters for this test.
        val vendorTripProperties = ByteArrayOutputStream().apply {
            writeStringField(1, "2331159")
            writeStringField(4, "0")
        }.toByteArray()

        val tripUpdate = ByteArrayOutputStream().apply {
            writeMessageField(1, tripDescriptor)
            writeMessageField(2, stopTimeUpdate)
            writeMessageField(6, vendorTripProperties) // undeclared before this fix
            writeStringField(7, "") // undeclared before this fix
            writeStringField(8, "974747") // undeclared before this fix
        }.toByteArray()

        val entity = ByteArrayOutputStream().apply {
            writeStringField(1, "ltc-entity")
            writeMessageField(3, tripUpdate)
        }.toByteArray()

        val feedMessage = ByteArrayOutputStream().apply {
            writeMessageField(1, minimalHeader())
            writeMessageField(2, entity)
        }.toByteArray()

        val decoded = ProtoBuf.decodeFromByteArray(GtfsRtFeedMessage.serializer(), feedMessage)

        val decodedTripUpdate = decoded.tripUpdatesByTripId["2331159"]
        assertNotNull(decodedTripUpdate, "TripUpdate with LTC-only fields must still decode")
        assertEquals(1, decodedTripUpdate.stopTimeUpdate.size)
        assertEquals(1786080480L, decodedTripUpdate.stopTimeUpdate[0].arrival?.time)
        assertEquals(1786080123L, decodedTripUpdate.stopTimeUpdate[0].arrival?.unusedField4)
    }

    private fun minimalHeader(): ByteArray = ByteArrayOutputStream().apply {
        writeStringField(1, "2.0")
        writeVarintField(2, 0)
        writeVarintField(3, 1786067248L)
    }.toByteArray()
}

// --- Minimal hand-rolled protobuf wire-format writer, just enough to build the fixtures above. ---

private fun ByteArrayOutputStream.writeVarint(value: Long) {
    var remaining = value
    while (true) {
        val lowSevenBits = (remaining and 0x7F).toInt()
        remaining = remaining ushr 7
        if (remaining == 0L) {
            write(lowSevenBits)
            return
        }
        write(lowSevenBits or 0x80)
    }
}

private fun ByteArrayOutputStream.writeTag(fieldNumber: Int, wireType: Int) =
    writeVarint(((fieldNumber shl 3) or wireType).toLong())

private fun ByteArrayOutputStream.writeVarintField(fieldNumber: Int, value: Long) {
    writeTag(fieldNumber, wireType = 0)
    writeVarint(value)
}

private fun ByteArrayOutputStream.writeStringField(fieldNumber: Int, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeTag(fieldNumber, wireType = 2)
    writeVarint(bytes.size.toLong())
    write(bytes)
}

private fun ByteArrayOutputStream.writeMessageField(fieldNumber: Int, bytes: ByteArray) {
    writeTag(fieldNumber, wireType = 2)
    writeVarint(bytes.size.toLong())
    write(bytes)
}

private fun ByteArrayOutputStream.writeFloatField(fieldNumber: Int, value: Float) {
    writeTag(fieldNumber, wireType = 5)
    val bits = java.lang.Float.floatToIntBits(value)
    write(bits and 0xFF)
    write((bits ushr 8) and 0xFF)
    write((bits ushr 16) and 0xFF)
    write((bits ushr 24) and 0xFF)
}