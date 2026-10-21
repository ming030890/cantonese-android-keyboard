package com.madeinhk.cantonesevoicekeyboard

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint
import com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate
import com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidateEngine
import com.madeinhk.cantonesevoicekeyboard.quick.QuickInputCoordinator
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow

/** Drives production orchestration with fake OS/model boundaries, never a second reducer. */
internal class KeyboardScenario(
    initialText: String = "",
    clipboardText: String? = "copied text",
    initialMode: KeyboardInputMode = KeyboardInputMode.Quick,
    scope: CoroutineScope? = null,
    lookupDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    englishEngine: com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidateEngine? = null,
    quickEngine: QuickCandidateEngine? = null,
    voiceFactory: ((KeyboardEditor) -> VoiceSession)? = null,
    englishDispatcher: CoroutineDispatcher? = null,
) : AutoCloseable {
    val editor = MemoryKeyboardEditor(initialText)
    val engine = ScenarioQuickEngine()
    val voice = ScenarioVoice(editor)
    private val sessionVoice = voiceFactory?.invoke(editor) ?: voice
    val handwriting = ScenarioHandwriting(editor)
    val suggestions = ScenarioSuggestions(editor, clipboardText)
    var quickVersion = 3
    var enabledLayouts = setOf(KeyboardSettings.LAYOUT_QUICK, KeyboardSettings.LAYOUT_ENGLISH,
                               KeyboardSettings.LAYOUT_HANDWRITING)
    val quick = QuickInputCoordinator({ quickEngine ?: engine }, editor, scope = scope, lookupDispatcher = lookupDispatcher)
    var autocorrectLayouts = setOf(KeyboardSettings.LAYOUT_ENGLISH, KeyboardSettings.LAYOUT_QUICK)
    val english = englishEngine?.let { engine ->
        com.madeinhk.cantonesevoicekeyboard.english.EnglishInputCoordinator(editor, { engine },
            scope ?: CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            englishDispatcher ?: if (scope == null) kotlinx.coroutines.Dispatchers.Unconfined else lookupDispatcher,
            sharedSuggestions = engine is com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine)
    }
    val controller = KeyboardSessionController(
        editor,
        KeyboardSessionSettings({ enabledLayouts }, { quickVersion }, { it in autocorrectLayouts }),
    ) { _, _, _ -> KeyboardSessionDependencies(sessionVoice, quick, handwriting, suggestions, english) }

    // The same key receiver handles down/up/cancel in the real screen.
    var emojiSearch = EmojiSearchState()
    private val editorReceiver = KeyboardScreenInputReceiver().apply {
        onTouchEvidence = controller::onTouchEvidence
        onInterruptVoice = controller::interruptVoiceInput
        onCommitText = { text ->
            dispatchKeyboardText(controller.keyboardInputMode.value, text, controller::onQuickKey, controller::commitText)
        }
        onDelete = controller::deleteSurroundingText
        onSendSpace = controller::sendSpace
        onSendEnter = controller::sendEnter
        onCandidateTapped = controller::selectCandidate
        onPasteClipboard = controller::pasteClipboard
        onToggleRecording = controller::toggleRecording
    }

    private val receiver = EmojiSearchInputReceiver(editorReceiver, { emojiSearch }, { emojiSearch = it })

    init { controller.selectKeyboardInputMode(initialMode) }
    fun start() { controller.onStartInput(null, false); controller.onStartInputView(null) }
    fun hide() = controller.onWindowHidden()
    fun copy(text: String?, sensitive: Boolean = false, mimeTypes: Set<String> = setOf(ClipMimeTypes.TEXT_PLAIN)) =
        suggestions.copy(text, sensitive, mimeTypes)
    fun mode(mode: KeyboardInputMode) = controller.selectKeyboardInputMode(mode)
    fun keyDown(kind: KeyboardKeyKind, text: String? = null) = receiver.onKeyDown(KeyData("scenario", kind, text))
    fun keyUp(kind: KeyboardKeyKind, text: String? = null) = receiver.onKeyUp(KeyData("scenario", kind, text))
    fun press(kind: KeyboardKeyKind, text: String? = null) {
        keyDown(kind, text)
        keyUp(kind, text)
    }
    fun pressWithTouch(text: String, evidence: com.madeinhk.cantonesevoicekeyboard.english.EnglishTouchEvidence?) {
        val data = KeyData("scenario", KeyboardKeyKind.Text, text)
        receiver.onKeyDown(data)
        receiver.onKeyUp(data, evidence)
    }
    fun keyRepeat(kind: KeyboardKeyKind) = receiver.onKeyRepeat(KeyData("scenario", kind))
    fun keyCancel(kind: KeyboardKeyKind, text: String? = null) = receiver.onKeyCancel(KeyData("scenario", kind, text))
    fun cancel(kind: KeyboardKeyKind) {
        keyDown(kind)
        keyCancel(kind)
    }
    fun type(text: String) = text.forEach { press(KeyboardKeyKind.Text, it.toString()) }
    fun choose(text: String) = press(KeyboardKeyKind.Candidate, text)
    fun snapshot() = controller.renderState()
    override fun close() = controller.destroy()
}

