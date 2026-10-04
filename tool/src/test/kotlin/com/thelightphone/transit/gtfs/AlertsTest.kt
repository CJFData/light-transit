@file:OptIn(ExperimentalSerializationApi::class)

package com.thelightphone.transit.gtfs

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/** Normalization and matching, against real alerts feeds (MBTA, RTD, King County Metro, and Muni's
 * slice of 511's regional feed) saved as fixtures, plus small hand-built alerts for each matching
 * rule. */
class AlertsTest {

    private fun feed(name: String): GtfsRtAlertFeedMessage {
        val bytes = javaClass.getResourceAsStream("/alerts/$name")!!.readBytes()
        return ProtoBuf.decodeFromByteArray(GtfsRtAlertFeedMessage.serializer(), bytes)
    }

    private fun alert(
        effect: Int = 8,
        periods: List<AlertPeriod> = emptyList(),
        vararg selectors: AlertSelector,
    ) = Alert("a", "test", "v", "Header", "", UNKNOWN_CAUSE, effect, 1, null, periods, selectors.toList())

    private fun sel(route: String? = null, stop: String? = null, dir: Int? = null, trip: String? = null, agency: String? = null) =
        AlertSelector(agency, null, route, dir, stop, trip)

    private val noStations = StopGraph(emptyMap())

    @Test
    fun decodesRealFeeds() {
        assertEquals(104, normalizeAlerts(feed("mbta_alerts.pb"), "mbta").size)
        assertEquals(141, normalizeAlerts(feed("rtd_alerts.pb"), "rtd").size)
        assertEquals(78, normalizeAlerts(feed("kcm_alerts.pb"), "kcm").size)
    }

    @Test
    fun decodesMtaFeedsWithTheirExtensions() {
        assertEquals(173, normalizeAlerts(feed("nyc_subway_alerts.pb"), "nyc_subway").size)
        assertEquals(230, normalizeAlerts(feed("nyc_bus_alerts.pb"), "nyc_bus_manhattan").size)
        assertEquals(14, normalizeAlerts(feed("nyc_lirr_alerts.pb"), "lirr").size)
        val mnr = normalizeAlerts(feed("nyc_mnr_alerts.pb"), "metro_north")
        assertEquals(14, mnr.size)
        assertTrue(mnr.flatMap { it.selectors }.all { it.agencyId == "MNR" })
    }

    @Test
    fun picksEnglishAndAllowsEmptyDescriptions() {
        val alerts = normalizeAlerts(feed("mbta_alerts.pb"), "mbta")
        assertTrue(alerts.all { it.header.isNotBlank() })
        assertFalse(alerts.any { it.header.startsWith("Para ") || it.header.startsWith("Para acomodar") })
        assertTrue(alerts.any { it.description.isEmpty() }, "MBTA sends some alerts with no description")
    }

    @Test
    fun recurringPeriodsCollapseToCurrentOrNext() {
        val recurring = normalizeAlerts(feed("mbta_alerts.pb"), "mbta").first { it.periods.size >= 3 && it.periods.all { p -> p.start != null && p.end != null } }
        val sorted = recurring.periods.sortedBy { it.start }
        val inFirst = sorted[0].start!! + 1
        assertEquals(sorted[0], recurring.displayPeriod(inFirst))
        assertEquals(AlertStatus.ACTIVE, recurring.status(inFirst))
        val betweenFirstAndSecond = sorted[0].end!! + 1
        if (betweenFirstAndSecond < sorted[1].start!!) {
            assertEquals(sorted[1], recurring.displayPeriod(betweenFirstAndSecond))
            assertEquals(AlertStatus.UPCOMING, recurring.status(betweenFirstAndSecond))
        }
        assertEquals(AlertStatus.EXPIRED, recurring.status(sorted.maxOf { it.end!! } + 1))
    }

