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

sealed class LineTypeSelectionState {
    object Loading : LineTypeSelectionState()
    data class Loaded(val lineTypes: List<LineType>) : LineTypeSelectionState()
    data class Error(val message: String) : LineTypeSelectionState()
}

class LineTypeSelectionViewModel(dbFile: File) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<LineTypeSelectionState>(LineTypeSelectionState.Loading)
    val state: StateFlow<LineTypeSelectionState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                LineTypeSelectionState.Loaded(repository.getAvailableLineTypes())
            } catch (e: Exception) {
                Log.e("LineTypeSelectionScreen", "Failed to load line types", e)
                LineTypeSelectionState.Error("Unable to load lines.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class LineTypeSelectionScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
) : LightScreen<Unit, LineTypeSelectionViewModel>(sealedActivity) {

    override val viewModelClass: Class<LineTypeSelectionViewModel>
        get() = LineTypeSelectionViewModel::class.java

    override fun createViewModel(): LineTypeSelectionViewModel = LineTypeSelectionViewModel(dbFile)

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
                    center = LightTopBarCenter.Text("Choose Line"),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                when (val s = state) {
                    is LineTypeSelectionState.Loading -> LightText(
                        text = "Loading lines...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is LineTypeSelectionState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is LineTypeSelectionState.Loaded -> if (s.lineTypes.isEmpty()) {
                        LightText(
                            text = "No lines found.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.lineTypes) { lineType ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            navigateTo(screenFactory = { activity ->
                                                RouteSelectionScreen(activity, dbFile, lineType)
                                            })
                                        }
                                        .padding(vertical = 12.dp),
                                ) {
                                    LightIcon(
                                        icon = lineType.toVehicleIcon(),
                                        size = 1.2f,
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                    LightText(text = lineType.label, variant = LightTextVariant.Copy)
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
