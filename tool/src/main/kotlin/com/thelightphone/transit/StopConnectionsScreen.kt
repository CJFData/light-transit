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
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.StopConnection
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

sealed class StopConnectionsState {
    object Loading : StopConnectionsState()
    data class Loaded(val connections: List<StopConnection>) : StopConnectionsState()
    data class Error(val message: String) : StopConnectionsState()
}

fun StopConnection.displayLabel(): String {
    val lineLabel = LineType.forGtfsRouteType(route.routeType)?.label
    val routeAndDirection = "${route.displayName} - ${direction.displayLabel()}"
    return lineLabel?.let { "$it - $routeAndDirection" } ?: routeAndDirection
}

class StopConnectionsViewModel(
    dbFile: File,
    private val stopId: String,
    private val afterTime: String,
    private val excludeTripId: String,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<StopConnectionsState>(StopConnectionsState.Loading)
    val state: StateFlow<StopConnectionsState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                val today = todayForGtfs()
                StopConnectionsState.Loaded(
                    repository.getNextConnections(stopId, afterTime, excludeTripId, today)
                )
            } catch (e: Exception) {
                Log.e("StopConnectionsScreen", "Failed to load connections for stop $stopId", e)
                StopConnectionsState.Error("Unable to load connections.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class StopConnectionsScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val stopId: String,
    private val stopLabel: String,
    private val afterTime: String,
    private val excludeTripId: String,
) : LightScreen<Unit, StopConnectionsViewModel>(sealedActivity) {

    override val viewModelClass: Class<StopConnectionsViewModel>
        get() = StopConnectionsViewModel::class.java

    override fun createViewModel(): StopConnectionsViewModel =
        StopConnectionsViewModel(dbFile, stopId, afterTime, excludeTripId)

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
                    center = LightTopBarCenter.Text("Connections"),
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                LightText(
                    text = "$stopLabel - After ${formatGtfsTime(afterTime)}",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                when (val s = state) {
                    is StopConnectionsState.Loading -> LightText(
                        text = "Loading connections...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is StopConnectionsState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is StopConnectionsState.Loaded -> if (s.connections.isEmpty()) {
                        LightText(
                            text = "No more connections today.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.connections) { connection ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            navigateTo(screenFactory = { activity ->
                                                TripDetailScreen(
                                                    activity,
                                                    dbFile,
                                                    connection.tripId,
                                                    connection.stopSequence,
                                                    connection.route.displayName,
                                                    connection.direction.displayLabel(),
                                                )
                                            })
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    LightText(
                                        text = connection.displayLabel(),
                                        variant = LightTextVariant.Copy,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 16.dp),
                                    )
                                    LightText(
                                        text = formatGtfsTime(connection.departureTime),
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
