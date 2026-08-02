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
            RawStopRow("place-sstat", "South Station", 42.352271, -71.055242, parentStation = null, locationType = 1),
            RawStopRow("70079", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 0),
            RawStopRow("70080", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 0),
            RawStopRow("74611", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 0),
            RawStopRow("74617", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 0),
            RawStopRow("84611", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 0),
            RawStopRow("NEC-2287", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 0),
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
        assertTrue(station.isStation, "a real location_type=1 record with 6 children must qualify as a station")
    }

    @Test
    fun excludesEntrancesAndPathwayNodesFromMemberStopIdsButKeepsBoardingAreas() {
        // Real shape from MBTA's South Station: real platforms (location_type=0), a boarding area
        // (location_type=4, e.g. a specific bus bay), a door that happens to be an elevator
        // (location_type=2, "door-sstat-deweyelev" in the real feed), and an escalator pathway node
        // (location_type=3, "node-382-lobby" in the real feed). Only the platform and boarding area
        // should end up as members -- the door and escalator node are not places a rider boards.
        val rows = listOf(
            RawStopRow("place-sstat", "South Station", 42.352271, -71.055242, parentStation = null, locationType = 1),
            RawStopRow("70079", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 0),
            RawStopRow("70080", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 0),
            RawStopRow("bus-bay-b1", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 4),
            RawStopRow("door-sstat-deweyelev", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 2),
            RawStopRow("node-382-lobby", "South Station", 42.352271, -71.055242, parentStation = "place-sstat", locationType = 3),
        )

        val result = groupStationsByParent(rows)

        assertEquals(1, result.size)
        assertEquals(
            listOf("70079", "70080", "bus-bay-b1"),
            result.single().memberStopIds,
            "entrances/elevators (location_type=2) and pathway nodes (location_type=3) must not be treated as boardable platforms",
        )
    }

    @Test
    fun fallsBackToAllChildrenWhenNoneAreRealPlatforms() {
        // A degenerate feed where every child under a parent is an entrance/pathway node -- rather
        // than produce a station with zero member stops (breaking every schedule lookup for it), fall
        // back to the unfiltered list so the station still resolves to something.
        val rows = listOf(
            RawStopRow("place-onlydoors", "Doors Only Station", 42.0, -71.0, parentStation = null, locationType = 1),
            RawStopRow("door-a", "Doors Only Station", 42.0, -71.0, parentStation = "place-onlydoors", locationType = 2),
            RawStopRow("node-b", "Doors Only Station", 42.0, -71.0, parentStation = "place-onlydoors", locationType = 3),
        )

        val result = groupStationsByParent(rows)

        assertEquals(listOf("door-a", "node-b"), result.single().memberStopIds)
    }

    @Test
    fun stopWithManyConvergingRoutesButNoStationRecordIsNotAStation() {
        // A stop many routes happen to converge at, but with no location_type=1 parent record
        // backing it -- per the Station sub-map rule, this must never qualify, no matter how many
        // routes serve it.
        val rows = listOf(
            RawStopRow("busy-stop", "Busy Corner", 42.0, -71.0, parentStation = null, locationType = 0),
        )

        val result = groupStationsByParent(rows)

        assertTrue(!result.single().isStation)
    }

    @Test
    fun stationRecordWithOnlyOneChildDoesNotQualify() {
        // A real location_type=1 record, but only a single child platform -- the rule requires 2+.
        val rows = listOf(
            RawStopRow("place-solo", "Lonely Station", 42.0, -71.0, parentStation = null, locationType = 1),
            RawStopRow("child-1", "Lonely Station", 42.0, -71.0, parentStation = "place-solo", locationType = 0),
        )

        val result = groupStationsByParent(rows)

        assertTrue(!result.single().isStation)
    }

    @Test
    fun fallbackPromotedRepresentativeIsNeverAStation() {
        // Same missing-parent-record scenario as fallsBackToFirstChildWhenTheParentRecordItselfIsMissing
        // below -- even with 2+ children, there's no real Station record to back it, so it must not
        // qualify as a station regardless of how many children point at the missing parent.
        val rows = listOf(
            RawStopRow("child-b", "Ghost Stop", 42.1, -71.1, parentStation = "place-ghost", locationType = 0),
            RawStopRow("child-a", "Ghost Stop", 42.1, -71.1, parentStation = "place-ghost", locationType = 0),
        )

        val result = groupStationsByParent(rows)

        assertTrue(!result.single().isStation)
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

    @Test
    fun platformLabelExtractsTheLastSegmentOfRealSouthStationStopDescs() {
        // Real stop_desc values from MBTA's static GTFS stops.txt for South Station's own child
        // platforms -- stop_name is identical ("South Station") across every one of these and can't
        // distinguish them, but stop_desc's last " - "-delimited segment names the specific platform.
        assertEquals("Ashmont/Braintree", platformLabelFromStopDesc("South Station - Red Line - Ashmont/Braintree"))
        assertEquals("Alewife", platformLabelFromStopDesc("South Station - Red Line - Alewife"))
        assertEquals(
            "SL2/SL3 Design Center/Chelsea",
            platformLabelFromStopDesc("South Station - Silver Line - SL2/SL3 Design Center/Chelsea"),
        )
        assertEquals("Track 1", platformLabelFromStopDesc("South Station - Commuter Rail - Track 1"))
        assertEquals("Track 13", platformLabelFromStopDesc("South Station - Commuter Rail - Track 13"))
    }

    @Test
    fun platformLabelFallsBackToNullWhenTheresNothingMoreSpecificToExtract() {
        assertEquals(null, platformLabelFromStopDesc(null))
        assertEquals(null, platformLabelFromStopDesc(""))
        assertEquals(null, platformLabelFromStopDesc("   "))
        // A single segment, no " - " separator at all -- nothing to split off as a platform.
        assertEquals(null, platformLabelFromStopDesc("South Station"))
    }
}
