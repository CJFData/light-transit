package com.thelightphone.transit

import android.Manifest
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.transit.gtfs.GeocodeResult
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.LocationPreferences
import com.thelightphone.transit.gtfs.NominatimGeocoder
import com.thelightphone.transit.gtfs.StopWithDistance
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val METERS_PER_MILE = 1609.344
private const val NEARBY_STOP_LIMIT = 20
// Polls GetCurrentLocation for ~12s (LightOS needs a moment to acquire a fix after
// RequestLocationUpdates) before giving up and falling back to manual search.
private const val LOCATION_POLL_ATTEMPTS = 8
private const val LOCATION_POLL_INTERVAL_MS = 1500L

/** Which action "Try Again" performs for a given [NearbyStopsMode.Error] -- a GPS/ranking failure
 * retries the location fix, a geocoding failure reopens address entry instead. */
enum class NearbyStopsErrorRetry { Location, Search }

/**
 * This screen has one job -- "closest stops to a location" -- and a search only ever changes
 * *which* location that is (GPS vs. a geocoded address), never what the screen does. [Input] /
 * [Searching] / [GeocodeResults] are a full-screen takeover for typing an address (same as this
 * screen's own former IP-geolocation prefill flow); everything else shares one frame with a pinned
 * Search/Current Location row (see [NearbyStopsScreen.Content]).
 */
sealed class NearbyStopsMode {
    /** Initial state, and re-entered on resume: checking/awaiting GPS location permission. */
    object Locating : NearbyStopsMode()
    /** Location permission was denied (or not yet granted) -- non-blocking; Search stays usable. */
    object NeedsPermission : NearbyStopsMode()
    /** The rider turned location off in Settings ([com.thelightphone.transit.gtfs.LocationPreferences]) --
     * distinct from [NeedsPermission] since there's nothing to grant here, only a Settings link. */
    object LocationOff : NearbyStopsMode()
    data class Input(val prefillText: String = "") : NearbyStopsMode()
    object Searching : NearbyStopsMode()
    data class GeocodeResults(val results: List<GeocodeResult>) : NearbyStopsMode()
    /** Ranking stops around a freshly-acquired GPS fix or a newly-picked search result. */
    object Ranking : NearbyStopsMode()
    /** [anchorLabel] is null while ranked against the GPS fix, or the picked [GeocodeResult]'s own
     * display name when ranked against a searched address instead -- drives both the pinned header
     * row's icon/text (see [NearbyStopsScreen.Content]) and which retry a ranking failure offers. */
    data class NearbyStops(val stops: List<StopWithDistance>, val anchorLabel: String?) : NearbyStopsMode()
    data class Error(val message: String, val retry: NearbyStopsErrorRetry) : NearbyStopsMode()
}

fun StopWithDistance.displayLabel(): String = stopName?.takeIf { it.isNotBlank() } ?: "Stop $stopId"

fun StopWithDistance.distanceLabel(): String = "%.1f mi".format(distanceMeters / METERS_PER_MILE)

