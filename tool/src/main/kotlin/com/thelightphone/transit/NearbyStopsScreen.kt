package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.GeocodeResult
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.IpGeolocator
import com.thelightphone.transit.gtfs.NominatimGeocoder
import com.thelightphone.transit.gtfs.StopWithDistance
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val METERS_PER_MILE = 1609.344
private const val NEARBY_STOP_LIMIT = 20

sealed class NearbyStopsMode {
    /** Initial state: resolving an IP-based approximate location to pre-fill the search field. */
    object Locating : NearbyStopsMode()
    data class LocationInput(val prefillText: String = "") : NearbyStopsMode()
    object Searching : NearbyStopsMode()
    data class GeocodeResults(val results: List<GeocodeResult>) : NearbyStopsMode()
    data class NearbyStops(val stops: List<StopWithDistance>) : NearbyStopsMode()
    data class Error(val message: String) : NearbyStopsMode()
}

fun StopWithDistance.displayLabel(): String = stopName?.takeIf { it.isNotBlank() } ?: "Stop $stopId"

fun StopWithDistance.distanceLabel(): String = "%.1f mi".format(distanceMeters / METERS_PER_MILE)

class NearbyStopsViewModel(dbFile: File) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val geocoder = NominatimGeocoder()
    private val ipGeolocator = IpGeolocator()

    private val _mode = MutableStateFlow<NearbyStopsMode>(NearbyStopsMode.Locating)
    val mode: StateFlow<NearbyStopsMode> = _mode

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            val prefill = try {
                ipGeolocator.locate().displayName
            } catch (e: Exception) {
                Log.e("NearbyStopsScreen", "IP geolocation failed, falling back to blank input", e)
                ""
            }
            _mode.value = NearbyStopsMode.LocationInput(prefillText = prefill)
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
                    NearbyStopsMode.Error("No matching location found.")
                } else {
                    NearbyStopsMode.GeocodeResults(results)
                }
            } catch (e: Exception) {
                Log.e("NearbyStopsScreen", "Geocoding failed for '$text'", e)
                NearbyStopsMode.Error("Unable to search that location.")
            }
        }
    }

    fun selectGeocodeResult(result: GeocodeResult) {
        _mode.value = NearbyStopsMode.Searching
        viewModelScope.launch(Dispatchers.IO) {
            _mode.value = try {
                val ranked = repository.rankStopsByDistance(result.lat, result.lon, NEARBY_STOP_LIMIT)
                NearbyStopsMode.NearbyStops(ranked)
            } catch (e: Exception) {
                Log.e("NearbyStopsScreen", "Failed to rank nearby stops", e)
                NearbyStopsMode.Error("Unable to load nearby stops.")
            }
        }
    }

    fun backToInput() {
        _mode.value = NearbyStopsMode.LocationInput()
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
        geocoder.close()
        ipGeolocator.close()
    }
}

class NearbyStopsScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val agency: GtfsAgency,
) : LightScreen<Unit, NearbyStopsViewModel>(sealedActivity) {

    override val viewModelClass: Class<NearbyStopsViewModel>
        get() = NearbyStopsViewModel::class.java

    override fun createViewModel(): NearbyStopsViewModel = NearbyStopsViewModel(dbFile)

    @Composable
    override fun Content() {
        val mode by viewModel.mode.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        when (val m = mode) {
            is NearbyStopsMode.LocationInput -> {
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
                ) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("Nearby Stops"),
                    )
                    Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                    when (m) {
                        is NearbyStopsMode.Locating -> LightText(
                            text = "Finding your approximate location...",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )

                        is NearbyStopsMode.Searching -> LightText(
                            text = "Searching...",
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
                                text = "Search Again",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.lightClickable { viewModel.backToInput() },
                            )
                        }

                        is NearbyStopsMode.GeocodeResults -> {
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
                            LightText(
                                text = "Search Again",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                                modifier = Modifier
                                    .lightClickable { viewModel.backToInput() }
                                    .padding(bottom = 16.dp),
                            )
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
                                                        // A deduplicated station's own stopId (see
                                                        // GtfsRepository.groupStationsByParent)
                                                        // typically has no stop_times of its own --
                                                        // a real child platform id is what schedule
                                                        // lookups need. Label stays the station's.
                                                        UpcomingArrivalsScreen(
                                                            activity,
                                                            dbFile,
                                                            agency,
                                                            stop.memberStopIds.first(),
                                                            stop.displayLabel(),
                                                        )
                                                    })
                                                }
                                                .padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            LightText(
                                                text = stop.displayLabel(),
                                                variant = LightTextVariant.Copy,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 16.dp),
                                            )
                                            LightText(
                                                text = stop.distanceLabel(),
                                                variant = LightTextVariant.Copy,
                                                lighten = true,
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

                        is NearbyStopsMode.LocationInput -> Unit
                    }
                    }
                }
            }
        }
    }
}
