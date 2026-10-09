package com.thelightphone.transit

import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.AgencyPreferences
import com.thelightphone.transit.gtfs.AttributionLegend
import com.thelightphone.transit.gtfs.AttributionPartner
import com.thelightphone.transit.gtfs.BoardedFuzzyRun
import com.thelightphone.transit.gtfs.BoardedFuzzyRunPreferences
import com.thelightphone.transit.gtfs.BoardedTrip
import com.thelightphone.transit.gtfs.BoardedTripPreferences
import com.thelightphone.transit.gtfs.FeedAttribution
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsCacheClearedSignal
import com.thelightphone.transit.gtfs.GtfsIngestor
import com.thelightphone.transit.gtfs.GtfsIngestStatus
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.Alert
import com.thelightphone.transit.gtfs.AlertPreferences
import com.thelightphone.transit.gtfs.GtfsRtStopTimeEvent
import com.thelightphone.transit.gtfs.GtfsRtStopTimeUpdate
import com.thelightphone.transit.gtfs.GtfsRtVehicleStatus
import com.thelightphone.transit.gtfs.FuzzyRunTrips
import com.thelightphone.transit.gtfs.LiveVehicleSource
import com.thelightphone.transit.gtfs.DefaultLocation
import com.thelightphone.transit.gtfs.LocationPreferences
import com.thelightphone.transit.gtfs.NetworkPreferences
import com.thelightphone.transit.gtfs.MultiGtfsFeed
import com.thelightphone.transit.gtfs.RegionalGroup
import com.thelightphone.transit.gtfs.StopPredictionSource
import com.thelightphone.transit.gtfs.fetchTripUpdate
import com.thelightphone.transit.gtfs.fetchVehiclePosition
import com.thelightphone.transit.gtfs.matchCurrentStopByProximity
import com.thelightphone.transit.gtfs.matchCurrentStopByShapeProjection
import com.thelightphone.transit.gtfs.gtfsZipFile
import com.thelightphone.transit.gtfs.TripPositionAnchor
import com.thelightphone.transit.gtfs.TripShapeSource
import com.thelightphone.transit.gtfs.HomeScreenPreferences
import com.thelightphone.transit.gtfs.TripStopRow
import com.thelightphone.transit.gtfs.computeArrivalEta
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.transit.gtfs.gtfsDbFile
import com.thelightphone.transit.gtfs.todayForGtfs
import android.Manifest
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

// Agency-row icon size, also used for the blank placeholder so names line up.
private const val AGENCY_ICON_SIZE = 1f

// The home screen clock, in the selected agency's time zone.
private val CLOCK_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")

/**
 * A short message on the home screen, picked by the day, or at random when that's turned on. TODO:
 * agency-specific, holiday, seasonal, or weather messages.
 */
private val DAILY_MESSAGES = listOf(
    // Transit-themed
    "Buses beat traffic. You beat the crowd. 🚌✨",
    "One ride, zero emissions guilt. 🌱🚏",
    "Transit day! Your commute, your planet. 🌍🚆",
    "Skip the parking hunt — hop a bus! 🎯🚌",
    "Every ride you take is a tree's thank-you. 🌳💚",
    "Rails not roadblocks today. 🚈☀️",
    "You + transit = fewer cars, cleaner air. 🌤️🚏",
    "Ride smart, arrive relaxed. 🚌😌",
    "Public transit: your daily eco win. 🌱🚉",
    "Someone else drives, you just vibe. 🎧🚆",
    "Small ride, big difference. 🌍🚌",
    "Transit today, cleaner tomorrow. ✨🚏",
    "Buses: the original carpool. 🚌🤝",
    "Hop on — the planet says thanks. 🌎💛",
    "Fewer cars, more sky. Nice work. ☁️🚈",
    "Riding today keeps the air a little brighter. 🌤️🚌",
    "You made a great choice this morning. 🌅🚏",
    "Transit riders make cities breathe easier. 🌬️🚆",
    "Zero traffic stress, all aboard energy. 🚌🎉",
    "Good for you, good for the block. 🏙️💚",
    "Bus arrives, stress doesn't. 🚌🍃",
    "Window seat, no steering wheel required. 🪟🚌",
    "Your commute just got a plot twist: relaxation. 📖🚆",
    "Miles logged, hands free. 🚏🙌",
    "Transit: because red lights aren't your problem today. 🚦🚌",
    "You + a bus seat = productivity mode unlocked. 💻🚆",
    "Less idling, more arriving. 🚌⏱️",
    "One swipe, zero traffic reports. 📻🚌",
    // General positive
    "Today's a good day to try. 🌱✨",
    "You've got this. 💪🌤️",
    "Small wins add up. 🌟",
    "Today's a fresh page. 📖🌤️",
    "Simple moments matter too. ☕✨",
    "You showed up — that's enough. 🌱",
    "Keep going — it's working. 🌤️🌱",
    "Good things are still coming. 🌅",
    "One step at a time works fine. 👣🌿",
    "Today counts, even the quiet parts. 🌙",
    // Light sass
    "You're welcome, gridlock. 😏🚌",
    "Cars stuck. You: not stuck. 😌✨",
    "Congrats on skipping the parking drama. 💅🎯",
    "Look at you, being efficient. 💁‍♀️🚏",
    "Not to brag, but you're basically eco-royalty. 👑🌱",
    "Traffic's problem, not yours today. 😏🚆",
    "Main character energy: took the bus. 🎬🚌",
    "You out here saving the planet, no big deal. 💁🌍",
    "Look who's not circling the block for parking. 😏🅿️",
    "Bus driver's problem now, not yours. 😌🚌",
    "You skipped the gas station and the guilt. ⛽😏",
    "Meanwhile, cars are still looking for parking. 😌🚗",
    "Your carbon footprint just filed a complaint — too small. 🐾😏",
    "Riding transit: the flex nobody asked for. 💁🚆",
)


