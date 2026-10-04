package com.thelightphone.transit.gtfs

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** GTFS-RT Alert.Effect values about a station's facilities, which only ever badge stops. */
private val FACILITY_EFFECTS = setOf(
    7, // OTHER_EFFECT
    11, // ACCESSIBILITY_ISSUE
)

const val UNKNOWN_CAUSE = 1

enum class AlertStatus { ACTIVE, UPCOMING, EXPIRED }

/** One active_period, in epoch seconds. A missing start means "since forever", a missing end
 * means open-ended. */
data class AlertPeriod(val start: Long?, val end: Long?) {
    fun contains(now: Long): Boolean = (start == null || start <= now) && (end == null || now < end)
}

/** One informed_entity. Its fields are ANDed together; an alert's selectors are ORed. */
data class AlertSelector(
    val agencyId: String?,
    val routeType: Int?,
    val routeId: String?,
    val directionId: Int?,
    val stopId: String?,
    val tripId: String?,
)

/** A GTFS-RT alert, normalized to plain values. [agencyId] is the app's own agency id. */
data class Alert(
    val id: String,
    val agencyId: String,
    val version: String,
    val header: String,
    val description: String,
    val cause: Int,
    val effect: Int,
    val severityLevel: Int,
    val url: String?,
    val periods: List<AlertPeriod>,
    val selectors: List<AlertSelector>,
) {
    /** Active if any period covers [now], upcoming if one starts later, otherwise expired. An
     * alert with no periods is active for as long as it's in the feed. */
    fun status(now: Long): AlertStatus = when {
        periods.isEmpty() || periods.any { it.contains(now) } -> AlertStatus.ACTIVE
        periods.any { it.start != null && it.start > now } -> AlertStatus.UPCOMING
        else -> AlertStatus.EXPIRED
    }

    /** The one period worth showing: the current one, else the next, else the most recent. Long
     * recurring lists collapse to this. */
    fun displayPeriod(now: Long): AlertPeriod? =
        periods.firstOrNull { it.contains(now) }
            ?: periods.filter { it.start != null && it.start > now }.minByOrNull { it.start!! }
            ?: periods.maxByOrNull { it.end ?: Long.MAX_VALUE }

    val isFacility: Boolean get() = effect in FACILITY_EFFECTS
}

/** Turns a decoded alerts feed into [Alert]s for [agencyId]. Deleted entities and entities with
 * no alert are skipped. */
fun normalizeAlerts(feed: GtfsRtAlertFeedMessage, agencyId: String): List<Alert> =
    feed.entity.mapNotNull { entity ->
        val alert = entity.alert ?: return@mapNotNull null
        if (entity.isDeleted == true) return@mapNotNull null
        val header = alert.headerText.preferredText().orEmpty()
        val description = alert.descriptionText.preferredText().orEmpty()
        val periods = alert.activePeriod.map { AlertPeriod(it.start, it.end) }
        val selectors = alert.informedEntity.map {
            AlertSelector(it.agencyId, it.routeType, it.routeId, it.directionId, it.stopId, it.trip?.tripId?.ifEmpty { null })
        }
        Alert(
            id = entity.id,
            agencyId = agencyId,
            version = alertVersion(header, periods),
            header = header,
            description = description,
            cause = alert.cause ?: UNKNOWN_CAUSE,
            effect = alert.effect ?: 8, // UNKNOWN_EFFECT
            severityLevel = alert.severityLevel ?: 1, // UNKNOWN_SEVERITY
            url = alert.url.preferredText(),
            periods = periods,
            selectors = selectors,
        )
    }

/** Rewrites each selector's stop_id and route_id, for agencies whose realtime ids differ from their
 * schedule's. An id the bridge can't convert is kept as it is. */
fun List<Alert>.bridgeIds(stopId: (String) -> String?, routeId: (String) -> String?): List<Alert> = map { alert ->
    alert.copy(
        selectors = alert.selectors.map { s ->
            s.copy(
                stopId = s.stopId?.let { raw -> stopId(raw) ?: raw },
                routeId = s.routeId?.let { raw -> routeId(raw) ?: raw },
            )
        },
    )
}

/** The English translation, falling back to the first one. */
private fun GtfsRtTranslatedString?.preferredText(): String? {
    val translations = this?.translation.orEmpty()
    return (translations.firstOrNull { it.language?.lowercase()?.startsWith("en") == true } ?: translations.firstOrNull())
        ?.text
}

/** A short hash that changes only when the header or time period changes; used to decide when a
 * seen alert pops up again. */
