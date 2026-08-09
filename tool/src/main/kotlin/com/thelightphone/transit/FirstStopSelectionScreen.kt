package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.StopOption
import com.thelightphone.transit.gtfs.TapHoldPreferences
import com.thelightphone.transit.gtfs.currentGtfsTimeOfDay
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

sealed class FirstStopSelectionState {
    object Loading : FirstStopSelectionState()
    data class Loaded(val stops: List<StopOption>) : FirstStopSelectionState()
    data class Error(val message: String) : FirstStopSelectionState()
}

fun StopOption.displayLabel(): String = stopName?.takeIf { it.isNotBlank() } ?: "Stop $stopId"

class FirstStopSelectionViewModel(
    dbFile: File,
    private val routeId: String,
    private val directionId: Int?,
    /** Null only for the auto-skip case (see [GtfsRepository.getStops]'s own doc) -- every real
     * chosen [com.thelightphone.transit.gtfs.DirectionOption] carries its own headsign (possibly
     * itself null, a distinct real value -- see [GtfsRepository.getStopsForVariant]), so this
     * screen can tell "no direction was ever chosen" apart from "the chosen variant's headsign
     * happens to be blank" via [directionId] rather than conflating both into this one field. */
    private val headsign: String?,
    private val tapHoldPreferences: TapHoldPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val agency = GtfsAgency.forDbFile(dbFile)

    private val _state = MutableStateFlow<FirstStopSelectionState>(FirstStopSelectionState.Loading)
    val state: StateFlow<FirstStopSelectionState> = _state

    /** Settings screen's "Tap and hold" toggle for this screen specifically (on by default) -- see
     * TapHoldPreferences.tapHoldScheduleArrivalsEnabledFlow. Read once at screen-open, same as every
     * other one-shot Settings read in this app -- Settings isn't shown at the same time as this
     * screen, so there's no need to react live. */
    val tapHoldArrivalsEnabled = MutableStateFlow(true)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            tapHoldArrivalsEnabled.value = tapHoldPreferences.tapHoldScheduleArrivalsEnabledFlow.first()
            _state.value = try {
                // Already in physical route order (see GtfsRepository.getStops's own doc comment) --
                // no location needed to pick a stop along a route, same as choosing a route or
                // direction isn't location-based either. Restricted to stops with a departure still
                // remaining today (see GtfsRepository.getStops's own doc) -- a stop that would just
                // lead to an empty "No departures today" screen isn't worth offering as a choice.
                val zoneId = agency?.zoneId ?: java.time.ZoneId.systemDefault()
                val today = todayForGtfs(zoneId)
                val afterTime = currentGtfsTimeOfDay(zoneId)
                val stops = directionId?.let { repository.getStopsForVariant(routeId, it, headsign, afterTime, today) }
                    ?: repository.getStops(routeId, null, afterTime, today)
                FirstStopSelectionState.Loaded(stops)
            } catch (e: Exception) {
                Log.e("FirstStopSelectionScreen", "Failed to load first stops for route $routeId", e)
                FirstStopSelectionState.Error("Unable to load stops.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class FirstStopSelectionScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val routeId: String,
    private val routeLabel: String,
    private val directionId: Int?,
    /** See [FirstStopSelectionViewModel]'s own doc for why this is kept separate from
     * [directionId] rather than folded into a single nullable signal. */
    private val headsign: String?,
    private val directionLabel: String,
) : LightScreen<Unit, FirstStopSelectionViewModel>(sealedActivity) {

    override val viewModelClass: Class<FirstStopSelectionViewModel>
        get() = FirstStopSelectionViewModel::class.java

    override fun createViewModel(): FirstStopSelectionViewModel =
        FirstStopSelectionViewModel(dbFile, routeId, directionId, headsign, TapHoldPreferences(lightContext.dataStore))

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
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
                    center = LightTopBarCenter.Text("Choose Stop"),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                LightText(
                    text = "$routeLabel - $directionLabel",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                when (val s = state) {
                    is FirstStopSelectionState.Loading -> LightText(
                        text = "Loading stops...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is FirstStopSelectionState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    // Empty now means "no stop on this route/direction has a departure left today"
                    // (see GtfsRepository.getStops/getStopsForVariant's own doc) -- not "this
                    // route/direction doesn't exist", so the message says so rather than implying
                    // something's missing or broken.
                    is FirstStopSelectionState.Loaded -> if (s.stops.isEmpty()) {
                        LightText(
                            text = "Nothing found in today's schedule.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        val agency = GtfsAgency.forDbFile(dbFile)
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.stops) { stop ->
                                LightText(
                                    text = stop.displayLabel(),
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // A short tap proceeds as usual to this route/direction's
                                        // scheduled departure times at this stop; tap-and-hold newly
                                        // opens the stop's actual (live) upcoming arrivals instead,
                                        // across every route serving it, not just this one.
                                        .pointerInput(stop.stopId) {
                                            detectTapGestures(
                                                onTap = {
                                                    navigateTo(screenFactory = { activity ->
                                                        DepartureListScreen(
                                                            activity,
                                                            dbFile,
                                                            routeId,
                                                            routeLabel,
                                                            directionId,
                                                            headsign,
                                                            directionLabel,
                                                            stop.stopId,
                                                            stop.displayLabel(),
                                                        )
                                                    })
                                                },
                                                onLongPress = {
                                                    if (!tapHoldArrivalsEnabled) return@detectTapGestures
                                                    val stopAgency = agency ?: return@detectTapGestures
                                                    navigateTo(screenFactory = { activity ->
                                                        UpcomingArrivalsScreen(activity, dbFile, stopAgency, listOf(stop.stopId), stop.displayLabel())
                                                    })
                                                },
                                            )
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
