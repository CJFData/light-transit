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
import com.thelightphone.transit.gtfs.gtfsDbFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Schedule download progress, for the primary and additional schedules. Additional-schedule
 * downloads run in the app's scope so leaving the screen doesn't cancel them.
 */
object AdditionalScheduleDownloads {
    private val _statuses = MutableStateFlow<Map<GtfsAgency, GtfsIngestStatus>>(emptyMap())
    val statuses: StateFlow<Map<GtfsAgency, GtfsIngestStatus>> = _statuses
    private val running = mutableSetOf<GtfsAgency>()

    /** Records progress for a download started elsewhere, such as the primary on Home. */
    fun report(agency: GtfsAgency, status: GtfsIngestStatus) {
        _statuses.update { it + (agency to status) }
    }

    /** Starts downloading [agency] unless it's already running. */
    @Synchronized
    fun start(agency: GtfsAgency, ingestor: GtfsIngestor) {
        if (!running.add(agency)) return
        HomeVisibility.scope.launch(Dispatchers.IO) {
            try {
                ingestor.ingest(agency) { status -> report(agency, status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failure leaves the last status showing; turning the agency off and on again
                // retries.
            } finally {
                synchronized(this@AdditionalScheduleDownloads) { running.remove(agency) }
            }
        }
    }
}

class ScheduleSelectionViewModel(
    private val agencyPreferences: AgencyPreferences,
    private val filesDir: File,
    connectivity: LightConnectivity,
    networkPreferences: NetworkPreferences,
) : LightViewModel<Unit>() {

    private val ingestor = GtfsIngestor(filesDir, connectivity, networkPreferences)

    val defaultAgency: StateFlow<GtfsAgency?>
        get() = _defaultAgency
    private val _defaultAgency = MutableStateFlow<GtfsAgency?>(null)

    /** Agencies the rider downloads in addition to [defaultAgency]. */
    val additionalDownloads: StateFlow<Set<GtfsAgency>>
        get() = _additionalDownloads
    private val _additionalDownloads = MutableStateFlow<Set<GtfsAgency>>(emptySet())

    /** Download progress for agencies turned on here, including after leaving and returning. */
    val ingestStatuses: StateFlow<Map<GtfsAgency, GtfsIngestStatus>> = AdditionalScheduleDownloads.statuses

    init {
        viewModelScope.launch {
            agencyPreferences.defaultAgencyFlow.collect { _defaultAgency.value = it }
        }
        viewModelScope.launch {
            agencyPreferences.additionalDownloadsFlow.collect { _additionalDownloads.value = it }
        }
        // Finishes any turned-on schedule that never completed, e.g. if the app closed mid-download.
        viewModelScope.launch(Dispatchers.IO) {
            for (agency in agencyPreferences.additionalDownloadsFlow.first()) {
                if (!gtfsDbFile(filesDir, agency).exists()) startIngest(agency)
            }
        }
    }

    /**
     * Turning an agency off stops tracking it but keeps its database, so turning it back on doesn't
     * need a download. The primary's row is disabled; it's changed through [AgencyPickerModal].
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
     * Tap and hold on a non-primary row: makes [agency] the primary, downloading it first if
     * needed.
     */
    fun makePrimary(agency: GtfsAgency) {
        if (agency == defaultAgency.value) return
        viewModelScope.launch { agencyPreferences.promoteToPrimary(agency) }
        if (agency !in additionalDownloads.value) startIngest(agency)
    }

    private fun startIngest(agency: GtfsAgency) = AdditionalScheduleDownloads.start(agency, ingestor)
}

/**
 * Additional Schedules: downloads other agencies in the primary's region on top of the primary,
 * e.g. a bus borough and LIRR alongside NYC Subway. Reached from Settings, which only shows the row
 * when the primary is in a [RegionalGroup]; [focusRegion] is that region.
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
     * Tap toggles the download; tap and hold makes the agency primary. Neither does anything on the
     * primary's own row.
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
                // The primary is underlined, like the alight stop in Trip Detail.
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
