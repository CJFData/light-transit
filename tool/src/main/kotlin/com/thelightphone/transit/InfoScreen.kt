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

/** Every icon this app actually draws on the map/HomeScreen that a rider would need explained --
 * kept as a single list so it can't quietly drift out of sync with what MapScreen/HomeScreen use
 * (verified against a full grep of every LightIcons.* reference in this package). Chrome-only icons
 * (back, settings, search, this screen's own entry point) aren't map/data indicators, so they're
 * left out per the same reasoning that excluded them from the request this screen was built for. */
private val ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.DIRECTIONS_SUBWAY, "Subway / Light Rail vehicle"),
    IconLegendEntry(LightIcons.DIRECTIONS_BUS, "Bus vehicle"),
    IconLegendEntry(LightIcons.DIRECTIONS_TRAIN, "Commuter Rail vehicle"),
    IconLegendEntry(LightIcons.DIRECTIONS_FERRY, "Ferry vehicle"),
    IconLegendEntry(LightIcons.DIRECTIONS_MIDDLE_FORK, "Multi-platform station (tap to see all its platforms)"),
    IconLegendEntry(LightIcons.DIRECTIONS_ARRIVAL, "Selected stop (large) / nearby stop (small) on the map"),
    IconLegendEntry(LightIcons.DOWNLOAD_ARROW, "Agency schedule not yet downloaded"),
    IconLegendEntry(LightIcons.REFRESH, "Checking for schedule updates"),
)

/** HomeScreen's own bottom icon rows -- kept as a single list for the same reason as [ICON_LEGEND],
 * verified against HomeScreen.kt's own LightBottomBar item lists. */
private val MENU_ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.ELLIPSES, "About (this screen)"),
    IconLegendEntry(LightIcons.SETTINGS, "Settings"),
    IconLegendEntry(LightIcons.LIST, "Schedule -- browse today's static route schedule"),
    IconLegendEntry(LightIcons.DIRECTIONS_PEDESTRIAN, "Explore -- find nearby stops and live upcoming arrivals"),
    IconLegendEntry(LightIcons.DIRECTIONS_MIDDLE_FORK, "Station -- browse a transit authority's multi-platform stations"),
    IconLegendEntry(LightIcons.PLAY, "Play/Board -- on a Trip Detail screen, boards that trip. Everywhere else, shown once a trip's boarded, to jump back to its live tracking"),
    IconLegendEntry(LightIcons.STOP, "Stop/Alight -- Trip Detail's header, shown in place of Play while that trip is the one you've boarded; taps end tracking"),
    IconLegendEntry(LightIcons.DELETE, "Trip switch warning -- Trip Detail's header, shown next to Play when a DIFFERENT trip is already boarded; boarding this one ends tracking of that one"),
    IconLegendEntry(LightIcons.CIRCLE, "Home -- every other screen's own footer button; jumps back to HomeScreen"),
)

/** Settings screen's own on/off toggles -- every one of them renders as one of these two icons
 * next to their label, per SettingsScreen's own ToggleRow. See SettingsScreen.kt for the current
 * list of toggles. */
private val TOGGLE_ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.TOGGLE_STATE_ON, "Setting is on -- tap the row to turn it off"),
    IconLegendEntry(LightIcons.TOGGLE_STATE_OFF, "Setting is off -- tap the row to turn it on"),
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
                        text = "A transit companion for the Light Phone III -- live arrivals, " +
                            "schedules, and station maps.",
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
                        text = "More coming soon. HomeScreen always credits whichever agency's GTFS " +
                            "feed the data actually came from, near the bottom of the screen.",
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
                        description = "Browse today's static route schedule: route, then direction, " +
                            "then stop, then departure times, then the full trip's stop list.",
                    )
                    ModeEntry(
                        name = "Explore",
                        description = "Find nearby stops by your current location and see live " +
                            "upcoming arrivals with status -- on time, early, or late.",
                    )
                    ModeEntry(
                        name = "Station",
                        description = "Browse every real multi-platform station an agency has (with a " +
                            "live-filter search box once the list gets long enough to need one), and " +
                            "view a zoomed-in map of a station's individual real platforms and gates. " +
                            "For MBTA commuter rail, a vehicle shows up on its assigned track once " +
                            "MBTA decides one.",
                    )

                    LightText(
                        text = "Boarding a trip",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LightText(
                        text = "Board a trip from its Trip Detail screen (Play/Stop icon, top right) to " +
                            "make it your current trip. While boarded, HomeScreen replaces \"Choose " +
                            "Transit Agency\" with your route, live ETA, and stops remaining to your " +
                            "designated alight stop, and hides the agency list until you alight. If " +
                            "the \"Trip progress bar\" setting is on, a bar with a vehicle-type marker " +
                            "also shows live progress between your boarding and alight stops. Tap a " +
                            "stop on Trip Detail to set (or clear) it as your alight stop -- reaching " +
                            "it shows a \"You've reached your stop!\" message and ends tracking " +
                            "automatically, whether you're on Trip Detail or HomeScreen at the time.",
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
                        text = "Settings Toggles",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    LightText(
                        text = "\"Track tapped stops\" (a tapped-open stop on the Map screen also " +
                            "contributes its own live vehicles) lives here now, alongside \"Tap and " +
                            "hold a stop\", \"Double-tap to open a station\", \"Trip progress bar\", " +
                            "and \"Daily message\" (the small rotating message near the bottom of " +
                            "the home screen). \"See everything\" shows every live vehicle in view on " +
                            "the Map/Station map, each labeled with just its route until tapped; " +
                            "\"Filter by stop\" and per-mode \"Modes shown\" toggles refine it further " +
                            "and only appear once it's on. \"Tap and hold -- Schedules\" and \"Tap " +
                            "and hold -- Stations\" (both on by default) add the same tap-and-hold-" +
                            "for-arrivals gesture to the Schedule stop list and the Stations list. " +
                            "\"Tap and hold -- Vehicles\" (on by default) opens a live vehicle's own " +
                            "Trip Detail when tapped and held on the Map screen or a Station map. " +
                            "\"Randomize daily message\" (off by default) only appears once \"Daily " +
                            "message\" itself is on, and picks a fresh message at random each time " +
                            "you return to the home screen instead of once per calendar day.",
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
                        text = "If Pico Transit helps you catch your bus, consider buying me a " +
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