private fun alertVersion(header: String, periods: List<AlertPeriod>): String {
    val text = buildString {
        append(header).append('\u0000')
        periods.forEach { append(it.start).append('-').append(it.end).append(';') }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

/** Alerts to pop up now, and the updated seen versions to save. */
data class PopupDecision(val toShow: List<Alert>, val seen: Map<String, String>)

/**
 * Decides which [candidates] pop up for [agencyId]. [seen] maps "agencyId:alertId" to the version
 * last shown, plus an "agencyId:" marker once the agency has a baseline. The first check for an
 * agency records every candidate without showing any. After that, a candidate pops when it's new
 * or its version changed. Entries for alerts no longer in the feed ([feedAlertIds]) are dropped.
 */
fun decidePopups(agencyId: String, candidates: List<Alert>, feedAlertIds: Set<String>, seen: Map<String, String>): PopupDecision {
    val prefix = "$agencyId:"
    val updated = seen.filterKeys { key -> !key.startsWith(prefix) || key == prefix || key.removePrefix(prefix) in feedAlertIds }
        .toMutableMap()
    val isBaseline = prefix !in seen
    val toShow = if (isBaseline) emptyList() else candidates.filter { updated[prefix + it.id] != it.version }
    candidates.forEach { updated[prefix + it.id] = it.version }
    updated[prefix] = ""
    return PopupDecision(toShow, updated)
}

private val PERIOD_FORMAT = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

/** A period as readers see it, in the agency's own time zone. */
fun AlertPeriod.label(zoneId: ZoneId, now: Long): String {
    fun format(seconds: Long) = PERIOD_FORMAT.format(Instant.ofEpochSecond(seconds).atZone(zoneId))
    return when {
        end == null -> "Ongoing"
        start == null || start <= now -> "Until ${format(end)}"
        else -> "${format(start)} – ${format(end)}"
    }
}

/** Static GTFS parent/child stop links, for expanding a stop across its station and platforms. */
class StopGraph(private val parentOf: Map<String, String>) {
    private val childrenOf: Map<String, Set<String>> =
        parentOf.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }

    /** The stop itself, its child platforms, and its parent station (but not sibling platforms). */
    fun expand(stopId: String): Set<String> =
        buildSet {
            add(stopId)
            addAll(childrenOf[stopId].orEmpty())
            parentOf[stopId]?.let { add(it) }
        }
}

/** An alert shown at a stop. [routeId] is set for a stop + route selector, which only shows
 * where that route is in context. */
data class StopAlert(val alert: Alert, val routeId: String?)

/** Active alerts indexed for quick lookup by stop, route, direction and trip. */
class AlertIndex(alerts: List<Alert>, private val graph: StopGraph, agencyRouteCount: Int, now: Long) {
    val active: List<Alert> = alerts.filter { it.status(now) == AlertStatus.ACTIVE }
    /** Every alert id in the feed, active or not. */
    val feedIds: Set<String> = alerts.mapTo(HashSet()) { it.id }

    private val byStop = HashMap<String, MutableList<StopAlert>>()
    private val byRoute = HashMap<String, MutableList<Alert>>()
    private val byRouteDirection = HashMap<Pair<String, Int>, MutableList<Alert>>()
    val agencyWide: List<Alert> = active.filter { isAgencyWide(it, agencyRouteCount) }

    init {
        for (alert in active) {
            for (selector in alert.selectors) {
                if (selector.stopId != null) {
                    for (stop in graph.expand(selector.stopId)) {
                        byStop.getOrPut(stop) { mutableListOf() }.addUnique(StopAlert(alert, selector.routeId))
                    }
                }
                // A route named with a stop gets the alert too, except for facility alerts.
                val routeId = selector.routeId ?: continue
                if (alert.isFacility) continue
                if (selector.directionId != null) {
                    byRouteDirection.getOrPut(routeId to selector.directionId) { mutableListOf() }.addUnique(alert)
                } else {
                    byRoute.getOrPut(routeId) { mutableListOf() }.addUnique(alert)
                }
            }
        }
    }

    /** Alerts for a stop. Stop-only alerts always show; stop + route alerts show only when their
     * route is one of [routesInContext]. */
    fun forStop(stopId: String, routesInContext: Set<String> = emptySet()): List<Alert> =
        byStop[stopId].orEmpty()
            .filter { it.routeId == null || it.routeId in routesInContext }
            .map { it.alert }
            .distinct()

    /** Every alert naming a stop, with or without a route, for screens about the stop itself. */
    fun forStopAnyRoute(stopId: String): List<Alert> = byStop[stopId].orEmpty().map { it.alert }.distinct()

    fun forRoute(routeId: String): List<Alert> =
        (byRoute[routeId].orEmpty() + byRouteDirection.filterKeys { it.first == routeId }.values.flatten()).distinct()

    fun forDirection(routeId: String, directionId: Int): List<Alert> =
        (byRoute[routeId].orEmpty() + byRouteDirection[routeId to directionId].orEmpty()).distinct()

    /** Alerts for a trip being viewed or boarded: a selector naming the trip, its route (and
     * direction, if given), or one of [stopIds] from the boarding stop onward. */
    fun forTrip(tripId: String, routeId: String?, directionId: Int?, stopIds: List<String>): List<Alert> {
        val tripStops = stopIds.flatMap { graph.expand(it) }.toSet()
        return active.filter { alert ->
            alert.selectors.any { s ->
                when {
                    s.tripId != null -> s.tripId == tripId
                    s.stopId != null -> s.stopId in tripStops && (s.routeId == null || s.routeId == routeId)
                    s.routeId != null -> s.routeId == routeId && (s.directionId == null || s.directionId == directionId)
                    else -> false
                }
            }
        }
    }
}

/**
 * Whether an alert belongs on the home screen as agency-wide. Kept in one place so the rule is
 * easy to change: selectors naming only an agency and/or route type, or route-only selectors
 * covering at least half of the agency's routes.
 */
fun isAgencyWide(alert: Alert, agencyRouteCount: Int): Boolean {
    if (alert.selectors.isEmpty()) return false
    val broad = alert.selectors.all { it.stopId == null && it.tripId == null && it.routeId == null }
    if (broad) return true
    val routeOnly = alert.selectors.filter { it.stopId == null && it.tripId == null && it.routeId != null }
    if (routeOnly.size != alert.selectors.size || agencyRouteCount <= 0) return false
    return routeOnly.mapNotNull { it.routeId }.toSet().size * 2 >= agencyRouteCount
}

private fun <T> MutableList<T>.addUnique(item: T) {
    if (item !in this) add(item)
}
