package com.thelightphone.transit.gtfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegionalStopIdPrefixBridgeTest {

    private val bridge = RegionalStopIdPrefixBridge(prefix = "6")

    @Test
    fun stripsPrefixAndZeroPadding() {
        // Real VTA/511 pairs: same physical stop, matched by name and coordinates in both feeds.
        assertEquals("236", bridge.bridgeStopId("60236"))
        assertEquals("5522", bridge.bridgeStopId("65522"))
        assertEquals("3350", bridge.bridgeStopId("63350"))
    }

    @Test
    fun rejectsAnIdWithoutThePrefix() {
        assertNull(bridge.bridgeStopId("12345"))
    }

    @Test
    fun rejectsANonNumericRemainder() {
        assertNull(bridge.bridgeStopId("6EL_VIR"))
    }
}
