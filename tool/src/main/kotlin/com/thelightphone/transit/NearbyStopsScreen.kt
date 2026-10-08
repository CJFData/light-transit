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
import com.thelightphone.transit.gtfs.DefaultLocation
import com.thelightphone.transit.gtfs.GeocodeResult
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.LocationPreferences
import com.thelightphone.transit.gtfs.NominatimGeocoder
import com.thelightphone.transit.gtfs.SavedLocation
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
// About 12 seconds of polling for a fix before falling back to search.
private const val LOCATION_POLL_ATTEMPTS = 8
private const val LOCATION_POLL_INTERVAL_MS = 1500L

/**
 * One GPS reading from LightOS, or null if none arrives in time. Holds a location lease only while
 * waiting for it.
 */
suspend fun readGpsOnce(): Pair<Double, Double>? {
    callRemoteServiceMethod(LightServiceMethod.RequestLocationUpdates, Unit)
    try {
        repeat(LOCATION_POLL_ATTEMPTS) { attempt ->
            val fix = callRemoteServiceMethod(LightServiceMethod.GetCurrentLocation, Unit).getOrNull()
            val lat = fix?.latitude
            val lon = fix?.longitude
            if (lat != null && lon != null) return lat to lon
            if (attempt < LOCATION_POLL_ATTEMPTS - 1) delay(LOCATION_POLL_INTERVAL_MS)
        }
        return null
    } finally {
        withContext(NonCancellable) {
            callRemoteServiceMethod(LightServiceMethod.ReleaseLocationUpdates, Unit)
        }
    }
}

/** What "Try Again" does: retry GPS, or reopen address entry after a search failure. */
enum class NearbyStopsErrorRetry { Location, Search }

/**
 * The screen shows the stops closest to a location, from GPS or a searched address. [Input],
 * [Searching], and [GeocodeResults] take over the screen for address entry; the rest share a frame
 * with the pinned Search row.
 */
sealed class NearbyStopsMode {
    /** Waiting for location permission or a fix. Also re-entered on resume. */
    object Locating : NearbyStopsMode()
    /** Location permission isn't granted. Search still works. */
    object NeedsPermission : NearbyStopsMode()
    /** Location is turned off in Settings, so there's nothing to grant. */
    object LocationOff : NearbyStopsMode()
    data class Input(val prefillText: String = "") : NearbyStopsMode()
    object Searching : NearbyStopsMode()
    data class GeocodeResults(val results: List<GeocodeResult>) : NearbyStopsMode()
    /** Ranking stops around a new GPS fix or search result. */
    object Ranking : NearbyStopsMode()
    /**
     * [anchorLabel] is null for GPS, or the searched address's name. Sets the Search row's text and
     * which retry a failure offers. [isDefault] is true when ranked around the default location.
     */
    data class NearbyStops(
        val stops: List<StopWithDistance>,
        val anchorLabel: String?,
        val isDefault: Boolean = false,
    ) : NearbyStopsMode()
    data class Error(val message: String, val retry: NearbyStopsErrorRetry) : NearbyStopsMode()
}

fun StopWithDistance.displayLabel(): String = stopName?.takeIf { it.isNotBlank() } ?: "Stop $stopId"

fun StopWithDistance.distanceLabel(): String = "%.1f mi".format(distanceMeters / METERS_PER_MILE)

