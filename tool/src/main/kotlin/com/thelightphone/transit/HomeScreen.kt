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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.AgencyPreferences
import com.thelightphone.transit.gtfs.BoardedTrip
import com.thelightphone.transit.gtfs.BoardedTripPreferences
import com.thelightphone.transit.gtfs.FeedAttribution
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsIngestStatus
import com.thelightphone.transit.gtfs.GtfsIngestor
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.SecondaryGtfsFeed
import com.thelightphone.transit.gtfs.fetchTripUpdate
import com.thelightphone.transit.gtfs.fetchVehiclePosition
import com.thelightphone.transit.gtfs.matchCurrentStopByProximity
import com.thelightphone.transit.gtfs.HomeScreenPreferences
import com.thelightphone.transit.gtfs.TripStopRow
import com.thelightphone.transit.gtfs.computeArrivalEta
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.transit.gtfs.gtfsDbFile
import com.thelightphone.transit.gtfs.todayForGtfs
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightProgressBar
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// One agency-row icon's footprint -- also used as a blank placeholder's size for the "already
// cached" state, so every agency name lines up at the same x position whether or not it has an
// icon next to it.
private const val AGENCY_ICON_SIZE = 1f

/**
 * A friendly, low-stakes message shown at the bottom of the home screen -- picked deterministically
 * by calendar day or randomly if random selection is enabled,so it stays the same across every visit
 * in a day and only changes once daily.. some of these need to be reqorked as a little corny and
 * I'd like to build on this further so thatit begins to acknowledge holidays and seasons,
 * maybe even weather.
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
    // General positive
    "Today's a good day to try. 🌱✨",
    "You've got this. 💪🌤️",
    "Small steps still count as progress. 🐾✨",
    "Be kind to yourself today. 💛",
    "Little wins add up. 🌟",
    "You're doing better than you think. 🌷",
    "Take a breath — you're okay. 🌬️💚",
    "Progress, not perfection. 🌱🎯",
    "Someone's glad you exist today. 💛",
    "Rest is productive too. 🌙✨",
    "You made it this far — nice. 🌟",
    "Today's a fresh page. 📖🌤️",
    "Be proud of small things too. 🌸",
    "You're allowed to go slow. 🐢💚",
    "Good things are still coming. 🌅",
    "You matter more than you know. 💛",
    "Kindness looks good on you. 🌷✨",
    "One step at a time works fine. 👣🌿",
    "You're not behind, you're on your way. 🛤️🌟",
    "Today counts, even the quiet parts. 🌙",
    "Give yourself the grace you'd give a friend. 💛",
    "You're capable of more than you feel. 💪🌤️",
    "Simple moments matter too. ☕✨",
    "You showed up — that's enough. 🌱",
    "Better days are built one hour at a time. ⏳🌸",
    "You're allowed to be proud of you. 🌟",
    "Small joys are still joys. 🍃💛",
    "Keep going — it's working. 🌤️🌱",
    "You're worth the effort you give others. 💐",
    "Today is enough, just as it is. 🌙✨",
    // Light sass
    "You're welcome, gridlock. 😏🚌",
    "Cars stuck. You: not stuck. 😌✨",
    "Congrats on skipping the parking drama. 💅🎯",
    "Look at you, being efficient. 💁‍♀️🚏",
    "Not to brag, but you're basically eco-royalty. 👑🌱",
    "Traffic's problem, not yours today. 😏🚆",
    "Main character energy: took the bus. 🎬🚌",
    "You out here saving the planet, no big deal. 💁🌍",
)

/** [random] is the Settings screen's own opt-in toggle (off by default) -- see
 * HomeScreenPreferences.dailyMessageRandomFlow. */
private fun dailyMessage(random: Boolean): String {
    if (random) return DAILY_MESSAGES.random()
    val dayOfYear = LocalDate.now().dayOfYear
    return DAILY_MESSAGES[dayOfYear % DAILY_MESSAGES.size]
}



/** Homevisibility sets isVisible to false (referring to if the back to homebutton fotter should be visible
 * becuase we're on the homescreen... this is false, no homescreen button here! Because the homescreen button
 * pops each previous screen as it goes back it is set to assure no further pops occur on this screen thus,
 * stopping at the homescreen, this both prevents infinite screens from opening and assures the home screen
 * can be easily returned to.
 */
