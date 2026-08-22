package com.thelightphone.transit

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import kotlinx.coroutines.flow.StateFlow

/**
 * Feeds keystrokes from Light's own public `light-keyboard` library into a [TextFieldState].
 * Shared by every screen in this app that docks the embedded keyboard inline alongside its own
 * content (a live-filtered list, a single-line search field) instead of using sdk/ui's
 * full-screen `LightTextInputEditor` -- that composable's own keystroke-handling glue is
 * deliberately internal to sdk/ui, so this mirrors its logic directly against the public
 * `light-keyboard` API rather than reaching into it.
 */
class InlineTextFieldKeyboardCallback(
    private val state: TextFieldState,
    private val singleLine: Boolean = true,
    private val onReturn: () -> Unit = {},
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onKeyReleased(code: Int) = insertCodePoint(code)

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> {
                val before = state.text.subSequence(0, state.selection.min)
                deleteBeforeCursor(surrogateAwareDeleteCount(before))
            }
            SpecialKey.Return -> if (singleLine) onReturn() else insertAtCursor("\n")
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            val before = state.text.subSequence(0, state.selection.min)
            deleteBeforeCursor(deleteWordCount(before))
        }
    }

    override fun onKeyRepeated(code: Int) = insertCodePoint(code)

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onSubmitWord(word: CharSequence) = insertAtCursor(word.toString())

    private fun insertCodePoint(code: Int) = insertAtCursor(buildString { appendCodePoint(code) })

    private fun insertAtCursor(text: String) {
        state.edit {
            val start = selection.min
            val end = selection.max
            replace(start, end, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBeforeCursor(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}

private fun surrogateAwareDeleteCount(value: CharSequence): Int {
    if (value.isEmpty()) return 0
    val last = value[value.length - 1]
    return if (Character.isLowSurrogate(last)) 2 else 1
}

private fun deleteWordCount(value: CharSequence): Int {
    val trimmed = value.trimEnd()
    val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
    return value.length - if (lastSpace >= 0) lastSpace + 1 else 0
}

/** One shared factory for every inline keyboard's own scoped ViewModel -- [key] must be unique per
 * screen (and stable across recomposition) since it's used as the ViewModelStore lookup key. */
@Composable
fun rememberInlineLp3KeyboardViewModel(
    key: String,
    callback: Lp3RepeatableKeyboardCallback,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
): Lp3KeyboardViewModel<*> = viewModel<EnQwertyLp3KeyboardViewModel<*>>(
    key = key,
    factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EnQwertyLp3KeyboardViewModel<Unit>(
                callback,
                keyboardOptionsFlow = keyboardOptionsFlow,
                optionsForLayout = { LayoutOptions(!it.isRootLayout) },
            ) as T
        }
    },
)
