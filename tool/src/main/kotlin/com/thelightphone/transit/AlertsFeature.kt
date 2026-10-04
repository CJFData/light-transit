package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.transit.gtfs.Alert
import com.thelightphone.transit.gtfs.AlertIndex
import com.thelightphone.transit.gtfs.AlertPreferences
import com.thelightphone.transit.gtfs.AlertsStore
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.RealtimeStopIdBridge
import com.thelightphone.transit.gtfs.bridgeStopIds
import com.thelightphone.transit.gtfs.StopGraph
import com.thelightphone.transit.gtfs.UNKNOWN_CAUSE
import com.thelightphone.transit.gtfs.label
import java.io.File
import java.time.ZoneId
import kotlin.math.abs
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first

/** Where alerts are shown, which decides which Settings toggles apply. */
enum class AlertSurface { HOME, MENUS, TRIP }

/** One agency's active alerts, ready for a screen to look up and display. [swipe] pages between
 * alerts by swiping instead of with buttons. */
class ScreenAlerts(
    val index: AlertIndex,
    val zoneId: ZoneId,
    val now: Long,
    private val appliesTo: Map<String, List<String>>,
    val swipe: Boolean,
) {
    fun appliesTo(alert: Alert): List<String> = appliesTo[alert.id].orEmpty()
}

/**
 * Alerts for [surface], or null when alerts are off or that surface is turned off in Settings.
 * Nothing is fetched in either case. A boarded trip's Trip Detail shows alerts even when "Show in
 * menus" is off. [repository] is null while the agency's schedule is still loading: stops and
 * routes can't be matched or named yet, so only alerts covering the whole agency are agency-wide.
 */
suspend fun loadScreenAlerts(
    dbFile: File,
    repository: GtfsRepository?,
    preferences: AlertPreferences,
    surface: AlertSurface,
    tripIsBoarded: Boolean = false,
): ScreenAlerts? {
    if (!preferences.enabledFlow.first()) return null
    val boardedOnly = preferences.boardedOnlyFlow.first()
    val shown = when (surface) {
        AlertSurface.HOME -> preferences.onHomeScreenFlow.first()
        AlertSurface.MENUS -> preferences.inMenusFlow.first() && !boardedOnly
        AlertSurface.TRIP -> tripIsBoarded || (preferences.inMenusFlow.first() && !boardedOnly)
    }
    if (!shown) return null
    val agency = GtfsAgency.forDbFile(dbFile) ?: return null
    val stopIdBridge = agency.component<RealtimeStopIdBridge>()
    val alerts = AlertsStore.shared.alertsFor(agency.id, agency.realtimeAlertsUrl, enabled = true)
        .let { fetched -> stopIdBridge?.let { fetched.bridgeStopIds(it::bridgeStopId) } ?: fetched }
    if (alerts.isEmpty()) return null

    val now = System.currentTimeMillis() / 1000
    val index = AlertIndex(alerts, repository?.getStopGraph() ?: StopGraph(emptyMap()), repository?.countRoutesWithTrips() ?: 0, now)
    val routeNames = repository?.getRouteNames(index.active.flatMap { a -> a.selectors.mapNotNull { it.routeId } }).orEmpty()
    val stopNames = repository?.getStopNames(index.active.flatMap { a -> a.selectors.mapNotNull { it.stopId } }).orEmpty()
    val appliesTo = index.active.associate { alert ->
        alert.id to alert.selectors.flatMap { s ->
            listOfNotNull(s.routeId?.let { routeNames[it] }, s.stopId?.let { stopNames[it] })
        }.distinct()
    }
    return ScreenAlerts(index, agency.zoneId, now, appliesTo, preferences.swipeFlow.first())
}

/** Alerts for a trip from [fromStopSequence] onward: ones naming the trip, its route and
 * direction, or one of its remaining stops. */
fun tripAlerts(screenAlerts: ScreenAlerts, repository: GtfsRepository, tripId: String, fromStopSequence: Int): List<Alert> =
    screenAlerts.index.forTrip(
        tripId,
        repository.getRouteIdForTrip(tripId),
        repository.getDirectionIdForTrip(tripId),
        repository.getTripStops(tripId, fromStopSequence).map { it.stopId },
    )

/** Alerts naming any of [stopIds] or the rest of their station, for a screen about that stop;
 * null when there are none to show. */
