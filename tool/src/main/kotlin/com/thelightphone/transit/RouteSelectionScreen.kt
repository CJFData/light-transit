package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.GtfsRepository
import com.thelightphone.transit.gtfs.LineType
import com.thelightphone.transit.gtfs.RouteOption
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
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

sealed class RouteSelectionState {
    object Loading : RouteSelectionState()
    data class Loaded(val routes: List<RouteOption>) : RouteSelectionState()
    data class Error(val message: String) : RouteSelectionState()
}

class RouteSelectionViewModel(dbFile: File, private val lineType: LineType) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<RouteSelectionState>(RouteSelectionState.Loading)
    val state: StateFlow<RouteSelectionState> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                RouteSelectionState.Loaded(repository.getRoutes(lineType))
            } catch (e: Exception) {
                Log.e("RouteSelectionScreen", "Failed to load routes", e)
                RouteSelectionState.Error("Unable to load routes.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}

class RouteSelectionScreen(
    sealedActivity: SealedLightActivity,
    private val dbFile: File,
    private val lineType: LineType,
) : LightScreen<Unit, RouteSelectionViewModel>(sealedActivity) {

    override val viewModelClass: Class<RouteSelectionViewModel>
        get() = RouteSelectionViewModel::class.java

    override fun createViewModel(): RouteSelectionViewModel = RouteSelectionViewModel(dbFile, lineType)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()
        var searchEditorOpen by remember { mutableStateOf(false) }
        var routeQuery by remember { mutableStateOf("") }
        val searchTextState = rememberTextFieldState(routeQuery)

        if (searchEditorOpen) {
            LightTheme(colors = themeColors) {
                LightTextInputEditor(
                    title = "Search Routes",
                    state = searchTextState,
                    onSubmit = {
                        routeQuery = it.toString().trim()
                        searchEditorOpen = false
                    },
                    onBack = { searchEditorOpen = false },
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    submitIcon = LightIcons.SEARCH,
                    singleLine = true,
                )
            }
        } else LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Choose Route"),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                LightText(
                    text = lineType.label,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                when (val s = state) {
                    is RouteSelectionState.Loading -> LightText(
                        text = "Loading routes...",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is RouteSelectionState.Error -> LightText(
                        text = s.message,
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is RouteSelectionState.Loaded -> if (s.routes.isEmpty()) {
                        LightText(
                            text = "No routes found.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        val filteredRoutes = s.routes.filter { route ->
                            routeQuery.isBlank() ||
                                route.routeId.contains(routeQuery, ignoreCase = true) ||
                                route.displayName.contains(routeQuery, ignoreCase = true)
                        }
                        LightTextField(
                            label = "Search routes",
                            value = routeQuery,
                            placeholder = "All routes",
                            onClick = { searchEditorOpen = true },
                            modifier = Modifier.padding(bottom = 20.dp),
                        )
                        if (filteredRoutes.isEmpty()) {
                            LightText(
                                text = "No matching routes.",
                                variant = LightTextVariant.Copy,
                                lighten = true,
                            )
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(filteredRoutes, key = { it.routeId }) { route ->
                                LightText(
                                    text = route.displayName,
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .lightClickable {
                                            navigateTo(screenFactory = { activity ->
                                                DirectionSelectionScreen(
                                                    activity,
                                                    dbFile,
                                                    route.routeId,
                                                    route.displayName,
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
                BackToHomeFooter(onGoBackOnce = { goBack() })
            }
        }
    }
}
