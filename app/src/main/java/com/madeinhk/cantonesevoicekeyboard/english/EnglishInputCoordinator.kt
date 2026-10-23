package com.madeinhk.cantonesevoicekeyboard.english

import android.text.InputType
import com.madeinhk.cantonesevoicekeyboard.KeyboardEditor
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.quick.QuickInputCoordinator
import com.madeinhk.cantonesevoicekeyboard.quick.QuickSuggestionInput
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
    val sharedSuggestions: Boolean = false,
    val unifiedEngine: UnifiedSuggestionEngine? = null,
    private val boundaryWorker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val previewWorker: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : AutoCloseable {
    private var engine: EnglishCandidateEngine? = null
    private var request = 0L
    private var pending: Deferred<EnglishPrediction?>? = null
    private var nextWordsJob: Job? = null
    private var nativeRequest: SuggestionRequest? = null
    private var previewRequest: SuggestionRequest? = null
    private var previewJob: Deferred<JoinedSuggestions?>? = null
    private var candidateRequest: Long? = null
    private var joinedQuick: QuickInputCoordinator? = null
    private var quickSnapshot: QuickSuggestionInput? = null
    var quickEnglishEnabled: () -> Boolean = { true }
    val hasUnifiedSuggestions get() = unifiedEngine != null

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

    fun attachQuick(quick: QuickInputCoordinator) {
        if (unifiedEngine == null) return
        joinedQuick = quick
        quick.isCandidateRequestActive = { id -> this.quick && quickSnapshot?.id == id && pending?.isCancelled == false }
        quick.onSuggestionsInvalidated = { cancelSuggestionWork(); ++request }
        quick.onCandidateRequest = { input ->
            quickSnapshot = input
            this.quick = true
            if (input.code.isNotEmpty()) {
                contextWindow.onTyping()
                undo = null
                if (word.isEmpty()) safeStart = safeWordStart(input.left)
            }
            update(input.code, null)
        }
    }

    private fun cancelSuggestionWork() {
        previewRequest?.cancel(); previewRequest = null
        previewJob?.cancel(); previewJob = null
        nativeRequest?.cancel(); nativeRequest = null
        nextWordsJob?.cancel(); nextWordsJob = null
        pending?.cancel(); pending = null
    }

    private fun startUnifiedLookup(text: String, left: String, right: String,
        input: QuickSuggestionInput? = null) {
        val backend = unifiedEngine ?: return
        cancelSuggestionWork()
        if (closed) return
        val id = ++request
        candidateRequest = if (text.isNotEmpty()) id else null
        resultWord = null; prediction = EnglishPrediction()
        if (text.isNotEmpty()) {
            _candidates.value = listOf(HandwritingCandidate(text, 0))
            if (input != null) joinedQuick?.publishLiteral(input)
        }
        val token = backend.newRequest()
        nativeRequest = token
        val allowEnglish = if (input != null) quickEnglishEnabled() && eligible() else eligible()
        var joined: JoinedSuggestions? = null
        val lookup = scope.async(worker) {
            try {
                token.ensureActive()
                val result = backend.compose(token, input?.version ?: 0, text, left, right, allowEnglish)
                token.ensureActive()
                joined = result
                result.english
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { token.ensureActive(); onError(error); null }
            finally { token.close() }
        }
        pending = lookup
        // A queued lookup may be cancelled before entering its body/finally.
        lookup.invokeOnCompletion { token.close() }
        if (text.isNotEmpty() && backend.supportsPreview && !lookup.isCompleted) {
            val previewToken = backend.newRequest()
            previewRequest = previewToken
            val preview = scope.async(previewWorker) {
                try {
                    previewToken.ensureActive()
                    backend.preview(previewToken, input?.version ?: 0, text, left, right, allowEnglish)
                        .also { previewToken.ensureActive() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { previewToken.ensureActive(); onError(error); null }
                finally { previewToken.close() }
            }
            previewJob = preview
            preview.invokeOnCompletion { previewToken.close() }
            scope.launch {
                val result = preview.await() ?: return@launch
                if (closed || request != id || word != text || lookup.isCompleted) return@launch
                publishUnified(id, text, result.english, result, input)
            }
        }
        scope.launch {
            val result = lookup.await()
            if (closed || request != id || word != text) return@launch
            val combined = joined ?: JoinedSuggestions(display = listOf(text).filter(String::isNotEmpty), codeLength = text.take(12).length)
            publishUnified(id, text, result ?: EnglishPrediction(), combined, input)
        }
    }

    private fun publishUnified(id: Long, text: String, result: EnglishPrediction,
        combined: JoinedSuggestions, input: QuickSuggestionInput?) {
            resultWord = text; prediction = result
            if (input != null) joinedQuick?.publishJoined(input, combined)
            val words = if (text.isEmpty()) {
                if (input == null) combined.display else emptyList()
            } else
                listOf(text) + result.candidates.map { caseWord(it.word, text) }.filter { it != text }.distinct().take(8)
            candidateRequest = id
            _candidates.value = words.mapIndexed { i, candidate -> HandwritingCandidate(candidate, i) }
    }

    fun reloadBackend() {
        reset()
        scope.launch(worker) { (engine as? AutoCloseable)?.close(); engine=null }
    }
    fun boundaryPending() = word.isNotEmpty() && resultWord != word && pending != null
    fun needsBoundaryTransaction()=!(hasUnifiedSuggestions && quick && !quickEnglishEnabled()) &&
        engine?.supportsAutomaticCorrection!=false &&
        (boundaryPending() || (word.isNotEmpty() && engine?.supportsContextRevision==true))
    suspend fun settleContextRevision()=contextWindow.settle()
    fun cancelBoundaryCorrection() {
        if (!boundaryPending()) return
        cancelSuggestionWork();prediction=EnglishPrediction(reason="boundary_cancelled");resultWord=word
    }
    suspend fun settleBoundary() {
        val id=request;val text=word
        val backend = unifiedEngine
        if (boundaryPending() && pending?.isCompleted == false && eligible() &&
            backend?.supportsIndependentBoundary == true) {
            // Space needs only the English decision, not Chinese search or bilingual ranking.
            // Cancel that work and use its independent native state and worker.
            val left = quickSnapshot?.takeIf { quick && it.code == text }?.left
                ?: editor.getTextBeforeCursor(512).orEmpty().removeSuffix(text)
            cancelSuggestionWork()
            val token = backend.newRequest()
            nativeRequest = token
            val lookup = scope.async(boundaryWorker) {
                try {
                    token.ensureActive()
                    backend.boundary(token, text, left).also { token.ensureActive() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { token.ensureActive(); onError(error); null }
                finally { token.close() }
            }
            pending = lookup
            lookup.invokeOnCompletion { token.close() }
        }
        val result=withTimeoutOrNull(40L) { pending?.await() }
        if(id!=request || text!=word) return
        if(result!=null) { prediction=result;resultWord=text }
        else {
            cancelSuggestionWork()
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
        if (hasUnifiedSuggestions && quick && word == text) {
            // Quick already scheduled this editor snapshot. Keep the tap without
            // launching the same shared-model request again.
            evidence?.let {
                if (!centers.contentEquals(it.centers)) { taps.fill(null); centers = it.centers.copyOf() }
                if (taps.isNotEmpty()) taps[taps.lastIndex] = it
            }
            return
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
        if (unifiedEngine != null) {
            val input = quickSnapshot?.takeIf { quick && it.code == word }
            val before = editor.getTextBeforeCursor(512).orEmpty()
            startUnifiedLookup(word, input?.left ?: before.removeSuffix(word),
                input?.right ?: editor.getTextAfterCursor(256).orEmpty(), input)
            return
        }
        cancelSuggestionWork(); val id = ++request
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
                engine?.predictObserved(text, geometry, points, left, right)
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
                engine?.rankCandidates(left, right, candidates, text) ?: candidates
            }.onFailure(onError).getOrDefault(candidates)
        }
    }

    private fun eligible() = safeStart && word.length in 1..64 && word.any { it.isAsciiLetter() } &&
        word.all { it.isAsciiLetter() || it == '\'' || it == '’' } &&
        (word == word.lowercase(Locale.ROOT) || word == word.uppercase(Locale.ROOT) ||
            word.drop(1) == word.drop(1).lowercase(Locale.ROOT))

    fun refreshNextWords() {
        if (!sharedSuggestions || word.isNotEmpty() || closed) return
        val left=editor.getTextBeforeCursor(512).orEmpty()
        val right=editor.getTextAfterCursor(256).orEmpty()
        if (unifiedEngine != null) {
            quick = false; quickSnapshot = null
            startUnifiedLookup("", left, right)
            return
        }
        cancelSuggestionWork(); val id=++request
        nextWordsJob = scope.launch {
            val words=withContext(worker) {
                runCatching {
                    if(engine==null) engine=loader()
                    engine?.nextWords(left,right).orEmpty()
                }.onFailure(onError).getOrDefault(emptyList())
            }
            if(!closed && id==request && word.isEmpty())
                _candidates.value=words.take(8).mapIndexed { i,text -> HandwritingCandidate(text,i) }
        }
    }

    fun canSelect(text: String) = (!hasUnifiedSuggestions || candidateRequest == request) && _candidates.value.any { it.text == text }

    fun finish(delimiter: String = "", correct: Boolean = false, chineseMatch: Boolean = false,
               selected: String? = null): Boolean {
        if (word.isEmpty()) {
            if(selected!=null && canSelect(selected)) {
                val left=editor.getTextBeforeCursor(1).orEmpty()
                editor.commitText((if(left.lastOrNull()?.isLetterOrDigit()==true) " " else "")+selected)
                reset(preserveDisplay = hasUnifiedSuggestions);return true
            }
            undo = null; return false
        }
        val allowCorrection = correct && engine?.supportsAutomaticCorrection != false
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
        val allowEnglishDecision=!chineseMatch || engine?.allowsQuickCodeCorrections==true
        val replacement = selected ?: if (allowCorrection && !suppress && allowEnglishDecision && eligible() && currentResult)
            source.correction?.let { caseWord(it, original) + quote } ?: original else original
        val delayed = pending?.takeIf {
            allowCorrection && !suppress && allowEnglishDecision && eligible() && !currentResult &&
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
        val record = if (replacement != original && selected == null && allowCorrection)
            Undo(undoOriginal, replacement + delimiter, quick, editor.cursorSelection()?.first) else null
        val wasQuick = quick
        val cursor = editor.cursorSelection()?.first
        val mayReconsider=allowCorrection && !suppress && allowEnglishDecision && eligible() && selected==null && delimiter==" "
        val remember=mayReconsider && currentResult && (!source.known || source.reconsiderKnown) && source.correction==null && replacement==original
        val originalCenters=centers.copyOf()
        reset(cancelLookup = delayed == null, preserveContext = mayReconsider, preserveDisplay = hasUnifiedSuggestions); undo = record
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

    fun reset(cancelLookup: Boolean = true, preserveContext: Boolean = false, preserveDisplay: Boolean = false) {
        if(!preserveContext) contextWindow.clear()
        if (cancelLookup) cancelSuggestionWork()
        pending = null; ++request; word = ""; taps.clear(); centers = FloatArray(0)
        pendingBoundaryCursor = null
        resultWord = null; prediction = EnglishPrediction()
        if (!preserveDisplay) _candidates.value = emptyList()
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
    val cls = inputType and InputType.TYPE_MASK_CLASS
    if (cls != InputType.TYPE_CLASS_TEXT && cls != InputType.TYPE_NULL) return false
    return inputType and InputType.TYPE_MASK_VARIATION !in setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
    )
}
