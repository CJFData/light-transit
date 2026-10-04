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
    /** Distinguished from [Loaded] with an empty list -- see [GtfsRepository.routeHasTrips]'s own
     * doc. This route genuinely has no trips scheduled at all, so there's nowhere useful to
     * auto-skip to (unlike an empty-but-has-trips [Loaded], which auto-advances to stop
     * selection). Rendered here instead, so back navigation behaves normally rather than bouncing
     * into a dead-end "Nothing found in today's schedule" screen. */
    object NoTrips : DirectionSelectionState()
    data class Error(val message: String) : DirectionSelectionState()
}

/** Matches MBTA-style headsigns like "Hospital District via CCRI Lincoln" -- the via-clause is
 * routing detail, not part of the destination riders actually look for. */
private val viaClauseRegex = Regex(""" via .*""", RegexOption.IGNORE_CASE)

/** Only shows a directionName if it's a real word. Some feeds put a bare digit (the direction_id
 * itself) in their direction column, which looks like a glitch, so we treat it as missing and fall
 * back to "Direction $directionId". */
private fun String?.asRealDirectionName(): String? = this?.takeIf { it.isNotBlank() && it.toIntOrNull() == null }

/** Prefers the feed's own curated direction/destination (directions.txt -- see [DirectionOption]'s
 * own doc) when published, since it's the only reliable source for whether a route's two
 * directions are "Inbound"/"Outbound", "Northbound"/"Southbound", or something else -- direction_id
 * alone carries no fixed meaning. Falls back to a headsign-derived "Toward X" label for any agency
 * that doesn't publish it, or [lastStopName] (see [DirectionOption]'s own doc) for one that has
 * neither a headsign nor directions.txt at all -- e.g. CTA. Used by every screen that shows a
 * [DirectionOption] on its own, outside a grouped picker (arrivals, stop connections, the map),
 * which are built directly from a specific trip, not a grouped/curated one, so directionName only
 * ever comes from a real directions.txt file here, never [DirectionSelectionScreen]'s own grouping.
 * NOT used within [DirectionSelectionScreen] itself -- see [rowLabel]'s own doc for why that needs
 * different precedence. */
fun DirectionOption.displayLabel(): String {
    directionName.asRealDirectionName()?.let { name ->
        return destination?.takeIf { it.isNotBlank() }?.let { "$name to $it" } ?: name
    }
    return (headsign?.takeIf { it.isNotBlank() }?.replace(viaClauseRegex, "")?.trim() ?: lastStopName?.takeIf { it.isNotBlank() })
        ?.let { "Toward $it" }
        ?: "Direction $directionId"
}

/** The picker row's text, also carried to downstream screens once chosen. Always headsign-first,
 * unlike [displayLabel]: several headsign variants can share one direction_id and its
 * directions.txt name, and preferring that name would make them render identically.
 * [lastStopName] (see [DirectionOption]) stands in for a missing headsign. Falls back to the
 * directionName/destination only when a row has neither. */
fun DirectionOption.rowLabel(): String =
    (headsign?.takeIf { it.isNotBlank() }?.replace(viaClauseRegex, "")?.trim() ?: lastStopName?.takeIf { it.isNotBlank() })
        ?.let { "Toward $it" }
        ?: directionName.asRealDirectionName()?.let { name ->
            destination?.takeIf { it.isNotBlank() }?.let { "$name to $it" } ?: name
        }
        ?: "Direction $directionId"

/**
 * [rowLabel], disambiguated within each direction_id group: two different headsigns can collapse
 * to the same text once [rowLabel] strips "via" clauses. Only the colliding rows fall back to
 * "Toward " plus the full headsign, so two identical-looking rows never lead to different stops.
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

        // Only reachable when this route DOES have trips (see DirectionSelectionState.NoTrips's own
        // doc) -- every one of them just has a null direction_id, so there's nothing meaningful to
        // distinguish here; skip straight to stop selection instead of showing an empty list with
        // nothing to tap.
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
                        // Grouped by direction_id (never more than 2 real groups -- see GtfsRepository.getDirections's
                        // own doc) with a section header only when every group has a real directionName -- e.g.
                        // MBTA's Framingham/Worcester Line reads as "Outbound: Toward Worcester, Toward
                        // Framingham" / "Inbound: Toward South Station" instead of 4 flat, unrelated-looking
                        // entries. Any agency without directions.txt has every directionName null; see
                        // asRealDirectionName's own doc for the other way a "real" name turns out not to be one.
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