internal class MemoryKeyboardEditor(var text: String = "", var right: String = "") : KeyboardEditor {
    var composing = ""
    val commits = mutableListOf<String>()
    override fun setComposingText(text: String) { composing = text }
    override fun finishComposingText() { text += composing; composing = "" }
    override fun commitText(text: String) { this.text += text; composing = ""; commits += text }
    override fun getTextBeforeCursor(length: Int) = (text + composing).takeLast(length)
    override fun getTextAfterCursor(length: Int) = right.take(length)
    override fun deleteSelectionOrPreviousCharacter() { text = text.dropLast(previousCharacterLength(text)) }
    var selectionOverride: Pair<Int, Int>? = null
    override fun cursorSelection() = selectionOverride ?: ((text.length + composing.length) to (text.length + composing.length))
    var actionEnter = false
    var actions = 0
    override fun enterDelimiter(): String? = if (actionEnter) null else "\n"
    override fun sendEnter() { if (actionEnter) actions++ else commitText("\n") }
    override fun replaceTextBeforeCursor(expected: String, replacement: String): Boolean {
        if (!text.endsWith(expected) || composing.isNotEmpty()) return false
        text = text.dropLast(expected.length) + replacement
        return true
    }
}

internal class ScenarioQuickEngine : QuickCandidateEngine {
    var classify: (String, String, String, String) -> com.madeinhk.cantonesevoicekeyboard.quick.QuickLanguageEvidence? = { _, _, _, _ -> null }
    override fun languageEvidence(code: String, left: String, right: String, chinese: String) = classify(code, left, right, chinese)
    var lookup: (Int, String, String, String) -> List<QuickCandidate> = { _, code, _, _ ->
        if (code == "of") listOf(QuickCandidate("你", 0), QuickCandidate("係", 1)) else emptyList()
    }
    var predict: (String, String, Int) -> List<QuickCandidate> = { left, _, _ ->
        when {
            left.endsWith("你") -> listOf(QuickCandidate("好", 0), QuickCandidate("哋", 1))
            left.endsWith("你好") -> listOf(QuickCandidate("嗎", 0))
            else -> listOf(QuickCandidate("我", 0), QuickCandidate("你", 1))
        }
    }
    val requests = mutableListOf<List<String>>()
    override fun candidates(version: Int, code: String, left: String, right: String): List<QuickCandidate> {
        requests += listOf("code", version.toString(), code, left, right)
        return lookup(version, code, left, right)
    }
    override fun predictions(left: String, right: String, limit: Int): List<QuickCandidate> {
        requests += listOf("prediction", left, right)
        return predict(left, right, limit)
    }
}

internal class ScenarioVoice(private val editor: KeyboardEditor? = null) : VoiceSession {
    override val state = MutableStateFlow<KeyboardUiState>(KeyboardUiState.Idle)
    override val volume = MutableStateFlow(0f)
    override fun onInputViewStarted(session: Long) = Unit
    override fun toggle() { state.value = if (isRecording()) KeyboardUiState.Transcribing else KeyboardUiState.Recording }
    override fun interrupt() {
        if (!isRecording() && state.value != KeyboardUiState.Transcribing) return
        editor?.finishComposingText()
        state.value = KeyboardUiState.Idle
    }
    override fun stopForHandwriting() = interrupt()
    override fun isRecording() = state.value == KeyboardUiState.Recording
    override fun finish(releaseModel: Boolean, session: Long) = interrupt()
    override fun destroy() = interrupt()
}

