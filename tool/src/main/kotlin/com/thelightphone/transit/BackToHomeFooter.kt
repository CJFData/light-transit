package com.thelightphone.transit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

//a home button footer was created so that you can return to the home screen of Pico transit at any point and switch
// between finding travel info and returning to tracking trip progress. Returning to the homescreen involves going back
// multiple times until the homescreen is met.There's a small delay between each back-navigation step so a screen that's
// still mid-database-query when it gets closed has time to actually cancel cleanly, instead of crashing when its DB
// connection closes out from under it. This isn't a full fix — just makes that crash much less likely to happen.
private const val POP_STEP_DELAY_MS = 80L

// the home button is centered icon in a short Box, it is not LightBottomBar -- that component's own height
// (BOTTOMBAR_HEIGHT_UNITS=4f) plus top margin (TOP_MARGIN_UNITS=1f) is sized for a full row of
// menu icons/labels (matching "LightOS ActionBar"), and reads as oversized dead space around one
// lonely circle and given that we use the header for back and play this further squashes scrolling space. Same
// reasoning as HomeScreen's own top-right Current Trip icons and Trip Detail's
// header icons, both hand-rolled for the same reason rather than stretched to fit an SDK component
// built for a bigger job.
private const val FOOTER_HEIGHT_UNITS = 3f
private const val ICON_SIZE_UNITS = 1.4f

/**
 * [onGoBackOnce] is each screen's own `goBack()`. This can't be called from here directly since it's a
 * `SimpleLightScreen` instance method, not something a free-floating composable has access to; this owns the "keep
 * popping until Home shows up" loop (with a small delay between each pop, see [POP_STEP_DELAY_MS]) so every call site
 * doesn't have to repeat it. See [HomeVisibility]'s own doc comment for why the loop's stopping condition is safe
 * without any "pop to root function in the SDK.
 */
@Composable
fun BackToHomeFooter(onGoBackOnce: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FOOTER_HEIGHT_UNITS.gridUnitsAsDp())
            .padding(top = 0.5f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(
            icon = LightIcons.CIRCLE,
            size = ICON_SIZE_UNITS,
            contentDescription = "Home",
            modifier = Modifier.lightClickable {
                HomeVisibility.scope.launch {
                    while (!HomeVisibility.isVisible.value) {
                        onGoBackOnce()
                        delay(POP_STEP_DELAY_MS)
                    }
                }
            },
        )
    }
}
