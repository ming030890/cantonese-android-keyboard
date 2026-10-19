package com.madeinhk.cantonesevoicekeyboard.english

import org.json.JSONArray

/** Completion only: one shared Cantonese/English LM, with explicit selection. */
internal class SharedLmEnglishEngine(
    private val contextHandle: () -> Long,
    private val complete: (Long, String, String) -> List<String> = { handle, left, prefix ->
        nativeComplete(handle, left, prefix).orEmpty().toList()
    },
    private val rank: (Long, String, String, List<String>) -> List<String> = { handle, left, right, words ->
        val array = JSONArray(checkNotNull(nativeRank(handle, left, right, words.toTypedArray())))
        val order = (0 until array.length()).map(array::getInt)
        check(order.sorted() == words.indices.toList())
        order.map(words::get)
    },
) : EnglishCandidateEngine {
    override val supportsAutomaticCorrection = false
    override fun nextWords(left: String, right: String) = complete(contextHandle(), left, "").distinct()
    override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction =
        predictObserved(word, centers, points, left, right)

    override fun predictObserved(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction {
        if (word.isEmpty()) return EnglishPrediction(reason = "shared_lm_completion")
        val candidates = complete(contextHandle(), left, word).distinct()
            .filter { it.startsWith(word, ignoreCase = true) && !it.equals(word, ignoreCase = true) }
            .map { EnglishCandidate(it, 0.0, 0.0, 0, (it.length - word.length).coerceAtLeast(0)) }
        return EnglishPrediction(candidates = candidates, correction = null,
            plausiblePrefix = candidates.isNotEmpty(), reason = "shared_lm_completion")
    }

    override fun rankCandidates(left: String, right: String, candidates: List<String>): List<String> =
        rank(contextHandle(), left, right, candidates)

    companion object {
        @JvmStatic private external fun nativeComplete(handle: Long, left: String, prefix: String): Array<String>?
        @JvmStatic private external fun nativeRank(handle: Long, left: String, right: String, words: Array<String>): String?
    }
}
