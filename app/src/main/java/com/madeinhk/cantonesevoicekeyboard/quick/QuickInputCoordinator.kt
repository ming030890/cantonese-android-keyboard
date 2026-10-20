package com.madeinhk.cantonesevoicekeyboard.quick

import android.content.Context
import com.madeinhk.cantonesevoicekeyboard.KeyboardEditor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class QuickInputCoordinator(
    private val engineLoader: () -> QuickCandidateEngine?,
    private val editor: KeyboardEditor,
    initialVersion: Int = 3,
    private val scope: CoroutineScope? = null,
    private val lookupDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val onLanguageError: (Throwable) -> Unit = {},
) : AutoCloseable {
    companion object {
        // Match the native engine's maximum so next-word candidates can be paged.
        private const val PREDICTION_LIMIT = 100
        private const val MAX_COMPOSING_CHARACTERS = 6
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
        onLanguageError = { Log.e("QuickInputCoordinator", "Quick language assessment failed", it) },
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
    private var languageEvidence: QuickLanguageEvidence? = null
    private val _candidates = MutableStateFlow<List<QuickCandidate>>(emptyList())
    val candidates = _candidates.asStateFlow()

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
        if (composingText.length >= MAX_COMPOSING_CHARACTERS && candidatesForCode == composingText &&
            languageEvidence?.shouldCommitChinese == true) {
            // A pending/failed/uncertain LM decision never forces conversion.
            val top = _candidates.value.firstOrNull()
            if (top != null && top.text.codePointCount(0, top.text.length) >= MAX_COMPOSING_CHARACTERS &&
                editor.getTextBeforeCursor(composingText.length)?.endsWith(composingText) == true) {
                val remainder = composingText.drop(candidateCodeLength)
                editor.commitText(top.text)
                clearComposingState()
                composingText = remainder
            }
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
        pending?.cancel()
        val id = ++requestId
        val code = composingText
        val version = currentVersion
        _candidates.value = emptyList()
        candidatesForCode = null
        languageEvidence = null
        if (closed) return
        val lookup = candidateLookup(code, version)
        if (scope == null) {
            val result = lookup()
            _candidates.value = result.candidates
            languageEvidence = result.evidence
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
                languageEvidence = result.evidence
                candidateCodeLength = result.codeLength
                candidatesForCode = code
            }
        }
    }

    private data class LookupResult(val candidates: List<QuickCandidate>, val evidence: QuickLanguageEvidence?, val codeLength: Int)

    private fun candidateLookup(code: String, version: Int, preview: Boolean = false): () -> LookupResult {
        // InputConnection includes the composing code before the cursor.
        val before = editor.getTextBeforeCursor(512).orEmpty()
        val left = before.removeSuffix(code)
        val right = editor.getTextAfterCursor(256).orEmpty()
        val lookupCode = code.take(MAX_COMPOSING_CHARACTERS * 2)
        return {
            runCatching {
                backend()?.let {
                    val words = if (preview) it.previewCandidates(version, lookupCode, left, right)
                        else if (code.isEmpty()) it.predictions(left, right, PREDICTION_LIMIT)
                        else it.candidates(version, lookupCode, left, right)
                    val top = words.firstOrNull()?.text
                    val evidence = if (top != null && lookupCode.length >= MAX_COMPOSING_CHARACTERS &&
                        top.codePointCount(0, top.length) >= MAX_COMPOSING_CHARACTERS)
                        runCatching { it.languageEvidence(code, left, right, top) }.onFailure(onLanguageError).getOrNull() else null
                    LookupResult(words, evidence, lookupCode.length)
                } ?: LookupResult(emptyList(), null, lookupCode.length)
            }.onFailure {
                Log.e("QuickInputCoordinator", "Quick inference failed", it)
            }.getOrDefault(LookupResult(emptyList(), null, lookupCode.length))
        }
    }

    private fun clearComposingState() {
        pending?.cancel()
        ++requestId
        composingText = ""
        candidatesForCode = null
        languageEvidence = null
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
