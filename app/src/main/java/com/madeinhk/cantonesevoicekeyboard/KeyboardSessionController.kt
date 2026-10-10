package com.madeinhk.cantonesevoicekeyboard

import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardAction
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class KeyboardSessionController(
    private val service: InputMethodService,
) {
    private var inputViewSession = 0L
    private var inputViewVisible = false
    private val editor = ImeEditor(
        connection = { service.currentInputConnection },
        editorInfo = { service.currentInputEditorInfo },
    )
    private val voice = VoiceInputCoordinator(
        context = service,
        editor = editor,
        currentSession = { inputViewSession },
        isInputViewVisible = { inputViewVisible },
    )
    private val handwriting = HandwritingInputCoordinator(
        context = service,
        editor = editor,
        currentSession = { inputViewSession },
        isInputViewVisible = { inputViewVisible },
        stopVoiceInput = voice::stopForHandwriting,
    )
    private val suggestions = KeyboardSuggestionCoordinator(
        service = service,
        editor = editor,
        isInputViewVisible = { inputViewVisible },
        acceptHandwriting = handwriting::accept,
    )

    val uiState = voice.state
    val volumeLevel = voice.volume
    val handwritingCandidates = handwriting.candidates
    val handwritingRecognizing = handwriting.recognizing
    val handwritingError = handwriting.error
    val autofillSuggestionView = suggestions.autofillSuggestionView
    val clipboardSuggestion = suggestions.clipboardSuggestion

    private val _keyboardAction = MutableStateFlow(KeyboardAction.Enter)
    val keyboardAction = _keyboardAction.asStateFlow()
    private val _keyboardInputMode = MutableStateFlow(KeyboardInputMode.Handwriting)
    val keyboardInputMode = _keyboardInputMode.asStateFlow()
    private val _handwritingSession = MutableStateFlow(0L)
    val handwritingSession = _handwritingSession.asStateFlow()

    fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        updateEditorPresentation(info)
        advanceSession()
        handwriting.onInputStarted()
        suggestions.onInputStarted()
        if (!restarting) voice.finish(releaseModel = false, session = inputViewSession)
    }

    fun onStartInputView(info: EditorInfo?) {
        updateEditorPresentation(info)
        suggestions.onInputStarted()
        inputViewVisible = true
        val session = advanceSession()
        voice.onInputViewStarted(session)
        handwriting.onInputViewStarted(session)
    }

    fun onFinishInput() {
        handwriting.discard()
        advanceSession()
        suggestions.onInputFinished()
        finishSession(releaseModel = false)
    }

    fun onFinishInputView() {
        handwriting.discard()
        suggestions.onInputFinished()
        inputViewVisible = false
        finishSession(releaseModel = true)
    }

    fun onWindowHidden() {
        handwriting.discard()
        advanceSession()
        suggestions.onInputFinished()
        inputViewVisible = false
        finishSession(releaseModel = true)
    }

    fun onSelectionChanged() = suggestions.onSelectionChanged()

    fun destroy() {
        inputViewVisible = false
        advanceSession()
        suggestions.onInputFinished()
        voice.destroy()
        handwriting.destroy()
    }

    fun createInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest =
        suggestions.createInlineSuggestionsRequest(uiExtras)

    fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean =
        suggestions.onInlineSuggestionsResponse(response)

    fun toggleRecording() {
        if (!voice.isRecording()) handwriting.accept()
        voice.toggle()
    }

    fun deleteSurroundingText() {
        suggestions.clearAutofill()
        if (handwriting.hasActiveInput()) handwriting.discard()
        else editor.deleteSelectionOrPreviousCharacter()
    }

    fun commitText(text: String) {
        suggestions.clearAutofill()
        handwriting.accept()
        editor.commitText(text)
    }

    fun sendSpace() = commitText(" ")

    fun sendEnter() {
        suggestions.clearAutofill()
        handwriting.accept()
        editor.sendEnter()
    }

    fun pasteClipboard() = suggestions.pasteClipboard()

    fun recognizeHandwriting(
        strokes: List<List<com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint>>,
        canvasWidth: Float,
        canvasHeight: Float,
        pixels: ByteArray,
        width: Int,
        height: Int,
    ) = handwriting.recognize(strokes, canvasWidth, canvasHeight, pixels, width, height)

    fun onHandwritingStrokeStarted(startsNewCharacter: Boolean) =
        handwriting.onStrokeStarted(startsNewCharacter)

    fun selectHandwritingCandidate(text: String) = handwriting.selectCandidate(text)

    fun selectKeyboardInputMode(mode: KeyboardInputMode) {
        handwriting.accept()
        _keyboardInputMode.value = mode
    }

    fun onAutofillTapped() = suggestions.onAutofillTapped()

    private fun updateEditorPresentation(info: EditorInfo?) {
        _keyboardAction.value = keyboardActionFor(info)
        _keyboardInputMode.value = keyboardInputModeFor(info)
    }

    private fun finishSession(releaseModel: Boolean) {
        voice.finish(releaseModel, inputViewSession)
        handwriting.finish(releaseModel, inputViewSession)
    }

    private fun advanceSession(): Long {
        inputViewSession++
        _handwritingSession.value = inputViewSession
        return inputViewSession
    }
}
