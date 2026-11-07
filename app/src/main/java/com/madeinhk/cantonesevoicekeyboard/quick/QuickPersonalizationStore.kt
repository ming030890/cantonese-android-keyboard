package com.madeinhk.cantonesevoicekeyboard.quick

import android.content.Context
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import java.io.File

/** One shared instance lets Settings clear the active IME's memory as well as its disk store. */
internal object QuickPersonalizationStore {
    @Volatile private var instance: QuickPersonalization? = null
    fun get(context: Context): QuickPersonalization = instance ?: synchronized(this) {
        instance ?: QuickPersonalization(File(context.applicationContext.noBackupFilesDir, "quick-history-v1.bin"))
            .also { it.enabled = KeyboardSettings.isQuickLearningEnabled(context); instance = it }
    }
}
