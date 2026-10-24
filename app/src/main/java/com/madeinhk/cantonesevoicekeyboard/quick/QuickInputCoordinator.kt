package com.madeinhk.cantonesevoicekeyboard.quick

import android.content.Context
import com.madeinhk.cantonesevoicekeyboard.KeyboardEditor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class QuickSuggestionInput(val id: Long, val code: String, val version: Int, val left: String, val right: String)

internal class QuickInputCoordinator(
    private val engineLoader: () -> QuickCandidateEngine?,
    private val editor: KeyboardEditor,
    initialVersion: Int = 3,
    private val scope: CoroutineScope? = null,
    private val lookupDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : AutoCloseable {
    companion object {
        // Match the native engine's maximum so next-word candidates can be paged.
        private const val PREDICTION_LIMIT = 100
    }

    constructor(
        context: Context,
        editor: KeyboardEditor,
        initialVersion: Int = 3,
    ) : this(
        engineLoader = { NativeQuickEngine.load(context) },
        editor = editor,
        initialVersion = initialVersion,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private var engine: QuickCandidateEngine? = null
    private val engineLock = Any()
    private var closed = false
    private var requestId = 0L
    private var pending: Job? = null
    private var currentVersion = initialVersion
    private var composingText = ""
    private var candidatesForCode: String? = null
    private var candidateCodeLength = 0
    private val _candidates = MutableStateFlow<List<QuickCandidate>>(emptyList())
    val candidates = _candidates.asStateFlow()
    private val _joinedCandidates = MutableStateFlow<List<QuickCandidate>>(emptyList())
    val joinedCandidates = _joinedCandidates.asStateFlow()
    var onCandidateRequest: ((QuickSuggestionInput) -> Unit)? = null
    var onSuggestionsInvalidated: (() -> Unit)? = null
    var isCandidateRequestActive: ((Long) -> Boolean)? = null
    private var displayedRequest: Long? = null
    private var joinedInput: QuickSuggestionInput? = null
    fun canSelectJoined(text: String) = displayedRequest == requestId && _joinedCandidates.value.any { it.text == text }
    fun publishLiteral(input: QuickSuggestionInput) {
        if (closed || requestId != input.id || composingText != input.code || currentVersion != input.version) return
        // Do not mark Chinese coverage complete until the native preview arrives.
        displayedRequest = input.id
        _joinedCandidates.value = listOf(QuickCandidate(input.code, 0))
    }
    fun publishJoined(input: QuickSuggestionInput, result: com.madeinhk.cantonesevoicekeyboard.english.JoinedSuggestions) {
        if (closed || requestId != input.id || composingText != input.code || currentVersion != input.version) return
        _candidates.value = result.chinese.mapIndexed { i, text -> QuickCandidate(text, i) }
        candidatesForCode = input.code
        candidateCodeLength = result.codeLength
        displayedRequest = input.id
        _joinedCandidates.value = result.display.mapIndexed { i, text -> QuickCandidate(text, i) }
    }

    fun setVersion(version: Int) {
        require(version == 3 || version == 5)
        if (currentVersion != version) { currentVersion = version; refreshCandidates() }
    }

    private fun backend(): QuickCandidateEngine? = synchronized(engineLock) {
        if (closed) null else engine ?: engineLoader().also { engine = it }
    }

    fun hasActiveInput(): Boolean = composingText.isNotEmpty()

    fun getComposingText(): String = composingText

    fun hasChineseCodeMatch(): Boolean = composingText.length <= 2 &&
        (candidatesForCode != composingText || _candidates.value.any { candidate -> candidate.text.any { it.code > 127 } })

    /** Called on the English worker, sharing the same native NN instead of loading a second copy. */
    fun englishContextHandle(): Long = (backend() as? NativeQuickEngine)?.englishContextHandle() ?: 0L

    fun completeExternalCommit() { clearComposingState(); refreshCandidates() }

    fun restoreComposingText(text: String) {
        composingText = text
        editor.setComposingText(text)
        refreshCandidates()
    }

    fun onKey(char: String) {
        if (char.length != 1 || (char[0] !in 'a'..'z' && char[0] !in 'A'..'Z')) {
            accept()
            editor.commitText(char)
            return
        }
        composingText += char
        editor.setComposingText(composingText)
        refreshCandidates()
    }

    fun selectCandidate(text: String) {
        // Keep adjacent English words separate when inserting a whole-word prediction.
        fun wordChar(c: Char?) = c != null && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9')
        val prediction = !hasActiveInput()
        val prefix = if (prediction && wordChar(text.firstOrNull()) &&
            wordChar(editor.getTextBeforeCursor(1)?.lastOrNull())) " " else ""
        val suffix = if (prediction && wordChar(text.lastOrNull()) &&
            wordChar(editor.getTextAfterCursor(1)?.firstOrNull())) " " else ""
        val remainder = if (!prediction && candidatesForCode == composingText &&
            _candidates.value.any { it.text == text }) composingText.drop(candidateCodeLength) else ""
        editor.commitText(prefix + text + suffix)
        clearComposingState()
        if (remainder.isNotEmpty()) {
            composingText = remainder
            editor.setComposingText(remainder)
        }
        refreshCandidates()
    }

    fun onSpace() {
        if (composingText.isNotEmpty()) {
            // Typing English: commits raw typed English text plus space
            editor.commitText("$composingText ")
            clearComposingState()
        } else {
            editor.commitText(" ")
        }
        refreshCandidates()
    }

    fun refreshPredictions() { if (!hasActiveInput()) refreshCandidates() }

    fun getPredictions(left: String, right: String, limit: Int = PREDICTION_LIMIT): List<QuickCandidate> = runCatching {
        backend()?.predictions(left, right, limit).orEmpty()
    }.onFailure { Log.e("QuickInputCoordinator", "Quick prediction failed", it) }.getOrDefault(emptyList())

    fun onDelete(): Boolean {
        if (composingText.isNotEmpty()) {
            composingText = composingText.dropLast(1)
            if (composingText.isNotEmpty()) {
                editor.setComposingText(composingText)
                refreshCandidates()
            } else {
                editor.setComposingText("")
                editor.finishComposingText()
                clearComposingState()
                refreshCandidates()
            }
            return true
        }
        return false
    }

    fun onEnter() {
        if (composingText.isNotEmpty()) {
            editor.commitText(composingText)
            clearComposingState()
        } else {
            editor.sendEnter()
        }
    }

    fun discard() {
        _joinedCandidates.value = emptyList()
        if (composingText.isNotEmpty()) {
            editor.setComposingText("")
            editor.finishComposingText()
        }
        clearComposingState()
    }

    fun accept() {
        if (composingText.isNotEmpty()) {
            editor.commitText(composingText)
            clearComposingState()
        }
    }

    private fun refreshCandidates() {
        val code = composingText
        val version = currentVersion
        val before = if (onCandidateRequest != null) editor.getTextBeforeCursor(code.length + 512).orEmpty() else ""
        val after = if (onCandidateRequest != null) editor.getTextAfterCursor(256).orEmpty() else ""
        val previous = joinedInput
        if (onCandidateRequest != null && previous != null && previous.code == code && previous.version == version &&
            previous.left == before.removeSuffix(code) && previous.right == after &&
            (displayedRequest == requestId || isCandidateRequestActive?.invoke(previous.id) == true)) return
        pending?.cancel()
        val id = ++requestId
        onCandidateRequest?.let { request ->
            candidatesForCode = null
            displayedRequest = null
            if (!closed) {
                val input = QuickSuggestionInput(id, code, version, before.removeSuffix(code), after)
                joinedInput = input
                request(input)
            }
            return
        }
        _candidates.value = emptyList()
        candidatesForCode = null
        if (closed) return
        val lookup = candidateLookup(code, version)
        if (scope == null) {
            val result = lookup()
            _candidates.value = result.candidates
            candidateCodeLength = result.codeLength
            candidatesForCode = code
            return
        }
        val previewLookup = if (code.length in 1..2) candidateLookup(code, version, preview = true) else null
        pending = scope.launch {
            if (previewLookup != null) {
                val preview = withContext(lookupDispatcher) { previewLookup() }
                if (!closed && requestId == id && composingText == code && currentVersion == version && preview.candidates.isNotEmpty()) {
                    _candidates.value = preview.candidates
                    candidateCodeLength = preview.codeLength
                    candidatesForCode = code
                }
            }
            val result = withContext(lookupDispatcher) { lookup() }
            if (!closed && requestId == id && composingText == code && currentVersion == version) {
                _candidates.value = result.candidates
                candidateCodeLength = result.codeLength
                candidatesForCode = code
            }
        }
    }

    private data class LookupResult(val candidates: List<QuickCandidate>, val codeLength: Int)

    private fun candidateLookup(code: String, version: Int, preview: Boolean = false): () -> LookupResult {
        // InputConnection includes the composing code before the cursor.
        val before = editor.getTextBeforeCursor(code.length + 512).orEmpty()
        val left = before.removeSuffix(code)
        val right = editor.getTextAfterCursor(256).orEmpty()
        val lookupCode = code
        return {
            runCatching {
                backend()?.let {
                    val words = if (preview) it.previewCandidates(version, lookupCode, left, right)
                        else if (code.isEmpty()) it.predictions(left, right, PREDICTION_LIMIT)
                        else it.candidates(version, lookupCode, left, right)
                    LookupResult(words, lookupCode.length)
                } ?: LookupResult(emptyList(), lookupCode.length)
            }.onFailure {
                Log.e("QuickInputCoordinator", "Quick inference failed", it)
            }.getOrDefault(LookupResult(emptyList(), lookupCode.length))
        }
    }

    private fun clearComposingState() {
        onSuggestionsInvalidated?.invoke()
        displayedRequest = null
        joinedInput = null
        pending?.cancel()
        ++requestId
        composingText = ""
        candidatesForCode = null
        candidateCodeLength = 0
        _candidates.value = emptyList()
    }

    override fun close() {
        discard()
        clearComposingState()
        scope?.cancel()
        synchronized(engineLock) { closed = true; engine?.close(); engine = null }
    }
}
