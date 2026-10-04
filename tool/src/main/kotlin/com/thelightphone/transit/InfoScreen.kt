package com.thelightphone.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

private data class IconLegendEntry(val icon: LightIconConfiguration, val label: String)

/** Every icon [MapScreen] actually draws on its own canvas -- kept as a single list so it can't
 * quietly drift out of sync with what MapScreen uses (verified against a full grep of every
 * LightIcons.* reference actually drawn there, not just imported). Chrome-only icons (back,
 * settings, search, this screen's own entry point) aren't map/data indicators, so they're left out
 * per the same reasoning that excluded them from the request this screen was built for. */
private val ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.DIRECTIONS_SUBWAY, "Subway or light rail"),
    IconLegendEntry(LightIcons.DIRECTIONS_BUS, "Bus"),
    IconLegendEntry(LightIcons.DIRECTIONS_TRAIN, "Commuter rail"),
    IconLegendEntry(LightIcons.DIRECTIONS_FERRY, "Ferry"),
    IconLegendEntry(LightIcons.DIRECTIONS_MIDDLE_FORK, "Station with several platforms; tap to see them"),
    IconLegendEntry(LightIcons.DIRECTIONS_ARRIVAL, "Your selected stop (large) or a nearby stop (small)"),
)

/** Icons that show up around schedule selection/download, not on the map or HomeScreen's own
 * bottom bar -- split out from [ICON_LEGEND] since neither one is actually drawn there:
 * [LightIcons.DOWNLOAD_ARROW] appears next to a not-yet-downloaded agency in the Transit Agency
 * picker and Additional Schedules; [LightIcons.REFRESH] appears inline next to the agency name on
 * HomeScreen while its schedule is being checked/downloaded. */
private val SCHEDULE_ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.DOWNLOAD_ARROW, "Schedule not downloaded yet"),
    IconLegendEntry(LightIcons.REFRESH, "Schedule downloading or checking for updates"),
)

/** HomeScreen's own bottom icon rows -- kept as a single list for the same reason as [ICON_LEGEND],
 * verified against HomeScreen.kt's own LightBottomBar item lists. */
private val MENU_ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.ELLIPSES, "About"),
    IconLegendEntry(LightIcons.SETTINGS, "Settings"),
    IconLegendEntry(LightIcons.LIST, "Schedule: browse routes and departure times"),
    IconLegendEntry(LightIcons.DIRECTIONS_PEDESTRIAN, "Explore: nearby stops and live arrivals"),
    IconLegendEntry(LightIcons.DIRECTIONS_MIDDLE_FORK, "Station: browse stations and their platforms"),
    IconLegendEntry(LightIcons.PLAY, "Board this trip, or jump back to the trip you're on"),
    IconLegendEntry(LightIcons.STOP, "Get off: stop tracking this trip"),
    IconLegendEntry(LightIcons.DELETE, "You're on another trip; boarding this one ends it"),
    IconLegendEntry(LightIcons.CIRCLE, "Back to the home screen"),
    IconLegendEntry(LightIcons.EMERGENCY, "Service alert; tap to read it"),
)

/** Settings screen's own on/off toggles -- every one of them renders as one of these two icons
 * next to their label, per SettingsScreen's own ToggleRow. See SettingsScreen.kt for the current
 * list of toggles. */
private val TOGGLE_ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.TOGGLE_STATE_ON, "On; tap to turn off"),
    IconLegendEntry(LightIcons.TOGGLE_STATE_OFF, "Off; tap to turn on"),
)

class InfoScreenViewModel : LightViewModel<Unit>()

/** HomeScreen's info/about entry point -- no dedicated "about screen" template exists anywhere in
 * the SDK (checked), so this follows the same LightTopBar+LightScrollView+LightText convention
 * SettingsScreen already established elsewhere in this app. */
class InfoScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, InfoScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<InfoScreenViewModel>
        get() = InfoScreenViewModel::class.java

    override fun createViewModel(): InfoScreenViewModel = InfoScreenViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("About"),
                    rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                        navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                    },
                )
                LightScrollView(modifier = Modifier.weight(1f).padding(32.dp)) {
                    LightText(
                        text = "Pico Transit",
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LightText(
                        text = "Schedules, live arrivals, and maps for public transit on the Light Phone III.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )

                    LightText(
                        text = "Supported Agencies",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LightText(
                        text = GtfsAgency.entries.joinToString(", ") { it.displayName },
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    LightText(
                        text = "More coming soon. Data credits are at the bottom of the home screen.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )

                    LightText(
                        text = "Modes",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ModeEntry(
                        name = "Schedule",
                        description = "Pick a route, direction, and stop to see departure times and each trip's stops.",
                    )
                    ModeEntry(
                        name = "Explore",
                        description = "Find stops near an address and see live arrivals: on time, early, or late.",
                    )
                    ModeEntry(
                        name = "Station",
                        description = "Browse stations, search them, and see a map of each station's platforms.",
                    )

                    LightText(
                        text = "Boarding a trip",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LightText(
                        text = "Tap Play on a trip to board it. The home screen then shows your route, " +
                            "arrival time, and progress. Tap a stop in the trip to mark where you'll get " +
                            "off; when you get there, you'll see a message and tracking ends.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )

                    LightText(
                        text = "Regional Schedules",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LightText(
                        text = "New York City, Denver, the San Francisco Bay Area, and Puget Sound group " +
                            "their agencies into regions. Add more of a region's schedules in Settings → " +
                            "Additional Schedules to browse them together; tap and hold one there to make " +
                            "it your main agency.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )

                    LightText(
                        text = "Service alerts",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LightText(
                        text = "Turn on Service alerts in Settings to see detours and closures, for " +
                            "agencies that publish them. Look for the alert icon on routes, stops, and trips.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )

                    LightText(
                        text = "Menu Icons",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    MENU_ICON_LEGEND.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 6.dp),
                        ) {
                            LightIcon(icon = entry.icon, size = 1f, modifier = Modifier.padding(end = 12.dp))
                            LightText(text = entry.label, variant = LightTextVariant.Detail, lighten = true)
                        }
                    }

                    LightText(
                        text = "Map Icons",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    ICON_LEGEND.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 6.dp),
                        ) {
                            LightIcon(icon = entry.icon, size = 1f, modifier = Modifier.padding(end = 12.dp))
                            LightText(text = entry.label, variant = LightTextVariant.Detail, lighten = true)
                        }
                    }

                    LightText(
                        text = "Schedule Icons",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    SCHEDULE_ICON_LEGEND.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 6.dp),
                        ) {
                            LightIcon(icon = entry.icon, size = 1f, modifier = Modifier.padding(end = 12.dp))
                            LightText(text = entry.label, variant = LightTextVariant.Detail, lighten = true)
                        }
                    }

                    LightText(
                        text = "Settings",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    LightText(
                        text = "Each setting describes what it does on the Settings screen.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TOGGLE_ICON_LEGEND.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 6.dp),
                        ) {
                            LightIcon(icon = entry.icon, size = 1f, modifier = Modifier.padding(end = 12.dp))
                            LightText(text = entry.label, variant = LightTextVariant.Detail, lighten = true)
                        }
                    }

                    LightText(
                        text = "Map tiles © OpenStreetMap contributors © CARTO",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 24.dp),
                    )

                    LightText(
                        text = "Made by Christian Ferreira · github.com/CJFData",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                    LightText(
                        text = "If Pico Transit helps you catch your bus, train, or ferry, consider buying me a " +
                            "coffee ☕ -- buymeacoffee.com/cjfdata",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeEntry(name: String, description: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        LightText(text = name, variant = LightTextVariant.Copy)
        LightText(text = description, variant = LightTextVariant.Detail, lighten = true)
    }
}
