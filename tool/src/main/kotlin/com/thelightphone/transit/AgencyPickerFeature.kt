 package com.thelightphone.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.thelightphone.transit.gtfs.GtfsAgency
import com.thelightphone.transit.gtfs.RegionalGroup
import com.thelightphone.transit.gtfs.gtfsDbFile
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Same threshold StationListScreen's own inline search affordance uses, compared against the
 * collapsed [pickerEntries] count (regions counted once, not per member) -- easily cleared today
 * with dozens of rows across regions and ungrouped agencies alike. */
private const val AGENCY_SEARCH_MIN_COUNT = 10

/** One row in the picker list -- either a plain agency, or, for one that belongs to a
 * [RegionalGroup], a row representing the whole region instead of its individual members. Picking
 * a region row (e.g. "New York City", "Denver") drills into that region's own member list (see
 * [AgencyPickerModal.activeRegion]) so the rider can then pick their primary agency there, the
 * same as picking an ungrouped agency directly. Downloading additional agencies within that same
 * region is a separate, later step, done via [ScheduleSelectionScreen] (Settings' "Additional
 * Schedules" row, right under the agency picker), not something this picker itself does. */
private sealed class PickerEntry {
    abstract val displayName: String
    data class Agency(val agency: GtfsAgency) : PickerEntry() {
        override val displayName get() = agency.displayName
    }
    data class Region(val group: RegionalGroup) : PickerEntry() {
        override val displayName get() = group.displayName
    }
}

/** [GtfsAgency.entries], with every grouped agency collapsed into one [PickerEntry.Region] row at
 * the position its first member would have sorted to -- so a rider sees "New York City" once,
 * not each of its 9 member agencies individually. */
private val pickerEntries: List<PickerEntry> by lazy {
    val seenGroups = mutableSetOf<RegionalGroup>()
    GtfsAgency.entries.mapNotNull { agency ->
        val group = RegionalGroup.forAgency(agency)
        when {
            group == null -> PickerEntry.Agency(agency)
            seenGroups.add(group) -> PickerEntry.Region(group)
            else -> null
        }
    }
}

// Matches HomeScreen's own former inline list -- keeps every agency name lined up at the same x
// position whether or not a download-arrow icon sits next to it.
private const val AGENCY_ICON_SIZE = 1f

/**
 * Shown via LightModalManager.show/activeModal?.Content() -- HomeScreen's "no agency selected yet"
 * onboarding step (Stage 1) and Settings' own agency switcher both trigger this same modal,
 * differing only in [allowCancel] (Settings allows backing out; onboarding doesn't). Modeled on
 * ReachedStopModal's own trigger/presentation/dismiss pattern (a transient overlay, not a screen on
 * the nav stack) but its own component, since picking an agency needs a real list (plus optional
 * search) rather than a single centered message.
 */
