package com.thelightphone.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.AgencyPreferences
import com.thelightphone.transit.gtfs.BoardedTripPreferences
import com.thelightphone.transit.gtfs.DeparturePreferences
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.HomeScreenPreferences
import com.thelightphone.transit.gtfs.MapPreferences
import com.thelightphone.transit.gtfs.TapHoldPreferences
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModalManager
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration

class SettingsViewModel(
    private val agencyPreferences: AgencyPreferences,
    private val mapPreferences: MapPreferences,
    private val boardedTripPreferences: BoardedTripPreferences,
    private val homeScreenPreferences: HomeScreenPreferences,
    private val tapHoldPreferences: TapHoldPreferences,
    private val departurePreferences: DeparturePreferences,
) : LightViewModel<Unit>() {

    val defaultAgency: StateFlow<GtfsAgency?>
        get() = _defaultAgency
    private val _defaultAgency = MutableStateFlow<GtfsAgency?>(null)

    val darkMapEnabled: StateFlow<Boolean>
        get() = _darkMapEnabled
    private val _darkMapEnabled = MutableStateFlow(true)

    val tapHoldArrivalsEnabled: StateFlow<Boolean>
        get() = _tapHoldArrivalsEnabled
    private val _tapHoldArrivalsEnabled = MutableStateFlow(true)

    val tapHoldScheduleArrivalsEnabled: StateFlow<Boolean>
        get() = _tapHoldScheduleArrivalsEnabled
    private val _tapHoldScheduleArrivalsEnabled = MutableStateFlow(true)

    val tapHoldStationArrivalsEnabled: StateFlow<Boolean>
        get() = _tapHoldStationArrivalsEnabled
    private val _tapHoldStationArrivalsEnabled = MutableStateFlow(true)

    val tapHoldVehicleEnabled: StateFlow<Boolean>
        get() = _tapHoldVehicleEnabled
    private val _tapHoldVehicleEnabled = MutableStateFlow(true)

    val doubleTapStationEnabled: StateFlow<Boolean>
        get() = _doubleTapStationEnabled
    private val _doubleTapStationEnabled = MutableStateFlow(true)

    val trackTappedStopsEnabled: StateFlow<Boolean>
        get() = _trackTappedStopsEnabled
    private val _trackTappedStopsEnabled = MutableStateFlow(false)

    val seeEverythingEnabled: StateFlow<Boolean>
        get() = _seeEverythingEnabled
    private val _seeEverythingEnabled = MutableStateFlow(true)

    val filterByStopEnabled: StateFlow<Boolean>
        get() = _filterByStopEnabled
    private val _filterByStopEnabled = MutableStateFlow(false)

    val seeEverythingShowBus: StateFlow<Boolean>
        get() = _seeEverythingShowBus
    private val _seeEverythingShowBus = MutableStateFlow(true)

    val seeEverythingShowSubway: StateFlow<Boolean>
        get() = _seeEverythingShowSubway
    private val _seeEverythingShowSubway = MutableStateFlow(true)

    val seeEverythingShowCommuterRail: StateFlow<Boolean>
        get() = _seeEverythingShowCommuterRail
    private val _seeEverythingShowCommuterRail = MutableStateFlow(true)

    val progressBarVisible: StateFlow<Boolean>
        get() = _progressBarVisible
    private val _progressBarVisible = MutableStateFlow(true)

    val dailyMessageVisible: StateFlow<Boolean>
        get() = _dailyMessageVisible
    private val _dailyMessageVisible = MutableStateFlow(true)

    val dailyMessageRandom: StateFlow<Boolean>
        get() = _dailyMessageRandom
    private val _dailyMessageRandom = MutableStateFlow(false)

    val mergeFeedStationsEnabled: StateFlow<Boolean>
        get() = _mergeFeedStationsEnabled
    private val _mergeFeedStationsEnabled = MutableStateFlow(true)

    val includeLongerTripsEnabled: StateFlow<Boolean>
        get() = _includeLongerTripsEnabled
    private val _includeLongerTripsEnabled = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            agencyPreferences.defaultAgencyFlow.collect { _defaultAgency.value = it }
        }
        viewModelScope.launch {
            mapPreferences.darkMapEnabledFlow.collect { _darkMapEnabled.value = it }
        }
        viewModelScope.launch {
            mapPreferences.tapHoldArrivalsEnabledFlow.collect { _tapHoldArrivalsEnabled.value = it }
        }
        viewModelScope.launch {
            tapHoldPreferences.tapHoldScheduleArrivalsEnabledFlow.collect { _tapHoldScheduleArrivalsEnabled.value = it }
        }
        viewModelScope.launch {
            tapHoldPreferences.tapHoldStationArrivalsEnabledFlow.collect { _tapHoldStationArrivalsEnabled.value = it }
        }
        viewModelScope.launch {
            tapHoldPreferences.tapHoldVehicleEnabledFlow.collect { _tapHoldVehicleEnabled.value = it }
        }
        viewModelScope.launch {
            mapPreferences.doubleTapStationEnabledFlow.collect { _doubleTapStationEnabled.value = it }
        }
        viewModelScope.launch {
            mapPreferences.trackTappedStopsEnabledFlow.collect { _trackTappedStopsEnabled.value = it }
        }
        viewModelScope.launch {
            mapPreferences.seeEverythingEnabledFlow.collect { _seeEverythingEnabled.value = it }
        }
        viewModelScope.launch {
            mapPreferences.filterByStopEnabledFlow.collect { _filterByStopEnabled.value = it }
        }
        viewModelScope.launch {
            mapPreferences.seeEverythingShowBusFlow.collect { _seeEverythingShowBus.value = it }
        }
        viewModelScope.launch {
            mapPreferences.seeEverythingShowSubwayFlow.collect { _seeEverythingShowSubway.value = it }
        }
        viewModelScope.launch {
            mapPreferences.seeEverythingShowCommuterRailFlow.collect { _seeEverythingShowCommuterRail.value = it }
        }
        viewModelScope.launch {
            boardedTripPreferences.progressBarVisibleFlow.collect { _progressBarVisible.value = it }
        }
        viewModelScope.launch {
            homeScreenPreferences.dailyMessageVisibleFlow.collect { _dailyMessageVisible.value = it }
        }
        viewModelScope.launch {
            homeScreenPreferences.dailyMessageRandomFlow.collect { _dailyMessageRandom.value = it }
        }
        viewModelScope.launch {
            agencyPreferences.mergeFeedStationsEnabledFlow.collect { _mergeFeedStationsEnabled.value = it }
        }
        viewModelScope.launch {
            departurePreferences.includeLongerTripsEnabledFlow.collect { _includeLongerTripsEnabled.value = it }
        }
    }

    /** Called from the AgencyPickerModal opened by the "Transit Agency" row below -- persisting the
     * new default is all this does. HomeScreenViewModel's own defaultAgencyFlow collector (not this
     * call) is what actually switches/ingests it, so this is the same effect a first-launch pick
     * has, just reached from Settings instead. */
    fun selectAgency(agency: GtfsAgency) {
        viewModelScope.launch {
            agencyPreferences.setDefaultAgency(agency)
        }
    }

    fun setDarkMapEnabled(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setDarkMapEnabled(enabled) }
    }

    fun setTapHoldArrivalsEnabled(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setTapHoldArrivalsEnabled(enabled) }
    }

    fun setTapHoldScheduleArrivalsEnabled(enabled: Boolean) {
        viewModelScope.launch { tapHoldPreferences.setTapHoldScheduleArrivalsEnabled(enabled) }
    }

    fun setTapHoldStationArrivalsEnabled(enabled: Boolean) {
        viewModelScope.launch { tapHoldPreferences.setTapHoldStationArrivalsEnabled(enabled) }
    }

    fun setTapHoldVehicleEnabled(enabled: Boolean) {
        viewModelScope.launch { tapHoldPreferences.setTapHoldVehicleEnabled(enabled) }
    }

    fun setDoubleTapStationEnabled(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setDoubleTapStationEnabled(enabled) }
    }

    fun setTrackTappedStopsEnabled(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setTrackTappedStopsEnabled(enabled) }
    }

    fun setSeeEverythingEnabled(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setSeeEverythingEnabled(enabled) }
    }

    fun setFilterByStopEnabled(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setFilterByStopEnabled(enabled) }
    }

    fun setSeeEverythingShowBus(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setSeeEverythingShowBus(enabled) }
    }

    fun setSeeEverythingShowSubway(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setSeeEverythingShowSubway(enabled) }
    }

    fun setSeeEverythingShowCommuterRail(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setSeeEverythingShowCommuterRail(enabled) }
    }

    fun setProgressBarVisible(visible: Boolean) {
        viewModelScope.launch { boardedTripPreferences.setProgressBarVisible(visible) }
    }

    fun setDailyMessageVisible(visible: Boolean) {
        viewModelScope.launch { homeScreenPreferences.setDailyMessageVisible(visible) }
    }

    fun setDailyMessageRandom(random: Boolean) {
        viewModelScope.launch { homeScreenPreferences.setDailyMessageRandom(random) }
    }

    fun setMergeFeedStationsEnabled(enabled: Boolean) {
        viewModelScope.launch { agencyPreferences.setMergeFeedStationsEnabled(enabled) }
    }

    fun setIncludeLongerTripsEnabled(enabled: Boolean) {
        viewModelScope.launch { departurePreferences.setIncludeLongerTripsEnabled(enabled) }
    }
}

class SettingsScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel = SettingsViewModel(
        AgencyPreferences(lightContext.dataStore),
        MapPreferences(lightContext.dataStore),
        BoardedTripPreferences(lightContext.dataStore),
        HomeScreenPreferences(lightContext.dataStore),
        TapHoldPreferences(lightContext.dataStore),
        DeparturePreferences(lightContext.dataStore),
    )

    /** Every on/off setting on this screen renders as one tappable row using the SDK's own
     * toggle-state icon, replacing the old two-separate-rows "On"/"Off" list this screen used to
     * use for [SettingsViewModel.tapHoldArrivalsEnabled]/[SettingsViewModel.doubleTapStationEnabled]. */
    @Composable
    private fun ToggleRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { onToggle(!enabled) }
                .padding(vertical = 12.dp),
        ) {
            LightIcon(
                icon = if (enabled) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                size = 1.2f,
                contentDescription = if (enabled) "On" else "Off",
                modifier = Modifier.padding(end = 12.dp),
            )
            LightText(text = label, variant = LightTextVariant.Copy)
        }
    }

    @Composable
    override fun Content() {
        val defaultAgency by viewModel.defaultAgency.collectAsState()
        val darkMapEnabled by viewModel.darkMapEnabled.collectAsState()
        val tapHoldArrivalsEnabled by viewModel.tapHoldArrivalsEnabled.collectAsState()
        val tapHoldScheduleArrivalsEnabled by viewModel.tapHoldScheduleArrivalsEnabled.collectAsState()
        val tapHoldStationArrivalsEnabled by viewModel.tapHoldStationArrivalsEnabled.collectAsState()
        val tapHoldVehicleEnabled by viewModel.tapHoldVehicleEnabled.collectAsState()
        val doubleTapStationEnabled by viewModel.doubleTapStationEnabled.collectAsState()
        val trackTappedStopsEnabled by viewModel.trackTappedStopsEnabled.collectAsState()
        val seeEverythingEnabled by viewModel.seeEverythingEnabled.collectAsState()
        val filterByStopEnabled by viewModel.filterByStopEnabled.collectAsState()
        val seeEverythingShowBus by viewModel.seeEverythingShowBus.collectAsState()
        val seeEverythingShowSubway by viewModel.seeEverythingShowSubway.collectAsState()
        val seeEverythingShowCommuterRail by viewModel.seeEverythingShowCommuterRail.collectAsState()
        val progressBarVisible by viewModel.progressBarVisible.collectAsState()
        val dailyMessageVisible by viewModel.dailyMessageVisible.collectAsState()
        val dailyMessageRandom by viewModel.dailyMessageRandom.collectAsState()
        val mergeFeedStationsEnabled by viewModel.mergeFeedStationsEnabled.collectAsState()
        val includeLongerTripsEnabled by viewModel.includeLongerTripsEnabled.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Settings"),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(32.dp)) {
                LightText(
                    text = "Transit Agency",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LightText(
                    text = "Tap to switch to a different agency.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            // Same AgencyPickerModal Stage 1 onboarding uses, just with a close
                            // button (allowCancel = true) since -- unlike first launch -- there's
                            // already a valid agency to fall back to here.
                            LightModalManager.show(
                                modal = AgencyPickerModal(
                                    filesDir = lightContext.filesDir,
                                    allowCancel = true,
                                    onAgencySelected = { agency -> viewModel.selectAgency(agency) },
                                ),
                                duration = Duration.INFINITE,
                            )
                        }
                        .padding(vertical = 12.dp),
                ) {
                    LightText(
                        text = defaultAgency?.displayName ?: "Choose agency",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.weight(1f),
                    )
                    LightIcon(
                        icon = LightIcons.ARROW_RIGHT,
                        size = 1f,
                        contentDescription = "Change agency",
                    )
                }

                LightText(
                    text = "Merge feed stations",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on, a merged secondary feed's stops at the same physical station as " +
                        "one of its parent agency's own are grouped into that station -- e.g. Bustang's " +
                        "gates at RTD Denver's Union Station.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Merge feed stations", mergeFeedStationsEnabled, viewModel::setMergeFeedStationsEnabled)

                LightText(
                    text = "Map style",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "Choose which map tiles the Map screen uses.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Column {
                    LightText(
                        text = "Light",
                        variant = LightTextVariant.Copy,
                        lighten = darkMapEnabled,
                        underline = !darkMapEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.setDarkMapEnabled(false) }
                            .padding(vertical = 12.dp),
                    )
                    LightText(
                        text = "Dark",
                        variant = LightTextVariant.Copy,
                        lighten = !darkMapEnabled,
                        underline = darkMapEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.setDarkMapEnabled(true) }
                            .padding(vertical = 12.dp),
                    )
                }

                LightText(
                    text = "Tap and hold to view arrivals",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on (the default for all three below), tap and hold a stop or station to " +
                        "jump straight to its live upcoming arrivals instead of whatever a plain tap would " +
                        "open there. Three separate toggles since each covers a different screen: Map (any " +
                        "stop marker, or a station's own name while already viewing its platform map), " +
                        "Schedules (a stop while choosing where to board, after picking a route and " +
                        "direction -- a plain tap there still shows that route's scheduled times either " +
                        "way), and Stations (a row in the Stations list -- a plain tap there still opens " +
                        "its platform map either way).",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Map", tapHoldArrivalsEnabled, viewModel::setTapHoldArrivalsEnabled)
                ToggleRow("Schedules", tapHoldScheduleArrivalsEnabled, viewModel::setTapHoldScheduleArrivalsEnabled)
                ToggleRow("Stations", tapHoldStationArrivalsEnabled, viewModel::setTapHoldStationArrivalsEnabled)

                LightText(
                    text = "Include longer trips in departures",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on (the default), a route/direction's departures list also includes " +
                        "any trip that runs at least as far as the direction you picked -- e.g. picking " +
                        "\"Toward Readville\" on MBTA's Franklin/Foxboro Line also shows \"Toward South " +
                        "Station\" departures at a stop they share, since either one gets you to " +
                        "Readville. It never works the other way around: picking \"Toward South Station\" " +
                        "never shows a Readville-only departure, since that trip doesn't reach that far. " +
                        "When off, departures match the picked direction exactly, with no broader trips " +
                        "mixed in either way.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Include longer trips in departures", includeLongerTripsEnabled, viewModel::setIncludeLongerTripsEnabled)

                LightText(
                    text = "Tap and hold -- Vehicles",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on, tap and hold a live vehicle on the Map screen or a Station map to " +
                        "open that vehicle's own Trip Detail.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Tap and hold -- Vehicles", tapHoldVehicleEnabled, viewModel::setTapHoldVehicleEnabled)

                LightText(
                    text = "Double-tap to open a station",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on, double-tap a multi-platform station on the Map screen to open a " +
                        "zoomed-in view of just its platforms -- and, symmetrically, double-tap a " +
                        "station's own name while viewing its Station map to zoom back out to the " +
                        "main map, centered on that station.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Double-tap to open a station", doubleTapStationEnabled, viewModel::setDoubleTapStationEnabled)

                LightText(
                    text = "Track tapped stops",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on, a nearby stop you've tapped open on the Map screen also contributes its " +
                        "own live vehicles to the map, not just its name label.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Track tapped stops", trackTappedStopsEnabled, viewModel::setTrackTappedStopsEnabled)

                LightText(
                    text = "See everything",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on (the default), the Map screen and Station map show every live vehicle " +
                        "in view, not just ones actually relevant to the stop you're looking at -- each " +
                        "labeled with just its route until tapped, which shows its full details. When off, " +
                        "the map shows only vehicles whose own trip is scheduled to serve a stop currently " +
                        "in view, matched against that stop's own upcoming departures -- fewer markers, but " +
                        "each one is guaranteed relevant to a stop on screen.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("See everything", seeEverythingEnabled, viewModel::setSeeEverythingEnabled)

                if (seeEverythingEnabled) {
                    LightText(
                        text = "Filter by stop",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    LightText(
                        text = "When on, tap a stop on the map to narrow \"See everything\" down to just " +
                            "vehicles heading to, arrived at, or departed from it -- labeled e.g. \"SL1-TO\", " +
                            "\"SL1-AT\", \"SL1-FROM\".",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    ToggleRow("Filter by stop", filterByStopEnabled, viewModel::setFilterByStopEnabled)

                    LightText(
                        text = "Modes shown",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    LightText(
                        text = "Which vehicle types \"See everything\" plots -- all three on by default.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    ToggleRow("Bus", seeEverythingShowBus, viewModel::setSeeEverythingShowBus)
                    ToggleRow("Subway", seeEverythingShowSubway, viewModel::setSeeEverythingShowSubway)
                    ToggleRow("Commuter Rail", seeEverythingShowCommuterRail, viewModel::setSeeEverythingShowCommuterRail)
                }

                LightText(
                    text = "Trip progress bar",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on, HomeScreen shows a progress bar from your boarding stop to your alight " +
                        "stop while a trip is boarded.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Trip progress bar", progressBarVisible, viewModel::setProgressBarVisible)

                LightText(
                    text = "Daily message",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on, HomeScreen shows a small rotating message near the bottom of the screen.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Daily message", dailyMessageVisible, viewModel::setDailyMessageVisible)

                if (dailyMessageVisible) {
                    LightText(
                        text = "Randomize daily message",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    LightText(
                        text = "When on, the message is picked at random every time you return to " +
                            "the home screen, instead of once per calendar day.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    ToggleRow("Randomize daily message", dailyMessageRandom, viewModel::setDailyMessageRandom)
                }
                }
                BackToHomeFooter(onGoBackOnce = { goBack() })
            }
        }
    }
}
