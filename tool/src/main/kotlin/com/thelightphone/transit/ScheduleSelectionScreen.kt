package com.thelightphone.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.transit.gtfs.AgencyPreferences
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsIngestStatus
import com.thelightphone.transit.gtfs.GtfsIngestor
import com.thelightphone.transit.gtfs.NetworkPreferences
import com.thelightphone.transit.gtfs.RegionalGroup
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScheduleSelectionViewModel(
    private val agencyPreferences: AgencyPreferences,
    filesDir: File,
    connectivity: LightConnectivity,
    networkPreferences: NetworkPreferences,
) : LightViewModel<Unit>() {

    private val ingestor = GtfsIngestor(filesDir, connectivity, networkPreferences)

    val defaultAgency: StateFlow<GtfsAgency?>
        get() = _defaultAgency
    private val _defaultAgency = MutableStateFlow<GtfsAgency?>(null)

    /** Agencies the rider has opted into downloading beyond [defaultAgency] -- see
     * [AgencyPreferences.additionalDownloadsFlow]'s own doc. */
    val additionalDownloads: StateFlow<Set<GtfsAgency>>
        get() = _additionalDownloads
    private val _additionalDownloads = MutableStateFlow<Set<GtfsAgency>>(emptySet())

    /** Per-agency ingest progress, only ever populated for an agency the rider has actively just
     * turned on here this session -- reset to [GtfsIngestStatus.Ready] once done, removed
     * entirely when turned back off (a currently-selected preference alone, not this map, is
     * what's persisted -- see [AgencyPreferences.setAgencyDownloadEnabled]). */
    val ingestStatuses: StateFlow<Map<GtfsAgency, GtfsIngestStatus>>
        get() = _ingestStatuses
    private val _ingestStatuses = MutableStateFlow<Map<GtfsAgency, GtfsIngestStatus>>(emptyMap())

    init {
        viewModelScope.launch {
            agencyPreferences.defaultAgencyFlow.collect { _defaultAgency.value = it }
        }
        viewModelScope.launch {
            agencyPreferences.additionalDownloadsFlow.collect { _additionalDownloads.value = it }
        }
    }

    /**
     * Turning an agency off just stops tracking it as an extra download; its already-downloaded
     * database is left on disk (see this screen's own doc) rather than deleted here, so
     * re-enabling it doesn't need a fresh download. Never touches the primary agency itself --
     * that's picked exclusively via [AgencyPickerModal] (its own region drill-down included, see
     * that class's own doc), a distinct, earlier step from this screen's own "add more on top of
     * my primary" purpose. A no-op for [agency] == [defaultAgency]'s own current value, since
     * that row renders disabled rather than ever calling this.
     */
    fun toggleAgency(agency: GtfsAgency) {
        if (agency == defaultAgency.value) return
        val turningOn = agency !in additionalDownloads.value
        viewModelScope.launch {
            agencyPreferences.setAgencyDownloadEnabled(agency, turningOn)
        }
        if (turningOn) {
            startIngest(agency)
        }
    }

    /**
     * Tap+hold on a non-primary row (see [ScheduleSelectionScreen.AgencyToggleRow]'s own doc) --
     * swaps [agency] in as the new primary via [AgencyPreferences.promoteToPrimary] (picked up by
     * HomeScreenViewModel's own defaultAgencyFlow collector exactly like a fresh pick through
     * [AgencyPickerModal] would be), ingesting it here if it isn't already downloaded -- the one
     * thing [promoteToPrimary] itself can't do, since it has no [GtfsIngestor] of its own. Mirrors
     * [toggleAgency]'s identical no-op guard for the current primary.
     */
    fun makePrimary(agency: GtfsAgency) {
        if (agency == defaultAgency.value) return
        viewModelScope.launch { agencyPreferences.promoteToPrimary(agency) }
        if (agency !in additionalDownloads.value) startIngest(agency)
    }

    // Runs off the main thread, like HomeScreenViewModel's agencyIngestJob. Downloading and parsing
    // a schedule takes a while and would freeze the UI otherwise.
    private fun startIngest(agency: GtfsAgency) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ingestor.ingest(agency) { status ->
                    _ingestStatuses.value = _ingestStatuses.value + (agency to status)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Left as GtfsIngestStatus.CheckingForUpdates/Downloading/Parsing (whatever it last
                // reached) rather than a fake "Ready" -- HomeScreenViewModel's own primary-agency
                // ingest has the identical no-explicit-error-state gap already; a rider can just
                // retry (retoggle, or tap+hold again).
            }
        }
    }
}

