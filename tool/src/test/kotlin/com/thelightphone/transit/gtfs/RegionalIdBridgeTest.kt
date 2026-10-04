package com.thelightphone.transit.gtfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegionalIdBridgeTest {

    private val bridge = RegionalIdBridge(stopIdPrefix = "6")

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

    @Test
    fun mapsRouteShortNamesToRouteIds() {
        // Real pairs: 511's VTA route_ids next to VTA's own routes.txt (route_short_name -> route_id).
        val vtaRoutes = mapOf("Blue Line" to "Blue", "Orange Line" to "Ornge", "Rapid 522" to "522", "22" to "22")
        assertEquals("Blue", bridge.bridgeRouteId("Blue Line", vtaRoutes))
        assertEquals("Ornge", bridge.bridgeRouteId("Orange Line", vtaRoutes))
        assertEquals("522", bridge.bridgeRouteId("Rapid 522", vtaRoutes))
        assertEquals("22", bridge.bridgeRouteId("22", vtaRoutes))
        assertNull(bridge.bridgeRouteId("Unknown Line", vtaRoutes))
    }
}
