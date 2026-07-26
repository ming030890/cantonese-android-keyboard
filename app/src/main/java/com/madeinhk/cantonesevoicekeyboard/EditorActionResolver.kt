package com.madeinhk.cantonesevoicekeyboard

import android.view.inputmethod.EditorInfo

/**
 * Returns the action that should be shown and sent for an editor.
 *
 * Some search fields set [EditorInfo.IME_FLAG_NO_ENTER_ACTION] alongside an
 * explicit search action. Search is still a terminal action rather than a
 * request for a multiline Enter key, so it must take precedence over the flag.
 */
internal fun standardImeAction(imeOptions: Int): Int {
    val action = imeOptions and EditorInfo.IME_MASK_ACTION
    if (action == EditorInfo.IME_ACTION_SEARCH) {
        return action
    }
    if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
        return EditorInfo.IME_ACTION_NONE
    }
    return action
}
