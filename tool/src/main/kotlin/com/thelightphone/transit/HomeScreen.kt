package com.thelightphone.transit

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.GtfsIngestStatus
import com.thelightphone.transit.gtfs.GtfsIngestor
import com.thelightphone.transit.gtfs.gtfsDbFile
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class HomeScreenViewModel(filesDir: File) : LightViewModel<Unit>() {

    private val ingestor = GtfsIngestor(filesDir)

    val selectedAgency = MutableStateFlow<GtfsAgency?>(null)
    val status = MutableStateFlow<String?>(null)

    /** Set once ingestion completes successfully; gates the "Explore Schedules" entry point. */
    val readyAgency = MutableStateFlow<GtfsAgency?>(null)

    fun selectAgency(agency: GtfsAgency) {
        selectedAgency.value = agency
        readyAgency.value = null
        Log.d("HomeScreen", "Selected agency: ${agency.displayName}")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                ingestor.ingest(agency) { ingestStatus ->
                    status.value = when (ingestStatus) {
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
        return HomeScreenViewModel(lightContext.filesDir)
    }

    @Composable
    override fun Content() {
        val selectedAgency by viewModel.selectedAgency.collectAsState()
        val status by viewModel.status.collectAsState()
        val readyAgency by viewModel.readyAgency.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
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
        }
    }
}
