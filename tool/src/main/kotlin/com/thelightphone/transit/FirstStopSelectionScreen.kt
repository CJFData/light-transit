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
import com.thelightphone.transit.gtfs.Alert
import com.thelightphone.transit.gtfs.AlertPreferences
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.CancellationException
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
    private val dbFile: File,
    private val routeId: String,
    private val directionId: Int?,
    /**
     * The chosen direction's headsign, which may be blank. Whether a direction was chosen at all
     * comes from [directionId].
     */
    private val headsign: String?,
    /** The chosen direction's last stop, standing in for a missing headsign. */
    private val lastStopId: String?,
    private val tapHoldPreferences: TapHoldPreferences,
    private val alertPreferences: AlertPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)
    private val agency = GtfsAgency.forDbFile(dbFile)

    private val _state = MutableStateFlow<FirstStopSelectionState>(FirstStopSelectionState.Loading)
    val state: StateFlow<FirstStopSelectionState> = _state

    /** Each stop's alerts for this route, when alerts are shown in menus. */
    val stopAlerts = MutableStateFlow<Pair<Map<String, List<Alert>>, ScreenAlerts>?>(null)

    /** Whether tap and hold opens a stop's arrivals. Read once when the screen opens. */
    val tapHoldArrivalsEnabled = MutableStateFlow(true)

    /** Whether the list shows tomorrow's schedule, like DepartureListScreen. */
    private val _showTomorrow = MutableStateFlow(false)
    val showTomorrow: StateFlow<Boolean> = _showTomorrow

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            tapHoldArrivalsEnabled.value = tapHoldPreferences.tapHoldScheduleArrivalsEnabledFlow.first()
            loadStops()
        }
    }

    fun toggleDay() {
        _showTomorrow.value = !_showTomorrow.value
        viewModelScope.launch(Dispatchers.IO) {
            loadStops()
        }
    }

    private suspend fun loadStops() {
        _state.value = try {
            // Stops in route order that still have a departure today. Tomorrow counts from
            // midnight.
            val zoneId = agency?.zoneId ?: java.time.ZoneId.systemDefault()
            val today = todayForGtfs(zoneId).let { if (_showTomorrow.value) it.plusDays(1) else it }
            val afterTime = if (_showTomorrow.value) "00:00:00" else currentGtfsTimeOfDay(zoneId)
            val stops = directionId?.let { repository.getStopsForVariant(routeId, it, headsign, lastStopId, afterTime, today) }
                ?: repository.getStops(routeId, null, afterTime, today)
            FirstStopSelectionState.Loaded(stops)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("FirstStopSelectionScreen", "Failed to load first stops for route $routeId", e)
            FirstStopSelectionState.Error("Unable to load stops.")
        }
        val stops = (_state.value as? FirstStopSelectionState.Loaded)?.stops ?: return
        stopAlerts.value = try {
            loadScreenAlerts(dbFile, repository, alertPreferences, AlertSurface.MENUS)?.let { screenAlerts ->
                stops.associate { it.stopId to screenAlerts.index.forStop(it.stopId, setOf(routeId)) }.filterValues { it.isNotEmpty() } to screenAlerts
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("FirstStopSelectionScreen", "Failed to load alerts", e)
            null
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
    private val headsign: String?,
    private val lastStopId: String?,
    private val directionLabel: String,
) : LightScreen<Unit, FirstStopSelectionViewModel>(sealedActivity) {

    override val viewModelClass: Class<FirstStopSelectionViewModel>
        get() = FirstStopSelectionViewModel::class.java

    override fun createViewModel(): FirstStopSelectionViewModel =
        FirstStopSelectionViewModel(
            dbFile, routeId, directionId, headsign, lastStopId,
            TapHoldPreferences(lightContext.dataStore), AlertPreferences(lightContext.dataStore),
        )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val tapHoldArrivalsEnabled by viewModel.tapHoldArrivalsEnabled.collectAsState()
        val showTomorrow by viewModel.showTomorrow.collectAsState()
        val stopAlerts by viewModel.stopAlerts.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    // The screen name stays on the first line; tapping either line switches between
                    // today and tomorrow.
                    center = LightTopBarCenter.TwoLineDetail(
                        line1 = "Choose Stop",
                        line2 = if (showTomorrow) "Tomorrow - tap for today" else "Today - tap for tomorrow",
                        onClick = { viewModel.toggleDay() },
                    ),
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

                    // Empty means no departures left today (or none tomorrow), not that the route
                    // is missing.
                    is FirstStopSelectionState.Loaded -> if (s.stops.isEmpty()) {
                        LightText(
                            text = if (showTomorrow) "Nothing found in tomorrow's schedule." else "Nothing found in today's schedule.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        val agency = GtfsAgency.forDbFile(dbFile)
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.stops) { stop ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                LightText(
                                    text = stop.displayLabel(),
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier
                                        .weight(1f)
                                        // Tap opens this route's departures at the stop; tap and
                                        // hold opens live arrivals for every route there.
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
                                                            lastStopId,
                                                            directionLabel,
                                                            stop.stopId,
                                                            stop.displayLabel(),
                                                            startOnTomorrow = showTomorrow,
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
                                stopAlerts?.let { (byStop, screenAlerts) -> AlertBadge(byStop[stop.stopId].orEmpty(), screenAlerts) }
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
