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

/** Prefers the feed's own curated direction/destination (directions.txt -- see [DirectionOption]'s
 * own doc) when published, since it's the only reliable source for whether a route's two
 * directions are "Inbound"/"Outbound", "Northbound"/"Southbound", or something else -- direction_id
 * alone carries no fixed meaning. Falls back to a headsign-derived "Toward X" label for any agency
 * that doesn't publish it. Used by every screen that shows a [DirectionOption] on its own, outside
 * a grouped picker (arrivals, stop connections, the map), which are built directly from a specific
 * trip's own headsign with no directionName/destination attached, so this only exercises the
 * headsign branch in practice today. NOT used within [DirectionSelectionScreen] itself -- see
 * [rowLabel]'s own doc for why that needs different precedence. */
fun DirectionOption.displayLabel(): String {
    directionName?.takeIf { it.isNotBlank() }?.let { name ->
        return destination?.takeIf { it.isNotBlank() }?.let { "$name to $it" } ?: name
    }
    return headsign?.takeIf { it.isNotBlank() }
        ?.replace(viaClauseRegex, "")
        ?.trim()
        ?.let { "Toward $it" }
        ?: "Direction $directionId"
}

/** The picker row's own text (and the label carried onward to every downstream screen once a
 * direction is chosen) -- always headsign-first, unlike [displayLabel]. Several distinct headsign
 * variants can share one direction_id (see [GtfsRepository.getDirections]'s own doc for real
 * examples), so they also share the same directions.txt-curated directionName/destination; if this
 * preferred directionName the way [displayLabel] does, every variant within a group would render
 * as identical text, defeating the point of keeping them separately selectable. Falls back to the
 * directionName/destination text only when a row has no headsign at all. */
fun DirectionOption.rowLabel(): String =
    headsign?.takeIf { it.isNotBlank() }
        ?.replace(viaClauseRegex, "")
        ?.trim()
        ?.let { "Toward $it" }
        ?: directionName?.takeIf { it.isNotBlank() }?.let { name ->
            destination?.takeIf { it.isNotBlank() }?.let { "$name to $it" } ?: name
        }
        ?: "Direction $directionId"

/**
 * [rowLabel] with per-group disambiguation: two variants within the same direction_id group can
 * have genuinely different headsigns that collide into identical text once [rowLabel]'s own
 * via-clause stripping is applied -- confirmed live on RIPTA route 9 direction 1, whose "Pascoag"
 * and "Pascoag via Citizens Bank" trips (two real, differently-stopping patterns) both stripped
 * down to "Toward Pascoag", rendering as an apparent duplicate. Falls back to "Toward " plus the
 * full, un-stripped headsign for just the rows that collide, so a rider never sees two
 * identical-looking taps that actually lead to different stop lists; a row whose stripped label is
 * already unique within its own group is untouched.
 */
fun List<DirectionOption>.disambiguatedRowLabels(): Map<DirectionOption, String> {
    val counts = groupingBy { it.rowLabel() }.eachCount()
    return associateWith { direction ->
        val label = direction.rowLabel()
        if (counts[label] == 1) label else direction.headsign?.takeIf { it.isNotBlank() }?.let { "Toward $it" } ?: label
    }
}

class DirectionSelectionViewModel(dbFile: File, private val routeId: String) : LightViewModel<Unit>() {

    private val repository = GtfsRepository(dbFile)

    private val _state = MutableStateFlow<DirectionSelectionState>(DirectionSelectionState.Loading)
    val state: StateFlow<DirectionSelectionState> = _state

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

        // Only reachable when this route DOES have trips (see DirectionSelectionState.NoTrips's own
        // doc) -- every one of them just has a null direction_id, so there's nothing meaningful to
        // distinguish here; skip straight to stop selection instead of showing an empty list with
        // nothing to tap.
        LaunchedEffect(state) {
            val loaded = state as? DirectionSelectionState.Loaded ?: return@LaunchedEffect
            if (loaded.directions.isEmpty()) {
                navigateTo(screenFactory = { activity ->
                    FirstStopSelectionScreen(activity, dbFile, routeId, routeLabel, null, null, "Route")
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
                        // own doc) with a section header only when the feed's directions.txt gives every group a
                        // real name -- e.g. MBTA's Framingham/Worcester Line reads as "Outbound: Toward
                        // Worcester, Toward Framingham" / "Inbound: Toward South Station" instead of 4 flat,
                        // unrelated-looking entries. Any agency without directions.txt has every directionName
                        // null, so showHeaders is false and this renders as the same flat list it always has.
                        val groups = s.directions.groupBy { it.directionId }.entries.sortedBy { it.key }
                        val showHeaders = s.directions.all { !it.directionName.isNullOrBlank() }
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            groups.forEach { (directionId, variants) ->
                                if (showHeaders) {
                                    item {
                                        LightText(
                                            text = variants.first().directionName ?: "Direction $directionId",
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                        )
                                    }
                                }
                                val disambiguatedLabels = variants.disambiguatedRowLabels()
                                items(variants) { direction ->
                                    val label = disambiguatedLabels.getValue(direction)
                                    LightText(
                                        text = label,
                                        variant = LightTextVariant.Copy,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .lightClickable {
                                                navigateTo(screenFactory = { activity ->
                                                    FirstStopSelectionScreen(
                                                        activity,
                                                        dbFile,
                                                        routeId,
                                                        routeLabel,
                                                        direction.directionId,
                                                        direction.headsign,
                                                        label,
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
