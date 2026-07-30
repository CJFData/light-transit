package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.GeocodeResult
import com.thelightphone.transit.gtfs.IpGeolocator
import com.thelightphone.transit.gtfs.NominatimGeocoder
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Same Locating/LocationInput/GeocodeResults/Error state machine as NearbyStopsScreen (IP-prefill,
 * editable, Nominatim search) — kept as its own copy rather than a shared abstraction, since what
 * happens after a location resolves differs (rank all stops globally vs. hand the point to
 * FirstStopSelectionScreen) enough that a callback-based shared controller would add more
 * indirection than it saves for two call sites.
 */
sealed class ScheduleLocationMode {
    object Locating : ScheduleLocationMode()
    data class LocationInput(val prefillText: String = "") : ScheduleLocationMode()
    object Searching : ScheduleLocationMode()
    data class GeocodeResults(val results: List<GeocodeResult>) : ScheduleLocationMode()
    data class Error(val message: String) : ScheduleLocationMode()
}

class ScheduleLocationViewModel(dbFile: File) : LightViewModel<Unit>() {

    private val geocoder = NominatimGeocoder()
    private val ipGeolocator = IpGeolocator()

    private val _mode = MutableStateFlow<ScheduleLocationMode>(ScheduleLocationMode.Locating)
    val mode: StateFlow<ScheduleLocationMode> = _mode

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val prefill = try {
                ipGeolocator.locate().displayName
            } catch (e: Exception) {
                Log.e("ScheduleLocationScreen", "IP geolocation failed, falling back to blank input", e)
                ""
            }
            _mode.value = ScheduleLocationMode.LocationInput(prefillText = prefill)
        }
    }

    fun search(query: CharSequence) {
        val text = query.toString().trim()
        if (text.isEmpty()) return
        _mode.value = ScheduleLocationMode.Searching
        viewModelScope.launch(Dispatchers.IO) {
            _mode.value = try {
                val results = geocoder.search(text)
                if (results.isEmpty()) {
                    ScheduleLocationMode.Error("No matching location found.")
                } else {
                    ScheduleLocationMode.GeocodeResults(results)
                }
            } catch (e: Exception) {
                Log.e("ScheduleLocationScreen", "Geocoding failed for '$text'", e)
                ScheduleLocationMode.Error("Unable to search that location.")
            }
        }
    }

    fun backToInput() {
        _mode.value = ScheduleLocationMode.LocationInput()
    }

    override fun onCleared() {
        super.onCleared()
        geocoder.close()
        ipGeolocator.close()
    }
}

class ScheduleLocationScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val routeId: String,
    private val routeLabel: String,
    private val directionId: Int,
    private val directionLabel: String,
) : LightScreen<Unit, ScheduleLocationViewModel>(sealedActivity) {

    override val viewModelClass: Class<ScheduleLocationViewModel>
        get() = ScheduleLocationViewModel::class.java

    override fun createViewModel(): ScheduleLocationViewModel = ScheduleLocationViewModel(dbFile)

    @Composable
    override fun Content() {
        val mode by viewModel.mode.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        when (val m = mode) {
            is ScheduleLocationMode.LocationInput -> {
                val textFieldState = rememberTextFieldState(m.prefillText)
                LightTheme(colors = themeColors) {
                    LightTextInputEditor(
                        title = "Search Location",
                        state = textFieldState,
                        onSubmit = { viewModel.search(it) },
                        onBack = { goBack() },
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        submitIcon = LightIcons.SEARCH,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            else -> LightTheme(colors = themeColors) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background)
                        .padding(32.dp)
                ) {
                    LightText(
                        text = "Search Location",
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LightText(
                        text = "$routeLabel - $directionLabel",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    when (m) {
                        is ScheduleLocationMode.Locating -> LightText(
                            text = "Finding your approximate location...",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )

                        is ScheduleLocationMode.Searching -> LightText(
                            text = "Searching...",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )

                        is ScheduleLocationMode.Error -> {
                            LightText(
                                text = m.message,
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            LightText(
                                text = "Search Again",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.lightClickable { viewModel.backToInput() },
                            )
                        }

                        is ScheduleLocationMode.GeocodeResults -> {
                            LightText(
                                text = "Search Again",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier
                                    .lightClickable { viewModel.backToInput() }
                                    .padding(bottom = 16.dp),
                            )
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(m.results) { result ->
                                    LightText(
                                        text = result.displayName,
                                        variant = LightTextVariant.Copy,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .lightClickable {
                                                navigateTo(screenFactory = { activity ->
                                                    FirstStopSelectionScreen(
                                                        activity,
                                                        dbFile,
                                                        routeId,
                                                        routeLabel,
                                                        directionId,
                                                        directionLabel,
                                                        result.lat,
                                                        result.lon,
                                                    )
                                                })
                                            }
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

                        is ScheduleLocationMode.LocationInput -> Unit
                    }
                }
            }
        }
    }
}
