package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.StopLocation
import com.thelightphone.transit.gtfs.TapHoldPreferences
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** Search is only worth surfacing once scrolling to find a station gets tedious -- most agencies
 * (RIPTA) have well under this many stations and never show it at all. */
private const val STATION_SEARCH_MIN_COUNT = 10

fun StopLocation.displayLabel(): String = stopName?.takeIf { it.isNotBlank() } ?: "Station $stopId"

sealed class StationListState {
    object Loading : StationListState()
    data class Loaded(val stations: List<StopLocation>) : StationListState()
    data class Error(val message: String) : StationListState()
}

class StationListViewModel(
    dbFile: File,
    private val tapHoldPreferences: TapHoldPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<StationListState>(StationListState.Loading)
    val state: StateFlow<StationListState> = _state

    /** Settings screen's "Tap and hold" toggle for this screen specifically (on by default) -- see
     * TapHoldPreferences.tapHoldStationArrivalsEnabledFlow. Read once at screen-open, same as every
     * other one-shot Settings read in this app. */
    val tapHoldArrivalsEnabled = MutableStateFlow(true)

    /** See TapHoldPreferences.stationTapArrivalsEnabledFlow -- on by default, swaps this screen's
     * tap/tap-and-hold gestures so a plain tap opens arrivals directly. Read once at screen-open,
     * same as [tapHoldArrivalsEnabled] above. */
    val stationTapArrivalsEnabled = MutableStateFlow(true)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            tapHoldArrivalsEnabled.value = tapHoldPreferences.tapHoldStationArrivalsEnabledFlow.first()
            stationTapArrivalsEnabled.value = tapHoldPreferences.stationTapArrivalsEnabledFlow.first()
            _state.value = try {
                StationListState.Loaded(repository.getAllStations())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("StationListScreen", "Failed to load stations", e)
                StationListState.Error("Unable to load stations.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

/**
 * HomeScreen's "Station" entry point: every real multi-platform station this agency has, listed
 * directly (see GtfsRepository.getAllStations) -- unlike "Explore", this never asks the rider to
 * search a location first, since a rider looking for a specific station already knows its name.
 * Tapping one opens its Map-Station sub-map directly (see MapStationScreen).
 */
class StationListScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val agency: GtfsAgency,
) : LightScreen<Unit, StationListViewModel>(sealedActivity) {

    override val viewModelClass: Class<StationListViewModel>
        get() = StationListViewModel::class.java

    override fun createViewModel(): StationListViewModel =
        StationListViewModel(dbFile, TapHoldPreferences(lightContext.dataStore))

    @Composable
    private fun StationRow(station: StopLocation, tapHoldArrivalsEnabled: Boolean, stationTapArrivalsEnabled: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // With stationTapArrivalsEnabled (on by default, this screen only -- see
                // TapHoldPreferences.stationTapArrivalsEnabledFlow), a short tap jumps straight to the
                // station's actual (live) upcoming arrivals across every platform, and tap-and-hold opens
                // its platform map instead -- the map is still one tap away from there via Upcoming
                // Arrivals' own "Selected stop" row. Off, gestures revert to the original assignment: a
                // short tap opens the platform map, and tapHoldArrivalsEnabled gates whether tap-and-hold
                // opens arrivals.
                .pointerInput(station.stopId, stationTapArrivalsEnabled, tapHoldArrivalsEnabled) {
                    detectTapGestures(
                        onTap = {
                            if (stationTapArrivalsEnabled) {
                                navigateTo(screenFactory = { activity ->
                                    UpcomingArrivalsScreen(activity, dbFile, agency, station.memberStopIds, station.displayLabel())
                                })
                            } else {
                                navigateTo(screenFactory = { activity ->
                                    MapStationScreen(activity, dbFile, agency, station.memberStopIds, station.displayLabel())
                                })
                            }
                        },
                        onLongPress = {
                            if (stationTapArrivalsEnabled) {
                                navigateTo(screenFactory = { activity ->
                                    MapStationScreen(activity, dbFile, agency, station.memberStopIds, station.displayLabel())
                                })
                            } else {
                                if (!tapHoldArrivalsEnabled) return@detectTapGestures
                                navigateTo(screenFactory = { activity ->
                                    UpcomingArrivalsScreen(activity, dbFile, agency, station.memberStopIds, station.displayLabel())
                                })
                            }
                        },
                    )
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Weighted so a long station name wraps within its own bounded share of the row, leaving
            // guaranteed room for the icon, instead of first greedily measuring against the row's full
            // width -- see NearbyStopsScreen's identical fix for the same Compose behavior.
            LightText(
                text = station.displayLabel(),
                variant = LightTextVariant.Copy,
                modifier = Modifier.weight(1f, fill = false),
            )
            LightIcon(
                icon = LightIcons.DIRECTIONS_MIDDLE_FORK,
                size = 1.2f,
                contentDescription = "Transfer station",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    /**
     * Inline live-filter search, shown only once scrolling the full list gets tedious (see
     * STATION_SEARCH_MIN_COUNT). Docks Light's own public `light-keyboard` library directly (see
     * [InlineTextFieldKeyboardCallback]'s own doc) alongside a live-filtered [LazyColumn], instead of
     * sdk/ui's full-screen `LightTextInputEditor` (which has no room for a results list of its own).
     *
     * [textFieldState] is passed in (hoisted to [Content], not created here) rather than via its
     * own `rememberTextFieldState` -- this composable is only ever in composition while search is
     * active, so a locally-created state would be a fresh instance every time search reopens.
     * `viewModel(key = "StationSearchKeyboard", ...)` below only calls its factory the very first
     * time this screen's ViewModelStore sees that key, so a local state would silently keep typing
     * into the first session's already-discarded instance on every later reopen. Hoisting the
     * state keeps it the same single instance across every reopen, so the callback captured on the
     * first open stays correctly wired for the screen's entire lifetime.
     */
    @Composable
    private fun SearchContent(
        stations: List<StopLocation>,
        tapHoldArrivalsEnabled: Boolean,
        stationTapArrivalsEnabled: Boolean,
        textFieldState: TextFieldState,
        onBack: () -> Unit,
    ) {
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val keyboardCallback = remember(textFieldState) {
            InlineTextFieldKeyboardCallback(state = textFieldState)
        }
        val keyboardViewModel: Lp3KeyboardViewModel<*> = rememberInlineLp3KeyboardViewModel(
            key = "StationSearchKeyboard",
            callback = keyboardCallback,
            keyboardOptionsFlow = keyboardOptionsFlow,
        )
        val query = textFieldState.text.toString()
        val filtered = remember(query, stations) {
            if (query.isBlank()) stations else stations.filter { it.displayLabel().contains(query, ignoreCase = true) }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
                center = LightTopBarCenter.Text("Search Stations"),
            )
            Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
                BasicText(
                    text = query,
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
            if (filtered.isEmpty()) {
                LightText(
                    text = "No stations found.",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 32.dp)) {
                items(filtered, key = { it.stopId }) { station -> StationRow(station, tapHoldArrivalsEnabled, stationTapArrivalsEnabled) }
            }
            LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val tapHoldArrivalsEnabled by viewModel.tapHoldArrivalsEnabled.collectAsState()
        val stationTapArrivalsEnabled by viewModel.stationTapArrivalsEnabled.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        var searchActive by remember { mutableStateOf(false) }
        // See SearchContent's own doc -- hoisted here (not created inside SearchContent) so it
        // stays the same instance across every close/reopen of search, not a fresh one each time.
        val searchTextFieldState = rememberTextFieldState("")

        LightTheme(colors = themeColors) {
            val loadedStations = (state as? StationListState.Loaded)?.stations.orEmpty()
            if (searchActive && loadedStations.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background)
                ) {
                    SearchContent(loadedStations, tapHoldArrivalsEnabled, stationTapArrivalsEnabled, searchTextFieldState, onBack = { searchActive = false })
                }
                return@LightTheme
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Stations"),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                when (val s = state) {
                    is StationListState.Loading -> LightText(
                        text = "Loading stations...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is StationListState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is StationListState.Loaded -> if (s.stations.isEmpty()) {
                        LightText(
                            text = "No stations found.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        if (s.stations.size > STATION_SEARCH_MIN_COUNT) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable { searchActive = true }
                                    .padding(bottom = 12.dp),
                            ) {
                                LightIcon(
                                    icon = LightIcons.SEARCH,
                                    size = 1.2f,
                                    contentDescription = "Search stations",
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                LightText(text = "Search stations", variant = LightTextVariant.Copy, lighten = true)
                            }
                        }
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.stations, key = { it.stopId }) { station -> StationRow(station, tapHoldArrivalsEnabled, stationTapArrivalsEnabled) }
                        }
                    }
                }
                }
                BackToHomeFooter(onGoBackOnce = { goBack() })
            }
        }
    }
}

