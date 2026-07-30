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
import com.thelightphone.transit.gtfs.DirectionOption
import com.thelightphone.transit.gtfs.GtfsRepository
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

sealed class DirectionSelectionState {
    object Loading : DirectionSelectionState()
    data class Loaded(val directions: List<DirectionOption>) : DirectionSelectionState()
    data class Error(val message: String) : DirectionSelectionState()
}

fun DirectionOption.displayLabel(): String =
    headsign?.takeIf { it.isNotBlank() }?.let { "Toward $it" } ?: "Direction $directionId"

class DirectionSelectionViewModel(dbFile: File, private val routeId: String) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<DirectionSelectionState>(DirectionSelectionState.Loading)
    val state: StateFlow<DirectionSelectionState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                DirectionSelectionState.Loaded(repository.getDirections(routeId))
            } catch (e: Exception) {
                Log.e("DirectionSelectionScreen", "Failed to load directions for route $routeId", e)
                DirectionSelectionState.Error("Unable to load directions.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class DirectionSelectionScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val routeId: String,
    private val routeLabel: String,
) : LightScreen<Unit, DirectionSelectionViewModel>(sealedActivity) {

    override val viewModelClass: Class<DirectionSelectionViewModel>
        get() = DirectionSelectionViewModel::class.java

    override fun createViewModel(): DirectionSelectionViewModel = DirectionSelectionViewModel(dbFile, routeId)

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
                    text = "Choose Direction",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LightText(
                    text = routeLabel,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                when (val s = state) {
                    is DirectionSelectionState.Loading -> LightText(
                        text = "Loading directions...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is DirectionSelectionState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is DirectionSelectionState.Loaded -> if (s.directions.isEmpty()) {
                        LightText(
                            text = "No directions found.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.directions) { direction ->
                                LightText(
                                    text = direction.displayLabel(),
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            navigateTo(screenFactory = { activity ->
                                                ScheduleLocationScreen(
                                                    activity,
                                                    dbFile,
                                                    routeId,
                                                    routeLabel,
                                                    direction.directionId,
                                                    direction.displayLabel(),
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