    @Test
    fun longPeriodSpanningDays() {
        val start = 1_790_000_000L
        val end = start + 5 * 24 * 3600
        val a = alert(periods = listOf(AlertPeriod(start, end)))
        assertEquals(AlertStatus.ACTIVE, a.status(start + 3 * 24 * 3600))
        assertEquals(AlertStatus.UPCOMING, a.status(start - 1))
        assertEquals(AlertStatus.EXPIRED, a.status(end))
        assertTrue(AlertPeriod(start, end).label(ZoneId.of("America/Denver"), start + 60).startsWith("Until "))
        assertEquals("Ongoing", AlertPeriod(start, null).label(ZoneId.of("America/Denver"), start + 60))
        assertEquals(AlertStatus.ACTIVE, alert(periods = listOf(AlertPeriod(null, null))).status(0))
    }

    @Test
    fun stopExpandsAcrossStationAndPlatforms() {
        val graph = StopGraph(mapOf("NEC-1851-03" to "place-NEC-1851", "NEC-1851-01" to "place-NEC-1851"))
        val onStation = AlertIndex(listOf(alert(selectors = arrayOf(sel(stop = "place-NEC-1851")))), graph, 10, 0)
        assertEquals(1, onStation.forStop("NEC-1851-03").size)
        assertEquals(1, onStation.forStop("place-NEC-1851").size)
        val onPlatform = AlertIndex(listOf(alert(selectors = arrayOf(sel(stop = "NEC-1851-03")))), graph, 10, 0)
        assertEquals(1, onPlatform.forStop("place-NEC-1851").size, "a platform alert shows on its station")
        assertEquals(0, onPlatform.forStop("NEC-1851-01").size, "but not on sibling platforms")
    }

    @Test
    fun stopRouteVersusStopOnly() {
        val index = AlertIndex(
            listOf(
                alert(selectors = arrayOf(sel(stop = "S1"))).copy(id = "stopOnly"),
                alert(selectors = arrayOf(sel(stop = "S1", route = "R1"))).copy(id = "stopRoute"),
            ),
            noStations, 10, 0,
        )
        assertEquals(setOf("stopOnly"), index.forStop("S1").map { it.id }.toSet())
        assertEquals(setOf("stopOnly", "stopRoute"), index.forStop("S1", setOf("R1")).map { it.id }.toSet())
        assertEquals(setOf("stopOnly"), index.forStop("S1", setOf("R2")).map { it.id }.toSet())
    }

    @Test
    fun directionFiltering() {
        val index = AlertIndex(listOf(alert(effect = 4, selectors = arrayOf(sel(route = "R1", dir = 1)))), noStations, 10, 0)
        assertEquals(1, index.forDirection("R1", 1).size)
        assertEquals(0, index.forDirection("R1", 0).size)
        assertEquals(1, index.forRoute("R1").size)
    }

    @Test
    fun stopAndRouteAlertsBadgeBoth() {
        val stopRouteUnknownEffect = AlertIndex(listOf(alert(effect = 8, selectors = arrayOf(sel(stop = "S1", route = "R1")))), noStations, 10, 0)
        assertEquals(1, stopRouteUnknownEffect.forRoute("R1").size)
        assertEquals(1, stopRouteUnknownEffect.forStop("S1", setOf("R1")).size)
        val stopRouteDetour = AlertIndex(listOf(alert(effect = 4, selectors = arrayOf(sel(stop = "S1", route = "R1")))), noStations, 10, 0)
        assertEquals(1, stopRouteDetour.forRoute("R1").size)
        val routeOnlyUnknownEffect = AlertIndex(listOf(alert(effect = 8, selectors = arrayOf(sel(route = "R1")))), noStations, 10, 0)
        assertEquals(1, routeOnlyUnknownEffect.forRoute("R1").size)
    }

    @Test
    fun facilityAlertsNeverBadgeRoutes() {
        for (facilityEffect in listOf(7, 11)) {
            val index = AlertIndex(
                listOf(alert(effect = facilityEffect, selectors = arrayOf(sel(stop = "S1", route = "R1"), sel(route = "R1")))),
                noStations, 10, 0,
            )
            assertEquals(0, index.forRoute("R1").size)
            assertEquals(1, index.forStop("S1", setOf("R1")).size)
        }
    }