object HomeVisibility {
    val isVisible = MutableStateFlow(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

// Matches every other live-polling screen's own cadence (see MapScreen/TripDetailScreen's
// identical constant) -- HomeScreen only ever runs this while a trip is actually boarded.
private const val LIVE_VEHICLE_POLL_INTERVAL_MS = 10_000L

/**
 * instantiates a constant for live polling. the homescreen only livepolls GTFS-RT when a trip is boarded to
 * show the progress bar and ETA of the trip minimally on the homescreen. while a trip is boarded.
 * [stopsRemaining]/[etaEpochSeconds] are null whenever there's nothing live to show yet (trip not yet
 * reporting a position, or the rider hasn't designated an alight stop on Trip Detail) -- [headingSubtitle] falls back to guidance text
 * rather than a blank line in that case.
 */

data class ActiveTripStatus(
    val routeLabel: String,
    val alightStopName: String?,
    val etaEpochSeconds: Long?,
    /** Count of stops between the vehicle's current/next stop (inclusive) and the alight stop
     * (exclusive) -- see [HomeScreenViewModel.refreshActiveTripStatus] for the exact computation.
     * Null whenever there's no live vehicle position to compute it from. */
    val stopsRemaining: Int?,
    /** How far along the vehicle is between the boarding stop (0f) and the alight stop (1f), by
     * stop_sequence position -- drives the progress bar's marker (Settings screen's "Trip progress
     * bar" toggle). Null under the same conditions as [stopsRemaining]. */
    val progressFraction: Float?,
)

private fun Long.asClockTime(): String {
    val time = LocalDateTime.ofInstant(Instant.ofEpochSecond(this), ZoneId.systemDefault())
    return formatGtfsTime("%02d:%02d:00".format(time.hour, time.minute))
}

fun ActiveTripStatus.headingSubtitle(): String {
    val stopsToDest = when {
        stopsRemaining == null -> null
        stopsRemaining <= 0 -> alightStopName?.let { "Arriving at $it" } ?: "Arriving"
        else -> {
            val stopsWord = if (stopsRemaining == 1) "stop" else "stops"
            alightStopName?.let { "$stopsRemaining $stopsWord to $it" } ?: "$stopsRemaining $stopsWord away"
        }
    }
    val etaText = etaEpochSeconds?.let { "ETA ${it.asClockTime()}" }
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
    private val homeScreenPreferences: HomeScreenPreferences,
) : LightViewModel<Unit>() {

    private val ingestor = GtfsIngestor(filesDir)

    /** The trip the rider is currently on (if any), independent of whichever agency is selected
     * above -- see BoardedTripPreferences' own doc comment for why this is a saved reference back
     * to Trip Detail rather than a background tracker. Collected for this ViewModel's whole
     * lifetime so it reflects a Board/Alight tap made on Trip Detail immediately upon returning here. */
    val boardedTrip = MutableStateFlow<BoardedTrip?>(null)

    /** Live progress toward the boarded trip's alight stop -- see [ActiveTripStatus]'s own doc
     * comment. Null whenever nothing's boarded; otherwise refreshed by [tripStatusPollJob] below. */
    val activeTripStatus = MutableStateFlow<ActiveTripStatus?>(null)

    /** Settings screen's "Trip progress bar" toggle (on by default) -- see
     * BoardedTripPreferences.progressBarVisibleFlow. */
    val progressBarVisible = MutableStateFlow(true)

    /** Settings screen's "Daily message" toggle (on by default) -- see
     * HomeScreenPreferences.dailyMessageVisibleFlow. */
    val dailyMessageVisible = MutableStateFlow(true)

    /** Settings screen's "Randomize daily message" toggle (off by default) -- see
     * HomeScreenPreferences.dailyMessageRandomFlow. */
    val dailyMessageRandom = MutableStateFlow(false)

    /** The message text itself, re-rolled once per [onScreenShow] rather than computed fresh on
     * every recomposition, so a random pick (when [dailyMessageRandom] is on) stays put for the
     * rest of this visit instead of changing under the rider on every recomposition. */
    val dailyMessageText = MutableStateFlow(dailyMessage(random = false))

    /** One-shot signal: non-null exactly when the rider has just dismissed the "you've arrived"
     * modal for the boarded trip's alight stop while HomeScreen was the visible screen -- mirrors
     * TripDetailViewModel's own identical field (see the shared checkReachedAlightStop). Content()
     * observes this to navigate to that stop's own Upcoming Arrivals, then calls
     * [clearReachedAlightStop] to consume it. Carries the agency alongside the stop (rather than
     * reading [boardedTrip] fresh when this fires) since [checkReachedAlightStop] already clears
     * [boardedTrip] to null before the modal even shows -- by the time it's dismissed, that flow
     * has long since gone null. */
    val reachedAlightStop = MutableStateFlow<Pair<GtfsAgency, TripStopRow>?>(null)

    /** Wakes the trip-status poll loop early on a boarded-trip change (board/alight/alight-stop
     * tap made on Trip Detail) rather than waiting out the rest of the current poll interval --
     * same conflated-trigger pattern MapScreen's own refreshTrigger uses. */
    private val tripStatusRefreshTrigger = Channel<Unit>(Channel.CONFLATED)
    private var tripStatusPollJob: Job? = null

    init {
        viewModelScope.launch {
            boardedTripPreferences.boardedTripFlow.collect {
                boardedTrip.value = it
                if (it == null) activeTripStatus.value = null
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
    }

    fun clearReachedAlightStop() {
        reachedAlightStop.value = null
    }

    /** When there is NO active trip this is the default state: Choose your agency, view the attribution
     * to the agency's data, and enjoy your daily message**/
    val selectedAgency = MutableStateFlow<GtfsAgency?>(null)
    /** Error text only now -- per-agency ready/syncing state renders as an icon next to each
     * agency's name instead (see [cachedAgencies]/[syncingAgency]). */
    val status = MutableStateFlow<String?>(null)

    /** Set once ingestion completes successfully; gates the "Schedule"/"Explore" mode buttons are available. */
    val readyAgency = MutableStateFlow<GtfsAgency?>(null)

    /** Agencies with a GTFS schedule already downloaded/cached on disk -- checked once at
     * screen-open, then grown as each agency's own ingest completes. An agency in this set (and
     * not currently in [syncingAgency]) shows no icon at all next to its name. A ready agency just means
     * they have an up to date GTFS file downloaded, not that they are selected, downloaded and buttons loaded*/
    val cachedAgencies = MutableStateFlow<Set<GtfsAgency>>(emptySet())

    /** The one agency (if any) currently checking for updates/downloading/parsing right now --
     * shows a spinning sync icon next to that agency's name only. */
    val syncingAgency = MutableStateFlow<GtfsAgency?>(null)
    private var agencyIngestJob: Job? = null

    /** Whether [readyAgency] has any real, qualifying multi-platform stations at all (see
     * GtfsRepository.getAllStations) -- an agency with none (e.g. RIPTA, which has no grouped
     * stations in its GTFS feed) shows no "Station" entry point rather than one that always opens
     * an empty list. Reset to false the moment a new agency is selected so a stale true from the
     * previous agency can't flash the link before this agency's own check completes. */
    val agencyHasStations = MutableStateFlow(false)

    /** The currently-selected agency's own GTFS-feed attribution, plus one entry for every
     * [SecondaryGtfsFeed] component it has (see that class's own doc) -- e.g. RTD Denver's own
     * attribution followed by "Bustang", so a merged feed's data source gets credited too, not
     * just the primary agency's. See GtfsRepository.getFeedAttribution's own doc for the primary
     * entry's fallback chain. Reset to empty the moment a new agency is selected, same reasoning
     * as [agencyHasStations]: a stale value from the previous agency shouldn't flash under the new
     * one before its own check completes. */
    val feedAttribution = MutableStateFlow<List<FeedAttribution>>(emptyList())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        HomeVisibility.isVisible.value = true
        // Re-rolled every time this screen becomes visible again (not just once per process) so
        // "Randomize daily message" actually delivers a fresh pick on each return trip, per its own
        // Settings description -- see dailyMessageText's own doc for why this isn't just computed
        // inline in Content() instead.
        dailyMessageText.value = dailyMessage(dailyMessageRandom.value)
        tripStatusPollJob?.cancel()
        tripStatusPollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (boardedTrip.value != null) refreshActiveTripStatus()
                withTimeoutOrNull(LIVE_VEHICLE_POLL_INTERVAL_MS) { tripStatusRefreshTrigger.receive() }
            }
        }
        // A configured default agency skips the manual tap -- the list stays visible/tappable in
        // case someone wants a different agency for this session, this just saves the first tap.
        // Only checked once per ViewModel (guarded by selectedAgency already being set), so
        // returning to HomeScreen from a child screen doesn't re-trigger it.
        if (selectedAgency.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            cachedAgencies.value = GtfsAgency.entries.filterTo(mutableSetOf()) { gtfsDbFile(filesDir, it).exists() }
            val default = preferences.defaultAgencyFlow.first()
            if (default != null && selectedAgency.value == null) {
                selectAgency(default)
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        HomeVisibility.isVisible.value = false
        tripStatusPollJob?.cancel()
        tripStatusPollJob = null
    }

    /**
     * Fetches this agency's live vehicle position (and, if available, a live TripUpdates
     * prediction) for the boarded trip and recomputes [activeTripStatus] -- mirrors the same
     * live-position/ETA pattern MapScreen and TripDetailScreen already use. No-ops (leaving the
     * previous status in place) on any fetch/lookup failure, same as those screens' own polling.
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
                    stopsRemaining = null, progressFraction = null,
                )
                return
            }
            val (stop, stops) = alightStop

            val vehicle = trip.agency.fetchVehiclePosition(trip.tripId)
            val currentSeq = vehicle?.currentStopSequence
            val stopsRemaining = currentSeq?.let { seq -> stops.count { it.stopSequence in seq until stop.stopSequence } }
            // Boarding stop (0f) to alight stop (1f) -- guards against a same-sequence divide (the
            // rider designated their own boarding stop as the alight stop too) by leaving it null
            // rather than producing a NaN/Infinity fraction.
            val progressFraction = if (currentSeq != null && stop.stopSequence != trip.fromStopSequence) {
                ((currentSeq - trip.fromStopSequence).toFloat() / (stop.stopSequence - trip.fromStopSequence).toFloat())
                    .coerceIn(0f, 1f)
            } else {
                null
            }

            val today = todayForGtfs(trip.agency.zoneId)
            val rtStopUpdate = trip.agency.fetchTripUpdate(trip.tripId)?.updateFor(stop.stopId, stop.stopSequence)
            val scheduledTime = stop.arrivalTime ?: stop.departureTime
            val eta = scheduledTime?.let { computeArrivalEta(it, today, rtStopUpdate, trip.agency.zoneId) }

            activeTripStatus.value = ActiveTripStatus(
                routeLabel = trip.routeLabel,
                alightStopName = stop.stopName,
                etaEpochSeconds = eta?.etaEpochSeconds,
                stopsRemaining = stopsRemaining,
                progressFraction = progressFraction,
            )

            // Checked here (not just from Trip Detail's own poll) so the "you've arrived" moment
            // fires even if the rider's just sitting on HomeScreen rather than Trip Detail -- see
            // the shared checkReachedAlightStop's own doc comment.
            checkReachedAlightStop(trip, stops, currentSeq, boardedTripPreferences) { reachedAlightStop.value = trip.agency to it }
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
        syncingAgency.value = agency
        agencyHasStations.value = false
        feedAttribution.value = emptyList()
        Log.d("HomeScreen", "Selected agency: ${agency.displayName}")

        agencyIngestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ingestor.ingest(agency) { ingestStatus ->
                    if (ingestStatus == GtfsIngestStatus.Ready && selectedAgency.value == agency) {
                        syncingAgency.value = null
                        cachedAgencies.value = cachedAgencies.value + agency
                    }
                }
                // A later selection may have started another ingest while this one was running.
                // Do not let the older job replace the newer agency's ready state or station check.
                if (selectedAgency.value != agency) return@launch
                readyAgency.value = agency
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeScreen", "GTFS ingestion failed for ${agency.displayName}", e)
                if (selectedAgency.value == agency) {
                    syncingAgency.value = null
                    status.value = "Unable to load ${agency.displayName} data."
                }
                return@launch
            }

            // Best-effort enrichment, not core to the agency being usable -- Schedule/Station/
            // Explore are already available from readyAgency above regardless of whether this
            // succeeds. A failure here (e.g. a schema addition an already-cached database hasn't
            // picked up yet -- see GtfsIngestor's own "upToDate" doc comment) shouldn't blank a
            // successfully-loaded agency out with a scary "unable to load" message.
            val stationRepo = GtfsRepository(gtfsDbFile(filesDir, agency))
            try {
                agencyHasStations.value = stationRepo.getAllStations().isNotEmpty()
                feedAttribution.value = listOfNotNull(stationRepo.getFeedAttribution()) +
                    agency.components.filterIsInstance<SecondaryGtfsFeed>().map { FeedAttribution(it.name, url = null) }
            } catch (e: Exception) {
                Log.e("HomeScreen", "Station/attribution lookup failed for ${agency.displayName}", e)
            } finally {
                stationRepo.close()
            }
        }
    }
}

@InitialScreen /** This screen appears when PICO transit is first installed, the agencies are available to select with download indicators next to each agency name
 no populated buttons at the bottom except for about and settings**/
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        return HomeScreenViewModel(
            lightContext.filesDir,
            AgencyPreferences(lightContext.dataStore),
            BoardedTripPreferences(lightContext.dataStore),
            HomeScreenPreferences(lightContext.dataStore),
        )
    }

    @Composable
    override fun Content() {
        val selectedAgency by viewModel.selectedAgency.collectAsState()
        val status by viewModel.status.collectAsState()
        val readyAgency by viewModel.readyAgency.collectAsState()
        val cachedAgencies by viewModel.cachedAgencies.collectAsState()
        val syncingAgency by viewModel.syncingAgency.collectAsState()
        val agencyHasStations by viewModel.agencyHasStations.collectAsState()
        val feedAttribution by viewModel.feedAttribution.collectAsState()
        val boardedTrip by viewModel.boardedTrip.collectAsState()
        val activeTripStatus by viewModel.activeTripStatus.collectAsState()
        val progressBarVisible by viewModel.progressBarVisible.collectAsState()
        val dailyMessageVisible by viewModel.dailyMessageVisible.collectAsState()
        val dailyMessageText by viewModel.dailyMessageText.collectAsState()
        val reachedAlightStop by viewModel.reachedAlightStop.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()


        /** OH this, isn't part of the initial screen, Only on the homescreen and trip detail screen will this appear
         * if you boarded a trip and you reached your selected to stop to alight you get a message. once it clears
         * it will immediatly open upcoming arrivals for the stop or station you land at so if you are transfering you know what's coming up*/
        // Fires once the "you've arrived" modal has been dismissed while HomeScreen was the
        // visible screen (manually or by timeout) -- see ReachedStopModal/checkReachedAlightStop.
        // Navigates to that stop's own Upcoming Arrivals, matching Trip Detail's identical handling.
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
                // Current Trip lives here (top-right corner) rather than in the bottom icon row --
                // it's the one entry that's about something already in progress, not a mode to pick,
                // so it reads more like a status indicator than another menu item. Built by hand
                // (matching LightTopBar's own height/padding) rather than via LightTopBar's
                // rightButton slot, which only accepts a single button -- this needs two icons
                // together (vehicle type, then Play).
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                ) {
                    // While a trip is boarded, this becomes an active-trip status instead of the
                    // agency-picker heading -- everything below (agency list, mode icons, daily
                    // message) stays exactly as-is underneath either way. Reverts back the moment
                    // the trip is alighted (boardedTrip/activeTripStatus both go null together).
                    if (boardedTrip != null) {
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
                        // Boarding stop (left) to alight stop (right), with a vehicle-type marker
                        // positioned at the live stop_sequence progress between them -- see
                        // ActiveTripStatus.progressFraction. Settings screen toggle (on by default);
                        // 0f (marker at the start) whenever there's no live position yet rather than
                        // hiding the bar entirely, so its layout doesn't jump once one arrives.
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
                    } else {
                        LightText(
                            text = "Choose Transit Agency",
                            variant = LightTextVariant.Heading,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }

                    // Only ever populated by an ingest failure now -- per-agency ready/syncing
                    // state is the icon next to each agency's name below, not a screen-wide banner.
                    status?.let {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }

                    // Lives here -- directly under the heading/status block, in the same spot
                    // whether boarded (below the progress bar) or not (below the agency-picker
                    // heading) -- rather than pinned to the bottom alongside the feed attribution
                    // below. Pinned to the bottom, it would scroll underneath (and become
                    // unreadable behind) the agency list once that list is long enough to need
                    // scrolling -- unlike attribution, which is *meant* to float over the list that
                    // way, this message has no such reason to compete with it for the same space.
                    if (dailyMessageVisible) {
                        LightText(
                            text = dailyMessageText,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }

                    // Hidden entirely while a trip is boarded -- the active-trip status above (and
                    // its progress bar) takes this space instead. Reappears the moment the trip is
                    // alighted, same as the heading reverting above.
                    //
                    // Wrapped in LightScrollView (weighted, so it only claims what's left after the
                    // heading/status text above) rather than a plain Column -- with three agencies
                    // today every entry fits on screen, but a plain Column has no way to reach
                    // entries once the list grows past that, same as [SettingsScreen]'s own
                    // default-agency list.
                    if (boardedTrip == null) {
                    LightScrollView(modifier = Modifier.weight(1f)) {
                        GtfsAgency.entries.forEach { agency ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable { viewModel.selectAgency(agency) }
                                    .padding(vertical = 12.dp),
                            ) {
                                when {
                                    agency == syncingAgency -> {
                                        val infiniteTransition = rememberInfiniteTransition(label = "agencySync")
                                        val angle by infiniteTransition.animateFloat(
                                            initialValue = 0f,
                                            targetValue = 360f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(durationMillis = 1000, easing = LinearEasing),
                                                repeatMode = RepeatMode.Restart,
                                            ),
                                            label = "agencySyncAngle",
                                        )
                                        LightIcon(
                                            icon = LightIcons.REFRESH,
                                            size = AGENCY_ICON_SIZE,
                                            contentDescription = "Checking for updates",
                                            modifier = Modifier
                                                .padding(end = 8.dp)
                                                .rotate(angle),
                                        )
                                    }
                                    agency !in cachedAgencies -> {
                                        LightIcon(
                                            icon = LightIcons.DOWNLOAD_ARROW,
                                            size = AGENCY_ICON_SIZE,
                                            contentDescription = "Download",
                                            modifier = Modifier.padding(end = 8.dp),
                                        )
                                    }
                                    else -> {
                                        Spacer(
                                            modifier = Modifier.size(
                                                width = AGENCY_ICON_SIZE.gridUnitsAsDp() + 8.dp,
                                                height = AGENCY_ICON_SIZE.gridUnitsAsDp(),
                                            ),
                                        )
                                    }
                                }
                                LightText(
                                    text = agency.displayName,
                                    variant = LightTextVariant.Copy,
                                    lighten = agency != selectedAgency,
                                    underline = agency == selectedAgency,
                                )
                            }
                        }
                    }
                    }

                }

                // Attribution sits directly above the icon row (not at the very bottom edge) so
                // nothing competes for the same space -- matches LightBottomBar's own "matching
                // LightOS ActionBar" convention (see its doc comment) rather than the previous
                // ad-hoc Alignment.BottomStart/BottomEnd pair. Deliberately a sibling of (not
                // nested inside) the agency list's own Column above -- Box stacks them, so once the
                // agency list is long enough to need scrolling, this bottom-pinned attribution
                // floats over its tail end rather than pushing it up or being pushed off itself.
                Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    // Standard, agency-agnostic attribution -- see GtfsRepository.getFeedAttribution's
                    // own doc for exactly which GTFS file this comes from, plus one name per
                    // SecondaryGtfsFeed component (e.g. "Bustang" alongside RTD Denver's own). Tied
                    // to whichever agency is currently selected (not just "ready"), so it reads
                    // correctly even mid-sync.
                    if (feedAttribution.isNotEmpty()) {
                        LightText(
                            text = "Transit data © " + feedAttribution.joinToString(", ") { it.name },
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                    // Settings, About first, then whichever of Schedule/Station/Explore are
                    // actually reachable right now (built, not a fixed-size list with null
                    // placeholders) -- Current Trip lives in the top-right corner instead (see
                    // LightTopBar above), so this row's own worst case is 5, always exactly one
                    // LightBottomBar row (which hard-caps at 5 items).
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
                            add(
                                LightBarButton.LightIcon(
                                    icon = LightIcons.LIST,
                                    contentDescription = "Schedule",
                                    onClick = {
                                        navigateTo(screenFactory = { activity ->
                                            LineTypeSelectionScreen(activity, gtfsDbFile(lightContext.filesDir, agency))
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
    }
}