/**
 * Lets a rider download more than one agency's static schedule *on top of* their existing primary
 * -- e.g. NYC Subway as primary, plus a specific bus borough and LIRR for transfers. The primary
 * itself is picked earlier and separately, via [AgencyPickerModal] (including its own region
 * drill-down for a grouped agency) -- this screen only ever adds extras alongside whatever that
 * already is, never sets or changes it (see [ScheduleSelectionViewModel.toggleAgency]'s own doc).
 * Reachable only from Settings' own "Additional Schedules" row, which only shows at all when the
 * primary agency is actually part of a [RegionalGroup] -- an ungrouped primary (MBTA, RIPTA, a
 * plain Colorado agency, etc.) has no region-mates to add, so there's nothing for this screen to
 * offer and the row (and this screen) don't appear for it at all. [focusRegion] is that region,
 * always the primary's own -- unlike an earlier version of this screen, there's no unfocused
 * "every region at once" mode anymore, since every real entry point already knows which one region
 * is relevant. Only the download/storage side of this: browsing Schedule/Map for one of these once
 * downloaded isn't wired up by this screen (see [RegionalGroup]'s own doc for the current state of
 * that).
 */
class ScheduleSelectionScreen(
    sealedActivity: SealedLightActivity,
    private val focusRegion: RegionalGroup,
) : LightScreen<Unit, ScheduleSelectionViewModel>(sealedActivity) {

    override val viewModelClass: Class<ScheduleSelectionViewModel>
        get() = ScheduleSelectionViewModel::class.java

    override fun createViewModel(): ScheduleSelectionViewModel = ScheduleSelectionViewModel(
        AgencyPreferences(lightContext.dataStore),
        lightContext.filesDir,
        lightContext.connectivity,
        NetworkPreferences(lightContext.dataStore),
    )

    /**
     * Tap toggles [enabled] (see [ScheduleSelectionViewModel.toggleAgency]); tap+hold on a
     * non-primary row instead swaps it in as the new primary agency (see
     * [ScheduleSelectionViewModel.makePrimary]'s own doc) -- [lightClickable] has no long-press
     * hook of its own, so this uses the same raw [detectTapGestures] pattern
     * FirstStopSelectionScreen's identical tap-vs-hold row already does, rather than inventing a
     * second one. Both gestures are no-ops on the primary's own row (`enabled = !isPrimary`
     * already reads false for [lightClickable]'s sibling rows elsewhere in this app -- mirrored
     * here as an explicit `if (isPrimary) return@detectTapGestures` guard on each callback since
     * this composable owns its own gesture detector instead of delegating to that modifier).
     */
    @Composable
    private fun AgencyToggleRow(
        agency: GtfsAgency,
        isPrimary: Boolean,
        enabled: Boolean,
        status: GtfsIngestStatus?,
        onToggle: () -> Unit,
        onMakePrimary: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(isPrimary) {
                    detectTapGestures(
                        onTap = {
                            if (isPrimary) return@detectTapGestures
                            onToggle()
                        },
                        onLongPress = {
                            if (isPrimary) return@detectTapGestures
                            onMakePrimary()
                        },
                    )
                }
                .padding(vertical = 10.dp),
        ) {
            LightIcon(
                icon = if (isPrimary || enabled) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                size = 1.2f,
                contentDescription = if (isPrimary || enabled) "On" else "Off",
                modifier = Modifier.padding(end = 12.dp),
            )
            LightText(
                text = agency.displayName,
                variant = LightTextVariant.Copy,
                // Same underline-marks-the-current-one convention TripDetailScreen's own alight
                // stop and SelectRunScreen's own boarded run already use, rather than greying the
                // primary row out.
                underline = isPrimary,
                modifier = Modifier.weight(1f),
            )
            if (status != null && status != GtfsIngestStatus.Ready) {
                LightText(
                    text = when (status) {
                        GtfsIngestStatus.CheckingForUpdates -> "Checking…"
                        GtfsIngestStatus.Downloading -> "Downloading…"
                        GtfsIngestStatus.Parsing -> "Parsing…"
                        GtfsIngestStatus.WaitingForWifi -> "Waiting for Wi-Fi"
                        GtfsIngestStatus.Ready -> ""
                    },
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
        }
    }

    @Composable
    private fun AgencyGroup(
        title: String,
        agencies: List<GtfsAgency>,
        defaultAgency: GtfsAgency?,
        additionalDownloads: Set<GtfsAgency>,
        ingestStatuses: Map<GtfsAgency, GtfsIngestStatus>,
    ) {
        LightText(
            text = title,
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        agencies.forEach { agency ->
            AgencyToggleRow(
                agency = agency,
                isPrimary = agency == defaultAgency,
                enabled = agency in additionalDownloads,
                status = ingestStatuses[agency],
                onToggle = { viewModel.toggleAgency(agency) },
                onMakePrimary = { viewModel.makePrimary(agency) },
            )
        }
    }

    @Composable
    override fun Content() {
        val defaultAgency by viewModel.defaultAgency.collectAsState()
        val additionalDownloads by viewModel.additionalDownloads.collectAsState()
        val ingestStatuses by viewModel.ingestStatuses.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(focusRegion.displayName),
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(32.dp)) {
                    LightText(
                        text = "Choose which ${focusRegion.displayName} schedules to include alongside " +
                            "your primary agency (Settings > Transit Agency).",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    LightText(
                        text = "Tap & hold a schedule to make it your primary agency instead.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    AgencyGroup(focusRegion.displayName, focusRegion.members, defaultAgency, additionalDownloads, ingestStatuses)
                }
            }
        }
    }
}
