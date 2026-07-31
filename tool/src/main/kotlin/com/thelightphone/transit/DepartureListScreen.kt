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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val directionId: Int,
    private val stopId: String,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<DepartureListState>(DepartureListState.Loading)
    val state: StateFlow<DepartureListState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                val today = todayForGtfs()
                DepartureListState.Loaded(repository.getDepartures(routeId, directionId, stopId, today))
            } catch (e: Exception) {
                Log.e("DepartureListScreen", "Failed to load departures for stop $stopId", e)
                DepartureListState.Error("Unable to load departures.")
            }
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
    private val directionId: Int,
    private val directionLabel: String,
    private val stopId: String,
    private val stopLabel: String,
) : LightScreen<Unit, DepartureListViewModel>(sealedActivity) {

    override val viewModelClass: Class<DepartureListViewModel>
        get() = DepartureListViewModel::class.java

    override fun createViewModel(): DepartureListViewModel =
        DepartureListViewModel(dbFile, routeId, directionId, stopId)

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
                    center = LightTopBarCenter.Text("Departures"),
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
                            text = "No departures today.",
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
            }
        }
    }
}
