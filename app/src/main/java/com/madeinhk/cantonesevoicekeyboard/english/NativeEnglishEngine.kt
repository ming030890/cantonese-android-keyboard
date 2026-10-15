package com.madeinhk.cantonesevoicekeyboard.english

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

internal data class EnglishCandidate(val word: String, val score: Double, val editCost: Double,
    val edits: Int, val completedChars: Int)
internal data class EnglishPrediction(val candidates: List<EnglishCandidate> = emptyList(),
    val correction: String? = null, val plausiblePrefix: Boolean = false, val known: Boolean = false,
    val replaceBefore: Int = 0, val reason: String = "legacy", val confidence: Double? = null,
    val replacementSpans: Map<String, Int> = emptyMap())

/** Immutable snapshot in key-width units; NaN denotes absent touch evidence. */
data class EnglishTouchEvidence(val x: Float, val y: Float, val centers: FloatArray)

internal interface EnglishCandidateEngine {
    fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction
    /** Requires separately approved calibration for revising already committed words. */
    val supportsContextRevision: Boolean get() = false
    fun reconsider(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction = EnglishPrediction()
    fun rankCandidates(left: String, right: String, candidates: List<String>): List<String> = candidates
    fun selected(word: String, replacement: String, centers: FloatArray, points: FloatArray) {}
    fun rejected(word: String, replacement: String) {}
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
        @JvmStatic private external fun nativeRankCandidates(contextHandle: Long, left: String, right: String,
            candidates: Array<String>): String?
        @JvmStatic private external fun nativeLoadV2(bytes: ByteArray): Boolean
        @JvmStatic private external fun nativeDecodeV2(word: String, centers: FloatArray, points: FloatArray,
            previous: String, session: Long, revision: Long): String?
        @JvmStatic private external fun nativeSequenceEvidence(contextHandle: Long,left: String,right: String,candidates:Array<String>):String?

        fun sharedContextScorer(contextHandle: () -> Long): ContextScorer = object : ContextScorer {
            override fun scores(left:String,right:String,candidates:List<String>):List<Double?> {
                val raw=nativeSequenceEvidence(contextHandle(),left,right,candidates.toTypedArray())
                if(raw==null || raw=="null") return candidates.map { null }
                val scores=JSONArray(raw)
                check(scores.length()==candidates.size)
                return candidates.indices.map { i -> scores.getJSONObject(i).let {
                    if(it.getBoolean("supported")) it.getDouble("meanLogProbability") else null
                }}
            }
        }

        fun experimental(context: Context, contextHandle: () -> Long = { 0L },
                         scorer: ContextScorer? = null, policy: ReplacementPolicy? = null,
                         learning: TypingLearning = TypingLearning.None,
                         revisionPolicy: ReplacementPolicy? = null): EnglishCandidateEngine {
            val baseline = load(context, contextHandle)
            check(nativeLoadV2(context.assets.open("english/en-v2.bin").use { it.readBytes() }))
            return JointTypingEngine(baseline, { request, previous ->
                JSONObject(checkNotNull(nativeDecodeV2(request.word, request.centers, request.points,
                    previous, request.session, request.revision)))
            }, scorer, policy, learning, revisionPolicy)
        }
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

    override fun rankCandidates(left: String, right: String, candidates: List<String>): List<String> {
        val order = JSONArray(checkNotNull(nativeRankCandidates(contextHandle(), left, right, candidates.toTypedArray())))
        val indices = (0 until order.length()).map { order.getInt(it) }
        check(indices.sorted() == candidates.indices.toList()) { "Invalid native candidate order" }
        return indices.map(candidates::get)
    }
}
