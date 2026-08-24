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

// A real, if small, pause between each pop -- not for visual pacing but so a screen just popped
// mid-query (its onScreenShow coroutine still fetching when goBack() closes its repository out
// from under it) gets a real chance to observe cancellation before the next pop starts. Popping
// every screen in one tight, zero-delay loop is what originally surfaced this race (an unguarded
// DB call outside its own try/catch threw once its connection pool was closed mid-query and
// crashed the app) -- this doesn't eliminate an unguarded call elsewhere still being possible,
// only makes the race far less likely to land within one loop's lifetime.
private const val POP_STEP_DELAY_MS = 80L

// A plain centered icon in a short Box, not LightBottomBar -- that component's own height
// (BOTTOMBAR_HEIGHT_UNITS=4f) plus top margin (TOP_MARGIN_UNITS=1f) is sized for a full row of
// menu icons/labels, and reads as oversized dead space around one lonely circle. Same reasoning
// as HomeScreen's own top-right Current Trip icons and Trip Detail's header icons, both
// hand-rolled for the same reason.
private const val FOOTER_HEIGHT_UNITS = 3f
private const val ICON_SIZE_UNITS = 1.4f

/**
 * Every non-Home screen's own footer -- a single circular button that jumps straight back to
 * HomeScreen, however many screens deep the rider currently is (HomeScreen itself keeps its own
 * full footer: settings/info icons + the daily message, unrelated to this). [onGoBackOnce] is
 * each screen's own `goBack()` -- can't be called from here directly since it's a
 * `SimpleLightScreen` instance method, not something a free-floating composable has access to;
 * this owns the "keep popping until Home shows up" loop (with a small delay between each pop, see
 * [POP_STEP_DELAY_MS]) so every call site doesn't have to repeat it. See [HomeVisibility]'s own
 * doc for why the loop's stopping condition is safe without a "pop to root" primitive.
 *
 * [leadingIcon]/[trailingIcon] are optional flanking slots -- only Trip Detail's own Next/Previous
 * run stepper uses these today (see TripDetailScreen's own doc), every other call site passes
 * neither and renders exactly as before. Positioned via independent Box alignments, not a Row, so
 * the Home circle stays exactly centered regardless of whether zero, one, or both slots are
 * populated -- a rider toggling between "first run, no Previous" and "not the first run anymore"
 * shouldn't see Home itself visibly shift.
 */
@Composable
fun BackToHomeFooter(
    onGoBackOnce: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FOOTER_HEIGHT_UNITS.gridUnitsAsDp())
            .padding(top = 0.5f.gridUnitsAsDp()),
    ) {
        leadingIcon?.let {
            Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 2f.gridUnitsAsDp())) { it() }
        }
        Box(modifier = Modifier.align(Alignment.Center)) {
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
        trailingIcon?.let {
            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2f.gridUnitsAsDp())) { it() }
        }
    }
}
