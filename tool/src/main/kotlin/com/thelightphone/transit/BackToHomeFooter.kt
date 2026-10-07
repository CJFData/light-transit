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

// A short pause between pops, so a screen closed mid-query can cancel before the next pop.
private const val POP_STEP_DELAY_MS = 80L

// Shorter than LightBottomBar, which is sized for a full row of icons.
private const val FOOTER_HEIGHT_UNITS = 3f
private const val ICON_SIZE_UNITS = 1.4f

/**
 * The footer on every screen but Home: one button that goes back to Home however deep the rider is.
 * [onGoBackOnce] is the screen's `goBack()`; this repeats it until Home shows (see
 * [HomeVisibility]).
 *
 * [leadingIcon] and [trailingIcon] are optional side slots (Trip Detail's run stepper). They're
 * aligned independently so the Home button stays centered.
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
