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
    /** [stops] is this trip's own FULL stop list, from its real origin (stop_sequence 0), not just
     * [SelectRunViewModel]'s own `fromStopSequence` onward -- deliberately wider than what Trip
     * Detail itself ever shows, so a run whose live position is still short of the rider's own
     * boarding stop is still visible and pickable here (see Content()'s own greyed-out styling for
     * those). [optionsByStop] keys every live run that survived `liveRunOptionsForTrip`'s own
     * filtering by its own [FuzzyRunOption.nextStopId] -- more than one entry at a stop_id means two
     * live runs currently share a next stop (rare but real); both still render, individually
     * tappable, rather than silently dropping one. */
    data class Loaded(
        val stops: List<TripStopRow>,
        val optionsByStop: Map<String, List<FuzzyRunOption>>,
    ) : SelectRunState()
    object Error : SelectRunState()
}

/**
 * Lets a rider explicitly pick which live run they're actually on, by tapping its own real vehicle
 * marker directly on this trip's own stop list -- see
 * [FuzzyRunTrips.liveRunOptions]/[FuzzyRunTrips.tripUpdateForRun]'s own docs for why this exists at
 * all: an automatic closest-match, even a sticky one, is still a guess, and boarding is the one
 * place in the app a wrong guess actively misleads a rider mid-journey. Only ever reachable from
 * Trip Detail while [tripId] is the boarded trip on a route [FuzzyRunTrips] actually covers -- see
 * TripDetailScreen's own Select Run row for that gating.
 *
 * [liveRunOptionsForTrip] already scopes the live pool to this exact trip's own direction and path
 * (see its own doc) -- confirmed live 2026-08-23 that without it, a rider boarding a northbound trip
 * saw southbound runs mixed in with no way to tell them apart.
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

    /** This trip's own vehicle mode, for the same live-marker icon Trip Detail itself uses --
     * almost always Subway in practice (every current [FuzzyRunTrips] source is rail-only), but
     * resolved for real rather than hardcoded, same as [TripDetailViewModel.lineType]. */
    val lineType = MutableStateFlow<LineType?>(null)

    /** The rider's own already-pinned run for this exact trip, if any -- Content() underlines its
     * row so a rider re-opening this screen can see what's currently selected, same convention
     * TripDetailScreen's own alight-stop underline already uses. Collected for this ViewModel's
     * whole lifetime so a pin made here is reflected the instant it lands, not just on next load. */
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
                // From the trip's real origin, not fromStopSequence -- see SelectRunState.Loaded's
                // own doc on why this screen deliberately shows more than Trip Detail itself does.
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

    /** Launched on [HomeVisibility.scope], not [viewModelScope] -- the tap that calls this always
     * immediately calls `goBack()` right after (see Content()'s own row), which clears this
     * ViewModel and cancels viewModelScope before a write launched there would actually reach
     * DataStore. Confirmed live 2026-08-23: the pin silently never persisted with viewModelScope
     * here, even though goBack() itself ran and returned to Trip Detail successfully -- same
     * "must outlive this exact screen" reasoning BackToHomeFooter's own pop-loop already uses
     * [HomeVisibility.scope] for. */
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
                                // A stop before Trip Detail's own first-shown stop (fromStopSequence)
                                // -- real route context, not part of this rider's own boarded
                                // journey, so it's greyed out (the SDK's own de-emphasized look --
                                // lighten on text, matching alpha for the row as a whole since
                                // LightIcon has no lighten of its own). The run markers underneath a
                                // stop are never greyed themselves, regardless of which stop they're
                                // on -- every option shown is equally real and equally selectable;
                                // only the stop's own relevance to this rider's trip is in question.
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
                                        // CTA's own delay flag, not a computed diff -- see
                                        // FuzzyRunOption.isDelayed's own doc for why. Omitted (not
                                        // "On time") whenever the source has no native signal at all.
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
