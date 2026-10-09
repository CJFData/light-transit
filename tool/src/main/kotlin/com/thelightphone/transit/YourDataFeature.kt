package com.thelightphone.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.CompletableDeferred

private val YOUR_DATA_PARAGRAPHS = listOf(
    "Pico Transit has no accounts or ads, and no personal data tracking. Your settings and downloaded " +
        "schedules stay on your phone.",
    "To show live arrivals, vehicles, alerts, and maps, Pico Transit asks gtfs.picotransit.com for " +
        "the transit feeds and map tiles you're viewing. That service relays public transit data and " +
        "counts how often each feed is used, since agencies limit requests and some ask for usage " +
        "numbers. Schedules download from each agency or through the same service.",
    "Location is off until you turn it on in Explore or Settings. Then Pico Transit only asks LightOS " +
        "for your location when you open Explore. It takes one reading, calculates distances to nearby " +
        "stops on your phone, and never sends your location anywhere.",
    "Searching an address sends what you type to OpenStreetMap, which turns it into coordinates that " +
        "Pico Transit compares with the stop locations in your transit schedule. A default location you " +
        "set is saved on your phone, so it's only looked up once.",
    "Vehicle locations on the map and on your trip come from the agencies' public transit feeds, not " +
        "from your phone.",
    "Pico Transit is built on Light's SDK and is open source on GitHub.",
)

/** The "Your data" heading and paragraphs, shared by About and the first-launch notice. */
@Composable
fun YourDataSection(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        LightText(
            text = "Your data",
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        YOUR_DATA_PARAGRAPHS.forEachIndexed { index, paragraph ->
            LightText(
                text = paragraph,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = if (index < YOUR_DATA_PARAGRAPHS.lastIndex) Modifier.padding(bottom = 12.dp) else Modifier,
            )
        }
    }
}

/** Shown once on first launch, before the agency picker. */
class YourDataModal(
    private val onContinue: () -> Unit,
) : LightModal {
    private val dismissSignal = CompletableDeferred<Unit>()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightScrollView(modifier = Modifier.weight(1f).padding(32.dp)) {
                    YourDataSection()
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            contentDescription = "Close",
                            onClick = onContinue,
                        ),
                    ),
                )
            }
        }
    }

    // Never fires; the modal has no timeout.
    override val onExpired: () -> Unit = {}

    override fun dismiss() {
        dismissSignal.complete(Unit)
    }

    override suspend fun awaitDismiss() = dismissSignal.await()
}