suspend fun loadStopAlerts(
    dbFile: File,
    repository: GtfsRepository,
    preferences: AlertPreferences,
    stopIds: List<String>,
): Pair<List<Alert>, ScreenAlerts>? = try {
    loadScreenAlerts(dbFile, repository, preferences, AlertSurface.MENUS)?.let { screenAlerts ->
        val station = stopIds.firstOrNull()?.let { repository.getStationContaining(it) }
        val alerts = (stopIds + station?.memberStopIds.orEmpty()).distinct()
            .flatMap { screenAlerts.index.forStopAnyRoute(it) }
            .distinct()
        alerts.takeIf { it.isNotEmpty() }?.let { it to screenAlerts }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e("AlertsFeature", "Failed to load stop alerts", e)
    null
}

/**
 * A top bar with the alert icon on the right, just before [rightButton], placed as on Trip Detail.
 * With no alerts it's the plain LightTopBar.
 */
@Composable
fun AlertsTopBar(
    title: String,
    onBack: () -> Unit,
    rightButton: LightBarButton.LightIcon?,
    alerts: List<Alert>,
    screenAlerts: ScreenAlerts?,
) {
    val leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack)
    if (screenAlerts == null || alerts.isEmpty()) {
        LightTopBar(leftButton = leftButton, center = LightTopBarCenter.Text(title), rightButton = rightButton)
        return
    }
    Box {
        LightTopBar(leftButton = leftButton, center = LightTopBarCenter.Text(title))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(3f.gridUnitsAsDp())
                .padding(horizontal = 1f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Spaced away from the right button so a tap meant for it doesn't open the alerts.
            AlertBadge(alerts, screenAlerts, Modifier.padding(end = 16.dp))
            rightButton?.let { button ->
                LightIcon(
                    icon = button.icon,
                    size = button.sizeUnits,
                    contentDescription = button.contentDescription,
                    modifier = Modifier.lightClickable { button.onClick?.invoke() },
                )
            }
        }
    }
}

fun showAlerts(alerts: List<Alert>, screenAlerts: ScreenAlerts) {
    if (alerts.isEmpty()) return
    LightModalManager.show(modal = AlertModal(alerts, screenAlerts), duration = Duration.INFINITE)
}

/** The alert icon, opening [alerts] in [AlertModal] unless [tappable] is off. [padding] is part of
 * the tap target; [modifier] spacing is outside it. */
@Composable
fun AlertBadge(
    alerts: List<Alert>,
    screenAlerts: ScreenAlerts?,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(12.dp),
    tappable: Boolean = true,
) {
    if (screenAlerts == null || alerts.isEmpty()) return
    LightIcon(
        icon = LightIcons.EMERGENCY,
        size = ALERT_ICON_SIZE,
        contentDescription = "Service alert",
        modifier = modifier.let { if (tappable) it.lightClickable { showAlerts(alerts, screenAlerts) } else it }.padding(padding),
    )
}

const val ALERT_ICON_SIZE = 1.2f

/** One dot per alert, between rewind/fast-forward buttons unless [swipe] is on. */
@Composable
fun AlertPageControls(count: Int, current: Int, onPage: (Int) -> Unit, swipe: Boolean, modifier: Modifier = Modifier) {
    if (count < 2) return
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!swipe) {
            LightIcon(
                icon = LightIcons.REWIND,
                size = 1.2f,
                contentDescription = "Previous alert",
                modifier = Modifier.lightClickable { onPage((current - 1 + count) % count) }.padding(12.dp),
            )
        }
        repeat(count) { page ->
            LightIcon(icon = if (page == current) LightIcons.SELECT_ON else LightIcons.SELECT_OFF, size = 0.6f, contentDescription = null)
        }
        if (!swipe) {
            LightIcon(
                icon = LightIcons.FAST_FORWARD,
                size = 1.2f,
                contentDescription = "Next alert",
                modifier = Modifier.lightClickable { onPage((current + 1) % count) }.padding(12.dp),
            )
        }
    }
}

private val SWIPE_DISTANCE = 48.dp