internal class ScenarioHandwriting(private val editor: KeyboardEditor) : HandwritingSession {
    override val candidates = MutableStateFlow<List<HandwritingCandidate>>(emptyList())
    override val recognizing = MutableStateFlow(false)
    override val error = MutableStateFlow<String?>(null)
    private var active = false
    override fun onInputStarted() = discard()
    override fun onInputViewStarted(session: Long) = Unit
    override fun recognize(strokes: List<List<StrokePoint>>, canvasWidth: Float, canvasHeight: Float,
                           pixels: ByteArray, width: Int, height: Int) { recognizing.value = true }
    override fun onStrokeStarted(startsNewCharacter: Boolean) { active = true }
    override fun selectCandidate(text: String) { editor.commitText(text); discard() }
    override fun hasActiveInput() = active
    override fun refreshPredictions() = Unit
    override fun accept(replacement: String?) {
        if (active) (replacement ?: candidates.value.firstOrNull()?.text)?.let(editor::commitText)
        discard()
    }
    override fun discard() { active = false; candidates.value = emptyList(); recognizing.value = false; error.value = null }
    override fun finish(releaseModel: Boolean, session: Long) = discard()
    override fun destroy() = discard()
}

internal class ScenarioSuggestions(private val editor: KeyboardEditor, text: String?) : SuggestionSession {
    private val clipboard = ClipboardSession()
    private var token = 0L
    private var text: String? = null
    override val clipboardSuggestion = clipboard.suggestion
    override val autofillSuggestionView = MutableStateFlow<View?>(null)
    init { copy(text) }
    fun copy(text: String?, sensitive: Boolean = false, mimeTypes: Set<String> = setOf(ClipMimeTypes.TEXT_PLAIN)) {
        this.text = text.takeIf { ClipMimeTypes.TEXT_PLAIN in mimeTypes }
        clipboard.update(text?.let { ClipboardSnapshot(++token,
            listOf(ClipboardItemSnapshot(mimeTypes, if (sensitive) null else it)), sensitive) })
    }
    override fun onInputStarted() { clipboard.start(); autofillSuggestionView.value = null }
    override fun onInputFinished() { clipboard.finish(); autofillSuggestionView.value = null }
    override fun onUserTyped() { clipboard.onUserTyped(); autofillSuggestionView.value = null }
    override fun onSelectionChanged() { autofillSuggestionView.value = null }
    override fun pasteClipboard() { clipboard.onPaste(); text?.let(editor::commitText) }
    override fun onAutofillTapped() { autofillSuggestionView.value = null }
    override fun createInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest =
        error("Inline provider inflation belongs in Android integration tests")
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse) = true
}

/** These snapshots are generated by event sequences, shared with screenshot previews. */
internal object KeyboardScenarios {
    // Fixed responses from the native regression; screenshots verify presentation.
    fun mixedChinesePhrase() = mixedRankingSnapshot("humo", listOf("兜兩", "自兩", "先兩", "看兩", "兒歌", "自歌"))
    fun mixedExactEnglish() = mixedRankingSnapshot("continue", listOf("continue", "continues", "continued", "convince", "convinced", "金劍對色水"))
    private fun mixedRankingSnapshot(code: String, order: List<String>) = KeyboardScenario(
        clipboardText=null,
        englishEngine=com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine(
            {1L},{_,_,_->emptyList()},{_,_,_,pool,_->
                pool.sortedBy { order.indexOf(it).takeIf { n -> n>=0 } ?: Int.MAX_VALUE }
            },{_,_,_->com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(
                candidates=order.filter { it.all { c -> c.code<128 } }.map {
                    com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate(it,0.0,0.0,0,0)
                })})).use { s ->
        s.engine.lookup={_,_,_,_->order.filter { it.any { c -> c.code>127 } }.mapIndexed { i,t -> QuickCandidate(t,i) }}
        s.start();s.type(code);s.snapshot()
    }

    fun quickApostropheCorrection() = KeyboardScenario(initialText="I ",
        initialMode=KeyboardInputMode.Quick, clipboardText=null,
        englishEngine=com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine(
            {1L},{_,_,_->emptyList()},{_,_,_,words,_->words},{_,_,_->
                com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(
                    candidates=listOf(com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate("don't",-5.39,2.0,1,0)),
                    correction="don't",known=true,reason="shared_lm_spelling")
            })).use { it.start();it.type("dont");it.snapshot() }

    fun quickEnglishCorrection() = KeyboardScenario(initialMode=KeyboardInputMode.Quick,
        clipboardText=null,
        englishEngine=com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine(
            {1L},{_,_,_->emptyList()},{_,_,_,words,_->words},{_,_,_->
                com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(
                    candidates=listOf(com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate("when",-8.82,2.0,1,0)),
                    correction="when",reason="shared_lm_spelling")
            })).use { it.start();it.type("whrn");it.snapshot() }

