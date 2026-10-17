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
    private data class Word(val original: String, var left: String, val centers: FloatArray,
                            val points: FloatArray, var suffix: String, var followingWords:Int=0)
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
        words.forEach { it.suffix+=committed+delimiter;++it.followingWords }
        if(remember) words+=Word(original,left,centers.copyOf(),points.copyOf(),committed+delimiter)
        // The deployed typing LM accepts four right-context tokens. Keep enough
        // untouched history to reconsider a word as that evidence arrives.
        words.removeAll { it.suffix.length>256 || it.followingWords>4 }
        words.removeAll { editor.getTextBeforeCursor(it.left.length+it.suffix.length).orEmpty()!=it.left+it.suffix }
        while(words.size>5) words.removeAt(0)
        val selection=editor.cursorSelection() ?: return
        if(selection.first!=selection.second) { clear();return }
        cursor=selection.first
        job=scope.launch {
          repeat(2) {
            val snapshots=words.filter { it.followingWords>0 }
                .map { it.copy(centers=it.centers.copyOf(),points=it.points.copyOf()) }
            if(snapshots.isEmpty()) return@launch
            val id=revision;val expectedCursor=cursor ?: return@launch;val after=editor.getTextAfterCursor(256).orEmpty()
            var applied=false
            for(w in snapshots) {
                val result=withTimeoutOrNull(40L) { withContext(worker) {
                    val model=engine()
                    if(model?.supportsContextRevision!=true) null else runCatching {
                        model.reconsiderObserved(w.original,w.centers,w.points,
                            w.left,w.suffix.drop(w.original.length)+after)
                    }.getOrNull()
                } } ?: continue
                val correction=result.correction ?: continue
                if(!result.requiredConfidence.isFinite() || result.requiredConfidence !in .1..1.0 || result.requiredConfidence==1.0 ||
                    result.confidence?.let { it.isFinite() && it>=result.requiredConfidence }!=true ||
                    correction.isEmpty() || correction.any { !it.isAsciiLetter() && it!='\'' } ||
                    result.replaceBefore>w.original.length) continue
                val fixed=caseWord(correction,w.original)
                if(fixed==w.original) continue
                if(id!=revision || !canApply() || editor.cursorSelection()!=(expectedCursor to expectedCursor) ||
                    editor.getTextAfterCursor(256).orEmpty()!=after ||
                    editor.getTextBeforeCursor(w.left.length+w.suffix.length).orEmpty()!=w.left+w.suffix) return@launch
                val replacement=fixed+w.suffix.drop(w.original.length)
                if(!editor.replaceTextBeforeCursor(w.suffix,replacement)) return@launch
                // Rebase the remaining untouched words on the edit. Positions
                // use suffix lengths, since left context is capped at512chars.
                val remaining=words.mapNotNull { q ->
                    val offset=q.suffix.length-w.suffix.length
                    when {
                        offset==0 -> null
                        offset>0 -> if(q.suffix.substring(offset).startsWith(w.original))
                            q.copy(suffix=q.suffix.replaceRange(offset,offset+w.original.length,fixed)) else null
                        else -> {
                            val start=q.left.length+offset
                            if(start>=0 && q.left.substring(start).startsWith(w.original))
                                q.copy(left=q.left.replaceRange(start,start+w.original.length,fixed)) else null
                        }
                    }
                }
                words.clear();words.addAll(remaining);++revision
                cursor=expectedCursor+fixed.length-w.original.length
                undo=Undo(w.suffix,replacement,cursor!!,w.original,correction)
                onRevision()
                applied=true;break
            }
            if(!applied) return@launch
          }
        }
    }

    /** Keep rapid queued input from canceling a just-started revision forever. */
    suspend fun settle() {
        val pending=job ?: return
        try {
            if(withTimeoutOrNull(40L) {pending.join()}==null) {
                pending.cancel();if(job===pending) {job=null;++revision}
            }
        } finally {
            if(!currentCoroutineContext().isActive) {pending.cancel();if(job===pending) {job=null;++revision}}
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
