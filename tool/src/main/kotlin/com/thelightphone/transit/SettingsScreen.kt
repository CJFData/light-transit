package com.thelightphone.transit

import android.Manifest
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import com.thelightphone.transit.gtfs.AgencyPreferences
import com.thelightphone.transit.gtfs.BoardedTripPreferences
import com.thelightphone.transit.gtfs.DeparturePreferences
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.HomeScreenPreferences
import com.thelightphone.transit.gtfs.clearAllCachedSchedules
import com.thelightphone.transit.gtfs.LocationPreferences
import com.thelightphone.transit.gtfs.DefaultLocation
import com.thelightphone.transit.gtfs.MapPreferences
import com.thelightphone.transit.gtfs.NetworkPreferences
import com.thelightphone.transit.gtfs.RegionalGroup
import com.thelightphone.transit.gtfs.RunSelectionPreferences
import com.thelightphone.transit.gtfs.AlertPreferences
import com.thelightphone.transit.gtfs.TapHoldPreferences
import com.thelightphone.transit.gtfs.TripDetailPreferences
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
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
import java.io.File
import kotlin.time.Duration

class SettingsViewModel(
    private val agencyPreferences: AgencyPreferences,
    private val mapPreferences: MapPreferences,
    private val boardedTripPreferences: BoardedTripPreferences,
    private val homeScreenPreferences: HomeScreenPreferences,
    private val tapHoldPreferences: TapHoldPreferences,
    private val departurePreferences: DeparturePreferences,
    private val networkPreferences: NetworkPreferences,
    private val runSelectionPreferences: RunSelectionPreferences,
    private val tripDetailPreferences: TripDetailPreferences,
    private val locationPreferences: LocationPreferences,
    private val alertPreferences: AlertPreferences,
    private val filesDir: File,
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

    val stationTapArrivalsEnabled: StateFlow<Boolean>
        get() = _stationTapArrivalsEnabled
    private val _stationTapArrivalsEnabled = MutableStateFlow(true)

    val doubleTapStationEnabled: StateFlow<Boolean>
        get() = _doubleTapStationEnabled
    private val _doubleTapStationEnabled = MutableStateFlow(true)

    val trackTappedStopsEnabled: StateFlow<Boolean>
        get() = _trackTappedStopsEnabled
    private val _trackTappedStopsEnabled = MutableStateFlow(false)

    val seeEverythingEnabled: StateFlow<Boolean>
        get() = _seeEverythingEnabled
    private val _seeEverythingEnabled = MutableStateFlow(true)


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

    val wifiOnlyDownloadsEnabled: StateFlow<Boolean>
        get() = _wifiOnlyDownloadsEnabled
    private val _wifiOnlyDownloadsEnabled = MutableStateFlow(true)

    val runSelectionEnabled: StateFlow<Boolean>
        get() = _runSelectionEnabled
    private val _runSelectionEnabled = MutableStateFlow(true)

    val alertsEnabled: StateFlow<Boolean>
        get() = _alertsEnabled
    private val _alertsEnabled = MutableStateFlow(false)

    val alertsOnHomeScreen: StateFlow<Boolean>
        get() = _alertsOnHomeScreen
    private val _alertsOnHomeScreen = MutableStateFlow(true)

    val alertsInMenus: StateFlow<Boolean>
        get() = _alertsInMenus
    private val _alertsInMenus = MutableStateFlow(true)

    val alertsBoardedOnly: StateFlow<Boolean>
        get() = _alertsBoardedOnly
    private val _alertsBoardedOnly = MutableStateFlow(false)

    val alertsPopUp: StateFlow<Boolean>
        get() = _alertsPopUp
    private val _alertsPopUp = MutableStateFlow(false)

    val alertsSwipe: StateFlow<Boolean>
        get() = _alertsSwipe
    private val _alertsSwipe = MutableStateFlow(false)

    val runStepperEnabled: StateFlow<Boolean>
        get() = _runStepperEnabled
    private val _runStepperEnabled = MutableStateFlow(false)

    val showStopsBeforeBoardingEnabled: StateFlow<Boolean>
        get() = _showStopsBeforeBoardingEnabled
    private val _showStopsBeforeBoardingEnabled = MutableStateFlow(false)

    /**
     * The app's own location toggle, separate from the system permission (see
     * [LocationPreferences]).
     */
    val locationEnabled: StateFlow<Boolean>
        get() = _locationEnabled
    private val _locationEnabled = MutableStateFlow(true)

    /**
     * The live location permission status, checked on every [onScreenShow]; null until the first
     * check.
     */
    val locationPermissionStatus: StateFlow<LightServiceMethod.GetPermission.Result?>
        get() = _locationPermissionStatus
    private val _locationPermissionStatus = MutableStateFlow<LightServiceMethod.GetPermission.Result?>(null)

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
            tapHoldPreferences.stationTapArrivalsEnabledFlow.collect { _stationTapArrivalsEnabled.value = it }
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
        viewModelScope.launch {
            tripDetailPreferences.showStopsBeforeBoardingEnabledFlow.collect { _showStopsBeforeBoardingEnabled.value = it }
        }
        viewModelScope.launch {
            networkPreferences.wifiOnlyDownloadsEnabledFlow.collect { _wifiOnlyDownloadsEnabled.value = it }
        }
        viewModelScope.launch {
            runSelectionPreferences.runSelectionEnabledFlow.collect { _runSelectionEnabled.value = it }
        }
        viewModelScope.launch { alertPreferences.enabledFlow.collect { _alertsEnabled.value = it } }
        viewModelScope.launch { alertPreferences.onHomeScreenFlow.collect { _alertsOnHomeScreen.value = it } }
        viewModelScope.launch { alertPreferences.inMenusFlow.collect { _alertsInMenus.value = it } }
        viewModelScope.launch { alertPreferences.boardedOnlyFlow.collect { _alertsBoardedOnly.value = it } }
        viewModelScope.launch { alertPreferences.popUpFlow.collect { _alertsPopUp.value = it } }
        viewModelScope.launch { alertPreferences.swipeFlow.collect { _alertsSwipe.value = it } }
        viewModelScope.launch {
            runSelectionPreferences.runStepperEnabledFlow.collect { _runStepperEnabled.value = it }
        }
        viewModelScope.launch {
            locationPreferences.locationEnabledFlow.collect { _locationEnabled.value = it }
        }
        viewModelScope.launch {
            locationPreferences.defaultLocationFlow.collect { _defaultLocation.value = it }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Checked on every visit, so a change made in the permission prompt or system settings
        // shows up.
        viewModelScope.launch(Dispatchers.IO) {
            _locationPermissionStatus.value = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                .getOrNull()?.permissionResult
        }
    }

    fun setLocationEnabled(enabled: Boolean) {
        viewModelScope.launch { locationPreferences.setLocationEnabled(enabled) }
    }

    /** Where Explore opens, if set. */
    val defaultLocation: StateFlow<DefaultLocation?>
        get() = _defaultLocation
    private val _defaultLocation = MutableStateFlow<DefaultLocation?>(null)

    fun clearDefaultLocation() {
        viewModelScope.launch { locationPreferences.clearDefaultLocation() }
    }

    /** Saves the new default agency; Home's defaultAgencyFlow collector switches to it. */
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

    fun setStationTapArrivalsEnabled(enabled: Boolean) {
        viewModelScope.launch { tapHoldPreferences.setStationTapArrivalsEnabled(enabled) }
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

    fun setWifiOnlyDownloadsEnabled(enabled: Boolean) {
        viewModelScope.launch { networkPreferences.setWifiOnlyDownloadsEnabled(enabled) }
    }

    fun setRunStepperEnabled(enabled: Boolean) {
        viewModelScope.launch { runSelectionPreferences.setRunStepperEnabled(enabled) }
    }

    fun setRunSelectionEnabled(enabled: Boolean) {
        viewModelScope.launch { runSelectionPreferences.setRunSelectionEnabled(enabled) }
    }

    fun setAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch { alertPreferences.setEnabled(enabled) }
    }

    fun setAlertsOnHomeScreen(enabled: Boolean) {
        viewModelScope.launch { alertPreferences.setOnHomeScreen(enabled) }
    }

    fun setAlertsInMenus(enabled: Boolean) {
        viewModelScope.launch { alertPreferences.setInMenus(enabled) }
    }

    fun setAlertsBoardedOnly(enabled: Boolean) {
        viewModelScope.launch { alertPreferences.setBoardedOnly(enabled) }
    }

    fun setAlertsPopUp(enabled: Boolean) {
        viewModelScope.launch { alertPreferences.setPopUp(enabled) }
    }

    fun setAlertsSwipe(enabled: Boolean) {
        viewModelScope.launch { alertPreferences.setSwipe(enabled) }
    }

    fun setShowStopsBeforeBoardingEnabled(enabled: Boolean) {
        viewModelScope.launch { tripDetailPreferences.setShowStopsBeforeBoardingEnabled(enabled) }
    }

    /**
     * Deletes every agency's downloaded schedule, then re-downloads the selected one (see
     * [clearAllCachedSchedules]).
     */
    fun clearScheduleCache() {
        viewModelScope.launch(Dispatchers.IO) { clearAllCachedSchedules(filesDir) }
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
        NetworkPreferences(lightContext.dataStore),
        RunSelectionPreferences(lightContext.dataStore),
        TripDetailPreferences(lightContext.dataStore),
        LocationPreferences(lightContext.dataStore),
        AlertPreferences(lightContext.dataStore),
        lightContext.filesDir,
    )

    /** An on/off setting as one tappable row with the SDK's toggle icon. */
    @Composable
    private fun ToggleRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit, available: Boolean = true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (available) 1f else 0.4f)
                .lightClickable { if (available) onToggle(!enabled) }
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
        val stationTapArrivalsEnabled by viewModel.stationTapArrivalsEnabled.collectAsState()
        val doubleTapStationEnabled by viewModel.doubleTapStationEnabled.collectAsState()
        val trackTappedStopsEnabled by viewModel.trackTappedStopsEnabled.collectAsState()
        val seeEverythingEnabled by viewModel.seeEverythingEnabled.collectAsState()
        val seeEverythingShowBus by viewModel.seeEverythingShowBus.collectAsState()
        val seeEverythingShowSubway by viewModel.seeEverythingShowSubway.collectAsState()
        val seeEverythingShowCommuterRail by viewModel.seeEverythingShowCommuterRail.collectAsState()
        val progressBarVisible by viewModel.progressBarVisible.collectAsState()
        val dailyMessageVisible by viewModel.dailyMessageVisible.collectAsState()
        val dailyMessageRandom by viewModel.dailyMessageRandom.collectAsState()
        val mergeFeedStationsEnabled by viewModel.mergeFeedStationsEnabled.collectAsState()
        val includeLongerTripsEnabled by viewModel.includeLongerTripsEnabled.collectAsState()
        val wifiOnlyDownloadsEnabled by viewModel.wifiOnlyDownloadsEnabled.collectAsState()
        val runSelectionEnabled by viewModel.runSelectionEnabled.collectAsState()
        val alertsEnabled by viewModel.alertsEnabled.collectAsState()
        val alertsOnHomeScreen by viewModel.alertsOnHomeScreen.collectAsState()
        val alertsInMenus by viewModel.alertsInMenus.collectAsState()
        val alertsBoardedOnly by viewModel.alertsBoardedOnly.collectAsState()
        val alertsPopUp by viewModel.alertsPopUp.collectAsState()
        val alertsSwipe by viewModel.alertsSwipe.collectAsState()
        val runStepperEnabled by viewModel.runStepperEnabled.collectAsState()
        val showStopsBeforeBoardingEnabled by viewModel.showStopsBeforeBoardingEnabled.collectAsState()
        val locationEnabled by viewModel.locationEnabled.collectAsState()
        val locationPermissionStatus by viewModel.locationPermissionStatus.collectAsState()
        val defaultLocation by viewModel.defaultLocation.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val locationPermissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.ACCESS_FINE_LOCATION)

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
                        .padding(start = 16.dp)
                        .lightClickable {
                            // The same picker as first launch, with a close button since there's
                            // already an agency.
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

                // Only shown when the primary agency is in a region with others to add.
                val primaryRegion = defaultAgency?.let { RegionalGroup.forAgency(it) }
                if (primaryRegion != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .lightClickable {
                                navigateTo(screenFactory = { activity -> ScheduleSelectionScreen(activity, primaryRegion) })
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        LightText(
                            text = "Additional Schedules",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier.weight(1f),
                        )
                        LightIcon(
                            icon = LightIcons.ARROW_RIGHT,
                            size = 1f,
                            contentDescription = "Manage additional schedules",
                        )
                    }
                }

                LightText(
                    text = "Clear schedule cache",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "Deletes downloaded schedules to free up space. Your current agency " +
                        "downloads again right away.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            LightModalManager.show(
                                modal = ClearCacheConfirmModal(onConfirm = { viewModel.clearScheduleCache() }),
                                duration = Duration.INFINITE,
                            )
                        }
                        .padding(vertical = 12.dp),
                ) {
                    LightText(
                        text = "Clear cache",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.weight(1f),
                    )
                }

                LightText(
                    text = "Only download over Wi-Fi",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "Schedule downloads and updates wait for Wi-Fi. Your current schedule " +
                        "keeps working until then.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Only download over Wi-Fi", wifiOnlyDownloadsEnabled, viewModel::setWifiOnlyDownloadsEnabled)

                LightText(
                    text = "Service alerts",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "Detours, closures, and other service changes, for agencies that publish " +
                        "them. Show in menus adds an alert icon to routes, stops, and trips.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Service alerts", alertsEnabled, viewModel::setAlertsEnabled)
                // Sub-options stay visible but greyed out while alerts are off.
                ToggleRow("Show on home screen", alertsOnHomeScreen, viewModel::setAlertsOnHomeScreen, available = alertsEnabled)
                ToggleRow("Show only for boarded trips", alertsBoardedOnly, viewModel::setAlertsBoardedOnly, available = alertsEnabled)
                ToggleRow("Show in menus", alertsInMenus, viewModel::setAlertsInMenus, available = alertsEnabled)
                ToggleRow("Pop up new alerts", alertsPopUp, viewModel::setAlertsPopUp, available = alertsEnabled)
                ToggleRow("Swipe between alerts", alertsSwipe, viewModel::setAlertsSwipe, available = alertsEnabled)

                LightText(
                    text = "Explore",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "Find stops near your location or a default place you choose. Both stay on " +
                        "your phone. Using your location is still in testing.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                // Indented under the Explore heading.
                Column(modifier = Modifier.padding(start = 24.dp)) {
                    ToggleRow("Use my location", locationEnabled, viewModel::setLocationEnabled)
                    if (locationEnabled) {
                        val statusText = when (locationPermissionStatus) {
                            LightServiceMethod.GetPermission.Result.Granted -> "Location access is enabled."
                            LightServiceMethod.GetPermission.Result.Denied,
                            LightServiceMethod.GetPermission.Result.BlockedByServer -> "Location access was denied."
                            LightServiceMethod.GetPermission.Result.Unknown, null -> "Location access hasn't been granted yet."
                        }
                        LightText(
                            text = statusText,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                        )
                        if (locationPermissionStatus != LightServiceMethod.GetPermission.Result.Granted) {
                            LightText(
                                text = "Enable Location Access",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier
                                    .lightClickable { locationPermissionLauncher?.launch() }
                                    .padding(bottom = 16.dp),
                            )
                        }
                    }

                    LightText(
                        text = "Default location",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    LightText(
                        text = when (val d = defaultLocation) {
                            is DefaultLocation.Place -> d.location.label
                            DefaultLocation.Current -> "Current location"
                            null -> "Set a default location"
                        },
                        variant = LightTextVariant.Copy,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                navigateTo(screenFactory = { activity -> DefaultLocationScreen(activity) })
                            }
                            .padding(bottom = 12.dp),
                    )
                    if (defaultLocation != null) {
                        LightText(
                            text = "Clear default location",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                            modifier = Modifier
                                .lightClickable { viewModel.clearDefaultLocation() }
                                .padding(bottom = 16.dp),
                        )
                    }
                }

                LightText(
                    text = "Merge feed stations",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "Groups a partner service's stops into the main agency's stations, like " +
                        "Bustang's gates at Denver's Union Station.",
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
                    text = "Light or dark map.",
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
                    text = "Tap and hold a stop or station to see its live arrivals. Choose where " +
                        "this works: on the map, in schedule stop lists, and in the Stations list.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Map", tapHoldArrivalsEnabled, viewModel::setTapHoldArrivalsEnabled)
                ToggleRow("Schedules", tapHoldScheduleArrivalsEnabled, viewModel::setTapHoldScheduleArrivalsEnabled)
                ToggleRow("Stations", tapHoldStationArrivalsEnabled, viewModel::setTapHoldStationArrivalsEnabled)

                LightText(
                    text = "In the Stations list, a tap opens live arrivals and tap and hold opens " +
                        "the platform map. Turn off to swap them.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ToggleRow("Stations list opens arrivals on tap", stationTapArrivalsEnabled, viewModel::setStationTapArrivalsEnabled)

                LightText(
                    text = "Include longer trips in departures",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "Also lists trips that go past your destination, since they still get you " +
                        "there. Turn off to see only trips that end where you picked.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Include longer trips in departures", includeLongerTripsEnabled, viewModel::setIncludeLongerTripsEnabled)

                LightText(
                    text = "Tap and hold vehicles",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "Tap and hold a live vehicle on a map to open its trip.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Tap and hold vehicles", tapHoldVehicleEnabled, viewModel::setTapHoldVehicleEnabled)

                LightText(
                    text = "Run selection",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "On CTA 'L' and MBTA subway trips, the app sometimes guesses which live train " +
                        "you're on. Select Run lets you pick the right one.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Run selection", runSelectionEnabled, viewModel::setRunSelectionEnabled)

                // Nested under run selection, since the stepper needs it.
                if (runSelectionEnabled) {
                    LightText(
                        text = "Adds next and previous buttons beside Select Run to switch trains " +
                            "with one tap.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                    ToggleRow("Next and previous buttons", runStepperEnabled, viewModel::setRunStepperEnabled)
                }

                LightText(
                    text = "Show earlier stops",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "On a boarded trip, also shows the stops before yours, so you can watch " +
                        "your ride approach.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("Show earlier stops", showStopsBeforeBoardingEnabled, viewModel::setShowStopsBeforeBoardingEnabled)

                LightText(
                    text = "Double-tap stations",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                LightText(
                    text = "Double-tap to zoom in/out of station maps to see its platforms.",
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
                    text = "When you tap a stop on the map, its live vehicles show too. With See " +
                        "everything also on, tapping stops shows only vehicles heading to, at, or leaving " +
                        "them; untap them to see everything again.",
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
                    text = "Shows every live vehicle on the map. Turn off to see only vehicles " +
                        "headed to stops on screen.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                ToggleRow("See everything", seeEverythingEnabled, viewModel::setSeeEverythingEnabled)

                if (seeEverythingEnabled) {
                    LightText(
                        text = "Modes shown",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    LightText(
                        text = "Which vehicle types to show.",
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
                    text = "While you're on a trip, the home screen shows how far you've gone.",
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
                    text = "A short message on the home screen that changes each day.",
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
                        text = "A new message each time you open the home screen.",
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