class AgencyPickerModal(
    private val filesDir: File,
    private val allowCancel: Boolean,
    private val onCancel: () -> Unit = {},
    private val onAgencySelected: (GtfsAgency) -> Unit,
) : LightModal {

    private val dismissSignal = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Non-null only while this picker is showing a region's own drill-down member list -- set
     * when a rider taps a region row (see [selectEntry]), not tied to whatever the current primary
     * agency is. A plain UI toggle, not persisted, same as [searchActive] in [Content]. A
     * [MutableStateFlow] rather than a `remember`ed Compose state since [selectEntry] (where a
     * region tap needs to set this) is a plain class method, not itself composable. */
    private val activeRegion = MutableStateFlow<RegionalGroup?>(null)

    /** Which agencies already have a GTFS database downloaded on disk -- an agency in this set
     * gets a blank spacer instead of the download-arrow icon next to its row, same as HomeScreen's
     * own former inline list. Computed once when the modal is shown (nothing ingests while this is
     * up -- see this class's own doc), not kept live. */
    private val cachedAgencies = MutableStateFlow<Set<GtfsAgency>>(emptySet())

    /** Every screen automatically gets its own ViewModel store; a LightModal doesn't, since its
     * Content() is composed as a sibling of the current screen in LightActivity rather than
     * nested under it (see LightActivity's Content()) -- so this modal creates and provides its
     * own, fresh per show and cleared on [dismiss]. Without it, the inline search keyboard's
     * `viewModel(key = ...)` call below would resolve against the Activity's shared default store
     * and keep reusing a stale callback bound to a discarded TextFieldState on later opens -- the
     * same "stops accepting input on reopen" bug the Stations search screen hit for the same
     * underlying reason. */
    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    init {
        scope.launch {
            cachedAgencies.value = GtfsAgency.entries.filterTo(mutableSetOf()) { gtfsDbFile(filesDir, it).exists() }
        }
    }

    private fun selectAndDismiss(agency: GtfsAgency) {
        onAgencySelected(agency)
        dismiss()
    }

    /** A region row never picks anything itself -- it drills into that region's own member list
     * (see [PickerEntry]'s and [activeRegion]'s own docs) so the rider can then pick their primary
     * agency from within it, the same as tapping an ungrouped agency directly always has. */
    private fun selectEntry(entry: PickerEntry) {
        when (entry) {
            is PickerEntry.Agency -> selectAndDismiss(entry.agency)
            is PickerEntry.Region -> activeRegion.value = entry.group
        }
    }

    @Composable
    private fun EntryRow(entry: PickerEntry, cached: Set<GtfsAgency>, onSelect: (PickerEntry) -> Unit) {
        val isCached = when (entry) {
            is PickerEntry.Agency -> entry.agency in cached
            is PickerEntry.Region -> entry.group.members.any { it in cached }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { onSelect(entry) }
                .padding(vertical = 12.dp),
        ) {
            if (isCached) {
                Spacer(
                    modifier = Modifier.size(
                        width = AGENCY_ICON_SIZE.gridUnitsAsDp() + 8.dp,
                        height = AGENCY_ICON_SIZE.gridUnitsAsDp(),
                    ),
                )
            } else {
                LightIcon(
                    icon = LightIcons.DOWNLOAD_ARROW,
                    size = AGENCY_ICON_SIZE,
                    contentDescription = "Download",
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            LightText(text = entry.displayName, variant = LightTextVariant.Copy)
        }
    }

    /** [activeRegion]'s own drill-down list -- a region's members, picked exactly like any
     * ungrouped agency (see [selectEntry]'s own doc). */
    @Composable
    private fun RegionContent(group: RegionalGroup, cached: Set<GtfsAgency>, onBack: () -> Unit, onSelectAgency: (GtfsAgency) -> Unit) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
            ) {
                LightIcon(
                    icon = LightIcons.BACK,
                    size = 1.2f,
                    contentDescription = "Back",
                    modifier = Modifier
                        .lightClickable { onBack() }
                        .padding(end = 12.dp),
                )
                LightText(text = group.displayName, variant = LightTextVariant.Copy, lighten = true)
            }
            LightScrollView(modifier = Modifier.weight(1f).padding(horizontal = 32.dp)) {
                group.members.forEach { agency ->
                    EntryRow(PickerEntry.Agency(agency), cached = cached, onSelect = { onSelectAgency(agency) })
                }
            }
        }
    }

    /** Inline live-filter search -- see StationListScreen's identical pattern (SearchContent's own
     * doc there), just against [GtfsAgency.entries] instead of a station list. [textFieldState] is
     * hoisted from [Content] for the same reason: this composable is only ever in composition
     * while search is active, so a state created locally would be a fresh instance every reopen. */
    @Composable
    private fun SearchContent(
        cached: Set<GtfsAgency>,
        textFieldState: TextFieldState,
        onBack: () -> Unit,
        onSelect: (PickerEntry) -> Unit,
    ) {
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val keyboardCallback = remember(textFieldState) {
            InlineTextFieldKeyboardCallback(state = textFieldState)
        }
        val keyboardViewModel = rememberInlineLp3KeyboardViewModel(
            key = "AgencyPickerSearch",
            callback = keyboardCallback,
            keyboardOptionsFlow = keyboardOptionsFlow,
        )
        val query = textFieldState.text.toString()
        // A region matches on its own name (e.g. "New York City") or any member's (e.g. searching
        // "Staten Island" still surfaces the "New York City" region row, not that one member
        // directly) -- see PickerEntry's own doc for why a member never gets its own row here.
        val filtered = remember(query) {
            if (query.isBlank()) {
                pickerEntries
            } else {
                pickerEntries.filter { entry ->
                    entry.displayName.contains(query, ignoreCase = true) ||
                        (entry is PickerEntry.Region && entry.group.members.any { it.displayName.contains(query, ignoreCase = true) })
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    LightIcon(
                        icon = LightIcons.BACK,
                        size = 1.2f,
                        contentDescription = "Back",
                        modifier = Modifier
                            .lightClickable { onBack() }
                            .padding(end = 12.dp),
                    )
                    LightText(text = "Search Agencies", variant = LightTextVariant.Copy, lighten = true)
                }
                Spacer(modifier = Modifier.height(16.dp))
                BasicText(
                    text = query,
                    style = LightThemeTokens.typography.copy.copy(color = LightThemeTokens.colors.content),
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LightThemeTokens.colors.content),
                )
            }
            if (filtered.isEmpty()) {
                LightText(
                    text = "No agencies found.",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 32.dp)) {
                items(filtered, key = { entry -> when (entry) {
                    is PickerEntry.Agency -> entry.agency.id
                    is PickerEntry.Region -> entry.group.id
                } }) { entry ->
                    EntryRow(entry, cached = cached, onSelect = onSelect)
                }
            }
            LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)
        }
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val cached by cachedAgencies.collectAsState()
        val region by activeRegion.collectAsState()
        var searchActive by remember { mutableStateOf(false) }
        val searchTextFieldState = rememberTextFieldState("")

        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            LightTheme(colors = themeColors) {
                if (region != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background),
                    ) {
                        RegionContent(
                            group = region!!,
                            cached = cached,
                            onBack = { activeRegion.value = null },
                            onSelectAgency = ::selectAndDismiss,
                        )
                    }
                    return@LightTheme
                }

                if (searchActive) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background),
                    ) {
                        SearchContent(
                            cached = cached,
                            textFieldState = searchTextFieldState,
                            onBack = { searchActive = false },
                            onSelect = ::selectEntry,
                        )
                    }
                    return@LightTheme
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    Column(modifier = Modifier.weight(1f).padding(32.dp)) {
                        LightText(
                            text = "Welcome to Pico Transit!",
                            variant = LightTextVariant.Heading,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        LightText(
                            text = "Let's find your way — choose your local transit agency.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        if (pickerEntries.size > AGENCY_SEARCH_MIN_COUNT) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable { searchActive = true }
                                    .padding(bottom = 12.dp),
                            ) {
                                LightIcon(
                                    icon = LightIcons.SEARCH,
                                    size = 1.2f,
                                    contentDescription = "Search agencies",
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                LightText(text = "Search agencies", variant = LightTextVariant.Copy, lighten = true)
                            }
                        }
                        LightScrollView(modifier = Modifier.weight(1f)) {
                            pickerEntries.forEach { entry ->
                                EntryRow(entry, cached = cached, onSelect = ::selectEntry)
                            }
                        }
                    }

                    if (allowCancel) {
                        LightBottomBar(
                            items = listOf(
                                LightBarButton.LightIcon(
                                    icon = LightIcons.CLOSE,
                                    onClick = {
                                        onCancel()
                                        dismiss()
                                    },
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    // No real timeout -- see this modal's Duration.INFINITE call site. Never fires in practice.
    override val onExpired: () -> Unit = {}

    override fun dismiss() {
        if (dismissSignal.complete(Unit)) {
            viewModelStoreOwner.viewModelStore.clear()
        }
    }

    override suspend fun awaitDismiss() = dismissSignal.await()
}
