package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

fun performKeyboardHaptic(
    view: View,
    config: KeyboardHapticConfig = KeyboardHapticConfig(),
) {
    val hapticConfig = config.clamped()
    if (!hapticConfig.shouldVibrate()) return
    if (hapticConfig.respectSystemSettings && !view.isHapticFeedbackEnabled) return

    if (hapticConfig.useSystemHapticInterface &&
        hapticConfig.respectSystemSettings &&
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    ) {
        return
    }

    val vibratorManager = view.context.getSystemService(VibratorManager::class.java) ?: return
    val vibrator = vibratorManager.defaultVibrator
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(
        VibrationEffect.createOneShot(
            hapticConfig.durationMs.toLong(),
            keyboardVibrationAmplitude(hapticConfig.strengthPercent),
        ),
    )
}

fun performSegmentTickHaptic(
    view: View,
    config: KeyboardHapticConfig = KeyboardHapticConfig(),
) {
    val hapticConfig = config.clamped()
    if (!hapticConfig.shouldVibrate()) return
    if (hapticConfig.respectSystemSettings && !view.isHapticFeedbackEnabled) return

    val feedbackConstant = if (android.os.Build.VERSION.SDK_INT >= 34) {
        HapticFeedbackConstants.SEGMENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }
    view.performHapticFeedback(feedbackConstant)
}

fun performGestureThresholdHaptic(
    view: View,
    config: KeyboardHapticConfig = KeyboardHapticConfig(),
) {
    val hapticConfig = config.clamped()
    if (!hapticConfig.shouldVibrate()) return
    if (hapticConfig.respectSystemSettings && !view.isHapticFeedbackEnabled) return

    val feedbackConstant = if (android.os.Build.VERSION.SDK_INT >= 34) {
        HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
    } else {
        HapticFeedbackConstants.KEYBOARD_PRESS
    }
    view.performHapticFeedback(feedbackConstant)
}
