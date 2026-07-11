package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

object KeyboardUiDefaults {
    const val KeyboardSurfaceHeightDp = 342

    val RowHeight = 57.dp
    val KeyboardHorizontalPadding = 4.dp
    val HorizontalKeyInset = 2.dp
    val VerticalKeyInset = 5.dp
    val ToolbarHeight = 48.dp
    val ToolbarIconTouchSize = 48.dp
    val ToolbarIconSize = 24.dp
    val ToolbarSettingsIconSize = 22.dp
    val ToolbarIconSpacing = 8.dp
    val StandardKeyCornerRadius = 6.dp
    val StandardKeyFontSize = 28.sp
    val VoiceRecordTouchSize = 72.dp
    val VoiceRecordVisualSize = 64.dp
    val VoiceRecordIconSize = 32.dp

    const val MinimumRowCount = 4
    const val StandardRowUnits = 10f

    const val StandardKeyWidthFactor = 1.00f
    const val FunctionKeyWidthFactor = 1.56f
    const val PhoneKeyWidthFactor = 2.68f

    const val DefaultVibrationDurationMs = 50
    const val DefaultVibrationStrengthPercent = 50

    const val MinimumVibrationDurationMs = 0
    const val MaximumVibrationDurationMs = 100
    const val MinimumVibrationStrengthPercent = 0
    const val MaximumVibrationStrengthPercent = 100

    const val AndroidMaximumVibrationAmplitude = 255
    const val AndroidMinimumVibrationAmplitude = 1
}

data class KeyboardHapticConfig(
    val enabled: Boolean = true,
    val respectSystemSettings: Boolean = true,
    val useSystemHapticInterface: Boolean = true,
    val durationMs: Int = KeyboardUiDefaults.DefaultVibrationDurationMs,
    val strengthPercent: Int = KeyboardUiDefaults.DefaultVibrationStrengthPercent,
)

fun KeyboardHapticConfig.clamped(): KeyboardHapticConfig = copy(
    durationMs = durationMs.coerceIn(
        KeyboardUiDefaults.MinimumVibrationDurationMs,
        KeyboardUiDefaults.MaximumVibrationDurationMs
    ),
    strengthPercent = strengthPercent.coerceIn(
        KeyboardUiDefaults.MinimumVibrationStrengthPercent,
        KeyboardUiDefaults.MaximumVibrationStrengthPercent
    )
)

fun KeyboardHapticConfig.shouldVibrate(): Boolean =
    enabled && durationMs > KeyboardUiDefaults.MinimumVibrationDurationMs &&
        strengthPercent > KeyboardUiDefaults.MinimumVibrationStrengthPercent

fun keyboardVibrationAmplitude(strengthPercent: Int): Int {
    val clampedStrength = strengthPercent.coerceIn(
        KeyboardUiDefaults.MinimumVibrationStrengthPercent,
        KeyboardUiDefaults.MaximumVibrationStrengthPercent
    )
    return (
        KeyboardUiDefaults.AndroidMaximumVibrationAmplitude * clampedStrength / 100f
        ).roundToInt().coerceIn(
        KeyboardUiDefaults.AndroidMinimumVibrationAmplitude,
        KeyboardUiDefaults.AndroidMaximumVibrationAmplitude,
    )
}

data class KeyboardLayoutDimensions(
    val effectiveRowCount: Int,
    val keyboardHeight: Dp,
    val standardKeyTouchWidth: Dp,
)

fun keyboardLayoutDimensions(
    rowCount: Int,
    availableWidth: Dp,
): KeyboardLayoutDimensions {
    val effectiveRowCount = rowCount.coerceAtLeast(KeyboardUiDefaults.MinimumRowCount)
    return KeyboardLayoutDimensions(
        effectiveRowCount = effectiveRowCount,
        keyboardHeight = KeyboardUiDefaults.RowHeight * effectiveRowCount,
        standardKeyTouchWidth = availableWidth / KeyboardUiDefaults.StandardRowUnits,
    )
}
