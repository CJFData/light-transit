package com.thelightphone.transit.gtfs

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

private const val REFRESH_SECONDS = 60L

/**
 * Fetches and caches each agency's alerts. Nothing is fetched while alerts are turned off. A
 * failed fetch keeps the last good copy, or returns no alerts; it never surfaces an error.
 */
class AlertsStore(
    private val fetch: suspend (String) -> GtfsRtAlertFeedMessage = GtfsRealtimeClient::fetchAlertsFeed,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private class Entry(val fetchedAt: Long, val alerts: List<Alert>)

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun alertsFor(agencyId: String, alertsUrl: String?, enabled: Boolean): List<Alert> {
        if (!enabled || alertsUrl == null) return emptyList()
        val now = clock()
        val cached = cache[agencyId]
        if (cached != null && now - cached.fetchedAt < REFRESH_SECONDS) return cached.alerts
        return try {
            normalizeAlerts(fetch(alertsUrl), agencyId).also { cache[agencyId] = Entry(now, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached?.alerts ?: emptyList()
        }
    }

    companion object {
        val shared = AlertsStore()
    }
}
