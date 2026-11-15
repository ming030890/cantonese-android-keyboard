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

/**
 * Keeps an in-progress handwriting gesture routed to the canvas until pen-up/cancel, even if
 * the stroke overshoots downward into the bottom bar or upward into the toolbar. Also blocks
 * horizontal canvas-margin touches from falling through to distant toolbar/bottom row hit boxes.
 */
internal class HandwritingGestureRouter {
    var strokeCapturedByCanvas: Boolean = false
        private set

    fun reset() {
        strokeCapturedByCanvas = false
    }

    fun shouldPassToCanvas(
        handwritingPanelActive: Boolean,
        handwritingBounds: Rect,
        actionMasked: Int,
        x: Float,
        y: Float,
    ): Boolean {
        if (!handwritingPanelActive || handwritingBounds.width <= 0f || handwritingBounds.height <= 0f) {
            strokeCapturedByCanvas = false
            return false
        }
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeCapturedByCanvas = handwritingBounds.contains(Offset(x, y))
                strokeCapturedByCanvas || isInHandwritingVerticalBand(handwritingBounds, y)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val captured = strokeCapturedByCanvas
                strokeCapturedByCanvas = false
                captured
            }
            else -> strokeCapturedByCanvas
        }
    }

    private fun isInHandwritingVerticalBand(bounds: Rect, y: Float): Boolean =
        y >= bounds.top && y < bounds.bottom
}

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
