package com.madeinhk.cantonesevoicekeyboard.english

import org.json.JSONArray
import org.json.JSONObject

/** Spelling candidates and completion ranked by the one shared keyboard LM. */
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
    private val suggest: (Long, String, String) -> EnglishPrediction = { handle, left, typed ->
        val result=JSONObject(checkNotNull(nativeSuggest(handle,left,typed)))
        val rows=result.getJSONArray("candidates")
        val candidates=(0 until rows.length()).map { i ->
            val row=rows.getJSONObject(i)
            val edits=row.getInt("edits")
            EnglishCandidate(row.getString("word"),row.getDouble("score"),2.0*edits,
                edits,row.getInt("completedChars"))
        }
        EnglishPrediction(candidates=candidates,
            correction=if(result.isNull("correction")) null else result.getString("correction"),
            known=result.getBoolean("known"),replaceBefore=typed.length,reason="shared_lm_spelling")
    },
) : EnglishCandidateEngine {
    override val supportsAutomaticCorrection = true
    override fun nextWords(left: String, right: String) = complete(contextHandle(), left, "").distinct()
    override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction =
        predictObserved(word, centers, points, left, right)

    override fun predictObserved(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction {
        if (word.isEmpty()) return EnglishPrediction(reason = "shared_lm_completion")
        val result=suggest(contextHandle(),left,word)
        val candidates=result.candidates.filter { !it.word.equals(word,ignoreCase=true) }
            .distinctBy { it.word.lowercase(java.util.Locale.ROOT) }
        return result.copy(candidates=candidates,
            correction=result.correction?.takeIf { fix -> candidates.any { it.word==fix } },
            plausiblePrefix=candidates.any {it.completedChars>0})
    }

    override fun rankCandidates(left: String, right: String, candidates: List<String>): List<String> =
        rank(contextHandle(), left, right, candidates)

    companion object {
        @JvmStatic private external fun nativeSuggest(handle: Long, left: String, typed: String): String?
        @JvmStatic private external fun nativeComplete(handle: Long, left: String, prefix: String): Array<String>?
        @JvmStatic private external fun nativeRank(handle: Long, left: String, right: String, words: Array<String>): String?
    }
}
