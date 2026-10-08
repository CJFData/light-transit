package com.thelightphone.uidemo

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class UiDemoLocationScreen(sealedLightActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedLightActivity) {
    private var resumeCount by mutableIntStateOf(0)

    override fun willShow() {
        resumeCount++
    }

    @Composable
    override fun Content() {
        var hasLocationPermission by remember { mutableStateOf(false) }
        var location by remember { mutableStateOf<LightOSLocation?>(null) }
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.ACCESS_FINE_LOCATION)

        LaunchedEffect(resumeCount) {
            val granted = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION).asKotlinResult
                .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
                .getOrDefault(false)
            hasLocationPermission = granted
            if (!granted) {
                permissionLauncher?.launch()
                return@LaunchedEffect
            }

            coroutineScope {
                val requestUpdatesJob = launch {
                    while (isActive) {
                        callRemoteServiceMethod(LightServiceMethod.RequestLocationUpdates, Unit)
                        delay(30.seconds)
                    }
                }
                try {
                    while (isActive) {
                        val current =
                            callRemoteServiceMethod(LightServiceMethod.GetCurrentLocation, Unit)
                                .getOrNull()
                        location = current
                            ?.takeIf {
                                it.latitude != null && it.longitude != null &&
                                    it.accuracyMeters != null && it.timestampMs != null
                            }
                            ?.let {
                                LightOSLocation(
                                    it.latitude!!,
                                    it.longitude!!,
                                    it.accuracyMeters!!,
                                    Instant.fromEpochMilliseconds(it.timestampMs!!),
                                )
                            }
                        delay(2.seconds)
                    }
                } finally {
                    requestUpdatesJob.cancel()
                    withContext(NonCancellable) {
                        callRemoteServiceMethod(LightServiceMethod.ReleaseLocationUpdates, Unit)
                    }
                }
            }
        }

        LocationScreen(
            hasLocationPermission = hasLocationPermission,
            location = location,
            onBack = { goBack() },
        )
    }
}

data class LightOSLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val timestamp: Instant,
)

@Composable
fun LocationScreen(
    hasLocationPermission: Boolean,
    location: LightOSLocation?,
    onBack: () -> Unit
) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(themeColors) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                ),
                center = LightTopBarCenter.Text("Location"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                @Composable fun CopyText(copy: String) {
                    LightText(
                        copy,
                        LightTextVariant.Copy,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(2f.gridUnitsAsDp()).align(Alignment.Center)
                    )
                }
                if (!hasLocationPermission) {
                    CopyText("Location permission required for this screen")
                } else if (location == null) {
                    CopyText("No location found yet...")
                } else {
                    LocationDisplay(location)
                }
            }
        }
    }
}

@Composable
fun LocationDisplay(location: LightOSLocation) {
    Column(Modifier.fillMaxSize().padding(1f.gridUnitsAsDp())) {
        LightText(
            "LATITUDE",
            LightTextVariant.Superfine,
            align = TextAlign.Center,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        LightText(
            location.latitude.toString(),
            LightTextVariant.Copy,
            align = TextAlign.Center
        )
        Spacer(Modifier.height(1f.gridUnitsAsDp()))
        LightText(
            "LONGITUDE",
            LightTextVariant.Superfine,
            align = TextAlign.Center,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        LightText(
            location.longitude.toString(),
            LightTextVariant.Copy,
            align = TextAlign.Center
        )
        Spacer(Modifier.height(1f.gridUnitsAsDp()))
        LightText(
            "ACCURACY",
            LightTextVariant.Superfine,
            align = TextAlign.Center,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        LightText(
            "${location.accuracyMeters}m",
            LightTextVariant.Copy,
            align = TextAlign.Center
        )
        Spacer(Modifier.height(1f.gridUnitsAsDp()))
        LightText(
            "TIMESTAMP",
            LightTextVariant.Superfine,
            align = TextAlign.Center,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        LightText(
            location.timestamp.toString(),
            LightTextVariant.Copy,
            align = TextAlign.Center
        )
        Spacer(Modifier.height(1f.gridUnitsAsDp()))
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
fun LocationScreenPreview() {
    LocationScreen(true, LightOSLocation(0.0, 0.5, 12.0, Clock.System.now())) {}
}