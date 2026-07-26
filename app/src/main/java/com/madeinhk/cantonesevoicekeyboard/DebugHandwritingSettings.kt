package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.net.Uri

object DebugHandwritingSettings {
    private const val PREFS_NAME = "developer_options"
    private const val KEY_STORE_DEBUG_HANDWRITING = "store_debug_handwriting"
    private const val KEY_SYNC_FOLDER_URI = "handwriting_sync_folder_uri"

    fun isStoreDebugHandwritingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_STORE_DEBUG_HANDWRITING, false)

    fun setStoreDebugHandwritingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STORE_DEBUG_HANDWRITING, enabled)
            .apply()
    }

    fun getSyncFolderUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SYNC_FOLDER_URI, null)
            ?.let(Uri::parse)

    fun setSyncFolderUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SYNC_FOLDER_URI, uri.toString())
            .apply()
    }
}
