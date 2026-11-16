package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object KeyboardSettings {
    private const val PREFS_NAME = "keyboard_settings"
    private const val KEY_ENABLED_LAYOUTS = "enabled_layouts"
    private const val KEY_HAPTIC = "haptic_feedback"
    private const val KEY_QUICK_VERSION = "quick_version"
    private const val KEY_AUTOCAPITALIZATION = "auto_capitalization"
    private const val KEY_VOICE_INPUT = "voice_input_enabled"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    const val DEFAULT_VOICE_INPUT = true

    fun isOnboardingCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun setOnboardingCompleted(context: Context, completed: Boolean = true) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, completed)
            .apply()
    }

    private val _voiceInputEnabledFlow = MutableStateFlow(DEFAULT_VOICE_INPUT)
    val voiceInputEnabledFlow: StateFlow<Boolean> = _voiceInputEnabledFlow.asStateFlow()

    fun isVoiceInputEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE_INPUT, DEFAULT_VOICE_INPUT)

    fun setVoiceInputEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VOICE_INPUT, enabled).apply()
        _voiceInputEnabledFlow.value = enabled
        if (enabled) ModelAssetPack.requestDownload(context) else ModelAssetPack.cancelDownload(context)
    }

    const val DEFAULT_AUTOCAPITALIZATION = true

    const val LAYOUT_ENGLISH = "english"
    const val LAYOUT_HANDWRITING = "handwriting"
    const val LAYOUT_QUICK = "quick"

    const val QUICK_VERSION_3 = 3
    const val QUICK_VERSION_5 = 5
    const val DEFAULT_QUICK_VERSION = QUICK_VERSION_3

    val DEFAULT_LAYOUTS = setOf(LAYOUT_HANDWRITING)

    private val _enabledLayoutsFlow = MutableStateFlow<Set<String>>(DEFAULT_LAYOUTS)
    val enabledLayoutsFlow: StateFlow<Set<String>> = _enabledLayoutsFlow.asStateFlow()

    private val _quickVersionFlow = MutableStateFlow(DEFAULT_QUICK_VERSION)
    val quickVersionFlow: StateFlow<Int> = _quickVersionFlow.asStateFlow()

    private val _autoCapitalizationFlow = MutableStateFlow(DEFAULT_AUTOCAPITALIZATION)
    val autoCapitalizationFlow: StateFlow<Boolean> = _autoCapitalizationFlow.asStateFlow()

    fun init(context: Context) {
        _voiceInputEnabledFlow.value = isVoiceInputEnabled(context)
        if (_voiceInputEnabledFlow.value) ModelAssetPack.requestDownload(context)
        else ModelAssetPack.cancelDownload(context)
        _enabledLayoutsFlow.value = getEnabledLayouts(context)
        KeyboardSubtypes.syncEnabled(context, _enabledLayoutsFlow.value)
        _quickVersionFlow.value = getQuickVersion(context)
        _autoCapitalizationFlow.value = isAutoCapitalizationEnabled(context)
    }

    fun isQuickLearningEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("quick_learning", true)

    fun setQuickLearningEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("quick_learning", enabled).apply()
        com.madeinhk.cantonesevoicekeyboard.quick.QuickPersonalizationStore.get(context).enabled = enabled
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
