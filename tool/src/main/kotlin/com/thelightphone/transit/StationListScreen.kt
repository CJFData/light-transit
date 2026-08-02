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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.StopLocation
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

fun StopLocation.displayLabel(): String = stopName?.takeIf { it.isNotBlank() } ?: "Station $stopId"

sealed class StationListState {
    object Loading : StationListState()
    data class Loaded(val stations: List<StopLocation>) : StationListState()
    data class Error(val message: String) : StationListState()
}

class StationListViewModel(dbFile: File) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<StationListState>(StationListState.Loading)
    val state: StateFlow<StationListState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                StationListState.Loaded(repository.getAllStations())
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

    override fun createViewModel(): StationListViewModel = StationListViewModel(dbFile)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
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
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.stations) { station ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            navigateTo(screenFactory = { activity ->
                                                MapStationScreen(activity, dbFile, agency, station.memberStopIds, station.displayLabel())
                                            })
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Weighted so a long station name wraps within its own bounded
                                    // share of the row, leaving guaranteed room for the icon, instead
                                    // of first greedily measuring against the row's full width -- see
                                    // NearbyStopsScreen's identical fix for the same Compose behavior.
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
                        }
                    }
                }
                }
                BackToHomeFooter(onGoBackOnce = { goBack() })
            }
        }
    }
}