class NearbyStopsViewModel(dbFile: File, private val locationPreferences: LocationPreferences) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val geocoder = NominatimGeocoder()

    private val _mode = MutableStateFlow<NearbyStopsMode>(NearbyStopsMode.Locating)
    val mode: StateFlow<NearbyStopsMode> = _mode

    /** Cached once GPS gives a real fix, so "recenter" is instant when possible instead of
     * re-polling from scratch. */
    private var lastGpsFix: Pair<Double, Double>? = null

    /** Snapshot of [_mode] taken by [openSearch], so cancelling out of address entry restores
     * exactly what was on screen before rather than always bouncing back to [NearbyStopsMode.Locating]. */
    private var modeBeforeSearch: NearbyStopsMode? = null

    /** The in-flight GPS poll, if any. [openSearch] cancels it: the poll ends by writing [_mode]
     * (a stop list or "Couldn't find your location"), which would otherwise replace the address
     * input -- closing the keyboard mid-typing -- whenever it finishes. */
    private var locateJob: Job? = null

    /** Drives whether the Search Location screen offers "Current Location" at all -- mirrored from
     * [LocationPreferences] into a plain [StateFlow] the Composable can collect, same pattern
     * [SettingsViewModel] already uses for its own preference-backed fields. */
    val locationEnabled: StateFlow<Boolean>
        get() = _locationEnabled
    private val _locationEnabled = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            locationPreferences.locationEnabledFlow.collect { _locationEnabled.value = it }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Runs on the first appearance, and again on any resume while still Locating/NeedsPermission/
        // LocationOff (e.g. returning from the system permission prompt Home may have already
        // triggered, or from flipping the Settings toggle) -- but never once real content (a stop
        // list, an in-progress search) is on screen, since that would discard it.
        if (_mode.value !is NearbyStopsMode.Locating &&
            _mode.value !is NearbyStopsMode.NeedsPermission &&
            _mode.value !is NearbyStopsMode.LocationOff
        ) return
        retryLocation()
    }

    private suspend fun locateFromGps() {
        if (!locationPreferences.locationEnabledFlow.first()) {
            _mode.value = NearbyStopsMode.LocationOff
            return
        }
        val granted = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION).getOrNull()
            ?.permissionResult == LightServiceMethod.GetPermission.Result.Granted
        if (!granted) {
            _mode.value = NearbyStopsMode.NeedsPermission
            return
        }
        _mode.value = NearbyStopsMode.Locating
        // Home already primes this lease when "Explore" is tapped; requesting it again here is
        // idempotent (a renewal) and covers a direct/returning entry that skipped Home's priming.
        callRemoteServiceMethod(LightServiceMethod.RequestLocationUpdates, Unit)
        try {
            repeat(LOCATION_POLL_ATTEMPTS) { attempt ->
                val fix = callRemoteServiceMethod(LightServiceMethod.GetCurrentLocation, Unit).getOrNull()
                val lat = fix?.latitude
                val lon = fix?.longitude
                if (lat != null && lon != null) {
                    lastGpsFix = lat to lon
                    rankAndShow(lat, lon, anchorLabel = null)
                    return
                }
                if (attempt < LOCATION_POLL_ATTEMPTS - 1) delay(LOCATION_POLL_INTERVAL_MS)
            }
            // No fix in time -- Search stays pinned above regardless, so this doesn't dead-end.
            _mode.value = NearbyStopsMode.Error("Couldn't find your location.", NearbyStopsErrorRetry.Location)
        } finally {
            withContext(NonCancellable) {
                callRemoteServiceMethod(LightServiceMethod.ReleaseLocationUpdates, Unit)
            }
        }
    }

    private suspend fun rankAndShow(lat: Double, lon: Double, anchorLabel: String?) {
        _mode.value = try {
            NearbyStopsMode.NearbyStops(repository.rankStopsByDistance(lat, lon, NEARBY_STOP_LIMIT), anchorLabel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("NearbyStopsScreen", "Failed to rank nearby stops", e)
            NearbyStopsMode.Error(
                "Unable to load nearby stops.",
                retry = if (anchorLabel != null) NearbyStopsErrorRetry.Search else NearbyStopsErrorRetry.Location,
            )
        }
    }

    fun retryLocation() {
        locateJob?.cancel()
        locateJob = viewModelScope.launch(Dispatchers.IO) { locateFromGps() }
    }

    /** Switches the ranking anchor back to GPS -- called from the Search Location screen's own
     * "Current Location" option, not just a same-screen "recenter" action. */
    fun recenterToGps() {
        modeBeforeSearch = null
        val fix = lastGpsFix
        if (fix != null) {
            viewModelScope.launch(Dispatchers.IO) { rankAndShow(fix.first, fix.second, anchorLabel = null) }
        } else {
            retryLocation()
        }
    }

    fun openSearch() {
        locateJob?.cancel()
        modeBeforeSearch = _mode.value
        _mode.value = NearbyStopsMode.Input()
    }

    fun cancelSearch() {
        val restore = modeBeforeSearch
        modeBeforeSearch = null
        // A Locating snapshot has no poll behind it anymore (openSearch cancelled it), so restoring
        // it as-is would leave the screen stuck on Locating -- start a fresh poll instead.
        if (restore != null && restore !is NearbyStopsMode.Locating) {
            _mode.value = restore
        } else {
            retryLocation()
        }
    }

    fun search(query: CharSequence) {
        val text = query.toString().trim()
        if (text.isEmpty()) return
        _mode.value = NearbyStopsMode.Searching
        viewModelScope.launch(Dispatchers.IO) {
            _mode.value = try {
                val results = geocoder.search(text)
                if (results.isEmpty()) {
                    NearbyStopsMode.Error("No matching location found.", NearbyStopsErrorRetry.Search)
                } else {
                    NearbyStopsMode.GeocodeResults(results)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("NearbyStopsScreen", "Geocoding failed for '$text'", e)
                NearbyStopsMode.Error("Unable to search that location.", NearbyStopsErrorRetry.Search)
            }
        }
    }

    fun selectGeocodeResult(result: GeocodeResult) {
        modeBeforeSearch = null
        _mode.value = NearbyStopsMode.Ranking
        viewModelScope.launch(Dispatchers.IO) { rankAndShow(result.lat, result.lon, anchorLabel = result.displayName) }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
        geocoder.close()
    }
}

class NearbyStopsScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val agency: GtfsAgency,
) : LightScreen<Unit, NearbyStopsViewModel>(sealedActivity) {

    override val viewModelClass: Class<NearbyStopsViewModel>
        get() = NearbyStopsViewModel::class.java

    override fun createViewModel(): NearbyStopsViewModel =
        NearbyStopsViewModel(dbFile, LocationPreferences(lightContext.dataStore))

    @Composable
    override fun Content() {
        val mode by viewModel.mode.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val locationPermissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.ACCESS_FINE_LOCATION)

        when (val m = mode) {
            is NearbyStopsMode.Input -> {
                val locationEnabled by viewModel.locationEnabled.collectAsState()
                val textFieldState = rememberTextFieldState(m.prefillText)
                // Submit-triggered, not live-filtered like Stations search -- this hits Nominatim's
                // free geocoding API (see this app's own "be kind to their free APIs" note), so it
                // should never fire on every keystroke, only when the rider actually asks to search.
                val keyboardCallback = remember(textFieldState) {
                    InlineTextFieldKeyboardCallback(state = textFieldState, onReturn = { viewModel.search(textFieldState.text) })
                }
                val keyboardViewModel: Lp3KeyboardViewModel<*> = rememberInlineLp3KeyboardViewModel(
                    key = "NearbyStopsSearchKeyboard",
                    callback = keyboardCallback,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                )
                LightTheme(colors = themeColors) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background)
                    ) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { viewModel.cancelSearch() }),
                            center = LightTopBarCenter.Text("Search Location"),
                        )
                        Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
                            BasicText(
                                text = textFieldState.text.toString(),
                                style = LightThemeTokens.typography.copy.copy(color = LightThemeTokens.colors.content),
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(LightThemeTokens.colors.content),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .lightClickable { viewModel.search(textFieldState.text) }
                                .padding(horizontal = 32.dp),
                        ) {
                            LightIcon(
                                icon = LightIcons.SEARCH,
                                size = 1.2f,
                                contentDescription = "Search",
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            LightText(text = "Search", variant = LightTextVariant.Copy, lighten = true)
                        }
                        // Always offered below Search -- the one exception is location turned off
                        // in Settings, since there's then no GPS fix for this to switch to at all.
                        if (locationEnabled) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .lightClickable { viewModel.recenterToGps() }
                                    .padding(horizontal = 32.dp, vertical = 8.dp),
                            ) {
                                LightIcon(
                                    icon = LightIcons.CROSSHAIR,
                                    size = 1.2f,
                                    contentDescription = "Current Location",
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                LightText(text = "Current Location", variant = LightTextVariant.Copy, lighten = true)
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)
                    }
                }
            }

            else -> LightTheme(colors = themeColors) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background)
                ) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("Nearby Stops"),
                        rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                            navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                        },
                    )
                    Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                    // Same row structure/spacing StationListScreen's own "Search stations" row uses
                    // (icon size 1.2f, lighten=true text, 12dp bottom gap, no divider) -- pinned
                    // regardless of body state below, since searching only ever changes which
                    // location this screen is ranking stops against, so it stays reachable the whole
                    // time. Also doubles as a live indicator of the current anchor: the magnifying
                    // glass is always shown (implying it's always replaceable), with either the
                    // searched address's own name, or "Current Location" plus a trailing crosshair
                    // when ranked against GPS instead. Tapping it always opens Search Location, which
                    // offers "Current Location" as its own option to switch back (see the Input
                    // branch above).
                    val searchedLabel = (m as? NearbyStopsMode.NearbyStops)?.anchorLabel
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.openSearch() }
                            .padding(bottom = 12.dp),
                    ) {
                        LightIcon(
                            icon = LightIcons.SEARCH,
                            size = 1.2f,
                            contentDescription = "Search",
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        if (searchedLabel != null) {
                            LightText(
                                text = searchedLabel,
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            LightText(text = "Current Location", variant = LightTextVariant.Copy, lighten = true)
                            LightIcon(
                                icon = LightIcons.CROSSHAIR,
                                size = 1f,
                                contentDescription = "Tracking current location",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    when (m) {
                        is NearbyStopsMode.Locating -> LightText(
                            text = "Finding your location...",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )

                        is NearbyStopsMode.NeedsPermission -> {
                            LightText(
                                text = "Enable location access to see stops near you.",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            LightText(
                                text = "Enable Location",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.lightClickable { locationPermissionLauncher?.launch() },
                            )
                        }

                        is NearbyStopsMode.LocationOff -> {
                            LightText(
                                text = "Location is turned off.",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            LightText(
                                text = "Go to Settings",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.lightClickable {
                                    navigateTo(screenFactory = { activity -> SettingsScreen(activity) })
                                },
                            )
                        }

                        is NearbyStopsMode.Searching -> LightText(
                            text = "Searching...",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )

                        is NearbyStopsMode.Ranking -> LightText(
                            text = "Loading nearby stops...",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )

                        is NearbyStopsMode.Error -> {
                            LightText(
                                text = m.message,
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            LightText(
                                text = "Try Again",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.lightClickable {
                                    when (m.retry) {
                                        NearbyStopsErrorRetry.Location -> viewModel.retryLocation()
                                        NearbyStopsErrorRetry.Search -> viewModel.openSearch()
                                    }
                                },
                            )
                        }

                        is NearbyStopsMode.GeocodeResults -> {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(m.results) { result ->
                                    LightText(
                                        text = result.displayName,
                                        variant = LightTextVariant.Copy,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .lightClickable { viewModel.selectGeocodeResult(result) }
                                            .padding(vertical = 12.dp),
                                    )
                                }
                            }
                            LightText(
                                text = "Location search © OpenStreetMap contributors",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }

                        is NearbyStopsMode.NearbyStops -> {
                            if (m.stops.isEmpty()) {
                                LightText(
                                    text = "No stops found.",
                                    variant = LightTextVariant.Copy,
                                    lighten = true,
                                )
                            } else {
                                LazyColumn(modifier = Modifier.weight(1f)) {
                                    items(m.stops) { stop ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .lightClickable {
                                                    navigateTo(screenFactory = { activity ->
                                                        // The full member list, not just one child platform -- a deduplicated station (see
                                                        // GtfsRepository.groupStationsByParent) can have several platforms
                                                        // each serving different lines (e.g. South Station), and arrivals
                                                        // need to be unioned across all of them, not just whichever one
                                                        // happened to be the dedup representative.
                                                        UpcomingArrivalsScreen(
                                                            activity,
                                                            dbFile,
                                                            agency,
                                                            stop.memberStopIds,
                                                            stop.displayLabel(),
                                                        )
                                                    })
                                                }
                                                .padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Weighted so a long stop name wraps within its own share of the row instead of first
                                            // greedily measuring against the row's full width and only then
                                            // discovering there's no room for the distance label, pushing it off
                                            // the right edge.
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                LightText(
                                                    text = stop.displayLabel(),
                                                    variant = LightTextVariant.Copy,
                                                )
                                                if (stop.isStation) {
                                                    LightIcon(
                                                        icon = LightIcons.DIRECTIONS_MIDDLE_FORK,
                                                        size = 1.2f,
                                                        contentDescription = "Transfer station",
                                                        modifier = Modifier.padding(start = 8.dp),
                                                    )
                                                }
                                            }
                                            LightText(
                                                text = stop.distanceLabel(),
                                                variant = LightTextVariant.Copy,
                                                lighten = true,
                                                modifier = Modifier.padding(start = 16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            LightText(
                                text = "Location search © OpenStreetMap contributors",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }

                        is NearbyStopsMode.Input -> Unit
                    }
                    }
                    BackToHomeFooter(onGoBackOnce = { goBack() })
                }
            }
        }
    }
}