    @Test
    fun tripMatching() {
        val index = AlertIndex(
            listOf(
                alert(selectors = arrayOf(sel(trip = "T1"))).copy(id = "trip"),
                alert(selectors = arrayOf(sel(route = "R1", dir = 0))).copy(id = "routeDir"),
                alert(selectors = arrayOf(sel(stop = "S9"))).copy(id = "laterStop"),
                alert(selectors = arrayOf(sel(stop = "S0"))).copy(id = "earlierStop"),
            ),
            noStations, 10, 0,
        )
        val matched = index.forTrip("T1", "R1", 0, listOf("S5", "S9")).map { it.id }.toSet()
        assertEquals(setOf("trip", "routeDir", "laterStop"), matched)
        assertEquals(setOf("laterStop"), index.forTrip("T2", "R1", 1, listOf("S9")).map { it.id }.toSet())
    }

    @Test
    fun agencyWideRule() {
        assertTrue(isAgencyWide(alert(selectors = arrayOf(sel(agency = "1"))), 10))
        assertTrue(isAgencyWide(alert(selectors = (1..5).map { sel(route = "R$it") }.toTypedArray()), 10))
        assertFalse(isAgencyWide(alert(selectors = (1..4).map { sel(route = "R$it") }.toTypedArray()), 10))
        assertFalse(isAgencyWide(alert(selectors = arrayOf(sel(agency = "1"), sel(stop = "S1"))), 10))
    }

    @Test
    fun onlyActiveAlertsAreIndexed() {
        val upcoming = alert(periods = listOf(AlertPeriod(100, 200)), selectors = arrayOf(sel(stop = "S1")))
        assertEquals(0, AlertIndex(listOf(upcoming), noStations, 10, 50).forStop("S1").size)
        assertEquals(1, AlertIndex(listOf(upcoming), noStations, 10, 150).forStop("S1").size)
    }

    @Test
    fun realFeedsMatchAtTheirOwnTimestamp() {
        val mbtaFeed = feed("mbta_alerts.pb")
        val index = AlertIndex(normalizeAlerts(mbtaFeed, "mbta"), noStations, 200, mbtaFeed.header.timestamp)
        assertTrue(index.active.isNotEmpty(), "some MBTA alerts are active at the feed's own timestamp")
    }

    @Test
    fun turnedOffNeverFetches() = runBlocking {
        var fetches = 0
        val store = AlertsStore(fetch = { fetches++; feed("rtd_alerts.pb") }, clock = { 1_000L })
        assertTrue(store.alertsFor("rtd", "https://example.invalid/alerts", enabled = false).isEmpty())
        assertEquals(0, fetches)
        assertEquals(141, store.alertsFor("rtd", "https://example.invalid/alerts", enabled = true).size)
        assertEquals(1, fetches)
    }

    @Test
    fun failedFetchKeepsLastCopy() = runBlocking {
        var now = 1_000L
        var fail = false
        val store = AlertsStore(fetch = { if (fail) error("offline") else feed("kcm_alerts.pb") }, clock = { now })
        assertEquals(78, store.alertsFor("kcm", "u", enabled = true).size)
        fail = true
        now += 120
        assertEquals(78, store.alertsFor("kcm", "u", enabled = true).size)
        assertTrue(AlertsStore(fetch = { error("offline") }).alertsFor("kcm", "u", enabled = true).isEmpty())
    }

    @Test
    fun regionalSliceKeepsOnlyItsAgencyWithPrefixesStripped() {
        val alerts = normalizeAlerts(feed("sf511_muni_alerts.pb"), "sfmta_muni")
        assertEquals(23, alerts.size)
        val selectors = alerts.flatMap { it.selectors }
        assertTrue(selectors.all { it.agencyId == "SF" })
        assertFalse(selectors.any { it.routeId?.contains(':') == true || it.stopId?.contains(':') == true })
        assertTrue(alerts.any { isAgencyWide(it, 70) }, "The feed has a Muni-wide alert naming only the agency")
    }

