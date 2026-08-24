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

/** Same threshold StationListScreen's own inline search affordance uses -- with only 4 agencies
 * today this stays dormant, engaging once the planned Colorado rollout grows the list. */
private const val AGENCY_SEARCH_MIN_COUNT = 10

// Matches HomeScreen's own former inline list -- keeps every agency name lined up at the same x
// position whether or not a download-arrow icon sits next to it.
private const val AGENCY_ICON_SIZE = 1f

/**
 * HomeScreen's "no agency selected yet" onboarding step (Stage 1), and Settings' own agency
 * switcher, both show this same modal -- differing only in [allowCancel]. Modeled on
 * ReachedStopModal's own trigger/presentation/dismiss pattern (a transient overlay shown via
 * LightModalManager.show/activeModal?.Content(), not a screen on the nav stack) but its own
 * component, since picking an agency needs a real list (plus optional search) rather than a
 * single centered message.
 */
class AgencyPickerModal(
    private val filesDir: File,
    private val allowCancel: Boolean,
    private val onCancel: () -> Unit = {},
    private val onAgencySelected: (GtfsAgency) -> Unit,
) : LightModal {

    private val dismissSignal = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Which agencies already have a GTFS database on disk -- drives the download-arrow icon next
     * to each row, same as HomeScreen's own former inline list. Computed once when the modal is
     * shown (nothing ingests while this is up -- see this class's own doc), not kept live. */
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

    @Composable
    private fun AgencyRow(agency: GtfsAgency, cached: Boolean, onSelect: (GtfsAgency) -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { onSelect(agency) }
                .padding(vertical = 12.dp),
        ) {
            if (cached) {
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
            LightText(text = agency.displayName, variant = LightTextVariant.Copy)
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
        onSelect: (GtfsAgency) -> Unit,
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
        val filtered = remember(query) {
            if (query.isBlank()) GtfsAgency.entries else GtfsAgency.entries.filter { it.displayName.contains(query, ignoreCase = true) }
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
                items(filtered, key = { it.id }) { agency ->
                    AgencyRow(agency, cached = agency in cached, onSelect = onSelect)
                }
            }
            LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)
        }
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val cached by cachedAgencies.collectAsState()
        var searchActive by remember { mutableStateOf(false) }
        val searchTextFieldState = rememberTextFieldState("")

        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            LightTheme(colors = themeColors) {
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
                            onSelect = ::selectAndDismiss,
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
                        if (GtfsAgency.entries.size > AGENCY_SEARCH_MIN_COUNT) {
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
                            GtfsAgency.entries.forEach { agency ->
                                AgencyRow(agency, cached = agency in cached, onSelect = ::selectAndDismiss)
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