private fun dailyMessage(random: Boolean): String {
    if (random) return DAILY_MESSAGES.random()
    val dayOfYear = LocalDate.now().dayOfYear
    return DAILY_MESSAGES[dayOfYear % DAILY_MESSAGES.size]
}



/** Whether Home is the current screen, so the footer's home button can hide itself there. */
object HomeVisibility {
    val isVisible = MutableStateFlow(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

// How often the boarded trip's live position is polled.
private const val LIVE_VEHICLE_POLL_INTERVAL_MS = 10_000L

/**
 * The boarded trip's progress and ETA for the home screen. Fields are null until there's live data
 * and an alight stop.
 */

data class ActiveTripStatus(
    val routeLabel: String,
    val alightStopName: String?,
    val etaEpochSeconds: Long?,
    /** Stops left from the vehicle's next stop to the alight stop. */
    val stopsRemaining: Int?,
    /** Progress from the boarding stop (0) to the alight stop (1), for the progress bar. */
    val progressFraction: Float?,
    /** The trip's agency time zone, for showing the ETA. */
    val zoneId: ZoneId,
    /** True when this came from a closest match rather than a confirmed position. */
    val isClosestMatch: Boolean = false,
)

private fun Long.asClockTime(zoneId: ZoneId): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(this), zoneId)
    return formatGtfsTime("%02d:%02d:00".format(time.hour, time.minute))
}

fun ActiveTripStatus.headingSubtitle(): String {
    // "~" marks a closest match on this one compact line.
    val approxPrefix = if (isClosestMatch) "~" else ""
    val stopsToDest = when {
        stopsRemaining == null -> null
        stopsRemaining <= 0 -> alightStopName?.let { "Arriving at $it" } ?: "Arriving"
        else -> {
            val stopsWord = if (stopsRemaining == 1) "stop" else "stops"
            alightStopName?.let { "$approxPrefix$stopsRemaining $stopsWord to $it" } ?: "$approxPrefix$stopsRemaining $stopsWord away"
        }
    }
    val etaText = etaEpochSeconds?.let { "${approxPrefix}ETA ${it.asClockTime(zoneId)}" }
    return listOfNotNull(stopsToDest, etaText).joinToString(" · ").ifBlank {
        if (alightStopName != null) {
            "Boarded"
        } else {
            "Boarded · tap a stop on Trip Detail to set where you're getting off"
        }
    }
}

