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
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.MapPreferences
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
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

class SettingsViewModel(
    private val agencyPreferences: AgencyPreferences,
    private val mapPreferences: MapPreferences,
    private val boardedTripPreferences: BoardedTripPreferences,
) : LightViewModel<Unit>() {

    val defaultAgency: StateFlow<GtfsAgency?>
        get() = _defaultAgency
    private val _defaultAgency = MutableStateFlow<GtfsAgency?>(null)

    val darkMapEnabled: StateFlow<Boolean>
        get() = _darkMapEnabled
    private val _darkMapEnabled = MutableStateFlow(false)

    val tapHoldArrivalsEnabled: StateFlow<Boolean>
        get() = _tapHoldArrivalsEnabled
    private val _tapHoldArrivalsEnabled = MutableStateFlow(false)

    val doubleTapStationEnabled: StateFlow<Boolean>
        get() = _doubleTapStationEnabled
    private val _doubleTapStationEnabled = MutableStateFlow(false)

    val trackTappedStopsEnabled: StateFlow<Boolean>
        get() = _trackTappedStopsEnabled
    private val _trackTappedStopsEnabled = MutableStateFlow(false)

    val progressBarVisible: StateFlow<Boolean>
        get() = _progressBarVisible
    private val _progressBarVisible = MutableStateFlow(true)

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
            mapPreferences.doubleTapStationEnabledFlow.collect { _doubleTapStationEnabled.value = it }
        }
        viewModelScope.launch {
            mapPreferences.trackTappedStopsEnabledFlow.collect { _trackTappedStopsEnabled.value = it }
        }
        viewModelScope.launch {
            boardedTripPreferences.progressBarVisibleFlow.collect { _progressBarVisible.value = it }
        }
    }

    /** Tapping the already-selected default clears it back to "no default", so there's a way to
     * turn the auto-skip back off, not just switch which agency it points at. */
    fun toggleDefaultAgency(agency: GtfsAgency) {
        viewModelScope.launch {
            agencyPreferences.setDefaultAgency(if (defaultAgency.value == agency) null else agency)
        }
    }

    fun setDarkMapEnabled(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setDarkMapEnabled(enabled) }
    }

    fun setTapHoldArrivalsEnabled(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setTapHoldArrivalsEnabled(enabled) }
    }

    fun setDoubleTapStationEnabled(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setDoubleTapStationEnabled(enabled) }
    }

    fun setTrackTappedStopsEnabled(enabled: Boolean) {
        viewModelScope.launch { mapPreferences.setTrackTappedStopsEnabled(enabled) }
    }

    fun setProgressBarVisible(visible: Boolean) {
        viewModelScope.launch { boardedTripPreferences.setProgressBarVisible(visible) }
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
        val doubleTapStationEnabled by viewModel.doubleTapStationEnabled.collectAsState()
        val trackTappedStopsEnabled by viewModel.trackTappedStopsEnabled.collectAsState()
        val progressBarVisible by viewModel.progressBarVisible.collectAsState()
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
                    text = "Default agency",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LightText(
                    text = "Tap your agency to skip picking it every launch. Tap it again to turn that off.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                Column {
                    GtfsAgency.entries.forEach { agency ->
                        LightText(
                            text = agency.displayName,
                            variant = LightTextVariant.Copy,
                            lighten = agency != defaultAgency,
                            underline = agency == defaultAgency,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { viewModel.toggleDefaultAgency(agency) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }

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
                    text = "Tap and hold a stop",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on, tap and hold any stop on the Map screen to jump straight to its upcoming arrivals.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Tap and hold a stop", tapHoldArrivalsEnabled, viewModel::setTapHoldArrivalsEnabled)

                LightText(
                    text = "Double-tap to open a station",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "When on, double-tap a multi-platform station on the Map screen to open a " +
                        "zoomed-in view of just its platforms.",
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
                }
                BackToHomeFooter(onGoBackOnce = { goBack() })
            }
        }
    }
}
