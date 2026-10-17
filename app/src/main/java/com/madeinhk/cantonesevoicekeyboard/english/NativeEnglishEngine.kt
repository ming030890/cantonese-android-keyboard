package com.madeinhk.cantonesevoicekeyboard.english

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

internal data class EnglishCandidate(val word: String, val score: Double, val editCost: Double,
    val edits: Int, val completedChars: Int)
internal data class EnglishPrediction(val candidates: List<EnglishCandidate> = emptyList(),
    val correction: String? = null, val plausiblePrefix: Boolean = false, val known: Boolean = false,
    val replaceBefore: Int = 0, val reason: String = "legacy", val confidence: Double? = null,
    val replacementSpans: Map<String, Int> = emptyMap(),
    val reconsiderKnown:Boolean=false,val requiredConfidence:Double=.999)

/** Immutable snapshot in key-width units; NaN denotes absent touch evidence. */
data class EnglishTouchEvidence(val x: Float, val y: Float, val centers: FloatArray)

internal interface EnglishCandidateEngine {
    fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction
    /** Keep observed casing available to real engines, while existing engines receive canonical words. */
    fun predictObserved(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
        predict(word.lowercase(Locale.ROOT).replace('’','\''),centers,points,left,right)
    /** Requires separately approved calibration for revising already committed words. */
    val supportsContextRevision: Boolean get() = false
    /** Contextual profile can judge short English despite overlapping Quick codes. */
    val allowsQuickCodeCorrections:Boolean get()=false
    fun reconsider(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction = EnglishPrediction()
    fun reconsiderObserved(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
        reconsider(word.lowercase(Locale.ROOT).replace('’','\''),centers,points,left,right)
    fun rankCandidates(left: String, right: String, candidates: List<String>): List<String> = candidates
    fun selected(word: String, replacement: String, centers: FloatArray, points: FloatArray) {}
    fun rejected(word: String, replacement: String) {}
}

internal class NativeEnglishEngine private constructor(private val contextHandle: () -> Long) : EnglishCandidateEngine {
    private var literalWords: ((String)->Boolean) = {false}
    override fun predictObserved(word:String,centers:FloatArray,points:FloatArray,left:String,right:String):EnglishPrediction {
        val result=predict(word.lowercase(Locale.ROOT).replace('’','\''),centers,points,left,right)
        return if(literalWords(word)) result.copy(correction=null,known=true,reason="valid_literal_preserved") else result
    }
    companion object {
        fun load(context: Context, contextHandle: () -> Long = { 0L }): NativeEnglishEngine {
            System.loadLibrary("cantonese_engine")
            return fromAssets({path->context.assets.open(path).use {it.readBytes()}},EnglishLiteralLexicon.assetPath(context),contextHandle)
        }
        fun fromAssets(read:(String)->ByteArray,validityPath:String,contextHandle:()->Long={0L}):NativeEnglishEngine {
            check(nativeLoad(read("english/en.bin")))
            val lexicon=read(validityPath).decodeToString().reader().use {
                if(validityPath.endsWith("valid_words.txt")) EnglishLiteralLexicon.fromWordsReader(it) else EnglishLiteralLexicon.fromReader(it)
            }
            return NativeEnglishEngine(contextHandle).also {it.literalWords=lexicon::contains}
        }
        @JvmStatic private external fun nativeLoad(dictionary: ByteArray): Boolean
        @JvmStatic private external fun nativeSearch(word: String, centers: FloatArray, points: FloatArray,
            contextHandle: Long, left: String, right: String): String?
        @JvmStatic private external fun nativeRankCandidates(contextHandle: Long, left: String, right: String,
            candidates: Array<String>): String?
        @JvmStatic private external fun nativeLoadV2(bytes: ByteArray): Boolean
        @JvmStatic private external fun nativeDecodeV2(word: String, centers: FloatArray, points: FloatArray,
            previous: String, session: Long, revision: Long): String?
        @JvmStatic private external fun nativeLoadV3(bytes: ByteArray): Boolean
        @JvmStatic private external fun nativeDecodeV3(word: String, centers: FloatArray, points: FloatArray,
            previous: String, session: Long, revision: Long): String?
        @JvmStatic private external fun nativeLoadV4(bytes: ByteArray): Boolean
        @JvmStatic private external fun nativeDecodeV4(word: String, centers: FloatArray, points: FloatArray,
            previous: String, session: Long, revision: Long): String?
        @JvmStatic private external fun nativeSequenceEvidence(contextHandle: Long,left: String,right: String,candidates:Array<String>):String?
        @JvmStatic private external fun nativeKnownSequences(contextHandle: Long,candidates:Array<String>):String?

        fun sharedContextScorer(contextHandle: () -> Long): ContextScorer = object : ContextScorer {
            override fun knownSequences(candidates:List<String>):List<Boolean>? {
                val raw=nativeKnownSequences(contextHandle(),candidates.toTypedArray())
                if(raw==null || raw=="null") return null
                val values=JSONArray(raw);check(values.length()==candidates.size)
                return candidates.indices.map {values.getBoolean(it)}
            }
            private fun evidence(left:String,right:String,candidates:List<String>):JSONArray? {
                val raw=nativeSequenceEvidence(contextHandle(),left,right,candidates.toTypedArray())
                if(raw==null || raw=="null") return null
                return JSONArray(raw).also {check(it.length()==candidates.size)}
            }
            override fun scores(left:String,right:String,candidates:List<String>):List<Double?> {
                val scores=evidence(left,right,candidates) ?: return candidates.map {null}
                return candidates.indices.map { i -> scores.getJSONObject(i).let {
                    if(it.getBoolean("supported")) it.getDouble("meanLogProbability") else null
                }}
            }
            override fun wordEvidence(left:String,right:String,candidates:List<String>):List<WordEvidence>? {
                val evidence=evidence(left,right,candidates) ?: return null
                return candidates.indices.map { i -> evidence.getJSONObject(i).let {
                    WordEvidence(if(it.getBoolean("supported")) it.getDouble("unitLogProbability") else null,it.getInt("tokens"),
                        if(it.has("spellingLogProbability")) it.getDouble("spellingLogProbability") else null,
                        if(it.has("spellingCharacters")) it.getInt("spellingCharacters") else null)
                }}
            }
        }

        fun experimental(context: Context, contextHandle: () -> Long = { 0L },
                         scorer: ContextScorer? = null, policy: ReplacementPolicy? = null,
                         learning: TypingLearning = TypingLearning.None,
                         revisionPolicy: ReplacementPolicy? = null,
                         preserveBoundaryCorrections:Boolean=false): EnglishCandidateEngine {
            System.loadLibrary("cantonese_engine")
            return experimentalFromAssets({path->context.assets.open(path).use {it.readBytes()}},EnglishLiteralLexicon.assetPath(context),
                contextHandle,scorer,policy,learning,revisionPolicy,preserveBoundaryCorrections)
        }

        fun experimentalFromAssets(read:(String)->ByteArray,validityPath:String,contextHandle:()->Long={0L},
                         scorer:ContextScorer?=null,policy:ReplacementPolicy?=null,learning:TypingLearning=TypingLearning.None,
                         revisionPolicy:ReplacementPolicy?=null,preserveBoundaryCorrections:Boolean=false):EnglishCandidateEngine {
            val baseline=fromAssets(read,validityPath,contextHandle)
            val spelling=policy?.usesSpellingNeighborhoods==true
            val robust=policy?.usesRobustTouch==true
            require(revisionPolicy==null || (revisionPolicy.usesRobustTouch==robust && revisionPolicy.usesSpellingNeighborhoods==spelling &&
                revisionPolicy.usesKnownAlternatives==policy?.usesKnownAlternatives && revisionPolicy.usesSpellingHead==policy?.usesSpellingHead &&
                revisionPolicy.usesMinimalPolicy==policy?.usesMinimalPolicy &&
                (policy==null || revisionPolicy.sameMinimalRule(policy)) &&
                revisionPolicy.allowsValidWordCorrections==policy?.allowsValidWordCorrections))
            val dictionary=read("english/en-v2.bin")
            check(if(robust) nativeLoadV4(dictionary) else if(spelling) nativeLoadV3(dictionary) else nativeLoadV2(dictionary))
            return JointTypingEngine(baseline, { request, previous ->
                JSONObject(checkNotNull(if(robust) nativeDecodeV4(request.word,request.centers,request.points,previous,request.session,request.revision)
                    else if(spelling) nativeDecodeV3(request.word,request.centers,request.points,previous,request.session,request.revision)
                    else nativeDecodeV2(request.word, request.centers, request.points,previous, request.session, request.revision)))
            }, scorer, policy, learning, revisionPolicy,preserveBoundaryCorrections,baseline.literalWords)
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
            correction = if (literalWords(word) || json.isNull("correction")) null else json.getString("correction"),
            plausiblePrefix = json.getBoolean("plausiblePrefix"), known = json.getBoolean("known") || literalWords(word),
            reason=if(literalWords(word)) "valid_literal_preserved" else "legacy",
        )
    }

    override fun rankCandidates(left: String, right: String, candidates: List<String>): List<String> {
        val order = JSONArray(checkNotNull(nativeRankCandidates(contextHandle(), left, right, candidates.toTypedArray())))
        val indices = (0 until order.length()).map { order.getInt(it) }
        check(indices.sorted() == candidates.indices.toList()) { "Invalid native candidate order" }
        return indices.map(candidates::get)
    }
}
