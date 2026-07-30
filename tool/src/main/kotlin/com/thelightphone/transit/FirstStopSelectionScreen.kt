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
import com.thelightphone.transit.gtfs.StopOption
import com.thelightphone.transit.gtfs.haversineMeters
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

sealed class FirstStopSelectionState {
    object Loading : FirstStopSelectionState()
    data class Loaded(val stops: List<StopOption>) : FirstStopSelectionState()
    data class Error(val message: String) : FirstStopSelectionState()
}

fun StopOption.displayLabel(): String = stopName?.takeIf { it.isNotBlank() } ?: "Stop $stopId"

private const val FEET_PER_METER = 3.28084

/** Null if this stop has no ingested coordinates — displayed with no distance rather than "0 ft". */
fun StopOption.distanceFeetLabel(userLat: Double, userLon: Double): String? {
    val stopLat = lat ?: return null
    val stopLon = lon ?: return null
    val feet = haversineMeters(userLat, userLon, stopLat, stopLon) * FEET_PER_METER
    return "%.0f ft".format(feet)
}

class FirstStopSelectionViewModel(
    dbFile: File,
    private val routeId: String,
    private val directionId: Int,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<FirstStopSelectionState>(FirstStopSelectionState.Loading)
    val state: StateFlow<FirstStopSelectionState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                FirstStopSelectionState.Loaded(repository.getStops(routeId, directionId))
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
    private val directionId: Int,
    private val directionLabel: String,
    private val userLat: Double,
    private val userLon: Double,
) : LightScreen<Unit, FirstStopSelectionViewModel>(sealedActivity) {

    override val viewModelClass: Class<FirstStopSelectionViewModel>
        get() = FirstStopSelectionViewModel::class.java

    override fun createViewModel(): FirstStopSelectionViewModel =
        FirstStopSelectionViewModel(dbFile, routeId, directionId)

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
                    text = "Choose Stop",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
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

                    is FirstStopSelectionState.Loaded -> if (s.stops.isEmpty()) {
                        LightText(
                            text = "No stops found.",
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
                                            navigateTo(screenFactory = { activity ->
                                                DepartureListScreen(
                                                    activity,
                                                    dbFile,
                                                    routeId,
                                                    routeLabel,
                                                    directionId,
                                                    directionLabel,
                                                    stop.stopId,
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
                                    stop.distanceFeetLabel(userLat, userLon)?.let {
                                        LightText(
                                            text = it,
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
}
