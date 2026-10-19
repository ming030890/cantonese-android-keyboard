package com.madeinhk.cantonesevoicekeyboard

import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import com.madeinhk.cantonesevoicekeyboard.quick.QuickInputCoordinator
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardAction
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardRenderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import com.madeinhk.cantonesevoicekeyboard.english.*

internal class KeyboardSessionController(
    private val editor: KeyboardEditor,
    private val settings: KeyboardSessionSettings,
    createDependencies: (KeyboardEditor, () -> Long, () -> Boolean) -> KeyboardSessionDependencies,
) {
    constructor(service: InputMethodService) : this(
        editor = ImeEditor({ service.currentInputConnection }, { service.currentInputEditorInfo }),
        settings = KeyboardSessionSettings(
            enabledLayouts = { KeyboardSettings.getEnabledLayouts(service) },
            quickVersion = { KeyboardSettings.getQuickVersion(service) },
            autocorrectEnabled = { KeyboardSettings.isAutocorrectEnabled(service, it) },
            jointDecoderEnabled = { KeyboardSettings.isJointDecoderEnabled(service) },
        ),
        createDependencies = { editor, currentSession, isVisible ->
            val voice = VoiceInputCoordinator(service, editor, currentSession, isVisible)
            val quick = QuickInputCoordinator(service, editor, KeyboardSettings.getQuickVersion(service))
            val handwriting = HandwritingInputCoordinator(
                context = service,
                editor = editor,
                currentSession = currentSession,
                isInputViewVisible = isVisible,
                stopVoiceInput = voice::stopForHandwriting,
                predictionProvider = quick::getPredictions,
            )
            val suggestions = KeyboardSuggestionCoordinator(service, editor, isVisible) { handwriting.accept() }
            val english = EnglishInputCoordinator(editor,
                {
                    SharedLmEnglishEngine(quick::englishContextHandle)
                },
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                onError = { android.util.Log.e("EnglishInputCoordinator", "English candidate engine failed", it) },
                completionOnly = true)
            KeyboardSessionDependencies(voice, quick, handwriting, suggestions, english)
        },
    )

    private var inputViewSession = 0L
    private var inputViewVisible = false
    private val dependencies = createDependencies(editor, { inputViewSession }, { inputViewVisible })
    private val voice = dependencies.voice
    private val quick = dependencies.quick
    private val handwriting = dependencies.handwriting
    private val suggestions = dependencies.suggestions
    private val english = dependencies.english
    init { english?.onContextRevised = { refreshPredictions() } }
    private var editorAllowsEnglish = true
    private var textPanel = KeyboardInputMode.Quick
    private var pendingTouch: EnglishTouchEvidence? = null
    private val boundaryQueue by lazy { english?.let { BoundaryTransactionQueue(it.scope) } }
    private fun queueEditorAction(action: () -> Unit): Boolean {
        if (boundaryQueue?.waiting != true) return false
        val touch=pendingTouch;pendingTouch=null
        return boundaryQueue!!.enqueue { pendingTouch=touch;action() }
    }
    private fun waitForBoundary(action: () -> Unit): Boolean {
        if (!settings.jointDecoderEnabled() || english?.needsBoundaryTransaction()!=true) return false
        return boundaryQueue?.begin({ english.settleBoundary() }, action,{english.settleContextRevision()}) == true
    }
    private fun flushBoundaryLiteral() {
        english?.cancelBoundaryCorrection()
        boundaryQueue?.flushLiteral()
    }

    private fun autocorrectActive(mode: KeyboardInputMode = textPanel): Boolean = english != null && editorAllowsEnglish &&
        when (mode) {
            KeyboardInputMode.Quick -> settings.autocorrectEnabled(KeyboardSettings.LAYOUT_QUICK)
            KeyboardInputMode.AlphaNum -> settings.autocorrectEnabled(KeyboardSettings.LAYOUT_ENGLISH)
            else -> false
        }

    val uiState = voice.state
    val volumeLevel = voice.volume
    val handwritingCandidates = handwriting.candidates
    val englishCandidates = english?.candidates ?: MutableStateFlow(emptyList<com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate>())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val quickCandidates = if (english == null) quick.candidates else combine(quick.candidates, english.candidates) { chinese, words ->
        val mix = english.quick && english.word == quick.getComposingText() && autocorrectActive(KeyboardInputMode.Quick)
        Triple(chinese, if (mix) words else emptyList(), quick.getComposingText())
    }.mapLatest { (chinese, words, code) ->
        if (words.isEmpty()) chinese else {
            val topCount = 8
            val topChinese = chinese.take(topCount)
            val pool = (topChinese + words).map { it.text }.distinct()
            val rankedTop = english.rankCandidates(pool)
            val tail = chinese.drop(topCount).filter { it.text !in rankedTop }
            val ranked = rankedTop + tail.map { it.text }
            if (code != quick.getComposingText()) emptyList() else
                ranked.mapIndexed { i, text -> com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate(text, i) }
        }
    }.stateIn(english.scope, SharingStarted.Eagerly, emptyList())
    val handwritingRecognizing = handwriting.recognizing
    val handwritingError = handwriting.error
    val autofillSuggestionView = suggestions.autofillSuggestionView
    val clipboardSuggestion = suggestions.clipboardSuggestion

    private val _keyboardAction = MutableStateFlow(KeyboardAction.Enter)
    val keyboardAction = _keyboardAction.asStateFlow()

    /** Read after an event or after advancing the test scheduler for asynchronous work. */
    fun renderState() = KeyboardRenderState(
        voice = uiState.value,
        volume = volumeLevel.value,
        action = keyboardAction.value,
        inputMode = keyboardInputMode.value,
        clipboard = clipboardSuggestion.value,
        handwritingCandidates = handwritingCandidates.value,
        quickCandidates = quickCandidates.value,
        englishCandidates = englishCandidates.value,
        handwritingBusy = handwritingRecognizing.value,
        handwritingError = handwritingError.value,
        handwritingSession = handwritingSession.value,
    )

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
        defaultInputMode(settings.enabledLayouts())
    )
    val keyboardInputMode = _keyboardInputMode.asStateFlow()
    private val _handwritingSession = MutableStateFlow(0L)
    val handwritingSession = _handwritingSession.asStateFlow()

    fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        boundaryQueue?.discard()
        if (restarting) finishEnglishLiteral() else english?.reset()
        english?.reloadBackend()
        updateEditorPresentation(info)
        advanceSession()
        handwriting.onInputStarted()
        suggestions.onInputStarted()
        if (!restarting) voice.finish(releaseModel = false, session = inputViewSession)
    }

    fun onStartInputView(info: EditorInfo?) {
        quick.setVersion(settings.quickVersion())
        updateEditorPresentation(info)
        ensureValidInputMode()
        suggestions.onInputStarted()
        inputViewVisible = true
        val session = advanceSession()
        voice.onInputViewStarted(session)
        handwriting.onInputViewStarted(session)
        refreshPredictions()
    }

    private fun ensureValidInputMode() {
        val enabledLayouts = settings.enabledLayouts()
        if (!isLayoutSupported(_keyboardInputMode.value, enabledLayouts)) {
            _keyboardInputMode.value = defaultInputMode(enabledLayouts)
        }
    }

    fun onFinishInput() {
        flushBoundaryLiteral()
        finishEnglishLiteral()
        // Commit unfinished literal input while this session still owns the editor.
        quick.accept()
        handwriting.discard()
        advanceSession()
        suggestions.onInputFinished()
        finishSession(releaseModel = false)
    }

    fun onFinishInputView() {
        flushBoundaryLiteral()
        finishEnglishLiteral()
        // Commit unfinished literal input while this session still owns the editor.
        quick.accept()
        handwriting.discard()
        suggestions.onInputFinished()
        inputViewVisible = false
        finishSession(releaseModel = true)
    }

    fun onWindowHidden() {
        flushBoundaryLiteral()
        finishEnglishLiteral()
        // Commit unfinished literal input while this session still owns the editor.
        quick.accept()
        handwriting.discard()
        advanceSession()
        suggestions.onInputFinished()
        inputViewVisible = false
        finishSession(releaseModel = true)
    }

    private fun refreshPredictions() {
        if (!inputViewVisible) return
        when (_keyboardInputMode.value) {
            KeyboardInputMode.AlphaNum -> if(autocorrectActive()) english?.refreshNextWords()
            KeyboardInputMode.Quick -> quick.refreshPredictions()
            KeyboardInputMode.Handwriting -> handwriting.refreshPredictions()
            else -> {}
        }
    }

    fun onSelectionChanged() {
        suggestions.onSelectionChanged()
        refreshPredictions()
    }

    fun onSelectionChanged(newStart: Int, newEnd: Int, composingEnd: Int) {
        if (boundaryQueue?.waiting == true && (newStart!=newEnd ||
                (composingEnd>=0 && newStart!=composingEnd))) boundaryQueue?.discard()
        val wasQuick = english?.quick == true && english.word.isNotEmpty()
        english?.onSelectionChanged(newStart, newEnd, composingEnd)
        if (wasQuick && english?.word.isNullOrEmpty()) quick.completeExternalCommit()
        onSelectionChanged()
    }

    fun onTouchEvidence(evidence: EnglishTouchEvidence?) { pendingTouch = evidence }

    /** Symbols keep the originating language owner; emoji ends the literal word. */
    fun onVisiblePanelChanged(mode: KeyboardInputMode?) {
        pendingTouch = null
        if (mode == null) { finishEnglishLiteral(); quick.accept(); return }
        if (mode == KeyboardInputMode.Number) return
        if (mode != textPanel) { finishEnglishLiteral(); quick.accept() }
        textPanel = mode
    }

    private fun finishEnglishLiteral() {
        if (english?.word?.isNotEmpty() == true) {
            val wasQuick = english.quick
            english.finish()
            if (wasQuick) quick.completeExternalCommit()
        }
        english?.reset()
    }

    private fun finishEnglishBoundary(delimiter: String): Boolean {
        val coordinator = english ?: return false
        if (coordinator.word.isEmpty()) return false
        val wasQuick = coordinator.quick
        val finished = coordinator.finish(delimiter, correct = autocorrectActive(),
            chineseMatch = wasQuick && quick.hasChineseCodeMatch())
        if (wasQuick) quick.completeExternalCommit()
        return finished
    }

    fun destroy() {
        boundaryQueue?.discard()
        inputViewVisible = false
        advanceSession()
        suggestions.onInputFinished()
        voice.destroy()
        handwriting.destroy()
        quick.close()
        english?.close()
    }

    fun createInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest =
        suggestions.createInlineSuggestionsRequest(uiExtras)

    fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean =
        suggestions.onInlineSuggestionsResponse(response)

    fun toggleRecording() {
        if (!voice.isRecording()) {
            finishEnglishLiteral()
            quick.accept()
            handwriting.accept()
        }
        voice.toggle()
    }

    fun interruptVoiceInput() {
        voice.interrupt()
    }

    fun deleteSurroundingText() {
        if (queueEditorAction { deleteSurroundingText() }) return
        voice.interrupt()
        suggestions.onUserTyped()
        pendingTouch = null
        if (english?.delete() == true) {
            if (english.quick && english.word.isNotEmpty()) quick.restoreComposingText(english.word)
        } else if (quick.hasActiveInput()) {
            quick.onDelete()
            if (autocorrectActive(KeyboardInputMode.Quick)) english?.observeQuick(quick.getComposingText(), null)
        } else if (handwriting.hasActiveInput()) {
            handwriting.discard()
            refreshPredictions()
        } else {
            editor.deleteSelectionOrPreviousCharacter()
            refreshPredictions()
        }
    }

    fun commitText(text: String) {
        if (queueEditorAction { commitText(text) }) return
        if (text.length == 1 && (text[0] in ",.!?;:" || text[0] in "，。！？；：") && waitForBoundary { commitText(text) }) return
        voice.interrupt()
        suggestions.onUserTyped()
        val evidence = pendingTouch.also { pendingTouch = null }
        if (autocorrectActive(KeyboardInputMode.AlphaNum) && textPanel == KeyboardInputMode.AlphaNum &&
            text.length == 1 && (text[0].isAsciiLetter() || (text[0] in "'’" && !english!!.word.isEmpty()))) {
            handwriting.accept()
            english!!.type(text, evidence)
            return
        }
        if (text.length == 1 && text[0] in "'’" && textPanel == KeyboardInputMode.Quick &&
            autocorrectActive() && quick.hasActiveInput()) {
            quick.restoreComposingText(quick.getComposingText() + text)
            english?.observeQuick(quick.getComposingText(), null)
            return
        }
        val isBoundaryPunctuation = text.length == 1 && !text[0].isLetterOrDigit() && text[0] !in "_@/'’-"
        if (isBoundaryPunctuation && finishEnglishBoundary(text)) {
            refreshPredictions()
            return
        }
        // Digits/identifiers are literal; don't correct a preceding partial token.
        finishEnglishLiteral()
        val isQuickComposing = quick.hasActiveInput()
        if (isQuickComposing) quick.accept()
        handwriting.accept()
        val textToCommit = if (text.length == 1 && SmartPunctuation.isPunctuation(text[0])) {
            val textBefore = editor.getTextBeforeCursor(64)
            val chineseContext = SmartPunctuation.isChineseContext(
                textBeforeCursor = textBefore,
                currentMode = _keyboardInputMode.value,
                isComposingQuick = isQuickComposing,
                isComposingEnglish = false,
            )
            SmartPunctuation.toSmartPunctuation(text, chineseContext, textBefore)
        } else {
            text
        }
        editor.commitText(textToCommit)
        refreshPredictions()
    }

    fun onQuickKey(char: String) {
        if (queueEditorAction { onQuickKey(char) }) return
        voice.interrupt()
        suggestions.onUserTyped()
        quick.onKey(char)
        val evidence = pendingTouch.also { pendingTouch = null }
        if (autocorrectActive(KeyboardInputMode.Quick)) english?.observeQuick(quick.getComposingText(), evidence)
    }

    fun sendSpace() {
        if (queueEditorAction { sendSpace() } || waitForBoundary { sendSpace() }) return
        voice.interrupt()
        suggestions.onUserTyped()
        pendingTouch = null
        if (finishEnglishBoundary(" ")) { refreshPredictions(); return }
        if (quick.hasActiveInput()) {
            quick.onSpace()
        } else {
            commitText(" ")
        }
    }

    fun sendEnter() {
        if (queueEditorAction { sendEnter() } || waitForBoundary { sendEnter() }) return
        voice.interrupt()
        suggestions.onUserTyped()
        pendingTouch = null
        val delimiter = editor.enterDelimiter()
        val finished = finishEnglishBoundary(delimiter.orEmpty())
        if (quick.hasActiveInput()) quick.accept()
        handwriting.accept()
        if (!finished || delimiter == null) editor.sendEnter()
        if (delimiter == null) english?.reset()
        refreshPredictions()
    }

    fun pasteClipboard() {
        flushBoundaryLiteral()
        voice.interrupt()
        finishEnglishLiteral()
        // Preserve literal Quick input; Chinese conversion requires explicit candidate selection.
        quick.accept()
        suggestions.pasteClipboard()
        refreshPredictions()
    }

    fun recognizeHandwriting(
        strokes: List<List<com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint>>,
        canvasWidth: Float,
        canvasHeight: Float,
        pixels: ByteArray,
        width: Int,
        height: Int,
    ) = handwriting.recognize(strokes, canvasWidth, canvasHeight, pixels, width, height)

    fun onHandwritingStrokeStarted(startsNewCharacter: Boolean) {
        suggestions.onUserTyped()
        handwriting.onStrokeStarted(startsNewCharacter)
    }

    fun selectCandidate(text: String) {
        if (queueEditorAction { selectCandidate(text) }) return
        voice.interrupt()
        suggestions.onUserTyped()
        if (english?.canSelect(text) == true &&
            (textPanel == KeyboardInputMode.AlphaNum || quick.candidates.value.none { it.text == text })) {
            val wasQuick = english.quick
            english.finish(selected = text)
            if (wasQuick) quick.completeExternalCommit()
            refreshPredictions()
            return
        }
        english?.reset()
        if (_keyboardInputMode.value == KeyboardInputMode.Quick) {
            quick.selectCandidate(text)
            if (quick.hasActiveInput() && autocorrectActive(KeyboardInputMode.Quick))
                english?.observeQuick(quick.getComposingText(), null)
        } else {
            handwriting.selectCandidate(text)
        }
    }

    fun selectHandwritingCandidate(text: String) = selectCandidate(text)

    fun selectKeyboardInputMode(mode: KeyboardInputMode) {
        flushBoundaryLiteral()
        voice.interrupt()
        // Returning from a temporary symbol panel doesn't end the language's word.
        if (mode == _keyboardInputMode.value && mode == textPanel) {
            lastUserSelectedLayout = mode
            refreshPredictions()
            return
        }
        finishEnglishLiteral()
        quick.accept()
        handwriting.accept()
        lastUserSelectedLayout = mode
        _keyboardInputMode.value = mode
        textPanel = mode
        refreshPredictions()
    }

    fun onAutofillTapped() = suggestions.onAutofillTapped()

    private fun updateEditorPresentation(info: EditorInfo?) {
        editorAllowsEnglish = englishAllowed(info?.inputType)
        if (!editorAllowsEnglish) TypingDiagnostics.update("editor_disallows_correction")
        _keyboardAction.value = keyboardActionFor(info)
        val targetMode = keyboardInputModeFor(info)
        if (targetMode == KeyboardInputMode.Number || targetMode == KeyboardInputMode.AlphaNum) {
            _keyboardInputMode.value = targetMode
        } else {
            val enabledLayouts = settings.enabledLayouts()
            val selected = lastUserSelectedLayout
            if (selected != null && isLayoutSupported(selected, enabledLayouts)) {
                _keyboardInputMode.value = selected
            } else {
                _keyboardInputMode.value = defaultInputMode(enabledLayouts)
            }
        }
        textPanel = _keyboardInputMode.value
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
