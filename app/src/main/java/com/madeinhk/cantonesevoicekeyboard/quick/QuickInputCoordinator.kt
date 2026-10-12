package com.madeinhk.cantonesevoicekeyboard.quick

import android.content.Context
import com.madeinhk.cantonesevoicekeyboard.ImeEditor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class QuickInputCoordinator(
    private val engineLoader: () -> QuickCandidateEngine?,
    private val editor: ImeEditor,
    initialVersion: Int = 3,
    private val scope: CoroutineScope? = null,
) : AutoCloseable {
    companion object {
        // Match the native engine's maximum so next-word candidates can be paged.
        private const val PREDICTION_LIMIT = 100
        private const val MAX_COMPOSING_CHARACTERS = 6
        private const val MAX_COMPOSING_CODE_LENGTH = MAX_COMPOSING_CHARACTERS * 2
    }

    constructor(
        context: Context,
        editor: ImeEditor,
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

    fun onKey(char: String) {
        if (char.length != 1 || (char[0] !in 'a'..'z' && char[0] !in 'A'..'Z')) {
            accept()
            editor.commitText(char)
            return
        }
        if (composingText.length >= MAX_COMPOSING_CHARACTERS) {
            // A one-letter root also counts as one Chinese character. Resolve the
            // current code if its asynchronous lookup has not finished yet.
            val top = if (candidatesForCode == composingText) _candidates.value.firstOrNull()
                else candidateLookup(composingText, currentVersion)().firstOrNull()
            if (top?.text?.let { it.codePointCount(0, it.length) >= MAX_COMPOSING_CHARACTERS } == true ||
                composingText.length >= MAX_COMPOSING_CODE_LENGTH) {
                editor.commitText(top?.text ?: composingText)
                clearComposingState()
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
        editor.commitText(prefix + text + suffix)
        clearComposingState()
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
        if (closed || code.length > MAX_COMPOSING_CODE_LENGTH) return
        val lookup = candidateLookup(code, version)
        if (scope == null) {
            _candidates.value = lookup()
            candidatesForCode = code
            return
        }
        pending = scope.launch {
            val result = withContext(Dispatchers.Default) { lookup() }
            if (!closed && requestId == id && composingText == code && currentVersion == version) {
                _candidates.value = result
                candidatesForCode = code
            }
        }
    }

    private fun candidateLookup(code: String, version: Int): () -> List<QuickCandidate> {
        // InputConnection includes the composing code before the cursor.
        val before = editor.getTextBeforeCursor(512).orEmpty()
        val left = before.removeSuffix(code)
        val right = editor.getTextAfterCursor(256).orEmpty()
        return {
            runCatching {
                backend()?.let {
                    if (code.isEmpty()) it.predictions(left, right, PREDICTION_LIMIT)
                    else it.candidates(version, code, left, right)
                }.orEmpty()
            }.onFailure {
                Log.e("QuickInputCoordinator", "Quick inference failed", it)
            }.getOrDefault(emptyList())
        }
    }

    private fun clearComposingState() {
        pending?.cancel()
        ++requestId
        composingText = ""
        candidatesForCode = null
        _candidates.value = emptyList()
    }

    override fun close() {
        discard()
        clearComposingState()
        scope?.cancel()
        synchronized(engineLock) { closed = true; engine?.close(); engine = null }
    }
}
