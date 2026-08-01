package com.thelightphone.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.AgencyPreferences
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.MapPreferences
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
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
}

class SettingsScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel =
        SettingsViewModel(AgencyPreferences(lightContext.dataStore), MapPreferences(lightContext.dataStore))

    @Composable
    override fun Content() {
        val defaultAgency by viewModel.defaultAgency.collectAsState()
        val darkMapEnabled by viewModel.darkMapEnabled.collectAsState()
        val tapHoldArrivalsEnabled by viewModel.tapHoldArrivalsEnabled.collectAsState()
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
                            text = if (agency == defaultAgency) "${agency.displayName} (default)" else agency.displayName,
                            variant = LightTextVariant.Copy,
                            lighten = agency != defaultAgency,
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
                        text = if (!darkMapEnabled) "Light (current)" else "Light",
                        variant = LightTextVariant.Copy,
                        lighten = darkMapEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.setDarkMapEnabled(false) }
                            .padding(vertical = 12.dp),
                    )
                    LightText(
                        text = if (darkMapEnabled) "Dark (current)" else "Dark",
                        variant = LightTextVariant.Copy,
                        lighten = !darkMapEnabled,
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
                Column {
                    LightText(
                        text = if (tapHoldArrivalsEnabled) "On (current)" else "On",
                        variant = LightTextVariant.Copy,
                        lighten = !tapHoldArrivalsEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.setTapHoldArrivalsEnabled(true) }
                            .padding(vertical = 12.dp),
                    )
                    LightText(
                        text = if (!tapHoldArrivalsEnabled) "Off (current)" else "Off",
                        variant = LightTextVariant.Copy,
                        lighten = tapHoldArrivalsEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.setTapHoldArrivalsEnabled(false) }
                            .padding(vertical = 12.dp),
                    )
                }
                }
            }
        }
    }
}
