package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.net.Uri

/** Public builds do not schedule or upload internal training-data collections. */
@Suppress("UNUSED_PARAMETER")
internal object PromptedHandwritingSync {
    fun enqueue(context: Context) = Unit

    suspend fun syncPending(context: Context, treeUri: Uri): PromptedSyncResult =
        error("Training-data sync is available only in internal builds")
}
