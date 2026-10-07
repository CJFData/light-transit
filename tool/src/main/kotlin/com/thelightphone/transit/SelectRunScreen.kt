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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.BoardedFuzzyRunPreferences
import com.thelightphone.transit.gtfs.FuzzyRunOption
import com.thelightphone.transit.gtfs.FuzzyRunTrips
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.TripStopRow
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.transit.gtfs.liveRunOptionsForTrip
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
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class SelectRunState {
    object Loading : SelectRunState()
    /**
     * [stops] is the trip's full stop list, wider than Trip Detail shows, so a run still short of
     * the boarding stop can be picked. [optionsByStop] groups live runs by their next stop; two
     * runs at the same stop both show.
     */
    data class Loaded(
        val stops: List<TripStopRow>,
        val optionsByStop: Map<String, List<FuzzyRunOption>>,
    ) : SelectRunState()
    object Error : SelectRunState()
}

/**
 * Lets a rider pick which live run they're on by tapping its vehicle marker on this trip's stop
 * list. An automatic closest match is still a guess, and boarding is where a wrong guess misleads a
 * rider mid-journey. Only reachable from Trip Detail while [tripId] is the boarded trip on a route
 * [FuzzyRunTrips] covers. [liveRunOptionsForTrip] limits the options to runs on this trip's own
 * direction and path.
 */
class SelectRunViewModel(
    private val dbFile: File,
    private val agency: GtfsAgency,
    private val routeId: String,
    private val tripId: String,
    private val fromStopSequence: Int,
    private val alightStopId: String?,
    private val boardedFuzzyRunPreferences: BoardedFuzzyRunPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<SelectRunState>(SelectRunState.Loading)
    val state: StateFlow<SelectRunState> = _state

    /** This trip's vehicle type, for the run markers. */
    val lineType = MutableStateFlow<LineType?>(null)

    /** The rider's pinned run for this trip, underlined in the list. */
    val boardedRunId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            boardedFuzzyRunPreferences.boardedFuzzyRunFlow.collect { pin ->
                boardedRunId.value = pin?.takeIf { it.tripId == tripId }?.runId
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                // From the trip's first stop, not the boarding stop.
                val stops = repository.getTripStops(tripId, 0)
                lineType.value = repository.getRouteTypeForTrip(tripId)?.let { LineType.forGtfsRouteType(it) }
                val source = agency.component<FuzzyRunTrips>()
                val options = source?.liveRunOptionsForTrip(
                    tripId, 0, routeId, repository, agency, agency.zoneId, alightStopId,
                ).orEmpty()
                SelectRunState.Loaded(stops, options.groupBy { it.nextStopId })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SelectRunScreen", "Failed to load live runs for route $routeId", e)
                SelectRunState.Error
            }
        }
    }

    /**
     * Launched on [HomeVisibility.scope], not [viewModelScope]: the tap that calls this immediately
     * calls `goBack()`, which clears this ViewModel and would cancel a viewModelScope write before
     * it reached DataStore.
     */
    fun selectRun(runId: String) {
        HomeVisibility.scope.launch { boardedFuzzyRunPreferences.selectRun(tripId, runId) }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class SelectRunScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val agency: GtfsAgency,
    private val routeId: String,
    private val routeLabel: String,
    private val tripId: String,
    private val fromStopSequence: Int,
    private val alightStopId: String?,
) : LightScreen<Unit, SelectRunViewModel>(sealedActivity) {

    override val viewModelClass: Class<SelectRunViewModel>
        get() = SelectRunViewModel::class.java

    override fun createViewModel(): SelectRunViewModel = SelectRunViewModel(
        dbFile, agency, routeId, tripId, fromStopSequence, alightStopId, BoardedFuzzyRunPreferences(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val lineType by viewModel.lineType.collectAsState()
        val boardedRunId by viewModel.boardedRunId.collectAsState()
        val vehicleIcon = lineType.toVehicleIcon()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Select Run"),
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                LightText(
                    text = routeLabel,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                when (val s = state) {
                    is SelectRunState.Loading -> LightText(
                        text = "Loading live runs...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is SelectRunState.Error -> LightText(
                        text = "Unable to load live runs.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is SelectRunState.Loaded -> if (s.optionsByStop.isEmpty()) {
                        LightText(
                            text = "No live runs right now.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        LightText(
                            text = "Tap the vehicle/run to track for this trip.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.stops) { stop ->
                                // Stops before the boarding stop are greyed out; their run markers
                                // stay selectable.
                                val isPriorToTripStart = stop.stopSequence < fromStopSequence
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(if (isPriorToTripStart) 0.5f else 1f)
                                        .padding(vertical = 8.dp),
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        LightText(
                                            text = stop.stopName ?: "Unknown stop",
                                            variant = LightTextVariant.Copy,
                                            lighten = isPriorToTripStart,
                                            modifier = Modifier.weight(1f),
                                        )
                                        LightText(
                                            text = formatGtfsTime(stop.arrivalTime ?: stop.departureTime),
                                            variant = LightTextVariant.Copy,
                                            lighten = true,
                                            modifier = Modifier.padding(start = 16.dp),
                                        )
                                    }
                                    for (option in s.optionsByStop[stop.stopId].orEmpty()) {
                                        // Only when the source flags a delay; there's no "On time".
                                        val statusSuffix = if (option.isDelayed == true) " - Delayed" else ""
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .lightClickable {
                                                    viewModel.selectRun(option.runId)
                                                    goBack()
                                                }
                                                .padding(top = 4.dp),
                                        ) {
                                            LightIcon(
                                                icon = vehicleIcon,
                                                size = 1.2f,
                                                contentDescription = "Track this vehicle",
                                                modifier = Modifier.padding(end = 8.dp),
                                            )
                                            LightText(
                                                text = "Run ${option.runId} - ${option.destinationLabel}$statusSuffix",
                                                variant = LightTextVariant.Detail,
                                                underline = option.runId == boardedRunId,
                                            )
                                        }
                                    }
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
