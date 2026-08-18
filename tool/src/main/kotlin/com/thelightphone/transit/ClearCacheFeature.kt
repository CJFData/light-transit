package com.thelightphone.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightModal
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.CompletableDeferred

/**
 * "Are you sure?" gate for Settings' "Clear schedule cache" row -- deleting every agency's
 * downloaded schedule can't be undone from within the app (it just re-downloads), so this is
 * cheap insurance against a mis-tap. Built from the same primitives ReachedStopModal/
 * AgencyPickerModal already use (no dedicated confirm-dialog component exists in the SDK yet)
 * rather than a single-button LightFullscreenModal, since this needs two distinct choices, not
 * one dismiss.
 */
class ClearCacheConfirmModal(
    private val onConfirm: () -> Unit,
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "Clear every agency's downloaded schedule from this device?",
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                    )
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.DENY,
                            contentDescription = "Cancel",
                            onClick = { dismiss() },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.ACCEPT,
                            contentDescription = "Confirm",
                            onClick = {
                                onConfirm()
                                dismiss()
                            },
                        ),
                    ),
                )
            }
        }
    }

    // No real timeout -- same reasoning as AgencyPickerModal's own onExpired: shown with
    // Duration.INFINITE, so this never actually fires.
    override val onExpired: () -> Unit = {}

    override fun dismiss() {
        dismissSignal.complete(Unit)
    }

    override suspend fun awaitDismiss() = dismissSignal.await()
}