class HomeScreenViewModel(
    private val filesDir: File,
    private val preferences: AgencyPreferences,
    private val boardedTripPreferences: BoardedTripPreferences,
    private val boardedFuzzyRunPreferences: BoardedFuzzyRunPreferences,
    private val homeScreenPreferences: HomeScreenPreferences,
    private val connectivity: LightConnectivity,
    private val networkPreferences: NetworkPreferences,
    private val locationPreferences: LocationPreferences,
    private val alertPreferences: AlertPreferences,
) : LightViewModel<Unit>() {

    private val ingestor = GtfsIngestor(filesDir, connectivity, networkPreferences)

    /** The trip the rider is on, if any, kept current so Board and Alight taps show right away. */
    val boardedTrip = MutableStateFlow<BoardedTrip?>(null)

    /** The rider's Select Run pick, kept current like [boardedTrip]. */
    val boardedFuzzyRun = MutableStateFlow<BoardedFuzzyRun?>(null)

    /** Live progress toward the boarded trip's alight stop; null when nothing's boarded. */
    val activeTripStatus = MutableStateFlow<ActiveTripStatus?>(null)

    val progressBarVisible = MutableStateFlow(true)

    val dailyMessageVisible = MutableStateFlow(true)

    val dailyMessageRandom = MutableStateFlow(false)

    /**
     * The message, picked once per visit so a random one doesn't change while the screen is open.
     */
    val dailyMessageText = MutableStateFlow(dailyMessage(random = false))

    /**
     * Set when the rider dismisses the "you've reached your stop" message on this screen; Content()
     * then opens that stop's arrivals and clears it.
     */
    val reachedAlightStop = MutableStateFlow<Pair<GtfsAgency, TripStopRow>?>(null)

    /** Wakes the trip-status poll early when the boarded trip changes. */
    private val tripStatusRefreshTrigger = Channel<Unit>(Channel.CONFLATED)
    private var tripStatusPollJob: Job? = null

    /** The home screen's alerts; null when there are none to show. */
    val homeAlerts = MutableStateFlow<Pair<List<Alert>, ScreenAlerts>?>(null)
    private var alertsPollJob: Job? = null

    /**
     * The selected agency. This and the fields below are declared before init because its
     * collectors can run during construction.
     */
    val selectedAgency = MutableStateFlow<GtfsAgency?>(null)
    /** Ingest failure text, if any. */
    val status = MutableStateFlow<String?>(null)

    /** Set once the agency's schedule is ready; enables Schedule and Explore. */
    val readyAgency = MutableStateFlow<GtfsAgency?>(null)
    private var agencyIngestJob: Job? = null

    /**
     * True when the download was skipped by "Only download over Wi-Fi", so reconnecting to Wi-Fi
     * retries it.
     */
    private val waitingForWifi = MutableStateFlow(false)

    /** The ticking clock text; restarted when the agency (and time zone) changes. */
    val currentTime = MutableStateFlow("")
    private var currentTimeJob: Job? = null

    /** Whether the agency has multi-platform stations, to show the Station button. */
    val agencyHasStations = MutableStateFlow(false)

    /**
     * Data credits for the selected agency, its extra feeds, and any additional schedules in its
     * region, with duplicates removed.
     */
    val feedAttribution = MutableStateFlow<List<FeedAttribution>>(emptyList())

    /**
     * Additional schedules the rider has downloaded, for the credits and the Schedule button's
     * picker.
     */
    val additionalDownloads = MutableStateFlow<Set<GtfsAgency>>(emptySet())

    init {
        viewModelScope.launch {
            boardedTripPreferences.boardedTripFlow.collect { newTrip ->
                // Reset the shared stop anchor only when the boarded trip itself changes.
                if (newTrip?.tripId != boardedTrip.value?.tripId) {
                    TripPositionAnchor.clear()
                }
                boardedTrip.value = newTrip
                if (newTrip == null) activeTripStatus.value = null
                tripStatusRefreshTrigger.trySend(Unit)
            }
        }
        viewModelScope.launch {
            boardedFuzzyRunPreferences.boardedFuzzyRunFlow.collect {
                boardedFuzzyRun.value = it
                tripStatusRefreshTrigger.trySend(Unit)
            }
        }
        viewModelScope.launch {
            boardedTripPreferences.progressBarVisibleFlow.collect { progressBarVisible.value = it }
        }
        viewModelScope.launch {
            homeScreenPreferences.dailyMessageRandomFlow.collect { dailyMessageRandom.value = it }
        }
        viewModelScope.launch {
            homeScreenPreferences.dailyMessageVisibleFlow.collect { dailyMessageVisible.value = it }
        }
        // Selects the saved default agency, or opens the picker when there isn't one. Also handles
        // a switch made in Settings.
        viewModelScope.launch {
            preferences.defaultAgencyFlow.collect { default ->
                when {
                    default != null && default != selectedAgency.value -> selectAgency(default)
                    default == null && selectedAgency.value == null -> showAgencyPicker()
                }
            }
        }
        viewModelScope.launch {
            preferences.additionalDownloadsFlow.collect { extras ->
                additionalDownloads.value = extras
                refreshFeedAttribution()
            }
        }
        // Clearing the schedule cache keeps the same agency, so re-download it once its files are
        // gone.
        viewModelScope.launch {
            GtfsCacheClearedSignal.version.drop(1).collect {
                selectedAgency.value?.let { agency -> selectAgency(agency) }
            }
        }
        // Retry a download skipped by "Only download over Wi-Fi" once Wi-Fi connects.
        viewModelScope.launch {
            connectivity.observeNetworkStatus()
                .map { it.isWifi }
                .distinctUntilChanged()
                .drop(1)
                .collect { isWifi ->
                    if (isWifi && waitingForWifi.value) {
                        selectedAgency.value?.let { agency -> selectAgency(agency) }
                    }
                }
        }
        // Restarted on each agency change, since the time zone changes too.
        viewModelScope.launch {
            selectedAgency.collect { agency ->
                currentTimeJob?.cancel()
                if (agency == null) {
                    currentTime.value = ""
                    return@collect
                }
                currentTimeJob = viewModelScope.launch {
                    while (isActive) {
                        currentTime.value = LocalTime.now(agency.zoneId).format(CLOCK_FORMATTER)
                        delay(60_000L)
                    }
                }
            }
        }
    }

    /**
     * Shows the agency picker when no agency is saved. Picking one saves it, and the default-agency
     * collector selects it.
     */
    private fun showAgencyPicker() {
        viewModelScope.launch {
            if (homeScreenPreferences.dataNoticeSeenFlow.first()) {
                showAgencyPickerModal()
            } else {
                // The "Your data" notice comes first, once; continuing opens the picker.
                LightModalManager.show(
                    modal = YourDataModal(
                        onContinue = {
                            viewModelScope.launch {
                                homeScreenPreferences.setDataNoticeSeen()
                                showAgencyPickerModal()
                            }
                        },
                    ),
                    duration = Duration.INFINITE,
                )
            }
        }
    }

    private fun showAgencyPickerModal() {
        LightModalManager.show(
            modal = AgencyPickerModal(
                filesDir = filesDir,
                allowCancel = false,
                onAgencySelected = { agency -> viewModelScope.launch { preferences.setDefaultAgency(agency) } },
            ),
            duration = Duration.INFINITE,
        )
    }

    fun clearReachedAlightStop() {
        reachedAlightStop.value = null
    }

    /**
     * Called when Explore is tapped: unless location is off or Explore will open on a saved place,
     * starts warming up GPS, and asks for permission if it's never been asked.
     */
    fun primeLocation(requestPermission: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!locationPreferences.locationEnabledFlow.first()) return@launch
            if (locationPreferences.defaultLocationFlow.first() is DefaultLocation.Place) return@launch
            callRemoteServiceMethod(LightServiceMethod.RequestLocationUpdates, Unit)
            val result = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION).getOrNull()?.permissionResult
            if (result == LightServiceMethod.GetPermission.Result.Unknown) {
                withContext(Dispatchers.Main) { requestPermission() }
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        HomeVisibility.isVisible.value = true
        // A new message each time the screen is shown.
        dailyMessageText.value = dailyMessage(dailyMessageRandom.value)
        tripStatusPollJob?.cancel()
        tripStatusPollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (boardedTrip.value != null) refreshActiveTripStatus()
                withTimeoutOrNull(LIVE_VEHICLE_POLL_INTERVAL_MS) { tripStatusRefreshTrigger.receive() }
            }
        }
        alertsPollJob?.cancel()
        alertsPollJob = viewModelScope.launch(Dispatchers.IO) {
            // No polling while alerts are off. Restarts when the agency changes and when its
            // schedule finishes loading.
            combine(alertPreferences.enabledFlow, selectedAgency, readyAgency) { enabled, _, _ -> enabled }.collectLatest { enabled ->
                homeAlerts.value = null
                if (!enabled) {
                    homeAlerts.value = null
                    return@collectLatest
                }
                while (isActive) {
                    refreshHomeAlerts()
                    delay(60_000L)
                }
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        HomeVisibility.isVisible.value = false
        tripStatusPollJob?.cancel()
        tripStatusPollJob = null
        alertsPollJob?.cancel()
        alertsPollJob = null
    }

    /** The boarded trip's alerts while one is boarded; otherwise agency-wide alerts, unless "Show
     * only for boarded trips" is on. */
    private suspend fun refreshHomeAlerts() {
        val agency = selectedAgency.value ?: return
        try {
            homeAlerts.value = homeScreenAlerts(agency, filesDir, alertPreferences, boardedTrip.value, AlertSurface.HOME)
                ?.takeIf { (alerts, _) -> alerts.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HomeScreen", "Alerts refresh failed", e)
        }
    }

    /**
     * Refreshes the boarded trip's position, progress, and ETA. Leaves the last status in place if
     * a lookup fails.
     */
    private suspend fun refreshActiveTripStatus() {
        val trip = boardedTrip.value ?: return
        val dbFile = gtfsDbFile(filesDir, trip.agency)
        val repository = GtfsRepository(dbFile)
        try {
            val alightStopId = trip.alightStopId
            val alightStop = alightStopId?.let { id ->
                repository.getTripStops(trip.tripId, trip.fromStopSequence).let { stops ->
                    val stop = stops.find { it.stopId == id } ?: return@let null
                    stop to stops
                }
            }

            if (alightStopId == null || alightStop == null) {
                activeTripStatus.value = ActiveTripStatus(
                    routeLabel = trip.routeLabel, alightStopName = null, etaEpochSeconds = null,
                    stopsRemaining = null, progressFraction = null, zoneId = trip.agency.zoneId,
                )
                return
            }
            val (stop, stops) = alightStop

            val vehicle = trip.agency.fetchVehiclePosition(trip.tripId, repository)
            val tripUpdate = trip.agency.fetchTripUpdate(trip.tripId, repository)

            // An agency without GTFS-RT may still locate the vehicle through its own live source.
            val liveVehicleSource = trip.agency.component<LiveVehicleSource>()
                ?.takeIf { source -> trip.lineType != null && trip.lineType in source.coveredLineTypes }
            val stopPredictionSource = trip.agency.component<StopPredictionSource>()
            // Closest-match tracking, for trips no live vehicle matches directly.
            val fuzzyRunTrips = trip.agency.component<FuzzyRunTrips>()
            val routeId = (liveVehicleSource != null || fuzzyRunTrips != null)
                .takeIf { it }
                ?.let { repository.getRoutesForTrips(setOf(trip.tripId))[trip.tripId]?.route?.routeId }
            val scopedFuzzyRunTrips = fuzzyRunTrips?.takeIf { source -> routeId != null && routeId in source.routeIds }
            // The rider's Select Run pick wins over the automatic closest match.
            val pinnedRun = boardedFuzzyRun.value?.takeIf { it.tripId == trip.tripId }
            val liveVehicleInfo = liveVehicleSource
                ?.takeIf { routeId != null }
                ?.let { source ->
                    try {
                        source.vehiclesByRoute(setOf(routeId!!), repository)[trip.tripId]
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("HomeScreen", "Live vehicle fetch failed", e)
                        null
                    }
                }
            val vehicleNextStop = stopPredictionSource?.let { source ->
                liveVehicleInfo?.vehicleId?.let { vehicleId ->
                    try {
                        source.nextStopForVehicle(vehicleId, repository, trip.agency.zoneId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("HomeScreen", "Vehicle-scoped prediction fetch failed", e)
                        null
                    }
                }
            }
            val matchedStopFromVehicle = vehicleNextStop?.let { next -> stops.find { it.stopId == next.stopId } }

            // The closest match is used only when nothing else locates the vehicle.
            val fuzzyTripUpdate = scopedFuzzyRunTrips?.let { source ->
                try {
                    if (pinnedRun != null) {
                        source.tripUpdateForRun(pinnedRun.runId, trip.tripId, routeId!!, repository, trip.agency, trip.agency.zoneId)
                    } else {
                        source.matchedTripUpdates(setOf(routeId!!), repository, trip.agency, trip.agency.zoneId)[trip.tripId]
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("HomeScreen", "Fuzzy run match fetch failed", e)
                    null
                }
            }
            // The matched run's next stop.
            val matchedStopFromFuzzy = fuzzyTripUpdate?.stopTimeUpdate?.firstOrNull()?.stopId
                ?.let { stopId -> stops.find { it.stopId == stopId } }
            val tripUpdateInferredSequence = tripUpdate?.inferCurrentStopSequence()
            val stopLocations = repository.getTripStopLocations(trip.tripId, trip.fromStopSequence)
            // Shape-based matching first, for agencies with a [TripShapeSource].
            val shapeSource = trip.agency.component<TripShapeSource>()
            suspend fun matchViaShape(lat: Double, lon: Double, bearing: Float?): Int? {
                val source = shapeSource ?: return null
                val zipFile = gtfsZipFile(filesDir, trip.agency)
                val points = source.shapePoints(trip.tripId, repository, zipFile) ?: return null
                val stopDistances = source.stopDistancesAlongShape(trip.tripId, repository, zipFile, stops, stopLocations)
                    ?: return null
                val match = matchCurrentStopByShapeProjection(
                    stops, stopDistances, points, lat, lon, bearing,
                    TripPositionAnchor.getShapeDistance(trip.tripId), tripUpdateInferredSequence,
                ) ?: return null
                TripPositionAnchor.recordShapeDistance(trip.tripId, match.distanceAlongShapeMeters)
                return match.stopSequence
            }

            // Current stop, in order of preference: the vehicle's reported stop, shape matching,
            // GPS proximity, then the trip update's remaining stops.
            val currentSeq = matchedStopFromVehicle?.stopSequence
                ?: liveVehicleInfo?.currentStopSequence
                ?: liveVehicleInfo?.let { info -> matchViaShape(info.latitude, info.longitude, null) }
                ?: liveVehicleInfo?.let { info ->
                    shapeSource?.let {
                        matchCurrentStopByProximity(
                            stops, stopLocations, info.latitude, info.longitude,
                            TripPositionAnchor.get(trip.tripId), coldStartSequenceHint = tripUpdateInferredSequence,
                        )
                    }
                }?.stopSequence
                ?: vehicle?.currentStopSequence
                ?: vehicle?.position?.let { pos -> matchViaShape(pos.latitude.toDouble(), pos.longitude.toDouble(), pos.bearing) }
                ?: vehicle?.position?.let { pos ->
                    shapeSource?.let {
                        matchCurrentStopByProximity(
                            stops, stopLocations, pos.latitude.toDouble(), pos.longitude.toDouble(),
                            TripPositionAnchor.get(trip.tripId), coldStartSequenceHint = tripUpdateInferredSequence,
                        )
                    }
                }?.stopSequence
                ?: tripUpdateInferredSequence
                ?: matchedStopFromFuzzy?.stopSequence
            currentSeq?.let { TripPositionAnchor.record(trip.tripId, it) }
            // A closest match only when nothing else found the stop and the rider hasn't picked a
            // run.
            val isClosestMatch = pinnedRun == null && matchedStopFromVehicle == null &&
                liveVehicleInfo == null && vehicle == null && tripUpdateInferredSequence == null &&
                matchedStopFromFuzzy != null

            val stopsRemaining = currentSeq?.let { seq -> stops.count { it.stopSequence in seq until stop.stopSequence } }
            // currentSeq is the stop the vehicle is heading to, so only the stops before it count
            // as done.
            val stopsCompleted = currentSeq?.let { seq ->
                if (vehicle?.currentStatus == GtfsRtVehicleStatus.STOPPED_AT) seq else seq - 1
            }
            // Null when the boarding and alight stops are the same.
            val progressFraction = if (stopsCompleted != null && stop.stopSequence != trip.fromStopSequence) {
                ((stopsCompleted - trip.fromStopSequence).toFloat() / (stop.stopSequence - trip.fromStopSequence).toFloat())
                    .coerceIn(0f, 1f)
            } else {
                null
            }

            val today = todayForGtfs(trip.agency.zoneId)
            val rtStopUpdate = tripUpdate?.updateFor(stop.stopId, stop.stopSequence)
            val scheduledTime = stop.arrivalTime ?: stop.departureTime
            // The predicted time at the alight stop, from the vehicle's next-stop prediction when
            // it's that stop.
            val predictedAlightTime = vehicleNextStop?.takeIf { it.stopId == stop.stopId }?.predictedEpochSeconds
                ?: stopPredictionSource?.let { source ->
                    try {
                        source.predictionsByStop(setOf(stop.stopId), repository, trip.agency.zoneId)[trip.tripId]
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("HomeScreen", "Stop prediction fetch failed", e)
                        null
                    }
                }
            val effectiveRtUpdate = predictedAlightTime?.let {
                GtfsRtStopTimeUpdate(departure = GtfsRtStopTimeEvent(time = it))
            } ?: rtStopUpdate ?: fuzzyTripUpdate?.updateFor(stop.stopId, stop.stopSequence)
            val eta = scheduledTime?.let { computeArrivalEta(it, today, effectiveRtUpdate, trip.agency.zoneId) }

            activeTripStatus.value = ActiveTripStatus(
                routeLabel = trip.routeLabel,
                alightStopName = stop.stopName,
                etaEpochSeconds = eta?.etaEpochSeconds,
                stopsRemaining = stopsRemaining,
                isClosestMatch = isClosestMatch,
                progressFraction = progressFraction,
                zoneId = trip.agency.zoneId,
            )

            // Also checked here so the arrival message fires while the rider is on Home.
            checkReachedAlightStop(trip, stops, currentSeq, boardedTripPreferences) { reachedAlightStop.value = trip.agency to it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HomeScreen", "Failed to refresh active trip status for ${trip.tripId}", e)
        } finally {
            repository.close()
        }
    }

    fun selectAgency(agency: GtfsAgency) {
        agencyIngestJob?.cancel()
        selectedAgency.value = agency
        readyAgency.value = null
        status.value = null
        waitingForWifi.value = false
        agencyHasStations.value = false
        feedAttribution.value = emptyList()
        Log.d("HomeScreen", "Selected agency: ${agency.displayName}")

        agencyIngestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ingestor.ingest(agency) { ingestStatus ->
                    AdditionalScheduleDownloads.report(agency, ingestStatus)
                    if (selectedAgency.value != agency) return@ingest
                    waitingForWifi.value = ingestStatus == GtfsIngestStatus.WaitingForWifi
                    status.value = if (waitingForWifi.value) {
                        "Waiting for Wi-Fi to update ${agency.displayName}'s schedule."
                    } else {
                        null
                    }
                }
                // A newer selection may have started another download; don't let this older one
                // overwrite its state.
                if (selectedAgency.value != agency) return@launch
                // Only ready once there's a database on disk.
                if (!gtfsDbFile(filesDir, agency).exists()) return@launch
                readyAgency.value = agency
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeScreen", "GTFS ingestion failed for ${agency.displayName}", e)
                if (selectedAgency.value == agency) {
                    status.value = "Unable to load ${agency.displayName} data."
                }
                return@launch
            }

            // Best effort; a failure here doesn't affect the agency being usable.
            val stationRepo = GtfsRepository(gtfsDbFile(filesDir, agency))
            try {
                agencyHasStations.value = stationRepo.getAllStations().isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeScreen", "Station lookup failed for ${agency.displayName}", e)
            } finally {
                stationRepo.close()
            }
            refreshFeedAttribution()
        }
    }

    /**
     * Data credits for an agency and its extra feeds with their own schedules. Empty if the agency
     * has no database yet.
     */
    private fun attributionForAgency(agency: GtfsAgency): List<FeedAttribution> {
        val dbFile = gtfsDbFile(filesDir, agency)
        if (!dbFile.exists()) return emptyList()
        val repo = GtfsRepository(dbFile)
        return try {
            val legend = agency.component<AttributionLegend>()?.text
            val partner = agency.component<AttributionPartner>()?.name
            // An agency that is its own partner is credited once, as the partner. A partner-sourced
            // feed names the partner as publisher, so the agency is credited by its agency.txt
            // name.
            val feedCredit = if (partner != null) repo.getAgencyAttribution() ?: repo.getFeedAttribution() else repo.getFeedAttribution()
            val credit = feedCredit?.let {
                if (partner == agency.displayName) it.copy(name = partner) else it.copy(partner = partner)
            }
            listOfNotNull(credit?.copy(requiredLegend = legend)) +
                agency.components.filterIsInstance<MultiGtfsFeed>().filter { it.feedUrl != null }
                    .map { FeedAttribution(it.name, url = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HomeScreen", "Attribution lookup failed for ${agency.displayName}", e)
            emptyList()
        } finally {
            repo.close()
        }
    }

    /**
     * Rebuilds the credits for the selected agency plus additional schedules in its region, without
     * duplicates.
     */
    private fun refreshFeedAttribution() {
        val primary = selectedAgency.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val regionExtras = RegionalGroup.forAgency(primary)?.members?.filter { it in additionalDownloads.value } ?: emptyList()
            val byName = LinkedHashMap<String, FeedAttribution>()
            (listOf(primary) + regionExtras).distinct().forEach { agency ->
                attributionForAgency(agency).forEach { entry -> byName.putIfAbsent(entry.name, entry) }
            }
            if (selectedAgency.value == primary) feedAttribution.value = byName.values.toList()
        }
    }
}

/**
 * The app's first screen: the agency picker when none is chosen, then the home screen with the
 * clock, trip status, and mode buttons.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        // Home is always the first screen, so pop-ups start watching here.
        AlertPopups.install(lightContext.dataStore, lightContext.filesDir)
        return HomeScreenViewModel(
            lightContext.filesDir,
            AgencyPreferences(lightContext.dataStore),
            BoardedTripPreferences(lightContext.dataStore),
            BoardedFuzzyRunPreferences(lightContext.dataStore),
            HomeScreenPreferences(lightContext.dataStore),
            lightContext.connectivity,
            NetworkPreferences(lightContext.dataStore),
            LocationPreferences(lightContext.dataStore),
            AlertPreferences(lightContext.dataStore),
        )
    }

    @Composable
    override fun Content() {
        val selectedAgency by viewModel.selectedAgency.collectAsState()
        val status by viewModel.status.collectAsState()
        val readyAgency by viewModel.readyAgency.collectAsState()
        val currentTime by viewModel.currentTime.collectAsState()
        val agencyHasStations by viewModel.agencyHasStations.collectAsState()
        val feedAttribution by viewModel.feedAttribution.collectAsState()
        val boardedTrip by viewModel.boardedTrip.collectAsState()
        val activeTripStatus by viewModel.activeTripStatus.collectAsState()
        val progressBarVisible by viewModel.progressBarVisible.collectAsState()
        val dailyMessageVisible by viewModel.dailyMessageVisible.collectAsState()
        val dailyMessageText by viewModel.dailyMessageText.collectAsState()
        val homeAlerts by viewModel.homeAlerts.collectAsState()
        val reachedAlightStop by viewModel.reachedAlightStop.collectAsState()
        val additionalDownloads by viewModel.additionalDownloads.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val locationPermissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.ACCESS_FINE_LOCATION)


        // After a boarded rider reaches their alight stop and dismisses the message, open that
        // stop's upcoming arrivals so they can see what's next. This happens on Home and Trip
        // Detail.
        LaunchedEffect(reachedAlightStop) {
            val (agency, stop) = reachedAlightStop ?: return@LaunchedEffect
            navigateTo(screenFactory = { activity ->
                UpcomingArrivalsScreen(activity, gtfsDbFile(lightContext.filesDir, agency), agency, listOf(stop.stopId), stop.stopName ?: "Stop ${stop.stopId}")
            })
            viewModel.clearReachedAlightStop()
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                // The vehicle type and Play icons for the current trip, top right.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3f.gridUnitsAsDp())
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    boardedTrip?.let { boarded ->
                        LightIcon(
                            icon = boarded.lineType.toVehicleIcon(),
                            size = 2f,
                            contentDescription = "Vehicle type",
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        LightIcon(
                            icon = LightIcons.PLAY,
                            size = 2f,
                            contentDescription = "Current Trip",
                            modifier = Modifier.lightClickable {
                                navigateTo(screenFactory = { activity ->
                                    TripDetailScreen(
                                        activity,
                                        gtfsDbFile(lightContext.filesDir, boarded.agency),
                                        boarded.tripId,
                                        boarded.fromStopSequence,
                                        boarded.routeLabel,
                                        boarded.directionLabel,
                                    )
                                })
                            },
                        )
                    }
                }
            // Everything between the header row and the icon row scrolls together when it doesn't fit.
            // When it does fit, the attribution sits at the bottom, just above the icon row.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val viewportHeight = maxHeight
                LightScrollView(modifier = Modifier.fillMaxSize(), scrollBarPosition = LightScrollBarPosition.Inside) {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(min = viewportHeight),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                        ) {
                            // While boarded, the heading shows the trip's status instead of the
                            // agency.
                            if (boardedTrip != null) {
                                LightText(
                                    text = currentTime,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                LightText(
                                    text = activeTripStatus?.routeLabel ?: boardedTrip?.routeLabel ?: "Current Trip",
                                    variant = LightTextVariant.Heading,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                LightText(
                                    text = activeTripStatus?.headingSubtitle() ?: "Boarded",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(bottom = 16.dp),
                                )
                                // Progress from the boarding stop to the alight stop, with a
                                // vehicle marker. Shown at the start until there's live data, so
                                // the layout doesn't jump.
                                if (progressBarVisible) {
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 16.dp),
                                    ) {
                                        LightProgressBar(colors = LightThemeTokens.colors, progress = activeTripStatus?.progressFraction ?: 0f)
                                        val markerSize = 1.4f
                                        val trackWidth = maxWidth - markerSize.gridUnitsAsDp()
                                        LightIcon(
                                            icon = boardedTrip?.lineType.toVehicleIcon(),
                                            size = markerSize,
                                            contentDescription = "Vehicle position",
                                            modifier = Modifier.offset(x = trackWidth * (activeTripStatus?.progressFraction ?: 0f)),
                                        )
                                    }
                                }
                            } else if (selectedAgency != null) {
                                // The clock, the agency's name, and a spinner while its schedule
                                // downloads.
                                LightText(
                                    text = currentTime,
                                    variant = LightTextVariant.Heading,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 16.dp),
                                ) {
                                    LightText(
                                        text = selectedAgency?.displayName.orEmpty(),
                                        variant = LightTextVariant.Subheading,
                                        lighten = true,
                                    )
                                    if (readyAgency != selectedAgency && status == null) {
                                        val infiniteTransition = rememberInfiniteTransition(label = "agencyLoading")
                                        val angle by infiniteTransition.animateFloat(
                                            initialValue = 0f,
                                            targetValue = 360f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(durationMillis = 1000, easing = LinearEasing),
                                                repeatMode = RepeatMode.Restart,
                                            ),
                                            label = "agencyLoadingAngle",
                                        )
                                        LightIcon(
                                            icon = LightIcons.REFRESH,
                                            size = AGENCY_ICON_SIZE,
                                            contentDescription = "Loading schedule",
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .rotate(angle),
                                        )
                                    }
                                }
                            }

                            status?.let {
                                LightText(
                                    text = it,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(bottom = 16.dp),
                                )
                            }

                            // Hidden while alerts are showing, but its space stays as the gap above
                            // them.
                            if (dailyMessageVisible || homeAlerts != null) {
                                LightText(
                                    text = dailyMessageText,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier
                                        .padding(bottom = 16.dp)
                                        .alpha(if (dailyMessageVisible && homeAlerts == null) 1f else 0f),
                                )
                            }
                            // While boarded these are the trip's alerts; tapping opens all of them in one modal.
                            homeAlerts?.let { (alerts, screenAlerts) -> HomeAlertsPager(alerts, screenAlerts) }
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Data credits, or an agency's required legend. Partner-sourced
                            // agencies are grouped after the partner, e.g. "Sound Transit & Pierce
                            // Transit, Community Transit".
                            val credits = feedAttribution.filter { it.requiredLegend == null }
                            val partners = credits.mapNotNull { it.partner }.toSet()
                            val creditParts = credits.filter { it.partner == null && it.name !in partners }.map { it.name } +
                                credits.filter { it.partner != null }.groupBy { it.partner!! }.map { (partner, group) ->
                                    val agencies = group.map { it.name }.filter { it != partner }
                                    if (agencies.isEmpty()) partner else "$partner & " + agencies.joinToString(", ")
                                }
                            if (creditParts.isNotEmpty()) {
                                LightText(
                                    text = "Public transit data © " + creditParts.joinToString(", "),
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                                )
                            }
                            feedAttribution.mapNotNull { it.requiredLegend }.distinct().forEach { legend ->
                                LightText(
                                    text = legend,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            // Settings and About, then whichever of Schedule, Station, and Explore are available.
            val bottomBarItems = buildList {
                add(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        contentDescription = "Settings",
                        onClick = {
                            navigateTo(screenFactory = { activity -> SettingsScreen(activity) })
                        },
                    ),
                )
                add(
                    LightBarButton.LightIcon(
                        icon = LightIcons.ELLIPSES,
                        contentDescription = "About",
                        onClick = {
                            navigateTo(screenFactory = { activity -> InfoScreen(activity) })
                        },
                    ),
                )
                readyAgency?.let { agency ->
                    // Only downloaded schedules in the agency's region count for the schedule
                    // picker.
                    val regionSchedules = RegionalGroup.forAgency(agency)?.members
                        ?.filter { it == agency || (it in additionalDownloads && gtfsDbFile(lightContext.filesDir, it).exists()) }
                        ?: listOf(agency)
                    add(
                        LightBarButton.LightIcon(
                            icon = LightIcons.LIST,
                            contentDescription = "Schedule",
                            onClick = {
                                navigateTo(screenFactory = { activity ->
                                    if (regionSchedules.size > 1) {
                                        ScheduleAgencyPickerScreen(activity, regionSchedules, lightContext.filesDir)
                                    } else {
                                        LineTypeSelectionScreen(activity, gtfsDbFile(lightContext.filesDir, agency))
                                    }
                                })
                            },
                        ),
                    )
                    if (agencyHasStations) {
                        add(
                            LightBarButton.LightIcon(
                                icon = LightIcons.DIRECTIONS_MIDDLE_FORK,
                                contentDescription = "Station",
                                onClick = {
                                    navigateTo(screenFactory = { activity ->
                                        StationListScreen(activity, gtfsDbFile(lightContext.filesDir, agency), agency)
                                    })
                                },
                            ),
                        )
                    }
                    add(
                        LightBarButton.LightIcon(
                            icon = LightIcons.DIRECTIONS_PEDESTRIAN,
                            contentDescription = if (agency.realtimeTripUpdatesUrl == null) "Explore (Offline)" else "Explore",
                            onClick = {
                                viewModel.primeLocation(requestPermission = { locationPermissionLauncher?.launch() })
                                navigateTo(screenFactory = { activity ->
                                    NearbyStopsScreen(activity, gtfsDbFile(lightContext.filesDir, agency), agency)
                                })
                            },
                        ),
                    )
                }
            }
            LightBottomBar(items = bottomBarItems)
            }
        }
    }
}
