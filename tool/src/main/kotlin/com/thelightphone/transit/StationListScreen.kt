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
import com.thelightphone.transit.gtfs.AgencyPreferences
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

/** Search is offered once there are this many stations. */
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
    private val agencyPreferences: AgencyPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<StationListState>(StationListState.Loading)
    val state: StateFlow<StationListState> = _state

    /** Whether tap and hold opens a station's arrivals. Read once when the screen opens. */
    val tapHoldArrivalsEnabled = MutableStateFlow(true)

    /**
     * Whether a tap opens arrivals and tap and hold opens the platform map, instead of the reverse.
     */
    val stationTapArrivalsEnabled = MutableStateFlow(true)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            tapHoldArrivalsEnabled.value = tapHoldPreferences.tapHoldStationArrivalsEnabledFlow.first()
            stationTapArrivalsEnabled.value = tapHoldPreferences.stationTapArrivalsEnabledFlow.first()
            val mergeFeedStationsEnabled = agencyPreferences.mergeFeedStationsEnabledFlow.first()
            _state.value = try {
                StationListState.Loaded(repository.getAllStations(mergeFeedStationsEnabled))
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
 * Home's Station button: every multi-platform station, listed by name. Tapping one opens its
 * platform map (or its arrivals; see [stationTapArrivalsEnabled]).
 */
class StationListScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val agency: GtfsAgency,
) : LightScreen<Unit, StationListViewModel>(sealedActivity) {

    override val viewModelClass: Class<StationListViewModel>
        get() = StationListViewModel::class.java

    override fun createViewModel(): StationListViewModel =
        StationListViewModel(dbFile, TapHoldPreferences(lightContext.dataStore), AgencyPreferences(lightContext.dataStore))

    @Composable
    private fun StationRow(station: StopLocation, tapHoldArrivalsEnabled: Boolean, stationTapArrivalsEnabled: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // With "Tap opens arrivals" on, tap opens the station's arrivals and tap and hold
                // opens the platform map. Off, tap opens the map and tap and hold opens arrivals
                // when that's enabled.
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
            // Weighted so a long station name wraps instead of pushing out the icon.
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
     * Inline search, shown once the list is long enough (see STATION_SEARCH_MIN_COUNT): the
     * light-keyboard library docked under a live-filtered list.
     *
     * [textFieldState] is hoisted to [Content]. The keyboard's `viewModel(key = ...)` keeps its
     * first callback for the screen's lifetime, so a fresh state on each reopen would stop
     * receiving input.
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
        // Hoisted so it stays the same instance across reopening search; see SearchContent.
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