    fun sharedLmKnownWordCorrection() = KeyboardScenario(initialText="let me know if ",
        initialMode=KeyboardInputMode.AlphaNum,clipboardText=null,
        englishEngine=com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine(
            {1L},{_,_,_->emptyList()},{_,_,_,words,_->words},{_,_,_->
                com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(
                    candidates=listOf(com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate("you",-2.2,1.0,1,0)),
                    correction="you",known=true,reason="shared_lm_spelling")
            })).use { it.start();it.type("hou");it.snapshot() }

    fun uncertainSharedLmSpelling() = KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,
        clipboardText=null,
        englishEngine=com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine(
            {1L},{_,_,_->emptyList()},{_,_,_,words,_->words},{_,_,_->
                com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(
                    candidates=listOf(com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate("barely",-10.0,1.0,1,0)),
                    correction=null,reason="shared_lm_spelling")
            })).use { it.start();it.type("varely");it.snapshot() }

    fun sharedLmSpellingSuggestions() = KeyboardScenario(initialText="how are ",
        initialMode=KeyboardInputMode.AlphaNum,clipboardText=null,
        englishEngine=com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine(
            {1L},{_,_,_->emptyList()},{_,_,_,words,_->words},{_,_,_->
                com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(
                    candidates=listOf(com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate("you",-2.4,2.0,1,0)),
                    correction="you",reason="shared_lm_spelling")
            })).use { it.start();it.type("yiu");it.snapshot() }

    fun sharedLmEnglishCompletion() = KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum,
        clipboardText = null, englishEngine = com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine(
            { 1L }, { _, _, _ -> listOf("hello", "help", "helpful") }, { _, _, _, words, _ -> words },
            { _,_,_ -> com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(candidates=
                listOf("hello","help","helpful").map { word -> com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate(word,0.0,0.0,0,2) }) }
        )).use { it.start(); it.type("hel"); it.snapshot() }

    private fun longWordEngine() = object :
        com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidateEngine {
        override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String) =
            com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(
                listOf("keyboard", "candidates", "configuration").map {
                    com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate(it, 8.0, 0.1, 1, 0)
                })
        override fun rankCandidates(left: String, right: String, candidates: List<String>, observed: String) =
            listOf("keyboard", "我想去食飯", "candidates", "應該可以", "configuration", "香港人", "keyboadr")
                .filter { it in candidates }
    }
    fun longMixedCandidates() = KeyboardScenario(clipboardText = null, englishEngine = longWordEngine()).use {
        it.engine.lookup = { _, _, _, _ -> listOf(QuickCandidate("我想去食飯", 0), QuickCandidate("應該可以", 1), QuickCandidate("香港人", 2)) }
        it.start(); it.type("keyboadr"); it.snapshot()
    }
    fun longEnglishCandidates() = KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum,
        clipboardText = null, englishEngine = longWordEngine()).use { it.start(); it.type("keyboadr"); it.snapshot() }
    fun missingFinalLetterCandidates() = KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum,
        clipboardText = null, englishEngine = ScenarioEnglishEngine()).use { it.start(); it.type("keyboar"); it.snapshot() }
    fun englishAutocorrectCandidates() = KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum,
        clipboardText = null, englishEngine = ScenarioEnglishEngine()).use { it.start(); it.type("teh"); it.snapshot() }
    fun uncertainCompanyCandidates() = KeyboardScenario(initialText="I work at ",initialMode=KeyboardInputMode.AlphaNum,
        clipboardText=null,englishEngine=object:com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidateEngine {
            override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
                com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(
                    listOf("Xentara","Centara","Sentara").map {
                        com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate(it,0.0,0.0,0,0)
                    })
        }).use { it.start();it.type("Xentara");it.snapshot() }
    fun quickEnglishCandidates() = KeyboardScenario(clipboardText = null, englishEngine = ScenarioEnglishEngine().apply {
        ranker = { _, _, pool -> listOf("the", "你", "係", "teh").filter { it in pool } }
    }).use {
        it.engine.lookup = { _, _, _, _ -> listOf(QuickCandidate("你", 0), QuickCandidate("係", 1)) }
        it.start(); it.type("teh"); it.snapshot()
    }
    fun twoLetterQuickCandidates() = KeyboardScenario(initialText = "I use ", clipboardText = null,
        englishEngine = ScenarioEnglishEngine().apply {
            ranker = { _, _, pool -> pool.sortedBy { text -> if (text.all { it.code < 128 }) 0 else 1 } }
        }).use {
        it.engine.lookup = { _, _, _, _ ->
            listOf("標", "杯", "禁", "紮", "焚", "杰", "棕", "楝", "柬").mapIndexed { i, text -> QuickCandidate(text, i) }
        }
        it.start(); it.type("df"); it.snapshot()
    }
    fun shortCodePreviewCandidates(): KeyboardRenderState {
        val queue = ArrayDeque<Runnable>()
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.addLast(block) }
        }
        var previewReady = false
        val engine = object : QuickCandidateEngine {
            override fun previewCandidates(version: Int, code: String, left: String, right: String): List<QuickCandidate> {
                previewReady = true
                return listOf("標", "杯", "禁", "紮", "焚").mapIndexed { i, text -> QuickCandidate(text, i) }
            }
            override fun candidates(version: Int, code: String, left: String, right: String): List<QuickCandidate> =
                error("Full ranking must still be queued when taking the preview snapshot")
        }
        return KeyboardScenario(initialText = "I use ", clipboardText = null, quickEngine = engine,
            scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            lookupDispatcher = worker, englishDispatcher = worker, englishEngine = ScenarioEnglishEngine()).use {
            it.start(); it.type("df")
            while (!previewReady) queue.removeFirst().run()
            it.snapshot()
        }
    }
    fun clipboardInitially() = KeyboardScenario().use { it.start(); it.snapshot() }
    fun clipboardAfterTyping() = KeyboardScenario().use { it.start(); it.type("o"); it.snapshot() }
    fun quickCandidates() = KeyboardScenario(clipboardText = null).use { it.start(); it.type("of"); it.snapshot() }
    fun nextWordPredictions() = KeyboardScenario().use { it.start(); it.type("of"); it.choose("你"); it.snapshot() }
    fun afterEmojiBackspace() = KeyboardScenario(initialText = "你", clipboardText = null).use {
        it.start(); it.press(KeyboardKeyKind.Text, "👍🏽"); it.press(KeyboardKeyKind.Delete); it.snapshot()
    }
    fun typingInterruptsVoice() = KeyboardScenario(clipboardText = null, englishEngine = ScenarioEnglishEngine()).use {
        it.start(); it.editor.setComposingText("語音輸入")
        it.voice.state.value = KeyboardUiState.Recording
        it.type("df"); it.snapshot()
    }
    fun richClipboard() = KeyboardScenario().use {
        it.start(); it.copy("plain version", mimeTypes = setOf(ClipMimeTypes.TEXT_PLAIN, "text/html")); it.snapshot()
    }
    fun clipboardAfterNewCopy() = KeyboardScenario().use { it.start(); it.type("of"); it.copy("fresh text"); it.snapshot() }
    fun handwritingRecognizing() = KeyboardScenario(initialMode = KeyboardInputMode.Handwriting).use {
        it.start(); it.controller.onHandwritingStrokeStarted(true); it.handwriting.recognizing.value = true; it.snapshot()
    }
    fun handwritingError() = KeyboardScenario(initialMode = KeyboardInputMode.Handwriting).use {
        it.start(); it.controller.onHandwritingStrokeStarted(true); it.handwriting.error.value = "Cannot recognize"; it.snapshot()
    }
}

/** Fixed model outputs keep scenarios independent of the native search implementation. */
internal class ScenarioEnglishEngine : com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidateEngine {
    data class Request(val word: String, val left: String, val right: String, val centers: FloatArray, val points: FloatArray)
    val requests = mutableListOf<Request>()
    var failure = false
    var ranker: (String, String, List<String>) -> List<String> = { _, _, candidates -> candidates }
    override fun rankCandidates(left: String, right: String, candidates: List<String>, observed: String) = ranker(left, right, candidates)
    override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction {
        requests += Request(word, left, right, centers, points)
        if (failure) error("Native unavailable")
        val correction = mapOf("teh" to "the", "helllo" to "hello", "aple" to "apple", "keyboar" to "keyboard")[word]
        val candidates = correction?.let {
            listOf(com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate(it, 8.0,
                if (word == "keyboar") 0.0 else 0.10536051565782628,
                if (word == "keyboar") 0 else 1, if (word == "keyboar") 1 else 0))
        }.orEmpty()
        return com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(candidates, correction,
            word in listOf("key", "keyb", "keybo", "keyboa", "keyboar", "keyboard") || correction != null,
            word in listOf("the", "hello", "keyboard", "of", "don't"))
    }
}
