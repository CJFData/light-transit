package com.thelightphone.transit.gtfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GtfsRepositoryDedupTest {

    @Test
    fun groupsRealSouthStationPlatformsUnderTheStationRecord() {
        // Real rows from MBTA's static GTFS stops.txt: the "South Station" parent (location_type=1)
        // plus its six real child platform/entrance stop_ids (location_type=0, parent_station=
        // place-sstat), verified by hand-downloading the feed during development.
        val rows = listOf(
            RawStopRow("place-sstat", "South Station", 42.352271, -71.055242, parentStation = null),
            RawStopRow("70079", "South Station", 42.352271, -71.055242, parentStation = "place-sstat"),
            RawStopRow("70080", "South Station", 42.352271, -71.055242, parentStation = "place-sstat"),
            RawStopRow("74611", "South Station", 42.352271, -71.055242, parentStation = "place-sstat"),
            RawStopRow("74617", "South Station", 42.352271, -71.055242, parentStation = "place-sstat"),
            RawStopRow("84611", "South Station", 42.352271, -71.055242, parentStation = "place-sstat"),
            RawStopRow("NEC-2287", "South Station", 42.352271, -71.055242, parentStation = "place-sstat"),
        )

        val result = groupStationsByParent(rows)

        assertEquals(1, result.size, "six platforms + their parent should collapse to one entry")
        val station = result.single()
        assertEquals("place-sstat", station.stopId, "the parent station record represents the group")
        assertEquals("South Station", station.stopName)
        assertEquals(
            listOf("70079", "70080", "74611", "74617", "84611", "NEC-2287").sorted(),
            station.memberStopIds,
            "member ids must be every real child platform, for schedule lookups",
        )
    }

    @Test
    fun standaloneStopWithNoParentPassesThroughUnchanged() {
        val rows = listOf(
            RawStopRow("1234", "Some Bus Stop", 42.0, -71.0, parentStation = null),
        )

        val result = groupStationsByParent(rows)

        assertEquals(1, result.size)
        val stop = result.single()
        assertEquals("1234", stop.stopId)
        assertEquals(listOf("1234"), stop.memberStopIds)
    }

    @Test
    fun fallsBackToFirstChildWhenTheParentRecordItselfIsMissing() {
        // parent_station points at "place-ghost", but no row with that stop_id exists (e.g. the
        // station record has no coordinates and got filtered out upstream by the SQL WHERE clause).
        val rows = listOf(
            RawStopRow("child-b", "Ghost Stop", 42.1, -71.1, parentStation = "place-ghost"),
            RawStopRow("child-a", "Ghost Stop", 42.1, -71.1, parentStation = "place-ghost"),
        )

        val result = groupStationsByParent(rows)

        assertEquals(1, result.size)
        val stop = result.single()
        assertEquals("child-a", stop.stopId, "lowest stop_id chosen deterministically as fallback representative")
        assertEquals(listOf("child-a", "child-b"), stop.memberStopIds)
    }

    @Test
    fun multipleDistinctStationsEachGroupSeparately() {
        val rows = listOf(
            RawStopRow("place-sstat", "South Station", 42.35, -71.05, parentStation = null),
            RawStopRow("70079", "South Station", 42.35, -71.05, parentStation = "place-sstat"),
            RawStopRow("place-north", "North Station", 42.36, -71.06, parentStation = null),
            RawStopRow("70200", "North Station", 42.36, -71.06, parentStation = "place-north"),
            RawStopRow("70201", "North Station", 42.36, -71.06, parentStation = "place-north"),
            RawStopRow("99999", "Unrelated Bus Stop", 42.40, -71.10, parentStation = null),
        )

        val result = groupStationsByParent(rows)

        assertEquals(3, result.size)
        assertTrue(result.any { it.stopId == "place-sstat" && it.memberStopIds == listOf("70079") })
        assertTrue(result.any { it.stopId == "place-north" && it.memberStopIds == listOf("70200", "70201") })
        assertTrue(result.any { it.stopId == "99999" && it.memberStopIds == listOf("99999") })
    }
}
