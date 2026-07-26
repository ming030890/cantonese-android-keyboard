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
