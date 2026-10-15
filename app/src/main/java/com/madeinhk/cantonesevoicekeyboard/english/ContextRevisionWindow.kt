package com.madeinhk.cantonesevoicekeyboard.english

import com.madeinhk.cantonesevoicekeyboard.KeyboardEditor
import kotlinx.coroutines.*
import java.util.Locale

/** Reassess only our own recent, untouched words at completed-word boundaries. */
internal class ContextRevisionWindow(
    private val editor: KeyboardEditor,
    private val engine: () -> EnglishCandidateEngine?,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val canApply: () -> Boolean,
    private val onRevision: () -> Unit,
) {
    private data class Word(val original: String, val left: String, val centers: FloatArray,
                            val points: FloatArray, var suffix: String)
    private data class Undo(val original: String, val replacement: String, val cursor: Int,
                            val typedWord: String, val fixedWord: String)
    private val words=mutableListOf<Word>()
    private var job: Job?=null
    private var revision=0L
    private var cursor: Int?=null
    private var undo: Undo?=null

    fun onTyping() { job?.cancel();job=null;++revision;undo=null }
    fun clear() { onTyping();words.clear();cursor=null }
    fun selectionChanged(start: Int, end: Int) {
        if(start!=end || (cursor!=null && start!=cursor)) clear()
    }

    fun boundary(original: String, committed: String, delimiter: String, left: String,
                 centers: FloatArray, points: FloatArray, remember: Boolean) {
        onTyping()
        // No crossing sentence/lifecycle boundaries or rewriting explicit selections.
        if(delimiter!=" " || committed.any { !it.isAsciiLetter() && it !in "'’" }) { clear();return }
        words.forEach { it.suffix+=committed+delimiter }
        if(remember) words+=Word(original,left,centers.copyOf(),points.copyOf(),committed+delimiter)
        words.removeAll { it.suffix.length>256 }
        words.removeAll { editor.getTextBeforeCursor(it.left.length+it.suffix.length).orEmpty()!=it.left+it.suffix }
        while(words.size>3) words.removeAt(0)
        val selection=editor.cursorSelection() ?: return
        if(selection.first!=selection.second) { clear();return }
        cursor=selection.first
        val snapshots=words.filter { it.suffix.drop(it.original.length).any(Char::isAsciiLetter) }
            .map { it.copy(centers=it.centers.copyOf(),points=it.points.copyOf()) }
        if(snapshots.isEmpty()) return
        val id=revision;val expectedCursor=selection.first;val after=editor.getTextAfterCursor(256).orEmpty()
        job=scope.launch {
            for(w in snapshots) {
                val result=withTimeoutOrNull(40L) { withContext(worker) {
                    val model=engine()
                    if(model?.supportsContextRevision!=true) null else runCatching {
                        model.reconsider(w.original.lowercase(Locale.ROOT).replace('’','\''),w.centers,w.points,
                            w.left,w.suffix.drop(w.original.length)+after)
                    }.getOrNull()
                } } ?: continue
                val correction=result.correction ?: continue
                if(result.confidence?.let { it.isFinite() && it>=0.999 }!=true ||
                    correction.isEmpty() || correction.any { !it.isAsciiLetter() && it!='\'' } ||
                    result.replaceBefore>w.original.length) continue
                val fixed=caseWord(correction,w.original)
                if(fixed==w.original) continue
                if(id!=revision || !canApply() || editor.cursorSelection()!=(expectedCursor to expectedCursor) ||
                    editor.getTextAfterCursor(256).orEmpty()!=after ||
                    editor.getTextBeforeCursor(w.left.length+w.suffix.length).orEmpty()!=w.left+w.suffix) return@launch
                val replacement=fixed+w.suffix.drop(w.original.length)
                if(!editor.replaceTextBeforeCursor(w.suffix,replacement)) return@launch
                words.clear();++revision
                cursor=expectedCursor+fixed.length-w.original.length
                undo=Undo(w.suffix,replacement,cursor!!,w.original,correction)
                onRevision()
                return@launch
            }
        }
    }

    fun undo(): Boolean {
        val saved=undo ?: return false
        clear()
        if(editor.cursorSelection()!=(saved.cursor to saved.cursor) ||
            !editor.replaceTextBeforeCursor(saved.replacement,saved.original)) return false
        engine()?.rejected(saved.typedWord.lowercase(Locale.ROOT),saved.fixedWord.lowercase(Locale.ROOT))
        onRevision()
        return true
    }
}