    @Test
    fun firstPopupCheckIsSilentAndRecordsEverything() {
        val alerts = normalizeAlerts(feed("mbta_alerts.pb"), "mbta")
        val decision = decidePopups("mbta", alerts, alerts.map { it.id }.toSet(), emptyMap())
        assertTrue(decision.toShow.isEmpty())
        assertEquals("", decision.seen["mbta:"])
        assertTrue(alerts.all { decision.seen["mbta:${it.id}"] == it.version })

        val again = decidePopups("mbta", alerts, alerts.map { it.id }.toSet(), decision.seen)
        assertTrue(again.toShow.isEmpty(), "Nothing changed, so nothing pops")
    }

    @Test
    fun newOrRetitledOrRetimedAlertsPopButDescriptionEditsDont() {
        fun popAlert(id: String, header: String = "Shuttle buses", description: String = "", periods: List<AlertPeriod> = emptyList()) =
            normalizeAlerts(
                GtfsRtAlertFeedMessage(
                    header = GtfsRtFeedHeader(gtfsRealtimeVersion = "2.0"),
                    entity = listOf(
                        GtfsRtAlertEntity(
                            id = id,
                            alert = GtfsRtAlert(
                                activePeriod = periods.map { GtfsRtTimeRange(it.start, it.end) },
                                headerText = GtfsRtTranslatedString(listOf(GtfsRtTranslation(header, "en"))),
                                descriptionText = GtfsRtTranslatedString(listOf(GtfsRtTranslation(description, "en"))),
                            ),
                        ),
                    ),
                ),
                "test",
            ).single()

        val original = popAlert("a")
        val baseline = decidePopups("test", listOf(original), setOf("a"), emptyMap()).seen

        assertEquals(listOf("b"), decidePopups("test", listOf(original, popAlert("b")), setOf("a", "b"), baseline).toShow.map { it.id })
        assertEquals(1, decidePopups("test", listOf(popAlert("a", header = "No service")), setOf("a"), baseline).toShow.size)
        assertEquals(1, decidePopups("test", listOf(popAlert("a", periods = listOf(AlertPeriod(1L, 2L)))), setOf("a"), baseline).toShow.size)
        assertTrue(decidePopups("test", listOf(popAlert("a", description = "Now with more detail")), setOf("a"), baseline).toShow.isEmpty())
    }

    @Test
    fun seenEntriesLeavingTheFeedArePrunedPerAgency() {
        val seen = mapOf("mbta:" to "", "mbta:gone" to "v1", "mbta:kept" to "v2", "rtd:" to "", "rtd:gone" to "v3")
        val decision = decidePopups("mbta", emptyList(), setOf("kept"), seen)
        assertEquals(mapOf("mbta:" to "", "mbta:kept" to "v2", "rtd:" to "", "rtd:gone" to "v3"), decision.seen)
    }

    @Test
    fun idBridgeRewritesOnlyIdsItRecognizes() {
        val bridge = RegionalIdBridge(stopIdPrefix = "6")
        val routes = mapOf("Blue Line" to "Blue", "22" to "22")
        val bridged = listOf(alert(selectors = arrayOf(sel(stop = "60123"), sel(stop = "14658"), sel(route = "Blue Line"), sel(route = "Mystery"))))
            .bridgeIds(bridge::bridgeStopId) { bridge.bridgeRouteId(it, routes) }
            .single()
        assertEquals(listOf("123", "14658", null, null), bridged.selectors.map { it.stopId })
        assertEquals(listOf(null, null, "Blue", "Mystery"), bridged.selectors.map { it.routeId })
        assertEquals(1, AlertIndex(listOf(bridged), noStations, 72, 0).forRoute("Blue").size)
    }
}
