package com.thelightphone.transit

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
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.gtfsDbFile
import java.io.File

class ScheduleAgencyPickerViewModel : LightViewModel<Unit>()

/**
 * Home's Schedule button opens this when the rider has more than one schedule downloaded in the
 * region, to pick which one to browse. With only one, it goes straight to that schedule.
 */
class ScheduleAgencyPickerScreen(
    sealedActivity: SealedLightActivity,
    private val agencies: List<GtfsAgency>,
    private val filesDir: File,
) : LightScreen<Unit, ScheduleAgencyPickerViewModel>(sealedActivity) {

    override val viewModelClass: Class<ScheduleAgencyPickerViewModel>
        get() = ScheduleAgencyPickerViewModel::class.java

    override fun createViewModel(): ScheduleAgencyPickerViewModel = ScheduleAgencyPickerViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Choose Schedule"),
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(32.dp)) {
                    agencies.forEach { agency ->
                        LightText(
                            text = agency.displayName,
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable {
                                    navigateTo(screenFactory = { activity ->
                                        LineTypeSelectionScreen(activity, gtfsDbFile(filesDir, agency))
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
