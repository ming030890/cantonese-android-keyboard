package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

/** Reuses the regular keyboard's events while keeping search out of the host editor. */
internal class EmojiSearchInputReceiver(
    private val keyboard: KeyboardInputReceiver,
    private val state: () -> EmojiSearchState,
    private val update: (EmojiSearchState) -> Unit,
) : KeyboardInputReceiver {
    override fun onKeyDown(data: KeyData) {
        if (!state().active) keyboard.onKeyDown(data)
        else if (data.kind == KeyboardKeyKind.Delete) update(state().delete())
    }

    override fun onKeyUp(data: KeyData) {
        if (!state().active) {
            keyboard.onKeyUp(data)
            return
        }
        when (data.kind) {
            KeyboardKeyKind.Text -> data.text?.let { update(state().type(it)) }
            KeyboardKeyKind.Space -> update(state().type(" "))
            KeyboardKeyKind.Enter, KeyboardKeyKind.Emoji -> update(state().close())
            KeyboardKeyKind.Shift, KeyboardKeyKind.ModeSwitch, KeyboardKeyKind.SymbolMore -> keyboard.onKeyUp(data)
            else -> Unit
        }
    }

    override fun onKeyUp(data: KeyData, touch: com.madeinhk.cantonesevoicekeyboard.english.EnglishTouchEvidence?) {
        if (state().active) onKeyUp(data) else keyboard.onKeyUp(data, touch)
    }

    override fun onKeyRepeat(data: KeyData) {
        if (!state().active) keyboard.onKeyRepeat(data)
        else if (data.kind == KeyboardKeyKind.Delete) update(state().delete())
    }

    override fun onKeyCancel(data: KeyData) {
        if (!state().active) keyboard.onKeyCancel(data)
    }
}

/** IME windows do not own Android focus, so hardware keys arrive at the service. */
internal fun handleEmojiSearchKey(
    event: android.view.KeyEvent,
    search: EmojiSearchState,
    update: (EmojiSearchState) -> Unit,
): Boolean {
    if (!search.active || event.action != android.view.KeyEvent.ACTION_DOWN) return false
    when (event.keyCode) {
        android.view.KeyEvent.KEYCODE_DEL -> update(search.delete())
        android.view.KeyEvent.KEYCODE_ESCAPE, android.view.KeyEvent.KEYCODE_ENTER -> update(search.close())
        else -> {
            val code = event.unicodeChar
            if (code >= 32 && code <= Character.MAX_CODE_POINT && !event.isCtrlPressed && !event.isMetaPressed) {
                update(search.type(String(Character.toChars(code))))
            } else return false
        }
    }
    return true
}
