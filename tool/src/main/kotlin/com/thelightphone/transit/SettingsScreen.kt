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
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
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

class SettingsViewModel(private val preferences: AgencyPreferences) : LightViewModel<Unit>() {

    val defaultAgency: StateFlow<GtfsAgency?>
        get() = _defaultAgency
    private val _defaultAgency = MutableStateFlow<GtfsAgency?>(null)

    init {
        viewModelScope.launch {
            preferences.defaultAgencyFlow.collect { _defaultAgency.value = it }
        }
    }

    /** Tapping the already-selected default clears it back to "no default", so there's a way to
     * turn the auto-skip back off, not just switch which agency it points at. */
    fun toggleDefaultAgency(agency: GtfsAgency) {
        viewModelScope.launch {
            preferences.setDefaultAgency(if (defaultAgency.value == agency) null else agency)
        }
    }
}

class SettingsScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel =
        SettingsViewModel(AgencyPreferences(lightContext.dataStore))

    @Composable
    override fun Content() {
        val defaultAgency by viewModel.defaultAgency.collectAsState()
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
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
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
                }
            }
        }
    }
}
