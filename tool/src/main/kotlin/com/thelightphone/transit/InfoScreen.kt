package com.thelightphone.transit

import androidx.compose.foundation.background
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
import com.thelightphone.sdk.ui.lightClickable

private data class IconLegendEntry(val icon: LightIconConfiguration, val label: String)

private val ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.DIRECTIONS_SUBWAY, "Subway or light rail"),
    IconLegendEntry(LightIcons.DIRECTIONS_BUS, "Bus"),
    IconLegendEntry(LightIcons.DIRECTIONS_TRAIN, "Commuter rail"),
    IconLegendEntry(LightIcons.DIRECTIONS_FERRY, "Ferry"),
    IconLegendEntry(LightIcons.DIRECTIONS_MIDDLE_FORK, "Station with several platforms; tap to see them"),
    IconLegendEntry(LightIcons.DIRECTIONS_ARRIVAL, "Your selected stop (large) or a nearby stop (small)"),
)

private val SCHEDULE_ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.DOWNLOAD_ARROW, "Schedule not downloaded yet"),
    IconLegendEntry(LightIcons.REFRESH, "Schedule downloading or checking for updates"),
)

private val MENU_ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.ELLIPSES, "About"),
    IconLegendEntry(LightIcons.SETTINGS, "Settings"),
    IconLegendEntry(LightIcons.LIST, "Schedule: browse routes and departure times"),
    IconLegendEntry(LightIcons.DIRECTIONS_PEDESTRIAN, "Explore: nearby stops and live arrivals"),
    IconLegendEntry(LightIcons.DIRECTIONS_MIDDLE_FORK, "Station: browse stations and their platforms"),
    IconLegendEntry(LightIcons.PLAY, "Board this trip, or return to the trip you're riding"),
    IconLegendEntry(LightIcons.STOP, "Get off: stop tracking this trip"),
    IconLegendEntry(LightIcons.DELETE, "Boarding this trip ends your current trip"),
    IconLegendEntry(LightIcons.CIRCLE, "Back to the home screen"),
    IconLegendEntry(LightIcons.EMERGENCY, "Service alert; tap to read it"),
)

private val TOGGLE_ICON_LEGEND = listOf(
    IconLegendEntry(LightIcons.TOGGLE_STATE_ON, "On; tap to turn off"),
    IconLegendEntry(LightIcons.TOGGLE_STATE_OFF, "Off; tap to turn on"),
)

enum class AboutSection(val title: String, val summary: String) {
    GETTING_STARTED("Getting started", "Agencies, app modes, and riding a trip"),
    ICONS("Icons", "What the menu, map, and schedule symbols mean"),
    YOUR_DATA("Your data", "Privacy, location, and transit data"),
    CREDITS("Credits", "Map data, creator, and support"),
}

class InfoScreenViewModel : LightViewModel<Unit>()

class InfoScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, InfoScreenViewModel>(sealedActivity) {
    override val viewModelClass: Class<InfoScreenViewModel>
        get() = InfoScreenViewModel::class.java

    override fun createViewModel(): InfoScreenViewModel = InfoScreenViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            AboutContainer(
                title = "About",
                onBack = { goBack() },
                rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                    navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                },
            ) {
                LightText("Pico Transit", LightTextVariant.Heading, modifier = Modifier.padding(bottom = 8.dp))
                LightText(
                    "Schedules, live arrivals, and maps for public transit on the Light Phone III.",
                    LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                AboutSection.values().forEach { section ->
                    AboutEntry(section.title, section.summary) {
                        navigateTo(screenFactory = { activity -> AboutDetailScreen(activity, section) })
                    }
                }
            }
        }
    }
}

class AboutDetailScreen(
    sealedActivity: SealedLightActivity,
    private val section: AboutSection,
) : LightScreen<Unit, InfoScreenViewModel>(sealedActivity) {
    override val viewModelClass: Class<InfoScreenViewModel>
        get() = InfoScreenViewModel::class.java

    override fun createViewModel(): InfoScreenViewModel = InfoScreenViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            AboutContainer(
                title = section.title,
                onBack = { goBack() },
                rightButton = currentTripTopBarButton(lightContext.dataStore, lightContext.filesDir) { dbFile, tripId, fromStopSequence, routeLabel, directionLabel ->
                    navigateTo(screenFactory = { activity -> TripDetailScreen(activity, dbFile, tripId, fromStopSequence, routeLabel, directionLabel) })
                },
            ) {
                when (section) {
                    AboutSection.GETTING_STARTED -> GettingStartedContent()
                    AboutSection.ICONS -> IconsContent()
                    AboutSection.YOUR_DATA -> YourDataSection()
                    AboutSection.CREDITS -> CreditsContent()
                }
            }
        }
    }
}

