package com.thelightphone.lp3Keyboard.ui.layout

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.thelightphone.lp3Keyboard.ui.FinalRow
import com.thelightphone.lp3Keyboard.ui.FirstRow
import com.thelightphone.lp3Keyboard.ui.ICON_KEY_WIDTH_DP
import com.thelightphone.lp3Keyboard.ui.IconKey
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardCallback
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardLayoutCapture
import com.thelightphone.lp3Keyboard.ui.MultiLabelKey
import com.thelightphone.lp3Keyboard.ui.R
import com.thelightphone.lp3Keyboard.ui.SecondRow
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.ThirdRow

private val FrAzertySwipeConfig: SwipeConfig by lazy {
    object : Lp3KeyboardLayoutCapture("abcdefghijklmnopqrstuvwxyz") {
        override fun report(code: Int, bounds: Rect) {
            val lower = if (code in 'A'.code..'Z'.code) code + 32 else code
            if (lower !in 'a'.code..'z'.code) return
            // onGloballyPositioned fires on every layout pass; skip identical
            // writes so we don't churn the snapshot or re-fire boundsFlow.
            if (letterBounds[lower] == bounds) return
            letterBounds[lower] = bounds
        }
    }
}


/** The layouts for French AZERTY. */
object FrAzerty {
    object LowerCaseLayout : Layout {
        override val isRootLayout: Boolean
            get() = true

        override val swipeConfig: SwipeConfig
            get() = FrAzertySwipeConfig

        @Composable
        override fun ColumnScope.Render(
            options: KeyboardOptions,
            callback: Lp3KeyboardCallback
        ) {
            FirstRow("azertyuiop", callback, swipeConfig, options.enableKeyAnimation)
            SecondRow("qsdfghjklm", callback, swipeConfig, options.enableKeyAnimation)
            ThirdRow("wxcvbn", callback, swipeConfig, options) {
                IconKey(
                    R.drawable.up_lp3,
                    SpecialKey.UpCase,
                    callback,
                    options.enableKeyAnimation,
                    width = ICON_KEY_WIDTH_DP.dp,
                    modifier = Modifier.padding(12.dp).padding(bottom = 6.dp, end = 8.dp)
                )
            }
            FinalRow(options, callback) {
                MultiLabelKey("123", SpecialKey.Numbers, callback, options.enableKeyAnimation)
            }
        }
    }

    object CapsLockedLayout : Layout {
        override val isRootLayout: Boolean
            get() = true
        override val swipeConfig: SwipeConfig
            get() = FrAzertySwipeConfig

        @Composable
        override fun ColumnScope.Render(
            options: KeyboardOptions,
            callback: Lp3KeyboardCallback
        ) {
            FirstRow("AZERTYUIOP", callback, swipeConfig, options.enableKeyAnimation)
            SecondRow("QSDFGHJKLM", callback, swipeConfig, options.enableKeyAnimation)
            ThirdRow("WXCVBN", callback, swipeConfig, options) {
                IconKey(
                    R.drawable.caps_lp3,
                    SpecialKey.DownCase,
                    callback,
                    options.enableKeyAnimation,
                    width = ICON_KEY_WIDTH_DP.dp,
                    modifier = Modifier.padding(9.dp).padding(bottom = 2.dp, end = 4.dp)
                )
            }
            FinalRow(options, callback) {
                MultiLabelKey("123", SpecialKey.Numbers, callback, options.enableKeyAnimation)
            }
        }
    }

    object UpperCaseLayout : Layout {
        override val isRootLayout: Boolean
            get() = true
        override val swipeConfig: SwipeConfig
            get() = FrAzertySwipeConfig

        @Composable
        override fun ColumnScope.Render(
            options: KeyboardOptions,
            callback: Lp3KeyboardCallback
        ) {
            FirstRow("AZERTYUIOP", callback, swipeConfig, options.enableKeyAnimation)
            SecondRow("QSDFGHJKLM", callback, swipeConfig, options.enableKeyAnimation)
            ThirdRow("WXCVBN", callback, swipeConfig, options) {
                IconKey(
                    R.drawable.down_lp3,
                    SpecialKey.DownCase,
                    callback,
                    options.enableKeyAnimation,
                    width = ICON_KEY_WIDTH_DP.dp,
                    modifier = Modifier.padding(12.dp).padding(bottom = 6.dp, end = 8.dp)
                )
            }
            FinalRow(options, callback) {
                MultiLabelKey("123", SpecialKey.Numbers, callback, options.enableKeyAnimation)
            }
        }
    }
}
