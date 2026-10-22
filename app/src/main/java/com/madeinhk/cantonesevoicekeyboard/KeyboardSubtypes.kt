package com.madeinhk.cantonesevoicekeyboard

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodManager
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode

/** Stable IDs are persisted by Android; retain the original 1001 for Quick on upgrade. */
internal object KeyboardSubtypes {
    const val QUICK = 1001
    const val ENGLISH = 1002
    const val HANDWRITING = 1003

    private val layouts = linkedMapOf(
        QUICK to KeyboardSettings.LAYOUT_QUICK,
        ENGLISH to KeyboardSettings.LAYOUT_ENGLISH,
        HANDWRITING to KeyboardSettings.LAYOUT_HANDWRITING,
    )

    fun enabledIds(enabledLayouts: Set<String>): IntArray =
        layouts.filterValues { it in enabledLayouts }.keys.toIntArray()

    fun mode(subtypeId: Int, enabledLayouts: Set<String>): KeyboardInputMode? {
        if (subtypeId !in layouts) return null
        val layout = layouts.getValue(subtypeId).takeIf { it in enabledLayouts }
            ?: layouts.values.firstOrNull { it in enabledLayouts } ?: return null
        return when (layout) {
            KeyboardSettings.LAYOUT_QUICK -> KeyboardInputMode.Quick
            KeyboardSettings.LAYOUT_ENGLISH -> KeyboardInputMode.AlphaNum
            else -> KeyboardInputMode.Handwriting
        }
    }

    fun syncEnabled(context: Context, enabledLayouts: Set<String>) {
        // Android 12/13 require the user's system subtype-enabler screen instead.
        if (Build.VERSION.SDK_INT < 34) return
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return
        val imeId = ComponentName(context, CantoneseInputMethodService::class.java).flattenToShortString()
        val ime = manager.inputMethodList.firstOrNull { it.id == imeId } ?: return
        val ids = enabledIds(enabledLayouts)
        val current = manager.getEnabledInputMethodSubtypeList(ime, false).map { it.hashCode() }.toSet()
        if (current != ids.toSet()) manager.setExplicitlyEnabledInputMethodSubtypes(imeId, ids)
    }
}
