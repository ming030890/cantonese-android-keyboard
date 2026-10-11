package com.madeinhk.cantonesevoicekeyboard

import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import com.madeinhk.cantonesevoicekeyboard.quick.QuickInputCoordinator
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
    private val quick = QuickInputCoordinator(
        context = service,
        editor = editor,
        initialVersion = KeyboardSettings.getQuickVersion(service),
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
    val quickCandidates = quick.candidates
    val handwritingRecognizing = handwriting.recognizing
    val handwritingError = handwriting.error
    val autofillSuggestionView = suggestions.autofillSuggestionView
    val clipboardSuggestion = suggestions.clipboardSuggestion

    private val _keyboardAction = MutableStateFlow(KeyboardAction.Enter)
    val keyboardAction = _keyboardAction.asStateFlow()

    private var lastUserSelectedLayout: KeyboardInputMode? = null

    private fun isLayoutSupported(mode: KeyboardInputMode, enabled: Set<String>): Boolean =
        when (mode) {
            KeyboardInputMode.Quick -> enabled.contains(KeyboardSettings.LAYOUT_QUICK)
            KeyboardInputMode.AlphaNum -> enabled.contains(KeyboardSettings.LAYOUT_ENGLISH)
            KeyboardInputMode.Handwriting -> enabled.contains(KeyboardSettings.LAYOUT_HANDWRITING)
            KeyboardInputMode.Number -> true
        }

    private fun defaultInputMode(enabled: Set<String>): KeyboardInputMode =
        when {
            enabled.contains(KeyboardSettings.LAYOUT_QUICK) -> KeyboardInputMode.Quick
            enabled.contains(KeyboardSettings.LAYOUT_ENGLISH) -> KeyboardInputMode.AlphaNum
            enabled.contains(KeyboardSettings.LAYOUT_HANDWRITING) -> KeyboardInputMode.Handwriting
            else -> KeyboardInputMode.Quick
        }

    private val _keyboardInputMode = MutableStateFlow(
        defaultInputMode(KeyboardSettings.getEnabledLayouts(service))
    )
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
        quick.setVersion(KeyboardSettings.getQuickVersion(service))
        updateEditorPresentation(info)
        ensureValidInputMode()
        suggestions.onInputStarted()
        inputViewVisible = true
        val session = advanceSession()
        voice.onInputViewStarted(session)
        handwriting.onInputViewStarted(session)
        refreshQuickPredictions()
    }

    private fun ensureValidInputMode() {
        val enabledLayouts = KeyboardSettings.getEnabledLayouts(service)
        if (!isLayoutSupported(_keyboardInputMode.value, enabledLayouts)) {
            _keyboardInputMode.value = defaultInputMode(enabledLayouts)
        }
    }

    fun onFinishInput() {
        quick.discard()
        handwriting.discard()
        advanceSession()
        suggestions.onInputFinished()
        finishSession(releaseModel = false)
    }

    fun onFinishInputView() {
        quick.discard()
        handwriting.discard()
        suggestions.onInputFinished()
        inputViewVisible = false
        finishSession(releaseModel = true)
    }

    fun onWindowHidden() {
        quick.discard()
        handwriting.discard()
        advanceSession()
        suggestions.onInputFinished()
        inputViewVisible = false
        finishSession(releaseModel = true)
    }

    private fun refreshQuickPredictions() {
        if (inputViewVisible && _keyboardInputMode.value == KeyboardInputMode.Quick) quick.refreshPredictions()
    }

    fun onSelectionChanged() {
        suggestions.onSelectionChanged()
        refreshQuickPredictions()
    }

    fun destroy() {
        inputViewVisible = false
        advanceSession()
        suggestions.onInputFinished()
        voice.destroy()
        handwriting.destroy()
        quick.close()
    }

    fun createInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest =
        suggestions.createInlineSuggestionsRequest(uiExtras)

    fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean =
        suggestions.onInlineSuggestionsResponse(response)

    fun toggleRecording() {
        if (!voice.isRecording()) {
            quick.discard()
            handwriting.accept()
        }
        voice.toggle()
    }

    fun deleteSurroundingText() {
        suggestions.clearAutofill()
        if (quick.hasActiveInput()) {
            quick.onDelete()
        } else if (handwriting.hasActiveInput()) {
            handwriting.discard()
        } else {
            editor.deleteSelectionOrPreviousCharacter()
            refreshQuickPredictions()
        }
    }

    fun commitText(text: String) {
        suggestions.clearAutofill()
        if (quick.hasActiveInput()) quick.discard()
        handwriting.accept()
        editor.commitText(text)
        refreshQuickPredictions()
    }

    fun onQuickKey(char: String) {
        suggestions.clearAutofill()
        quick.onKey(char)
    }

    fun sendSpace() {
        if (quick.hasActiveInput()) {
            quick.onSpace()
        } else {
            commitText(" ")
        }
    }

    fun sendEnter() {
        suggestions.clearAutofill()
        if (quick.hasActiveInput()) quick.discard()
        handwriting.accept()
        editor.sendEnter()
        refreshQuickPredictions()
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

    fun selectCandidate(text: String) {
        if (_keyboardInputMode.value == KeyboardInputMode.Quick) {
            quick.selectCandidate(text)
        } else {
            handwriting.selectCandidate(text)
        }
    }

    fun selectHandwritingCandidate(text: String) = selectCandidate(text)

    fun selectKeyboardInputMode(mode: KeyboardInputMode) {
        quick.discard()
        handwriting.accept()
        lastUserSelectedLayout = mode
        _keyboardInputMode.value = mode
        refreshQuickPredictions()
    }

    fun onAutofillTapped() = suggestions.onAutofillTapped()

    private fun updateEditorPresentation(info: EditorInfo?) {
        _keyboardAction.value = keyboardActionFor(info)
        val targetMode = keyboardInputModeFor(info)
        if (targetMode == KeyboardInputMode.Number || targetMode == KeyboardInputMode.AlphaNum) {
            _keyboardInputMode.value = targetMode
        } else {
            val enabledLayouts = KeyboardSettings.getEnabledLayouts(service)
            val selected = lastUserSelectedLayout
            if (selected != null && isLayoutSupported(selected, enabledLayouts)) {
                _keyboardInputMode.value = selected
            } else {
                _keyboardInputMode.value = defaultInputMode(enabledLayouts)
            }
        }
    }

    private fun finishSession(releaseModel: Boolean) {
        quick.discard()
        voice.finish(releaseModel, inputViewSession)
        handwriting.finish(releaseModel, inputViewSession)
    }

    private fun advanceSession(): Long {
        inputViewSession++
        _handwritingSession.value = inputViewSession
        return inputViewSession
    }
}
