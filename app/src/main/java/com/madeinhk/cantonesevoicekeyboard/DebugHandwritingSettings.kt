package com.madeinhk.cantonesevoicekeyboard

import android.content.Context

object DebugHandwritingSettings {
    private const val PREFS_NAME = "developer_options"
    private const val KEY_STORE_DEBUG_HANDWRITING = "store_debug_handwriting"

    fun isStoreDebugHandwritingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_STORE_DEBUG_HANDWRITING, false)

    fun setStoreDebugHandwritingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STORE_DEBUG_HANDWRITING, enabled)
            .apply()
    }
}
