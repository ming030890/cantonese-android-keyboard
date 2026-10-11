package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object KeyboardSettings {
    private const val PREFS_NAME = "keyboard_settings"
    private const val KEY_ENABLED_LAYOUTS = "enabled_layouts"
    private const val KEY_MULTILINGUAL = "multilingual_typing"
    private const val KEY_HAPTIC = "haptic_feedback"

    const val LAYOUT_ENGLISH = "english"
    const val LAYOUT_HANDWRITING = "handwriting"

    val DEFAULT_LAYOUTS = setOf(LAYOUT_ENGLISH, LAYOUT_HANDWRITING)

    private val _enabledLayoutsFlow = MutableStateFlow<Set<String>>(DEFAULT_LAYOUTS)
    val enabledLayoutsFlow: StateFlow<Set<String>> = _enabledLayoutsFlow.asStateFlow()

    fun init(context: Context) {
        _enabledLayoutsFlow.value = getEnabledLayouts(context)
    }

    fun getEnabledLayouts(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_ENABLED_LAYOUTS, null)
        return if (set.isNullOrEmpty()) DEFAULT_LAYOUTS else set
    }

    fun setEnabledLayouts(context: Context, layouts: Set<String>) {
        val safeLayouts = if (layouts.isEmpty()) DEFAULT_LAYOUTS else layouts
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ENABLED_LAYOUTS, safeLayouts)
            .apply()
        _enabledLayoutsFlow.value = safeLayouts
    }

    fun isLayoutEnabled(context: Context, layoutId: String): Boolean =
        getEnabledLayouts(context).contains(layoutId)

    fun computeToggle(current: Set<String>, layoutId: String): Set<String> {
        val updated = current.toMutableSet()
        if (updated.contains(layoutId)) {
            // Cannot deselect all layouts - at least one must remain
            if (updated.size > 1) {
                updated.remove(layoutId)
            }
        } else {
            updated.add(layoutId)
        }
        return updated
    }

    fun toggleLayout(context: Context, layoutId: String): Set<String> {
        val current = getEnabledLayouts(context)
        val updated = computeToggle(current, layoutId)
        setEnabledLayouts(context, updated)
        return updated
    }

    fun isMultilingualTypingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MULTILINGUAL, false)

    fun setMultilingualTypingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MULTILINGUAL, enabled)
            .apply()
    }

    fun isHapticFeedbackEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAPTIC, true)

    fun setHapticFeedbackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAPTIC, enabled)
            .apply()
    }
}
