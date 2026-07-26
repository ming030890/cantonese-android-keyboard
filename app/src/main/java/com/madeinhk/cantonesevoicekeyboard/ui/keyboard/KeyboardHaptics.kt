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
