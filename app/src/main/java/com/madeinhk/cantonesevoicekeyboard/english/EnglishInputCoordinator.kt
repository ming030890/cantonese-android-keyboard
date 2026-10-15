package com.madeinhk.cantonesevoicekeyboard.english

import android.text.InputType
import com.madeinhk.cantonesevoicekeyboard.KeyboardEditor
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** English owns composition only on the English layout. Quick supplies its own literal word. */
internal class EnglishInputCoordinator(
    private val editor: KeyboardEditor,
    private val loader: () -> EnglishCandidateEngine?,
    val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {
    private var engine: EnglishCandidateEngine? = null
    private var request = 0L
    private var pending: Deferred<EnglishPrediction?>? = null
    private var resultWord: String? = null
    private var prediction = EnglishPrediction()
    private var apostropheBase: Pair<String, EnglishPrediction>? = null
    private var centers = FloatArray(0)
    private val taps = mutableListOf<EnglishTouchEvidence?>()
    private var suppress = false
    private var safeStart = true
    private var closed = false
    var onContextRevised: () -> Unit = {}
    private val contextWindow = ContextRevisionWindow(editor, { engine }, scope, worker,
        { !closed && word.isEmpty() }, { undo=null;onContextRevised() })
    var word = ""
        private set
    var quick = false
        private set
    private data class Undo(val original: String, val committed: String, val quick: Boolean, val cursor: Int?)
    private var undo: Undo? = null
    private var pendingBoundaryCursor: Int? = null
    private val _candidates = MutableStateFlow<List<HandwritingCandidate>>(emptyList())
    val candidates = _candidates.asStateFlow()

    fun reloadBackend() {
        reset()
        scope.launch(worker) { (engine as? AutoCloseable)?.close(); engine=null }
    }
    fun boundaryPending() = word.isNotEmpty() && resultWord != word && pending != null
    fun cancelBoundaryCorrection() {
        if (!boundaryPending()) return
        pending?.cancel();pending=null;prediction=EnglishPrediction(reason="boundary_cancelled");resultWord=word
    }
    suspend fun settleBoundary() {
        val id=request;val text=word
        val result=withTimeoutOrNull(40L) { pending?.await() }
        if(id!=request || text!=word) return
        if(result!=null) { prediction=result;resultWord=text }
        else {
            pending?.cancel();pending=null
            // A timed-out decoder must not rewrite text after the boundary.
            resultWord=text;prediction=EnglishPrediction(reason="boundary_timeout")
        }
    }

    fun type(letter: String, evidence: EnglishTouchEvidence?) {
        contextWindow.onTyping()
        undo = null
        if (word.isEmpty()) safeStart = safeWordStart(editor.getTextBeforeCursor(256).orEmpty())
        quick = false
        editor.setComposingText(word + letter)
        update(word + letter, evidence)
    }

    fun observeQuick(text: String, evidence: EnglishTouchEvidence?) {
        contextWindow.onTyping()
        undo = null
        if (word.isEmpty()) {
            val preceding = editor.getTextBeforeCursor(text.length + 256).orEmpty().removeSuffix(text)
            safeStart = safeWordStart(preceding)
        }
        quick = true
        update(text, evidence)
    }

    private fun update(text: String, evidence: EnglishTouchEvidence?) {
        val old = word
        apostropheBase = if (text.length == old.length + 1 && text.startsWith(old) &&
            text.last() in "'’" && resultWord == old) old to prediction else null
        if (text != old) suppress = false
        if (text.startsWith(old) && text.length == old.length + 1) taps += evidence
        else if (old.startsWith(text)) while (taps.size > text.length) taps.removeAt(taps.lastIndex)
        else { taps.clear(); repeat(text.length) { taps += null } }
        evidence?.let {
            if (!centers.contentEquals(it.centers)) {
                // A new layout/size invalidates prior samples; retain this event's own snapshot.
                for (i in 0 until taps.lastIndex) taps[i] = null
                centers = it.centers.copyOf()
            }
        }
        word = text
        refresh()
    }

    private fun refresh() {
        pending?.cancel(); val id = ++request
        resultWord = null; prediction = EnglishPrediction()
        _candidates.value = if (eligible()) listOf(HandwritingCandidate(word, 0)) else emptyList()
        if (!eligible() || closed) return
        val text = word
        val geometry = centers.copyOf()
        val before = editor.getTextBeforeCursor(512).orEmpty()
        val left = before.removeSuffix(text)
        val right = editor.getTextAfterCursor(256).orEmpty()
        val points = FloatArray(text.length * 2) { Float.NaN }
        taps.forEachIndexed { i, tap -> tap?.let { points[i*2] = it.x; points[i*2+1] = it.y } }
        val lookup = scope.async(worker) {
            runCatching {
                if (engine == null) engine = loader()
                engine?.predict(text.lowercase(Locale.ROOT).replace('’', '\''), geometry, points, left, right)
            }.onFailure(onError).getOrNull()
        }
        pending = lookup
        scope.launch {
            val result = lookup.await()
            if (closed || id != request || word != text) return@launch
            resultWord = text; prediction = result ?: EnglishPrediction()
            val alternatives = result?.candidates.orEmpty().map { caseWord(it.word, text) }
                .filter { it != text }.distinct().take(8)
            _candidates.value = (listOf(text) + alternatives).mapIndexed { i, candidate -> HandwritingCandidate(candidate, i) }
        }
    }

    /** Rank a single bilingual pool with the same shared model used by Quick. */
    suspend fun rankCandidates(candidates: List<String>): List<String> {
        val text = word
        val left = editor.getTextBeforeCursor(512).orEmpty().removeSuffix(text)
        val right = editor.getTextAfterCursor(256).orEmpty()
        return withContext(worker) {
            runCatching {
                if (engine == null) engine = loader()
                engine?.rankCandidates(left, right, candidates) ?: candidates
            }.onFailure(onError).getOrDefault(candidates)
        }
    }

    private fun eligible() = safeStart && word.length in 1..64 && word.any { it.isAsciiLetter() } &&
        word.all { it.isAsciiLetter() || it == '\'' || it == '’' } &&
        (word == word.lowercase(Locale.ROOT) || word == word.uppercase(Locale.ROOT) ||
            word.drop(1) == word.drop(1).lowercase(Locale.ROOT))

    fun canSelect(text: String) = _candidates.value.any { it.text == text }

    fun finish(delimiter: String = "", correct: Boolean = false, chineseMatch: Boolean = false,
               selected: String? = null): Boolean {
        if (word.isEmpty()) { undo = null; return false }
        val original = word
        val originalLeft=editor.getTextBeforeCursor(512).orEmpty().removeSuffix(original)
        val evidence=FloatArray(taps.size*2) { Float.NaN }.also { values ->
            taps.forEachIndexed { i,t -> t?.let { values[i*2]=it.x;values[i*2+1]=it.y } }
        }
        if(selected!=null) engine?.selected(original.lowercase(Locale.ROOT),selected.lowercase(Locale.ROOT),centers,evidence)
        // An apostrophe followed by a boundary is a closing quote, rather than a contraction.
        val quote = if (original.last() in "'’" && apostropheBase?.first == original.dropLast(1)) original.takeLast(1) else ""
        val source = if (quote.isNotEmpty()) apostropheBase!!.second else prediction
        val currentResult = if (quote.isNotEmpty()) true else resultWord == word
        val replacement = selected ?: if (correct && !suppress && !chineseMatch && eligible() && currentResult)
            source.correction?.let { caseWord(it, original) + quote } ?: original else original
        val delayed = pending?.takeIf {
            correct && !suppress && !chineseMatch && eligible() && !currentResult &&
                selected == null && delimiter.isNotEmpty()
        }
        // Only replace composition still immediately before this cursor.
        if (!editor.getTextBeforeCursor(original.length).orEmpty().endsWith(original)) {
            reset(); return false
        }
        val replaceCount=if(selected!=null) source.replacementSpans[selected.lowercase(Locale.ROOT)]?.coerceAtLeast(original.length) ?: original.length
            else if(replacement!=original) source.replaceBefore.coerceAtLeast(original.length) else original.length
        var undoOriginal=original
        if(replaceCount>original.length) {
            val before=editor.getTextBeforeCursor(replaceCount).orEmpty()
            val prefix=before.dropLast(original.length)
            // Only one immediately adjacent ASCII fragment plus its space.
            if(before.length!=replaceCount || !prefix.endsWith(' ') || prefix.dropLast(1).isEmpty() ||
                !prefix.dropLast(1).all { it.isAsciiLetter() }) { reset();return false }
            editor.finishComposingText()
            if(!editor.replaceTextBeforeCursor(before,replacement+delimiter)) { editor.commitText(delimiter);reset();return true }
            undoOriginal=before
        } else editor.commitText(replacement + delimiter)
        val record = if (replacement != original && selected == null && correct)
            Undo(undoOriginal, replacement + delimiter, quick, editor.cursorSelection()?.first) else null
        val wasQuick = quick
        val cursor = editor.cursorSelection()?.first
        val mayReconsider=correct && !suppress && !chineseMatch && eligible() && selected==null && delimiter==" "
        val remember=mayReconsider && currentResult && source.correction==null && replacement==original
        val originalCenters=centers.copyOf()
        reset(cancelLookup = delayed == null, preserveContext = mayReconsider); undo = record
        if(mayReconsider) contextWindow.boundary(original,replacement,delimiter,originalLeft,originalCenters,evidence,remember)
        if (delayed != null) {
            pendingBoundaryCursor = cursor
            val finishedRequest = request
            scope.launch {
                val result = delayed.await() ?: return@launch
                val fixed = result.correction?.let { caseWord(it, original) } ?: return@launch
                val selection = editor.cursorSelection()
                if (closed || request != finishedRequest || word.isNotEmpty() ||
                    (selection != null && (selection.first != selection.second ||
                        (cursor != null && selection.first != cursor)))) return@launch
                pendingBoundaryCursor = null
                if (editor.replaceTextBeforeCursor(original + delimiter, fixed + delimiter)) {
                    undo = Undo(original, fixed + delimiter, wasQuick, editor.cursorSelection()?.first)
                }
            }
        }
        return true
    }

    fun delete(): Boolean {
        if(contextWindow.undo()) return true
        contextWindow.clear()
        undo?.let { record ->
            undo = null
            val selection = editor.cursorSelection()
            if ((selection == null || (selection.first == selection.second &&
                    (record.cursor == null || record.cursor == selection.first))) &&
                editor.replaceTextBeforeCursor(record.committed, "")) {
                engine?.rejected(record.original.lowercase(Locale.ROOT),record.committed.trimEnd().lowercase(Locale.ROOT))
                quick = record.quick; safeStart = true; word = record.original; suppress = true
                taps.clear(); repeat(word.length) { taps += null }
                if (!quick) editor.setComposingText(word)
                refresh()
                return true
            }
        }
        if (!quick && word.isNotEmpty()) {
            editor.setComposingText(word.dropLast(1))
            update(word.dropLast(1), null)
            if (word.isEmpty()) editor.finishComposingText()
            return true
        }
        return false
    }

    fun reset(cancelLookup: Boolean = true, preserveContext: Boolean = false) {
        if(!preserveContext) contextWindow.clear()
        if (cancelLookup) pending?.cancel()
        pending = null; ++request; word = ""; taps.clear(); centers = FloatArray(0)
        pendingBoundaryCursor = null
        resultWord = null; prediction = EnglishPrediction(); _candidates.value = emptyList()
        apostropheBase = null
        suppress = false; undo = null; safeStart = true
    }

    fun onSelectionChanged(newStart: Int, newEnd: Int, composingEnd: Int) {
        if(word.isEmpty()) contextWindow.selectionChanged(newStart,newEnd)
        pendingBoundaryCursor?.let { expected ->
            if (newStart != expected || newEnd != expected) {
                ++request; pendingBoundaryCursor = null
            }
        }
        if (newStart != newEnd || (word.isNotEmpty() && newStart != composingEnd)) {
            // The platform owns the composition now; don't write through a moved cursor.
            editor.finishComposingText(); reset()
        } else if (word.isEmpty() && undo != null) {
            // Own commit callbacks preserve undo only while its exact suffix remains at the cursor.
            val selection = editor.cursorSelection()
            if ((selection != null && (selection.first != selection.second ||
                    (undo!!.cursor != null && undo!!.cursor != selection.first))) ||
                !editor.getTextBeforeCursor(undo!!.committed.length).orEmpty().endsWith(undo!!.committed)) undo = null
        }
    }

    override fun close() {
        closed=true;reset()
        // Closing an ONNX session must serialize with in-flight inference.
        scope.launch(worker) { (engine as? AutoCloseable)?.close() }.invokeOnCompletion { scope.cancel() }
    }
}

internal fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
private fun tokenCharacter(c: Char) = c.isAsciiLetter() || c.isDigit() || c in "_@/-"
private fun safeWordStart(before: String): Boolean {
    if (before.lastOrNull()?.let(::tokenCharacter) == true) return false
    if (before.lastOrNull()?.let { it in "'’" } == true && before.dropLast(1).lastOrNull()?.isAsciiLetter() == true) return false
    val token = before.takeLastWhile { !it.isWhitespace() && it.code < 128 }.lowercase(Locale.ROOT)
    return '@' !in token && "://" !in token && !token.startsWith("www.")
}
internal fun caseWord(word: String, typed: String): String = when {
    typed == typed.uppercase(Locale.ROOT) -> word.uppercase(Locale.ROOT)
    typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
    else -> word
}
internal fun englishAllowed(inputType: Int?): Boolean {
    inputType ?: return true
    if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
    if (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
    return inputType and InputType.TYPE_MASK_VARIATION !in setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
    )
}