/** With [enabled], swiping left shows the next alert and swiping right the previous one. */
fun Modifier.alertSwipe(enabled: Boolean, count: Int, current: Int, onPage: (Int) -> Unit): Modifier =
    if (!enabled || count < 2) this else pointerInput(count, current) {
        var dragged = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = {
                if (abs(dragged) >= SWIPE_DISTANCE.toPx()) {
                    onPage(if (dragged < 0) (current + 1) % count else (current - 1 + count) % count)
                }
            },
        ) { change, amount ->
            change.consume()
            dragged += amount
        }
    }

/** The home screen's agency-wide alerts, one header at a time. Tapping one opens it. */
@Composable
fun HomeAlertsPager(alerts: List<Alert>, screenAlerts: ScreenAlerts, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) return
    var page by remember(alerts) { mutableIntStateOf(0) }
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .alertSwipe(screenAlerts.swipe, alerts.size, page, onPage = { page = it })
                .lightClickable { showAlerts(alerts.drop(page) + alerts.take(page), screenAlerts) },
        ) {
            LightIcon(icon = LightIcons.EMERGENCY, size = ALERT_ICON_SIZE, contentDescription = "Service alert", modifier = Modifier.padding(end = 12.dp))
            LightText(text = alerts[page].header, variant = LightTextVariant.Detail, maxLines = 3)
        }
        AlertPageControls(alerts.size, page, onPage = { page = it }, swipe = screenAlerts.swipe)
    }
}

private val CAUSE_LABELS = mapOf(
    2 to "Other cause", 3 to "Technical problem", 4 to "Strike", 5 to "Demonstration", 6 to "Accident",
    7 to "Holiday", 8 to "Weather", 9 to "Maintenance", 10 to "Construction", 11 to "Police activity",
    12 to "Medical emergency",
)
private const val APPLIES_TO_LIMIT = 4

/**
 * One alert's details. What it applies to is the title, with how long it lasts and its cause on one
 * grey line under it; then the header and the description. Without route or stop names, the header
 * becomes the title.
 */
@Composable
fun AlertDetails(alert: Alert, screenAlerts: ScreenAlerts) {
    val appliesTo = screenAlerts.appliesTo(alert)
    val title = if (appliesTo.isEmpty()) {
        alert.header
    } else {
        val more = if (appliesTo.size > APPLIES_TO_LIMIT) " +${appliesTo.size - APPLIES_TO_LIMIT} more" else ""
        appliesTo.take(APPLIES_TO_LIMIT).joinToString(", ") + more
    }
    val subtitle = listOfNotNull(
        alert.displayPeriod(screenAlerts.now)?.label(screenAlerts.zoneId, screenAlerts.now),
        CAUSE_LABELS[alert.cause]?.takeIf { alert.cause != UNKNOWN_CAUSE },
    ).joinToString(" · ")

    LightText(text = title, variant = LightTextVariant.Copy, modifier = Modifier.padding(bottom = 4.dp))
    if (subtitle.isNotEmpty()) {
        LightText(text = subtitle, variant = LightTextVariant.Detail, lighten = true)
    }
    Column(modifier = Modifier.padding(top = 24.dp)) {
        if (appliesTo.isNotEmpty()) {
            LightText(text = alert.header, variant = LightTextVariant.Paragraph, modifier = Modifier.padding(bottom = 16.dp))
        }
        alert.description.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            LightText(text = it, variant = LightTextVariant.Detail, modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}

/** Full alert details, with a page per alert and dots when there's more than one. */
class AlertModal(private val alerts: List<Alert>, private val screenAlerts: ScreenAlerts) : LightModal {
    private val dismissSignal = CompletableDeferred<Unit>()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(center = LightTopBarCenter.Text("Service Alert"))
                var page by remember { mutableIntStateOf(0) }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .alertSwipe(screenAlerts.swipe, alerts.size, page, onPage = { page = it }),
                ) {
                    AlertPage(alerts[page])
                }
                AlertPageControls(alerts.size, page, onPage = { page = it }, swipe = screenAlerts.swipe)
                LightBottomBar(
                    items = listOf(LightBarButton.LightIcon(icon = LightIcons.CLOSE, contentDescription = "Close", onClick = { dismiss() })),
                )
            }
        }
    }

    @Composable
    private fun AlertPage(alert: Alert) {
        LightScrollView(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            AlertDetails(alert, screenAlerts)
        }
    }

    override val onExpired: () -> Unit = {}

    override fun dismiss() {
        dismissSignal.complete(Unit)
    }

    override suspend fun awaitDismiss() = dismissSignal.await()
}
