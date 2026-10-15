package com.madeinhk.cantonesevoicekeyboard

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint
import com.madeinhk.cantonesevoicekeyboard.quick.QuickInputCoordinator
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.ClipboardSuggestion
import kotlinx.coroutines.flow.StateFlow

/** Android's InputConnection lives behind this boundary; scenarios use an in-memory editor. */
internal interface KeyboardEditor {
    fun setComposingText(text: String)
    fun finishComposingText()
    fun commitText(text: String)
    fun getTextBeforeCursor(length: Int): String?
    fun getTextAfterCursor(length: Int): String?
    fun deleteSelectionOrPreviousCharacter()
    fun sendEnter()
    fun enterDelimiter(): String? = "\n"
    fun cursorSelection(): Pair<Int, Int>? = null
    /** Guarded undo: never delete through a changed cursor/selection. */
    fun replaceTextBeforeCursor(expected: String, replacement: String): Boolean = false
}

internal interface VoiceSession {
    val state: StateFlow<KeyboardUiState>
    val volume: StateFlow<Float>
    fun onInputViewStarted(session: Long)
    fun toggle()
    fun interrupt()
    fun stopForHandwriting()
    fun isRecording(): Boolean
    fun finish(releaseModel: Boolean, session: Long)
    fun destroy()
}

internal interface HandwritingSession {
    val candidates: StateFlow<List<HandwritingCandidate>>
    val recognizing: StateFlow<Boolean>
    val error: StateFlow<String?>
    fun onInputStarted()
    fun onInputViewStarted(session: Long)
    fun recognize(strokes: List<List<StrokePoint>>, canvasWidth: Float, canvasHeight: Float,
                  pixels: ByteArray, width: Int, height: Int)
    fun onStrokeStarted(startsNewCharacter: Boolean)
    fun selectCandidate(text: String)
    fun hasActiveInput(): Boolean
    fun refreshPredictions()
    fun accept(replacement: String? = null)
    fun discard()
    fun finish(releaseModel: Boolean, session: Long)
    fun destroy()
}

internal interface SuggestionSession {
    val autofillSuggestionView: StateFlow<View?>
    val clipboardSuggestion: StateFlow<ClipboardSuggestion?>
    fun onInputStarted()
    fun onInputFinished()
    fun onUserTyped()
    fun onSelectionChanged()
    fun pasteClipboard()
    fun onAutofillTapped()
    fun createInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest
    fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean
}

internal data class KeyboardSessionSettings(
    val enabledLayouts: () -> Set<String>,
    val quickVersion: () -> Int,
    val autocorrectEnabled: (String) -> Boolean = { true },
    val jointDecoderEnabled: () -> Boolean = { false },
)

internal data class KeyboardSessionDependencies(
    val voice: VoiceSession,
    val quick: QuickInputCoordinator,
    val handwriting: HandwritingSession,
    val suggestions: SuggestionSession,
    val english: com.madeinhk.cantonesevoicekeyboard.english.EnglishInputCoordinator? = null,
)
