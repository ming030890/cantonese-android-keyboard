package com.madeinhk.cantonesevoicekeyboard.english

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

internal data class AutocorrectEpisode(
    val timestampMillis: Long,
    val layout: String,
    val left: String,
    val typed: String,
    val autoCommitted: String,
    val target: String,
    val signal: String,
    val known: Boolean,
    val topCandidate: String? = null,
    val topScore: Double? = null,
    val topEdits: Int? = null,
    val centers: FloatArray = FloatArray(0),
    val points: FloatArray = FloatArray(0),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("ts", timestampMillis)
        put("layout", layout)
        put("left", left)
        put("typed", typed)
        put("auto_committed", autoCommitted)
        put("target", target)
        put("signal", signal)
        put("known", known)
        if (topCandidate != null) put("top_candidate", topCandidate)
        if (topScore != null && topScore.isFinite()) put("top_score", topScore)
        if (topEdits != null) put("top_edits", topEdits)
        if (centers.isNotEmpty()) {
            put("centers", JSONArray().also { arr ->
                centers.forEach { v -> arr.put(if (v.isFinite()) v.toDouble() else JSONObject.NULL) }
            })
        }
        if (points.isNotEmpty() && points.any { it.isFinite() }) {
            put("points", JSONArray().also { arr ->
                points.forEach { v -> arr.put(if (v.isFinite()) v.toDouble() else JSONObject.NULL) }
            })
        }
    }
}

/**
 * Observes user edit patterns (Undo, Undo+Edit, Backspace-Retype, Candidate Selection,
 * Cursor Inspection, and Unchallenged Commits) to produce self-labeled training episodes.
 */
