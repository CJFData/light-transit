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
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.TripStopRow
import com.thelightphone.transit.gtfs.formatGtfsTime
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
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

sealed class TripDetailState {
    object Loading : TripDetailState()
    data class Loaded(val stops: List<TripStopRow>) : TripDetailState()
    data class Error(val message: String) : TripDetailState()
}

class TripDetailViewModel(
    dbFile: File,
    private val tripId: String,
    private val fromStopSequence: Int,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<TripDetailState>(TripDetailState.Loading)
    val state: StateFlow<TripDetailState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                TripDetailState.Loaded(repository.getTripStops(tripId, fromStopSequence))
            } catch (e: Exception) {
                Log.e("TripDetailScreen", "Failed to load stop times for trip $tripId", e)
                TripDetailState.Error("Unable to load trip detail.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class TripDetailScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val tripId: String,
    private val fromStopSequence: Int,
    private val routeLabel: String,
    private val directionLabel: String,
) : LightScreen<Unit, TripDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<TripDetailViewModel>
        get() = TripDetailViewModel::class.java

    override fun createViewModel(): TripDetailViewModel = TripDetailViewModel(dbFile, tripId, fromStopSequence)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(32.dp)
            ) {
                LightText(
                    text = "Trip Detail",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LightText(
                    text = "$routeLabel ($directionLabel)",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                when (val s = state) {
                    is TripDetailState.Loading -> LightText(
                        text = "Loading trip...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is TripDetailState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is TripDetailState.Loaded -> if (s.stops.isEmpty()) {
                        LightText(
                            text = "No stops found for this trip.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.stops) { stop ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            val afterTime = stop.arrivalTime ?: stop.departureTime
                                            if (afterTime != null) {
                                                navigateTo(screenFactory = { activity ->
                                                    StopConnectionsScreen(
                                                        activity,
                                                        dbFile,
                                                        stop.stopId,
                                                        stop.stopName ?: "Stop ${stop.stopId}",
                                                        afterTime,
                                                        tripId,
                                                    )
                                                })
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    LightText(
                                        text = stop.stopName ?: "Unknown stop",
                                        variant = LightTextVariant.Copy,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 16.dp),
                                    )
                                    LightText(
                                        text = formatGtfsTime(stop.arrivalTime ?: stop.departureTime),
                                        variant = LightTextVariant.Copy,
                                        lighten = true,
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
