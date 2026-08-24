package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.Departure
import com.thelightphone.transit.gtfs.DeparturePreferences
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.transit.gtfs.todayForGtfs
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

sealed class DepartureListState {
    object Loading : DepartureListState()
    data class Loaded(val departures: List<Departure>) : DepartureListState()
    data class Error(val message: String) : DepartureListState()
}

class DepartureListViewModel(
    dbFile: File,
    private val routeId: String,
    private val directionId: Int?,
    /** See [FirstStopSelectionViewModel]'s own doc for why this is kept separate from
     * [directionId] rather than conflated into one nullable signal. */
    private val headsign: String?,
    /** See [FirstStopSelectionViewModel]'s own doc. */
    private val lastStopId: String?,
    private val stopId: String,
    private val departurePreferences: DeparturePreferences,
    /** True when the stop that led here was picked while [FirstStopSelectionScreen] was itself
     * showing tomorrow's schedule -- carries that choice forward so this screen opens already on
     * tomorrow instead of re-defaulting to today (which would likely just be empty again, the
     * same "nothing found" state the rider was trying to get past). */
    startOnTomorrow: Boolean = false,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val agency = GtfsAgency.forDbFile(dbFile)

    private val _state = MutableStateFlow<DepartureListState>(DepartureListState.Loading)
    val state: StateFlow<DepartureListState> = _state

    /** Whether the list below shows tomorrow's service day instead of today's, toggled by tapping
     * the header (see [DepartureListScreen.Content]). Departures queries already accept an
     * arbitrary service date, so shifting this by one day is all [loadDepartures] needs to do. */
    private val _showTomorrow = MutableStateFlow(startOnTomorrow)
    val showTomorrow: StateFlow<Boolean> = _showTomorrow

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            loadDepartures()
        }
    }

    fun toggleDay() {
        _showTomorrow.value = !_showTomorrow.value
        viewModelScope.launch(Dispatchers.IO) {
            loadDepartures()
        }
    }

    private suspend fun loadDepartures() {
        _state.value = try {
            val serviceDate = todayForGtfs(agency?.zoneId ?: java.time.ZoneId.systemDefault())
                .let { if (_showTomorrow.value) it.plusDays(1) else it }
            // Read once at screen-open, same as every other one-shot Settings read in this app
            // (see TapHoldPreferences' own usage) -- Settings isn't shown at the same time as
            // this screen, so no need to react live.
            val includeLongerTrips = departurePreferences.includeLongerTripsEnabledFlow.first()
            val departures = when {
                directionId == null -> repository.getDepartures(routeId, null, stopId, serviceDate)
                // See GtfsRepository.getDeparturesForVariant's own doc: also includes any
                // longer trip that reaches at least as far as the chosen variant (e.g. a
                // "South Station" train under a "Toward Readville" pick), unless the rider has
                // turned that off in Settings, in which case it's an exact headsign match only.
                includeLongerTrips -> repository.getDeparturesForVariant(routeId, directionId, headsign, lastStopId, stopId, serviceDate)
                else -> repository.getDeparturesForExactVariant(routeId, directionId, headsign, lastStopId, stopId, serviceDate)
            }
            DepartureListState.Loaded(departures)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DepartureListScreen", "Failed to load departures for stop $stopId", e)
            DepartureListState.Error("Unable to load departures.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class DepartureListScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val routeId: String,
    private val routeLabel: String,
    private val directionId: Int?,
    /** See [FirstStopSelectionViewModel]'s own doc for why this is kept separate from
     * [directionId] rather than conflated into one nullable signal. */
    private val headsign: String?,
    /** See [FirstStopSelectionViewModel]'s own doc. */
    private val lastStopId: String?,
    private val directionLabel: String,
    private val stopId: String,
    private val stopLabel: String,
    private val startOnTomorrow: Boolean = false,
) : LightScreen<Unit, DepartureListViewModel>(sealedActivity) {

    override val viewModelClass: Class<DepartureListViewModel>
        get() = DepartureListViewModel::class.java

    override fun createViewModel(): DepartureListViewModel =
        DepartureListViewModel(dbFile, routeId, directionId, headsign, lastStopId, stopId, DeparturePreferences(lightContext.dataStore), startOnTomorrow)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val showTomorrow by viewModel.showTomorrow.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    // Screen name stays on line1 always -- only line2 doubles as the day toggle (tapping either
                    // line flips between today's and tomorrow's schedule, see DepartureListViewModel.toggleDay).
                    // Every departures query already takes an arbitrary service date, so this is a pure UI/state
                    // addition, not a new query path. Same line1/line2 convention as FirstStopSelectionScreen's own
                    // header, one screen earlier in this flow.
                    center = LightTopBarCenter.TwoLineDetail(
                        line1 = "Departures",
                        line2 = if (showTomorrow) "Tomorrow - tap for today" else "Today - tap for tomorrow",
                        onClick = { viewModel.toggleDay() },
                    ),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                LightText(
                    text = "$stopLabel - $routeLabel ($directionLabel)",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                when (val s = state) {
                    is DepartureListState.Loading -> LightText(
                        text = "Loading departures...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is DepartureListState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is DepartureListState.Loaded -> if (s.departures.isEmpty()) {
                        LightText(
                            text = if (showTomorrow) "No departures tomorrow." else "No departures today.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.departures) { departure ->
                                LightText(
                                    text = formatGtfsTime(departure.departureTime),
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            navigateTo(screenFactory = { activity ->
                                                TripDetailScreen(
                                                    activity,
                                                    dbFile,
                                                    departure.tripId,
                                                    departure.stopSequence,
                                                    routeLabel,
                                                    directionLabel,
                                                )
                                            })
                                        }
                                        .padding(vertical = 12.dp),
                                )
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
