package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.AgencyPreferences
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsIngestStatus
import com.thelightphone.transit.gtfs.GtfsIngestor
import com.thelightphone.transit.gtfs.gtfsDbFile
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class HomeScreenViewModel(
    filesDir: File,
    private val preferences: AgencyPreferences,
) : LightViewModel<Unit>() {

    private val ingestor = GtfsIngestor(filesDir)

    val selectedAgency = MutableStateFlow<GtfsAgency?>(null)
    val status = MutableStateFlow<String?>(null)

    /** Set once ingestion completes successfully; gates the "Explore Schedules" entry point. */
    val readyAgency = MutableStateFlow<GtfsAgency?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // A configured default agency skips the manual tap -- the list stays visible/tappable in
        // case someone wants a different agency for this session, this just saves the first tap.
        // Only checked once per ViewModel (guarded by selectedAgency already being set), so
        // returning to HomeScreen from a child screen doesn't re-trigger it.
        if (selectedAgency.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val default = preferences.defaultAgencyFlow.first()
            if (default != null && selectedAgency.value == null) {
                selectAgency(default)
            }
        }
    }

    fun selectAgency(agency: GtfsAgency) {
        selectedAgency.value = agency
        readyAgency.value = null
        Log.d("HomeScreen", "Selected agency: ${agency.displayName}")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                ingestor.ingest(agency) { ingestStatus ->
                    status.value = when (ingestStatus) {
                        GtfsIngestStatus.CheckingForUpdates -> "Checking for updates..."
                        GtfsIngestStatus.Downloading -> "Downloading ${agency.displayName} data..."
                        GtfsIngestStatus.Parsing -> "Parsing ${agency.displayName} data..."
                        GtfsIngestStatus.Ready -> "Ready"
                    }
                }
                readyAgency.value = agency
            } catch (e: Exception) {
                Log.e("HomeScreen", "GTFS ingestion failed for ${agency.displayName}", e)
                status.value = "Unable to load ${agency.displayName} data."
            }
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        return HomeScreenViewModel(lightContext.filesDir, AgencyPreferences(lightContext.dataStore))
    }

    @Composable
    override fun Content() {
        val selectedAgency by viewModel.selectedAgency.collectAsState()
        val status by viewModel.status.collectAsState()
        val readyAgency by viewModel.readyAgency.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                ) {
                    LightText(
                        text = "Choose Transit Agency",
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    status?.let {
                        LightText(
                            text = it,
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }

                    Column {
                        GtfsAgency.entries.forEach { agency ->
                            LightText(
                                text = agency.displayName,
                                variant = LightTextVariant.Copy,
                                lighten = agency != selectedAgency,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable { viewModel.selectAgency(agency) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }

                    readyAgency?.let { agency ->
                        LightText(
                            text = "Explore Schedules",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    navigateTo(screenFactory = { activity ->
                                        LineTypeSelectionScreen(activity, gtfsDbFile(lightContext.filesDir, agency))
                                    })
                                }
                                .padding(top = 24.dp),
                        )
                        LightText(
                            text = if (agency.realtimeTripUpdatesUrl == null) "Leave Now (Offline)" else "Leave Now",
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    navigateTo(screenFactory = { activity ->
                                        NearbyStopsScreen(activity, gtfsDbFile(lightContext.filesDir, agency), agency)
                                    })
                                }
                                .padding(top = 12.dp),
                        )
                    }
                }

                LightIcon(
                    icon = LightIcons.SETTINGS,
                    contentDescription = "Settings",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .lightClickable {
                            navigateTo(screenFactory = { activity -> SettingsScreen(activity) })
                        },
                )
            }
        }
    }
}