class NearbyStopsViewModel(dbFile: File, private val locationPreferences: LocationPreferences) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val geocoder = NominatimGeocoder()

    private val _mode = MutableStateFlow<NearbyStopsMode>(NearbyStopsMode.Locating)
    val mode: StateFlow<NearbyStopsMode> = _mode

    /** The last GPS fix, so switching back to Current Location is instant. */
    private var lastGpsFix: Pair<Double, Double>? = null

    /** Where the current list is ranked from, for "Set as default"; null for GPS. */
    private var lastAnchor: SavedLocation? = null

    /** The mode before [openSearch], restored when search is cancelled. */
    private var modeBeforeSearch: NearbyStopsMode? = null

    /**
     * The running GPS poll. [openSearch] cancels it so a late result can't replace the address
     * input.
     */
    private var locateJob: Job? = null

    /** Whether Search offers "Current Location". */
    val locationEnabled: StateFlow<Boolean>
        get() = _locationEnabled
    private val _locationEnabled = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            locationPreferences.locationEnabledFlow.collect { _locationEnabled.value = it }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Runs on first show, and on resume while still waiting on location (e.g. back from the
        // permission prompt or Settings), but never once results or a search are showing.
        if (_mode.value !is NearbyStopsMode.Locating &&
            _mode.value !is NearbyStopsMode.NeedsPermission &&
            _mode.value !is NearbyStopsMode.LocationOff
        ) return
        start()
    }

    /** Opens on the default location when there is one, otherwise tries GPS. */
    private fun start() {
        locateJob?.cancel()
        locateJob = viewModelScope.launch(Dispatchers.IO) {
            val default = locationPreferences.defaultLocationFlow.first()
            if (default is DefaultLocation.Place) {
                val place = default.location
                rankAndShow(place.lat, place.lon, anchorLabel = place.label, isDefault = true)
            } else {
                locateFromGps()
            }
        }
    }

    /** Makes the list's place the default: the searched address, or current location for GPS. */
    fun setAsDefault() {
        val anchor = lastAnchor
        viewModelScope.launch(Dispatchers.IO) {
            if (anchor != null) locationPreferences.setDefaultLocation(anchor)
            else locationPreferences.setDefaultToCurrentLocation()
            (_mode.value as? NearbyStopsMode.NearbyStops)?.let { _mode.value = it.copy(isDefault = true) }
        }
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
        // Home may already have requested location when Explore was tapped; asking again just
        // renews it.
        val fix = readGpsOnce()
        if (fix != null) {
            lastGpsFix = fix
            val isDefault = locationPreferences.defaultLocationFlow.first() is DefaultLocation.Current
            rankAndShow(fix.first, fix.second, anchorLabel = null, isDefault = isDefault)
        } else {
            _mode.value = NearbyStopsMode.Error("Couldn't find your location.", NearbyStopsErrorRetry.Location)
        }
    }

    private suspend fun rankAndShow(lat: Double, lon: Double, anchorLabel: String?, isDefault: Boolean = false) {
        lastAnchor = anchorLabel?.let { SavedLocation(lat, lon, it) }
        _mode.value = try {
            NearbyStopsMode.NearbyStops(repository.rankStopsByDistance(lat, lon, NEARBY_STOP_LIMIT), anchorLabel, isDefault)
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

    /**
     * Explore's "Use my location": turns the setting on, asks for permission if it's never been
     * asked, then looks up the location.
     */
    fun enableLocation(requestPermission: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            locationPreferences.setLocationEnabled(true)
            val result = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION).getOrNull()?.permissionResult
            if (result == LightServiceMethod.GetPermission.Result.Unknown) {
                withContext(Dispatchers.Main) { requestPermission() }
            }
            retryLocation()
        }
    }

    /** Switches back to ranking by GPS, from Search's "Current Location" option. */
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
        // openSearch cancelled the poll, so restoring Locating needs a new one.
        if (restore != null && restore !is NearbyStopsMode.Locating) {
            _mode.value = restore
        } else {
            start()
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
                // Searches on submit rather than on every keystroke, to go easy on the free
                // geocoding API.
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
                        // Hidden only when location is off in Settings.
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
                    // The pinned Search row, styled like Stations search. Shows the current
                    // location: the searched address, or "Current Location" with a crosshair.
                    // Tapping it opens Search.
                    val searchedLabel = (m as? NearbyStopsMode.NearbyStops)?.anchorLabel
                    val locationOn by viewModel.locationEnabled.collectAsState()
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
                        } else if (!locationOn) {
                            LightText(text = "Search an address", variant = LightTextVariant.Copy, lighten = true)
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
                    if (m is NearbyStopsMode.NearbyStops && !m.isDefault) {
                        LightText(
                            text = "Set as default location",
                            variant = LightTextVariant.Detail,
                            modifier = Modifier
                                .lightClickable { viewModel.setAsDefault() }
                                .padding(bottom = 12.dp),
                        )
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
                                text = "Search an address above, or use your location to see stops near you. " +
                                    "It's only used on your phone.",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            LightText(
                                text = "Use my location",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.lightClickable {
                                    viewModel.enableLocation(requestPermission = { locationPermissionLauncher?.launch() })
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
                                                        // Every platform of the station, so
                                                        // arrivals cover all of them.
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
                                            // Weighted so a long stop name wraps instead of pushing
                                            // the distance off screen.
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
