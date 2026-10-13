package com.madeinhk.cantonesevoicekeyboard.english

import android.content.Context
import org.json.JSONObject

internal data class EnglishCandidate(val word: String, val score: Double, val editCost: Double,
    val edits: Int, val completedChars: Int)
internal data class EnglishPrediction(val candidates: List<EnglishCandidate> = emptyList(),
    val correction: String? = null, val plausiblePrefix: Boolean = false, val known: Boolean = false)

/** Immutable snapshot in key-width units; NaN denotes absent touch evidence. */
data class EnglishTouchEvidence(val x: Float, val y: Float, val centers: FloatArray)

internal interface EnglishCandidateEngine {
    fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction
}

internal class NativeEnglishEngine private constructor(private val contextHandle: () -> Long) : EnglishCandidateEngine {
    companion object {
        fun load(context: Context, contextHandle: () -> Long = { 0L }): NativeEnglishEngine {
            System.loadLibrary("cantonese_engine")
            check(nativeLoad(context.assets.open("english/en.bin").use { it.readBytes() }))
            return NativeEnglishEngine(contextHandle)
        }
        @JvmStatic private external fun nativeLoad(dictionary: ByteArray): Boolean
        @JvmStatic private external fun nativeSearch(word: String, centers: FloatArray, points: FloatArray,
            contextHandle: Long, left: String, right: String): String?
    }

    override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction {
        val handle = runCatching { contextHandle() }.getOrDefault(0L)
        val json = JSONObject(checkNotNull(nativeSearch(word, centers, points, handle, left, right)))
        val array = json.getJSONArray("candidates")
        return EnglishPrediction(
            candidates = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                EnglishCandidate(item.getString("word"), item.getDouble("score"),
                    item.getDouble("editCost"), item.getInt("edits"), item.getInt("completedChars"))
            },
            correction = if (json.isNull("correction")) null else json.getString("correction"),
            plausiblePrefix = json.getBoolean("plausiblePrefix"), known = json.getBoolean("known"),
        )
    }
}