@Composable
private fun AboutContainer(
    title: String,
    onBack: () -> Unit,
    rightButton: LightBarButton?,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text(title),
            rightButton = rightButton,
        )
        LightScrollView(Modifier.weight(1f).padding(32.dp)) { content() }
        BackToHomeFooter(onGoBackOnce = onBack)
    }
}

@Composable
private fun AboutEntry(title: String, summary: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().lightClickable { onClick() }.padding(vertical = 16.dp)) {
        LightText(title, LightTextVariant.Copy)
        LightText(summary, LightTextVariant.Detail, lighten = true, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun GettingStartedContent() {
    AboutSubheading("Supported agencies")
    AboutBody(GtfsAgency.entries.joinToString(", ") { it.displayName })
    AboutBody("More agencies are added over time. Data credits appear near the bottom of the home screen.", bottom = 20)

    AboutSubheading("Schedule")
    AboutBody("Choose an agency, route, direction, and stop to browse departures and the stops on each trip.")
    AboutSubheading("Explore")
    AboutBody("Find stops near an address or your location, then check live arrivals and whether vehicles are early, on time, or late.")
    AboutSubheading("Station")
    AboutBody("Browse stations, search the list, and view a map of a station's platforms.", bottom = 20)

    AboutSubheading("Board a trip")
    AboutBody(
        "Tap Play on a trip to board it. The home screen shows your route, arrival time, and progress. " +
            "Tap a stop on the trip to mark where you'll get off; reaching it ends tracking.",
    )
    AboutSubheading("Regional schedules")
    AboutBody(
        "Some regions group nearby agencies. Add schedules from Settings → Agency and downloads, " +
            "then tap and hold a schedule to make it your primary agency.",
    )
}

@Composable
private fun IconsContent() {
    AboutSubheading("Menu")
    IconLegend(MENU_ICON_LEGEND)
    AboutSubheading("Map")
    IconLegend(ICON_LEGEND)
    AboutSubheading("Schedules")
    IconLegend(SCHEDULE_ICON_LEGEND)
    AboutSubheading("Settings")
    AboutBody("Each setting is explained on its settings page.", bottom = 8)
    IconLegend(TOGGLE_ICON_LEGEND)
}

@Composable
private fun CreditsContent() {
    AboutSubheading("Service alerts")
    AboutBody("Enable Service alerts in Settings to see detours and closures from agencies that publish them.")
    AboutSubheading("Map data")
    AboutBody("Map tiles © OpenStreetMap contributors © CARTO")
    AboutSubheading("Made by")
    AboutBody("Christian Ferreira · github.com/CJFData")
    AboutSubheading("Thanks")
    AboutBody(
        "To Light's SDK development team for their support implementing updates that allowed the toggle for downloads to occur only over wi-fi, and GPS support for explore mode, especially to Guy Dupont for both your code contributions and communication and support to the development community. Big thank you to Jose Briones for the Settings and About " +
            "cleanup and for his help adding and testing Denver-area support and for being an avid public transit rider!"
    )
    AboutSubheading("Support Pico Transit")
    AboutBody("If Pico Transit helps you catch your bus, train, or ferry, consider buying me a coffee ☕ buymeacoffee.com/cjfdata")
}

@Composable
private fun IconLegend(entries: List<IconLegendEntry>) {
    entries.forEach { entry ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        ) {
            LightIcon(icon = entry.icon, size = 1f, modifier = Modifier.padding(end = 12.dp))
            LightText(entry.label, LightTextVariant.Detail, lighten = true)
        }
    }
}

@Composable
private fun AboutSubheading(text: String) {
    LightText(text, LightTextVariant.Copy, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun AboutBody(text: String, bottom: Int = 12) {
    LightText(text, LightTextVariant.Detail, lighten = true, modifier = Modifier.padding(bottom = bottom.dp))
}
