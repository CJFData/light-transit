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
import androidx.compose.runtime.LaunchedEffect
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
import com.thelightphone.transit.gtfs.Alert
import com.thelightphone.transit.gtfs.AlertPreferences
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class DirectionSelectionState {
    object Loading : DirectionSelectionState()
    data class Loaded(val directions: List<DirectionOption>) : DirectionSelectionState()
    /**
     * The route has no trips at all, unlike an empty [Loaded], which skips ahead to stop selection.
     * Shown here so back navigation works normally.
     */
    object NoTrips : DirectionSelectionState()
    data class Error(val message: String) : DirectionSelectionState()
}

/**
 * A "via" clause (e.g. "Hospital District via CCRI Lincoln") is routing detail, not the
 * destination.
 */
private val viaClauseRegex = Regex(""" via .*""", RegexOption.IGNORE_CASE)

/** A bare digit in a direction column isn't a name, so it's treated as missing. */
private fun String?.asRealDirectionName(): String? = this?.takeIf { it.isNotBlank() && it.toIntOrNull() == null }

/**
 * A direction's label outside the picker: the feed's directions.txt name when there is one, else
 * "Toward" the headsign, else the last stop. Used by arrivals, stop connections, and the map. The
 * picker uses [rowLabel].
 */
fun DirectionOption.displayLabel(): String {
    directionName.asRealDirectionName()?.let { name ->
        return destination?.takeIf { it.isNotBlank() }?.let { "$name to $it" } ?: name
    }
    return (headsign?.takeIf { it.isNotBlank() }?.replace(viaClauseRegex, "")?.trim() ?: lastStopName?.takeIf { it.isNotBlank() })
        ?.let { "Toward $it" }
        ?: "Direction $directionId"
}

/**
 * The picker row's text, also passed on once chosen. Headsign first, since several headsigns can
 * share one direction name; the last stop stands in for a missing headsign, then the direction
 * name.
 */
fun DirectionOption.rowLabel(): String =
    (headsign?.takeIf { it.isNotBlank() }?.replace(viaClauseRegex, "")?.trim() ?: lastStopName?.takeIf { it.isNotBlank() })
        ?.let { "Toward $it" }
        ?: directionName.asRealDirectionName()?.let { name ->
            destination?.takeIf { it.isNotBlank() }?.let { "$name to $it" } ?: name
        }
        ?: "Direction $directionId"

/**
 * [rowLabel] can make two headsigns look the same once "via" is stripped; those rows get "Toward"
 * and the full headsign instead.
 */
fun List<DirectionOption>.disambiguatedRowLabels(): Map<DirectionOption, String> {
    val counts = groupingBy { it.rowLabel() }.eachCount()
    return associateWith { direction ->
        val label = direction.rowLabel()
        if (counts[label] == 1) label else direction.headsign?.takeIf { it.isNotBlank() }?.let { "Toward $it" } ?: label
    }
}

class DirectionSelectionViewModel(
    private val dbFile: File,
    private val routeId: String,
    private val alertPreferences: AlertPreferences,
) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<DirectionSelectionState>(DirectionSelectionState.Loading)
    val state: StateFlow<DirectionSelectionState> = _state

    /** Each direction's alerts (keyed by direction_id), when alerts are shown in menus. */
    val directionAlerts = MutableStateFlow<Pair<Map<Int?, List<Alert>>, ScreenAlerts>?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                val directions = repository.getDirections(routeId)
                if (directions.isEmpty() && !repository.routeHasTrips(routeId)) {
                    DirectionSelectionState.NoTrips
                } else {
                    DirectionSelectionState.Loaded(directions)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DirectionSelectionScreen", "Failed to load directions for route $routeId", e)
                DirectionSelectionState.Error("Unable to load directions.")
            }
            val directions = (_state.value as? DirectionSelectionState.Loaded)?.directions ?: return@launch
            directionAlerts.value = try {
                loadScreenAlerts(dbFile, repository, alertPreferences, AlertSurface.MENUS)?.let { screenAlerts ->
                    directions.map { it.directionId }.distinct().associateWith { directionId ->
                        if (directionId == null) screenAlerts.index.forRoute(routeId) else screenAlerts.index.forDirection(routeId, directionId)
                    } to screenAlerts
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DirectionSelectionScreen", "Failed to load alerts", e)
                null
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

    override fun createViewModel(): DirectionSelectionViewModel =
        DirectionSelectionViewModel(dbFile, routeId, AlertPreferences(lightContext.dataStore))

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val directionAlerts by viewModel.directionAlerts.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        // The route has trips but none have a direction_id, so skip straight to stop selection.
        LaunchedEffect(state) {
            val loaded = state as? DirectionSelectionState.Loaded ?: return@LaunchedEffect
            if (loaded.directions.isEmpty()) {
                navigateTo(screenFactory = { activity ->
                    FirstStopSelectionScreen(activity, dbFile, routeId, routeLabel, null, null, null, "Route")
                })
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Choose Direction"),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                )
                Column(modifier = Modifier.weight(1f).padding(32.dp)) {
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

                    is DirectionSelectionState.NoTrips -> LightText(
                        text = "No trips currently scheduled for this route.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )

                    is DirectionSelectionState.Loaded -> {
                        // Grouped by direction_id, with a header when every group has a real name,
                        // e.g. "Outbound: Toward Worcester, Toward Framingham".
                        val groups = s.directions.groupBy { it.directionId }.entries.sortedBy { it.key }
                        val showHeaders = s.directions.all { it.directionName.asRealDirectionName() != null }
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            groups.forEach { (directionId, variants) ->
                                if (showHeaders) {
                                    item {
                                        LightText(
                                            text = variants.first().directionName.asRealDirectionName() ?: "Direction $directionId",
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                        )
                                    }
                                }
                                val disambiguatedLabels = variants.disambiguatedRowLabels()
                                items(variants) { direction ->
                                    val label = disambiguatedLabels.getValue(direction)
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    LightText(
                                        text = label,
                                        variant = LightTextVariant.Copy,
                                        modifier = Modifier
                                            .weight(1f)
                                            .lightClickable {
                                                navigateTo(screenFactory = { activity ->
                                                    FirstStopSelectionScreen(
                                                        activity,
                                                        dbFile,
                                                        routeId,
                                                        routeLabel,
                                                        direction.directionId,
                                                        direction.headsign,
                                                        direction.lastStopId,
                                                        label,
                                                    )
                                                })
                                            }
                                            .padding(vertical = 12.dp),
                                    )
                                    directionAlerts?.let { (byDirection, screenAlerts) -> AlertBadge(byDirection[direction.directionId].orEmpty(), screenAlerts) }
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
