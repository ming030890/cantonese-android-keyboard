package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal fun shouldPassToEmojiPicker(
    emojiPanelActive: Boolean,
    emojiPickerBounds: Rect,
    x: Float,
    y: Float,
): Boolean = emojiPanelActive && emojiPickerBounds.contains(Offset(x, y))

internal fun shouldPassToHandwritingCanvas(
    handwritingPanelActive: Boolean,
    handwritingBounds: Rect,
    x: Float,
    y: Float,
): Boolean = handwritingPanelActive && handwritingBounds.contains(Offset(x, y))

internal fun shouldPassToAutofillView(
    autofillViewPresent: Boolean,
    autofillBounds: Rect,
    x: Float,
    y: Float,
): Boolean = autofillViewPresent && autofillBounds.contains(Offset(x, y))

internal fun shouldDismissAutofillOnTouch(
    autofillViewPresent: Boolean,
    autofillBounds: Rect,
    action: Int,
    x: Float,
    y: Float,
): Boolean = action == MotionEvent.ACTION_DOWN &&
    shouldPassToAutofillView(autofillViewPresent, autofillBounds, x, y)

internal fun computeNextLayoutTarget(
    currentMode: KeyboardInputMode,
    enabledLayouts: Set<String>,
): String? {
    val layoutOrder = listOf(
        com.madeinhk.cantonesevoicekeyboard.KeyboardSettings.LAYOUT_QUICK,
        com.madeinhk.cantonesevoicekeyboard.KeyboardSettings.LAYOUT_ENGLISH,
        com.madeinhk.cantonesevoicekeyboard.KeyboardSettings.LAYOUT_HANDWRITING
    )
    val activeList = layoutOrder.filter { enabledLayouts.contains(it) }
    if (activeList.size <= 1) return null
    val currentLayoutId = when (currentMode) {
        KeyboardInputMode.Quick -> com.madeinhk.cantonesevoicekeyboard.KeyboardSettings.LAYOUT_QUICK
        KeyboardInputMode.AlphaNum -> com.madeinhk.cantonesevoicekeyboard.KeyboardSettings.LAYOUT_ENGLISH
        KeyboardInputMode.Handwriting -> com.madeinhk.cantonesevoicekeyboard.KeyboardSettings.LAYOUT_HANDWRITING
        KeyboardInputMode.Number -> return null
    }
    val currentIndex = activeList.indexOf(currentLayoutId)
    val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % activeList.size else 0
    return when (activeList[nextIndex]) {
        com.madeinhk.cantonesevoicekeyboard.KeyboardSettings.LAYOUT_QUICK -> "quick"
        com.madeinhk.cantonesevoicekeyboard.KeyboardSettings.LAYOUT_ENGLISH -> "alpha"
        com.madeinhk.cantonesevoicekeyboard.KeyboardSettings.LAYOUT_HANDWRITING -> "handwriting"
        else -> null
    }
}
