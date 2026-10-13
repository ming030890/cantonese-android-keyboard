package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.ClipboardSnapshot
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.ClipboardSuggestion
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.clipboardSuggestionFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Clipboard visibility policy. Android observation only supplies snapshots. */
internal class ClipboardSession {
    private var active = false
    private var typed = false
    private var snapshot: ClipboardSnapshot? = null
    private val _suggestion = MutableStateFlow<ClipboardSuggestion?>(null)
    val suggestion = _suggestion.asStateFlow()

    fun start() {
        active = true
        typed = false
        publish()
    }

    fun finish() {
        active = false
        publish()
    }

    fun update(snapshot: ClipboardSnapshot?) {
        // A newly copied supported item starts a fresh paste opportunity, even after typing.
        if (snapshot?.token != this.snapshot?.token &&
            clipboardSuggestionFor(snapshot, dismissedToken = null) != null) typed = false
        this.snapshot = snapshot
        publish()
    }

    fun onUserTyped() {
        typed = true
        publish()
    }

    fun onPaste() = onUserTyped()

    val canSuggest: Boolean get() = active && !typed

    private fun publish() {
        _suggestion.value = if (canSuggest) clipboardSuggestionFor(snapshot, dismissedToken = null) else null
    }
}
