package com.madeinhk.cantonesevoicekeyboard

import android.content.Context

object DebugAudioSettings {
    private const val PREFS_NAME = "developer_options"
    private const val KEY_STORE_DEBUG_AUDIO = "store_debug_audio"

    fun isStoreDebugAudioEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_STORE_DEBUG_AUDIO, false)

    fun setStoreDebugAudioEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STORE_DEBUG_AUDIO, enabled)
            .apply()
    }
}
