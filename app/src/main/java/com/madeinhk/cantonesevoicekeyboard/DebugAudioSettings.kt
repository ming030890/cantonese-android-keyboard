package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.net.Uri

object DebugAudioSettings {
    private const val PREFS_NAME = "developer_options"
    private const val KEY_STORE_DEBUG_AUDIO = "store_debug_audio"
    private const val KEY_SYNC_FOLDER_URI = "sync_folder_uri"

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