internal class AutocorrectEditTracker(
    private val now: () -> Long = System::currentTimeMillis,
    private val onEpisode: (AutocorrectEpisode) -> Unit,
) {
    private data class PendingCommit(
        val timestampMillis: Long,
        val layout: String,
        val left: String,
        val typed: String,
        var committed: String,
        val known: Boolean,
        val topCandidate: String?,
        val topScore: Double?,
        val topEdits: Int?,
        val centers: FloatArray,
        val points: FloatArray,
        var undone: Boolean = false,
    )

    private var pending: PendingCommit? = null

    fun onUndo(original: String, committed: String) {
        val current = pending ?: return
        if (current.typed.equals(original, ignoreCase = true) ||
            current.committed.equals(committed, ignoreCase = true)
        ) {
            current.undone = true
        }
    }

    fun onDelayedBoundaryCorrection(original: String, fixed: String) {
        val current = pending ?: return
        if (current.typed == original && !current.undone) {
            current.committed = fixed
        }
    }

    fun onCursorWordReplaced(left: String, original: String, replacement: String, quick: Boolean) {
        val nowMs = now()
        val trimmedLeft = normalizeLeft(left)
        val current = pending
        if (current != null && current.left == trimmedLeft && current.committed == original) {
            emit(
                current,
                target = replacement,
                signal = if (current.undone) "undo_then_edit" else "cursor_inspect_repair",
                timestampMillis = nowMs,
            )
            pending = null
            return
        }
        flushPending(nowMs)
        if (original != replacement) {
            onEpisode(
                AutocorrectEpisode(
                    timestampMillis = nowMs,
                    layout = if (quick) "quick" else "english",
                    left = trimmedLeft,
                    typed = original,
                    autoCommitted = original,
                    target = replacement,
                    signal = "cursor_inspect_repair",
                    known = false,
                )
            )
        }
    }

    fun onWordCommitted(
        left: String,
        typed: String,
        committed: String,
        selected: String?,
        proposedCorrection: String?,
        known: Boolean,
        candidates: List<EnglishCandidate>,
        quick: Boolean,
        centers: FloatArray,
        points: FloatArray,
    ) {
        val nowMs = now()
        val trimmedLeft = normalizeLeft(left)
        val layout = if (quick) "quick" else "english"
        val best = candidates.firstOrNull()

        val prev = pending
        if (prev != null) {
            val withinWindow = nowMs - prev.timestampMillis in 0..EDIT_WINDOW_MS
            // Case 1: Re-committed at the exact same left context (after Undo or Backspace-Retype).
            if (withinWindow && trimmedLeft == prev.left) {
                if (prev.undone) {
                    val signal = if (committed == prev.typed) "undo_keep_literal" else "undo_then_edit"
                    emit(prev, target = committed, signal = signal, timestampMillis = nowMs)
                    pending = null
                    return
                }
                if (committed != prev.committed && isPlausibleRepair(prev.typed, committed)) {
                    emit(
                        prev,
                        target = committed,
                        signal = if (selected != null) "backspace_then_select" else "backspace_retype_repair",
                        timestampMillis = nowMs,
                    )
                    pending = null
                    return
                }
            }
            // Case 2: Partial backspace into prev.committed where the editor retained a prefix
            // (e.g. Quick mode where restoreComposingText does not merge the prefix).
            if (withinWindow && !prev.undone && trimmedLeft.startsWith(prev.left)) {
                val retainedPrefix = trimmedLeft.substring(prev.left.length)
                if (retainedPrefix.isNotEmpty() &&
                    retainedPrefix.length < prev.committed.length &&
                    retainedPrefix.all { it.isAsciiLetter() } &&
                    prev.committed.startsWith(retainedPrefix)
                ) {
                    val combined = retainedPrefix + committed
                    if (combined != prev.committed && isPlausibleRepair(prev.typed, combined)) {
                        emit(
                            prev,
                            target = combined,
                            signal = "backspace_retype_repair",
                            timestampMillis = nowMs,
                        )
                        pending = null
                        return
                    }
                }
            }
            flushPending(nowMs)
        }

        if (selected != null) {
            if (selected != typed) {
                onEpisode(
                    AutocorrectEpisode(
                        timestampMillis = nowMs,
                        layout = layout,
                        left = trimmedLeft,
                        typed = typed,
                        autoCommitted = proposedCorrection ?: typed,
                        target = selected,
                        signal = "candidate_selected",
                        known = known,
                        topCandidate = best?.word,
                        topScore = best?.score,
                        topEdits = best?.edits,
                        centers = centers.copyOf(),
                        points = points.copyOf(),
                    )
                )
            } else if (proposedCorrection != null && proposedCorrection != typed) {
                onEpisode(
                    AutocorrectEpisode(
                        timestampMillis = nowMs,
                        layout = layout,
                        left = trimmedLeft,
                        typed = typed,
                        autoCommitted = proposedCorrection,
                        target = typed,
                        signal = "candidate_keep_literal",
                        known = known,
                        topCandidate = best?.word,
                        topScore = best?.score,
                        topEdits = best?.edits,
                        centers = centers.copyOf(),
                        points = points.copyOf(),
                    )
                )
            }
            pending = null
            return
        }

        pending = PendingCommit(
            timestampMillis = nowMs,
            layout = layout,
            left = trimmedLeft,
            typed = typed,
            committed = committed,
            known = known,
            topCandidate = best?.word,
            topScore = best?.score,
            topEdits = best?.edits,
            centers = centers.copyOf(),
            points = points.copyOf(),
        )
    }

    fun onSessionFinished() {
        flushPending(now())
    }

    private fun flushPending(nowMs: Long) {
        val current = pending ?: return
        pending = null
        when {
            current.undone -> {
                emit(current, target = current.typed, signal = "undo_keep_literal", timestampMillis = nowMs)
            }
            current.committed != current.typed -> {
                emit(current, target = current.committed, signal = "accepted_correction", timestampMillis = nowMs)
            }
            current.topCandidate != null -> {
                emit(current, target = current.committed, signal = "unchallenged_keep", timestampMillis = nowMs)
            }
        }
    }

    private fun emit(commit: PendingCommit, target: String, signal: String, timestampMillis: Long) {
        onEpisode(
            AutocorrectEpisode(
                timestampMillis = timestampMillis,
                layout = commit.layout,
                left = commit.left,
                typed = commit.typed,
                autoCommitted = commit.committed,
                target = target,
                signal = signal,
                known = commit.known,
                topCandidate = commit.topCandidate,
                topScore = commit.topScore,
                topEdits = commit.topEdits,
                centers = commit.centers,
                points = commit.points,
            )
        )
    }

    companion object {
        private const val EDIT_WINDOW_MS = 30_000L
        private const val MAX_LEFT_CHARS = 128

        private fun normalizeLeft(left: String): String =
            if (left.length <= MAX_LEFT_CHARS) left else left.takeLast(MAX_LEFT_CHARS)

        internal fun isPlausibleRepair(typed: String, replacement: String): Boolean {
            val a = typed.lowercase(Locale.ROOT).replace('’', '\'')
            val b = replacement.lowercase(Locale.ROOT).replace('’', '\'')
            if (a == b || a.isEmpty() || b.isEmpty()) return false
            if (abs(a.length - b.length) > 4) return false
            val maxEdits = when {
                min(a.length, b.length) <= 3 -> 2
                else -> 3
            }
            return osaDistance(a, b) <= maxEdits || b.startsWith(a) || a.startsWith(b)
        }

        /** Optimal string alignment distance (insertion, deletion, substitution, adjacent transposition). */
        internal fun osaDistance(a: String, b: String): Int {
            val n = a.length
            val m = b.length
            if (n == 0) return m
            if (m == 0) return n
            var prev2 = IntArray(m + 1)
            var prev1 = IntArray(m + 1) { it }
            var curr = IntArray(m + 1)
            for (i in 1..n) {
                curr[0] = i
                for (j in 1..m) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var best = minOf(
                        prev1[j] + 1,
                        curr[j - 1] + 1,
                        prev1[j - 1] + cost,
                    )
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        best = min(best, prev2[j - 2] + 1)
                    }
                    curr[j] = best
                }
                val tmp = prev2
                prev2 = prev1
                prev1 = curr
                curr = tmp
            }
            return prev1[m]
        }
    }
}
