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
    private const val KEY_QUICK_VERSION = "quick_version"
    private const val KEY_AUTOCAPITALIZATION = "auto_capitalization"
    const val DEFAULT_AUTOCAPITALIZATION = true

    const val LAYOUT_ENGLISH = "english"
    const val LAYOUT_HANDWRITING = "handwriting"
    const val LAYOUT_QUICK = "quick"

    const val QUICK_VERSION_3 = 3
    const val QUICK_VERSION_5 = 5
    const val DEFAULT_QUICK_VERSION = QUICK_VERSION_3

    fun isAutocorrectEnabled(context: Context, layout: String): Boolean {
        // Autocorrect is always on
        return true
    }

    fun setAutocorrectEnabled(context: Context, layout: String, enabled: Boolean) {
        // Autocorrect is always on; setting is preserved for API compatibility
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("autocorrect_$layout", true).apply()
    }

    val DEFAULT_LAYOUTS = setOf(LAYOUT_QUICK, LAYOUT_ENGLISH, LAYOUT_HANDWRITING)

    private val _enabledLayoutsFlow = MutableStateFlow<Set<String>>(DEFAULT_LAYOUTS)
    val enabledLayoutsFlow: StateFlow<Set<String>> = _enabledLayoutsFlow.asStateFlow()

    private val _quickVersionFlow = MutableStateFlow(DEFAULT_QUICK_VERSION)
    val quickVersionFlow: StateFlow<Int> = _quickVersionFlow.asStateFlow()

    private val _autoCapitalizationFlow = MutableStateFlow(DEFAULT_AUTOCAPITALIZATION)
    val autoCapitalizationFlow: StateFlow<Boolean> = _autoCapitalizationFlow.asStateFlow()

    fun init(context: Context) {
        _enabledLayoutsFlow.value = getEnabledLayouts(context)
        KeyboardSubtypes.syncEnabled(context, _enabledLayoutsFlow.value)
        _quickVersionFlow.value = getQuickVersion(context)
        _autoCapitalizationFlow.value = isAutoCapitalizationEnabled(context)
    }

    fun getQuickVersion(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_QUICK_VERSION, DEFAULT_QUICK_VERSION)
    }

    fun setQuickVersion(context: Context, version: Int) {
        val safeVersion = if (version == QUICK_VERSION_5) QUICK_VERSION_5 else QUICK_VERSION_3
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_QUICK_VERSION, safeVersion)
            .apply()
        _quickVersionFlow.value = safeVersion
    }

    fun getEnabledLayouts(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_ENABLED_LAYOUTS, null)
        if (!set.isNullOrEmpty()) return set
        val inferred = KeyboardLayoutInference.inferFromSystem(context)
        val safeLayouts = if (inferred.isEmpty()) DEFAULT_LAYOUTS else inferred
        prefs.edit().putStringSet(KEY_ENABLED_LAYOUTS, safeLayouts).apply()
        return safeLayouts
    }

    fun hasUserConfiguredLayouts(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_ENABLED_LAYOUTS)
    }

    fun setEnabledLayouts(context: Context, layouts: Set<String>) {
        val safeLayouts = if (layouts.isEmpty()) DEFAULT_LAYOUTS else layouts
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ENABLED_LAYOUTS, safeLayouts)
            .apply()
        _enabledLayoutsFlow.value = safeLayouts
        KeyboardSubtypes.syncEnabled(context, safeLayouts)
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

    fun handleLayoutToggle(context: Context, currentLayouts: Set<String>, layoutId: String): Set<String> {
        if (currentLayouts.contains(layoutId) && currentLayouts.size <= 1) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.settings_at_least_one_layout),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return currentLayouts
        }
        return toggleLayout(context, layoutId)
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

    fun isAutoCapitalizationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOCAPITALIZATION, DEFAULT_AUTOCAPITALIZATION)

    fun setAutoCapitalizationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTOCAPITALIZATION, enabled)
            .apply()
        _autoCapitalizationFlow.value = enabled
    }
}
