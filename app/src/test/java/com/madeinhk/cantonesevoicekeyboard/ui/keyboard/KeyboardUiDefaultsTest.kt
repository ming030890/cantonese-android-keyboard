package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

class KeyboardUiDefaultsTest {
    @Test
    fun handwritingCanvasUsesTheRemainingKeyboardSurfaceHeight() {
        assertEquals(269.dp, handwritingCanvasHeight(24.dp))
        assertEquals(293.dp, handwritingCanvasHeight(0.dp))
    }

    @Test
    fun layoutUsesMinimumRowsAndStandardUnits() {
        val dimensions = keyboardLayoutDimensions(
            rowCount = KeyboardUiDefaults.MinimumRowCount - 1,
            availableWidth = 400.dp,
        )

        assertEquals(KeyboardUiDefaults.MinimumRowCount, dimensions.effectiveRowCount)
        assertEquals(KeyboardUiDefaults.RowHeight * KeyboardUiDefaults.MinimumRowCount, dimensions.keyboardHeight)
        assertEquals(40.dp, dimensions.standardKeyTouchWidth)
    }

    @Test
    fun hapticConfigClampsAndSkipsZeroValues() {
        val clamped = KeyboardHapticConfig(
            durationMs = KeyboardUiDefaults.MaximumVibrationDurationMs + 1,
            strengthPercent = KeyboardUiDefaults.MaximumVibrationStrengthPercent + 1,
        ).clamped()
        assertEquals(KeyboardUiDefaults.MaximumVibrationDurationMs, clamped.durationMs)
        assertEquals(KeyboardUiDefaults.MaximumVibrationStrengthPercent, clamped.strengthPercent)
        assertFalse(KeyboardHapticConfig(durationMs = KeyboardUiDefaults.MinimumVibrationDurationMs).shouldVibrate())
        assertFalse(KeyboardHapticConfig(strengthPercent = KeyboardUiDefaults.MinimumVibrationStrengthPercent).shouldVibrate())
        assertTrue(KeyboardHapticConfig().shouldVibrate())
    }

    @Test
    fun vibrationAmplitudeUsesCentralizedAndroidMaximum() {
        assertEquals(
            KeyboardUiDefaults.AndroidMaximumVibrationAmplitude,
            keyboardVibrationAmplitude(KeyboardUiDefaults.MaximumVibrationStrengthPercent),
        )
        assertEquals(
            KeyboardUiDefaults.AndroidMinimumVibrationAmplitude,
            keyboardVibrationAmplitude(KeyboardUiDefaults.MinimumVibrationStrengthPercent),
        )
    }
}
