package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.text.InputType
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
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import com.madeinhk.cantonesevoicekeyboard.english.*

internal class KeyboardSessionController(
    private val editor: KeyboardEditor,
    private val settings: KeyboardSessionSettings,
    private val context: Context? = null,
    createDependencies: (KeyboardEditor, () -> Long, () -> Boolean) -> KeyboardSessionDependencies,
) {
    constructor(service: InputMethodService) : this(
        editor = ImeEditor({ service.currentInputConnection }, { service.currentInputEditorInfo }),
        settings = KeyboardSessionSettings(
            enabledLayouts = { KeyboardSettings.getEnabledLayouts(service) },
            quickVersion = { KeyboardSettings.getQuickVersion(service) },
            autocorrectEnabled = { KeyboardSettings.isAutocorrectEnabled(service, it) },
            autoCapitalizationEnabled = { KeyboardSettings.isAutoCapitalizationEnabled(service) },
        ),
        context = service,
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
                sharedSuggestions = true,
                unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle))
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
    init {
        english?.onContextRevised = { refreshPredictions() }
        english?.quickEnglishEnabled = { autocorrectActive(KeyboardInputMode.Quick) }
        english?.attachQuick(quick)
        if (english?.hasUnifiedSuggestions == true) english.onCursorCandidates = { words ->
            quick.publishCursorCandidates(words.map { com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate(it.text, it.rank) })
        }
    }
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
        if (english?.needsBoundaryTransaction()!=true) return false
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
    private val _chinesePunctuation = MutableStateFlow(true)
    val chinesePunctuation = _chinesePunctuation.asStateFlow()

    private fun refreshPunctuation(textBefore: String? = null) {
        if (english?.word?.isNotEmpty() == true) {
            _chinesePunctuation.value = false
            return
        }
        val text = textBefore ?: editor.getTextBeforeCursor(64)
        _chinesePunctuation.value = SmartPunctuation.isChineseContext(
            text, _keyboardInputMode.value,
            isComposingQuick = quick.hasActiveInput(),
            isComposingEnglish = false,
        )
    }

    private fun resolvePunctuation(text: String): String {
        refreshPunctuation()
        return if (text.length == 1 && SmartPunctuation.isPunctuation(text[0]))
            SmartPunctuation.toSmartPunctuation(text, _chinesePunctuation.value, editor.getTextBeforeCursor(64))
        else text
    }
    val handwritingCandidates = handwriting.candidates
    val cursorWordActive = english?.cursorWordActive ?: MutableStateFlow(false)
    val englishCandidates = english?.candidates ?: MutableStateFlow(emptyList<com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate>())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val quickCandidates = if (english?.hasUnifiedSuggestions == true) quick.joinedCandidates else if (english == null) quick.candidates else combine(quick.candidates, english.candidates) { chinese, words ->
        val mix = english.inspectingWord || (english.quick && english.word == quick.getComposingText() && autocorrectActive(KeyboardInputMode.Quick))
        Triple(chinese, if (mix) words else emptyList(), quick.getComposingText())
    }.transformLatest { (chinese, words, code) ->
        if (english.inspectingWord) emit(words) else if (words.isEmpty()) emit(chinese) else if (code.length == 2) {
            // A complete Quick code keeps every Chinese match ahead of English
            // suggestions, even when the surrounding text is English.
            // Also skip mixed ranking while Chinese lookup is pending: ranking
            // the literal English code alone competes for the same native LM.
            emit((chinese + words).distinctBy { it.text }.mapIndexed { i, candidate -> candidate.copy(rank = i) })
        } else {
            // Chinese lookup is already ranked. Make it usable immediately while
            // the English worker prepares the final bilingual ordering.
            emit(chinese)
            // Phrase output is bounded at 100, within JNI's 128-candidate limit
            // with English. Only a one-letter root keeps the existing small
            // shortlist: its dictionary can contain hundreds of single characters.
            val participating = if (code.length == 1) chinese.take(8) else chinese
            val pool = (participating + words).map { it.text }.distinct()
            val rankedTop = english.rankCandidates(pool)
            val ranked = rankedTop + chinese.filter { it.text !in rankedTop }.map { it.text }
            if (code == quick.getComposingText())
                emit(ranked.mapIndexed { i, text -> com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate(text, i) })
        }
    }.stateIn(english.scope, SharingStarted.Eagerly, emptyList())
    val handwritingRecognizing = handwriting.recognizing
    val handwritingError = handwriting.error
    val autofillSuggestionView = suggestions.autofillSuggestionView
    val clipboardSuggestion = suggestions.clipboardSuggestion

    private val _keyboardAction = MutableStateFlow(KeyboardAction.Enter)
    val keyboardAction = _keyboardAction.asStateFlow()

    private val _shiftState = MutableStateFlow(ShiftState.Off)
    val shiftState = _shiftState.asStateFlow()
    private var lastShiftTapTime = 0L
    private var manualShift = false
    private var currentEditorInfo: EditorInfo? = null

    /** Read after an event or after advancing the test scheduler for asynchronous work. */
    fun renderState() = KeyboardRenderState(
        voice = uiState.value,
        volume = volumeLevel.value,
        action = keyboardAction.value,
        inputMode = keyboardInputMode.value,
        shiftState = _shiftState.value,
        clipboard = clipboardSuggestion.value,
        inspectingWord = cursorWordActive.value,
        handwritingCandidates = handwritingCandidates.value,
        quickCandidates = quickCandidates.value,
        englishCandidates = englishCandidates.value,
        chinesePunctuation = chinesePunctuation.value,
        handwritingBusy = handwritingRecognizing.value,
        handwritingError = handwritingError.value,
        handwritingSession = handwritingSession.value,
        keyboardSession = keyboardSession.value,
    )

    fun onToggleShift(now: Long = System.currentTimeMillis()) {
        when (_shiftState.value) {
            ShiftState.CapsLock -> {
                _shiftState.value = ShiftState.Off
                manualShift = false
                lastShiftTapTime = 0L
            }
            ShiftState.Shifted -> {
                if (now - lastShiftTapTime in 1 until 400L) {
                    _shiftState.value = ShiftState.CapsLock
                    manualShift = true
                } else {
                    _shiftState.value = ShiftState.Off
                    manualShift = false
                }
                lastShiftTapTime = now
            }
            ShiftState.Off -> {
                _shiftState.value = ShiftState.Shifted
                manualShift = true
                lastShiftTapTime = now
            }
        }
    }

    fun refreshAutoCapitalization(textBefore: String? = null) {
        if (_shiftState.value == ShiftState.CapsLock) return
        if (_keyboardInputMode.value !in setOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            if (!manualShift) _shiftState.value = ShiftState.Off
            return
        }
        val isAllCharacters = (currentEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0
        if (isAllCharacters) {
            _shiftState.value = ShiftState.Shifted
            manualShift = false
        } else if (!manualShift) {
            _shiftState.value = ShiftState.Off
        }
    }

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

    fun onStartInput(info: EditorInfo?, restarting: Boolean, systemSubtypeId: Int? = null) {
        currentEditorInfo = info
        manualShift = false
        systemSubtypeId?.let { id ->
            KeyboardSubtypes.mode(id, settings.enabledLayouts())?.let { lastUserSelectedLayout = it }
        }
        boundaryQueue?.discard()
        if (restarting) finishEnglishLiteral() else english?.reset()
        english?.reloadBackend()
        updateEditorPresentation(info)
        advanceSession()
        handwriting.onInputStarted()
        suggestions.onInputStarted()
        if (!restarting) voice.finish(releaseModel = false, session = inputViewSession)
        val initialText = if (Build.VERSION.SDK_INT >= 30) {
            info?.getInitialTextBeforeCursor(64, 0)?.toString()
        } else null
        refreshAutoCapitalization(initialText)
    }

    private val _keyboardSession = MutableStateFlow(0L)
    val keyboardSession = _keyboardSession.asStateFlow()

    fun onStartInputView(info: EditorInfo?) {
        currentEditorInfo = info
        manualShift = false
        _keyboardSession.value++
        quick.setVersion(settings.quickVersion())
        updateEditorPresentation(info)
        ensureValidInputMode()
        suggestions.onInputStarted()
        inputViewVisible = true
        val session = advanceSession()
        voice.onInputViewStarted(session)
        handwriting.onInputViewStarted(session)
        val initialText = if (Build.VERSION.SDK_INT >= 30) {
            info?.getInitialTextBeforeCursor(64, 0)?.toString()
        } else null
        refreshPredictions(initialText)
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

    private fun refreshPredictions(cachedTextBefore: String? = null) {
        val textBefore = cachedTextBefore ?: editor.getTextBeforeCursor(64)
        refreshPunctuation(textBefore)
        refreshAutoCapitalization(textBefore)
        if (!inputViewVisible || english?.inspectingWord == true) return
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
        if (inputViewVisible && autocorrectActive() &&
            _keyboardInputMode.value in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick) &&
            !quick.hasActiveInput() && editor.cursorSelection() == (newStart to newEnd)) english?.inspectWordAtCursor()
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
        refreshAutoCapitalization()
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
        if (coordinator.inspectingWord) coordinator.reset()
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
            refreshPunctuation()
        } else if (quick.hasActiveInput()) {
            quick.onDelete()
            if (autocorrectActive(KeyboardInputMode.Quick)) english?.observeQuick(quick.getComposingText(), null)
            refreshPunctuation()
        } else if (handwriting.hasActiveInput()) {
            handwriting.discard()
            refreshPredictions()
        } else {
            editor.deleteSelectionOrPreviousCharacter()
            refreshPredictions()
        }
        refreshAutoCapitalization()
    }

    fun commitText(text: String) {
        if (queueEditorAction { commitText(text) }) return
        if (text.length == 1 && (text[0] in ",.!?;:" || text[0] in "，。！？；：") && waitForBoundary { commitText(text) }) return
        voice.interrupt()
        suggestions.onUserTyped()

        val charToCommit = if (text.length == 1 && text[0].isLetter() && _shiftState.value.isUppercase && text[0].isLowerCase()) {
            text.uppercase()
        } else text

        if (_shiftState.value == ShiftState.Shifted && charToCommit.length == 1 && charToCommit[0].isLetter()) {
            _shiftState.value = ShiftState.Off
            manualShift = false
        }

        val evidence = pendingTouch.also { pendingTouch = null }
        if (autocorrectActive(KeyboardInputMode.AlphaNum) && textPanel == KeyboardInputMode.AlphaNum &&
            charToCommit.length == 1 && (charToCommit[0].isAsciiLetter() || (charToCommit[0] in "'’" && !english!!.word.isEmpty()))) {
            handwriting.accept()
            english!!.type(charToCommit, evidence)
            refreshPunctuation()
            refreshAutoCapitalization()
            return
        }
        if (charToCommit.length == 1 && charToCommit[0] in "'’" && textPanel == KeyboardInputMode.Quick &&
            autocorrectActive() && quick.hasActiveInput()) {
            quick.restoreComposingText(quick.getComposingText() + charToCommit)
            english?.observeQuick(quick.getComposingText(), null)
            refreshPunctuation()
            return
        }
        val isBoundaryPunctuation = charToCommit.length == 1 && !charToCommit[0].isLetterOrDigit() && charToCommit[0] !in "_@/'’-"
        val textToCommit = resolvePunctuation(charToCommit)
        if (isBoundaryPunctuation && finishEnglishBoundary(textToCommit)) {
            refreshPredictions()
            return
        }
        // Digits/identifiers are literal; don't correct a preceding partial token.
        finishEnglishLiteral()
        if (quick.hasActiveInput()) quick.accept()
        handwriting.accept()
        editor.commitText(textToCommit)
        refreshPredictions()
    }

    fun onQuickKey(char: String) {
        if (queueEditorAction { onQuickKey(char) }) return
        voice.interrupt()
        suggestions.onUserTyped()
        val typed = if (_shiftState.value.isUppercase) char.uppercase() else char
        quick.onKey(typed)
        if (_shiftState.value == ShiftState.Shifted) {
            _shiftState.value = ShiftState.Off
            manualShift = false
        }
        val evidence = pendingTouch.also { pendingTouch = null }
        if (autocorrectActive(KeyboardInputMode.Quick)) english?.observeQuick(quick.getComposingText(), evidence)
        refreshPunctuation()
        refreshAutoCapitalization()
    }

    fun sendSpace() {
        if (queueEditorAction { sendSpace() } || waitForBoundary { sendSpace() }) return
        voice.interrupt()
        suggestions.onUserTyped()
        pendingTouch = null
        if (finishEnglishBoundary(" ")) { refreshPredictions(); return }
        if (quick.hasActiveInput()) {
            quick.onSpace()
            refreshPunctuation()
            refreshAutoCapitalization()
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
        if (english != null && (_keyboardInputMode.value == KeyboardInputMode.AlphaNum || english.inspectingWord) &&
            !english.canSelect(text)) return
        if (_shiftState.value == ShiftState.Shifted) {
            _shiftState.value = ShiftState.Off
            manualShift = false
        }
        if (english?.hasUnifiedSuggestions == true) {
            if (_keyboardInputMode.value == KeyboardInputMode.Quick && !english.inspectingWord && !quick.canSelectJoined(text)) return
        }
        if (queueEditorAction { selectCandidate(text) }) return
        voice.interrupt()
        suggestions.onUserTyped()
        if (english?.canSelect(text) == true &&
            (english.inspectingWord || textPanel == KeyboardInputMode.AlphaNum || quick.candidates.value.none { it.text == text })) {
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
        refreshPunctuation()
    }

    fun selectHandwritingCandidate(text: String) = selectCandidate(text)

    fun onSystemSubtypeSelected(subtypeId: Int) {
        val mode = KeyboardSubtypes.mode(subtypeId, settings.enabledLayouts()) ?: return
        selectKeyboardInputMode(mode)
    }

    fun selectKeyboardInputMode(mode: KeyboardInputMode) {
        flushBoundaryLiteral()
        voice.interrupt()
        manualShift = false
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

    private fun inferAppLocaleMode(info: EditorInfo?, enabled: Set<String>): KeyboardInputMode? {
        val hintLocale = if (Build.VERSION.SDK_INT >= 24) {
            info?.hintLocales?.takeIf { !it.isEmpty }?.get(0)
        } else null

        val appLocale = if (hintLocale != null) {
            hintLocale
        } else if (Build.VERSION.SDK_INT >= 33 && context != null && !info?.packageName.isNullOrEmpty()) {
            try {
                val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                localeManager?.getApplicationLocales(info.packageName)?.takeIf { !it.isEmpty }?.get(0)
            } catch (_: SecurityException) {
                null
            }
        } else null

        val lang = appLocale?.language?.lowercase(java.util.Locale.ROOT) ?: return null
        return when {
            lang == "en" && enabled.contains(KeyboardSettings.LAYOUT_ENGLISH) -> KeyboardInputMode.AlphaNum
            (lang == "zh" || lang == "yue") -> when {
                enabled.contains(KeyboardSettings.LAYOUT_QUICK) -> KeyboardInputMode.Quick
                enabled.contains(KeyboardSettings.LAYOUT_HANDWRITING) -> KeyboardInputMode.Handwriting
                else -> null
            }
            else -> null
        }
    }

    private fun updateEditorPresentation(info: EditorInfo?) {
        editorAllowsEnglish = englishAllowed(info?.inputType)
        if (!editorAllowsEnglish) TypingDiagnostics.update("editor_disallows_correction")
        _keyboardAction.value = keyboardActionFor(info)
        val targetMode = keyboardInputModeFor(info)
        if (targetMode == KeyboardInputMode.Number || targetMode == KeyboardInputMode.AlphaNum) {
            _keyboardInputMode.value = targetMode
        } else {
            val enabledLayouts = settings.enabledLayouts()
            val localeInferred = inferAppLocaleMode(info, enabledLayouts)
            val selected = lastUserSelectedLayout ?: localeInferred
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
