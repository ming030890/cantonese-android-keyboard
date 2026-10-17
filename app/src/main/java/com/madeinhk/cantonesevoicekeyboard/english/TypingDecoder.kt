package com.madeinhk.cantonesevoicekeyboard.english

import org.json.JSONObject
import kotlin.math.exp
import java.util.Locale

/** UTF-16 replacement spans are restricted to the current ASCII word and one adjacent fragment. */
internal data class TypingHypothesis(
    val text: String, val spatialScore: Double, val lexicalScore: Double,
    val edits: Int, val completion: Int, val replaceBefore: Int, val literal: Boolean,
    val contextScore: Double = 0.0, val personalScore: Double = 0.0,
    val initialContextScore: Double? = null,
    val encodedTokens: Int? = null, val standardWord: Boolean = false,
    val rankingScore: Double? = null,
    val spellingLogProbability:Double?=null,val spellingCharacters:Int?=null,
) { val score get() = rankingScore ?: (spatialScore + lexicalScore + contextScore + personalScore) }
internal data class TypingRequest(val session: Long, val revision: Long, val word: String,
    val centers: FloatArray, val points: FloatArray, val left: String, val right: String)
internal data class TypingResult(val session: Long, val revision: Long, val hypotheses: List<TypingHypothesis>,
    val replacement: TypingHypothesis? = null, val confidence: Double? = null,
    val reason: String, val budgetExhausted: Boolean = false)

internal interface ContextScorer : AutoCloseable {
    /** Null means unavailable or unsupported, never a neutral probability. */
    fun scores(left: String, right: String, candidates: List<String>): List<Double?>
    /** Separate API so an older per-encoded-token scorer cannot impersonate word evidence. */
    fun wordEvidence(left: String, right: String, candidates: List<String>): List<WordEvidence>? = null
    /** Cheap exact vocabulary support, with no fallback-byte probability work. */
    fun knownSequences(candidates: List<String>): List<Boolean>? = null
    override fun close() {}
}
internal data class WordEvidence(val score: Double?, val encodedTokens: Int,
    val spellingLogProbability:Double?=null,val spellingCharacters:Int?=null)

/** Persisted, fitted coefficients. A candidate-set softmax is deliberately not used. */
internal class ReplacementPolicy private constructor(
    private val weights: DoubleArray, private val means: DoubleArray, private val scales: DoubleArray,
    private val thresholds: Map<String, Double>,
    val correctionScope: String,
    private val calibrationEdges: DoubleArray,
    private val calibrationValues: DoubleArray,
    private val rankWeights: DoubleArray? = null,
    val usesSpellingNeighborhoods: Boolean = false,
    val usesRobustTouch: Boolean = false,
    val usesKnownAlternatives: Boolean = false,
    private val forest: TypingConfidenceForest? = null,
    val usesSpellingHead:Boolean=false,
    val allowsValidWordCorrections:Boolean=false,
    val usesModelFamiliarity:Boolean=false,
    val legacyConsensusThreshold:Double?=null,
    private val minimal:MinimalTypingPolicy?=null,
) {
    val usesMinimalPolicy get() = minimal != null
    fun sameMinimalRule(other:ReplacementPolicy)=if(minimal==null) other.minimal==null
        else other.minimal?.let {minimal.sameRule(it)}==true
    val usesWordEvidence get() = rankWeights != null || minimal != null
    fun rank(h: TypingHypothesis): TypingHypothesis = minimal?.rank(h) ?: rankWeights?.let { w ->
        h.copy(rankingScore=if(usesSpellingNeighborhoods) w[0]*h.contextScore+w[1]*h.spatialScore+w[2]*h.lexicalScore-
            w[3]*h.edits-w[4]*h.completion+w[5]*(if(h.standardWord) h.encodedTokens ?: 0 else 0)+
                (if(usesSpellingHead) w[6]*(h.spellingLogProbability ?: 0.0)/(h.spellingCharacters ?: 1).coerceAtLeast(1)+w[7]*(h.spellingLogProbability ?: 0.0) else 0.0)+h.personalScore
            else w[0]*h.contextScore+w[1]*h.spatialScore+w[2]*h.lexicalScore-w[3]*h.completion+h.personalScore)
    } ?: h
    fun decide(request: TypingRequest, hypotheses: List<TypingHypothesis>, exhausted: Boolean): TypingResult {
        minimal?.let {return it.decide(request,hypotheses,exhausted,correctionScope)}
        // A failed scorer must not fall through the calibrated map's upper bin.
        if(hypotheses.any { !it.spatialScore.isFinite() || !it.lexicalScore.isFinite() ||
                !it.contextScore.isFinite() || !it.personalScore.isFinite() || !it.score.isFinite() })
            return TypingResult(request.session,request.revision,hypotheses,reason="invalid_score",budgetExhausted=exhausted)
        val ranked = hypotheses.sortedWith(compareByDescending<TypingHypothesis> { it.score }.thenBy { it.text })
        val literal = ranked.firstOrNull { it.literal }
        val top = ranked.firstOrNull { !it.literal && it.completion <= 1 &&
            (!usesWordEvidence || it.text.matches(Regex("[a-z]+(?:'[a-z]+)*"))) }
        if (literal == null || top == null || (!usesWordEvidence && top.score <= literal.score))
            return TypingResult(request.session, request.revision, ranked, reason = "literal_preferred", budgetExhausted = exhausted)
        // Search truncation is incomplete evidence, never confidence in a winner.
        if (exhausted) return TypingResult(request.session, request.revision, ranked, reason = "search_budget", budgetExhausted = true)
        val rival = ranked.filter { it !== top }.maxOfOrNull { it.score } ?: literal.score
        var features = doubleArrayOf(top.score - rival, top.score - literal.score, -top.spatialScore,
            request.word.length.toDouble(), top.contextScore - literal.contextScore, top.edits.toDouble())
        if(usesWordEvidence) {
            val tokens=literal.encodedTokens
            if(tokens==null || tokens !in 1..64)
                return TypingResult(request.session,request.revision,ranked,reason="word_evidence_unavailable")
            val editMargin=ranked.filter {it!==top && !it.literal && it.completion==0 && ' ' !in it.text}.minOfOrNull {it.edits}
                ?.minus(top.edits)?.toDouble() ?: 1.0
            features+=doubleArrayOf(top.contextScore,literal.contextScore,top.lexicalScore,literal.lexicalScore,
                top.completion.toDouble(),if(usesSpellingNeighborhoods) editMargin else if(top.standardWord) 1.0 else 0.0,
                if(request.points.any {it.isFinite()}) 1.0 else 0.0,tokens.toDouble())
        }
        if(usesSpellingHead) {
            val a=top.spellingLogProbability;val b=literal.spellingLogProbability
            val na=top.spellingCharacters;val nb=literal.spellingCharacters
            if(a==null || b==null || na==null || nb==null || na<=0 || nb<=0 || !a.isFinite() || !b.isFinite())
                return TypingResult(request.session,request.revision,ranked,reason="spelling_evidence_unavailable")
            features+=doubleArrayOf(a/na,b/nb,a-b,a/na-b/nb)
        }
        if(correctionScope=="context_revision") {
            val oldTop=top.initialContextScore;val oldLiteral=literal.initialContextScore
            if(oldTop==null || oldLiteral==null || !oldTop.isFinite() || !oldLiteral.isFinite() || request.right.isBlank())
                return TypingResult(request.session,request.revision,ranked,reason="initial_context_unavailable")
            val originalGain=oldTop-oldLiteral
            val gainIncrease=features[4]-originalGain
            // Passing time alone must never turn uncertainty into permission.
            if(gainIncrease<=0.0) return TypingResult(request.session,request.revision,ranked,reason="no_new_context_evidence")
            val followingWords=Regex("[A-Za-z]+(?:'[A-Za-z]+)*").findAll(request.right).take(2).count()
            if(followingWords==0) return TypingResult(request.session,request.revision,ranked,reason="no_following_word")
            features+=doubleArrayOf(originalGain,gainIncrease,followingWords.toDouble())
        }
        val logit = forest?.logit(features) ?: (weights[0] + features.indices.sumOf { weights[it+1] * ((features[it]-means[it])/scales[it]) })
        if(!logit.isFinite()) return TypingResult(request.session,request.revision,ranked,reason="invalid_confidence")
        val rawProbability = 1.0 / (1.0 + exp(-logit.coerceIn(-40.0,40.0)))
        val probability = if(calibrationEdges.isEmpty()) rawProbability else {
            val found=calibrationEdges.binarySearch(rawProbability)
            val index=if(found>=0) found else -found-1
            calibrationValues[index.coerceAtMost(calibrationValues.lastIndex)]
        }
        val kind = when {
            top.replaceBefore > request.word.length || ' ' in top.text -> "spacing"
            literal.lexicalScore > 0 || (usesModelFamiliarity && literal.encodedTokens==1) -> "valid_word"
            request.word.length <= 2 -> "short_word"
            else -> "spelling"
        }
        // 1.0 is the artifact's disabled-category sentinel. Calibrated step
        // mappings and floating-point sigmoid saturation can equal exactly1.
        val threshold=thresholds.getValue(kind)
        val replace = threshold<1.0 && probability>=threshold
        return TypingResult(request.session,request.revision,ranked,if(replace) top else null,probability,
            if(replace) "confident_$kind" else "uncertain_$kind")
    }
    fun requiredConfidence(word:String,literalKnown:Boolean,spacing:Boolean=false)=minimal?.let {
        if(literalKnown) it.threshold else it.nonWordThreshold
    } ?: thresholds.getValue(when {
        spacing->"spacing";literalKnown->"valid_word";word.length<=2->"short_word";else->"spelling"
    })
    companion object {
        fun load(json: String, modelSha256: String, scope: String = "boundary",
                 allowInternalTrial: Boolean = false, dictionarySha256: String? = null,
                 validitySha256: String? = null): ReplacementPolicy {
            val value=JSONObject(json)
            if(value.optString("ranker_schema") in setOf("native-v7-lm-typing-v1","native-v8-lm-typing-v1")) {
                val simple=MinimalTypingPolicy.load(value,modelSha256,dictionarySha256,validitySha256,scope,allowInternalTrial)
                return ReplacementPolicy(doubleArrayOf(),doubleArrayOf(),doubleArrayOf(),emptyMap(),scope,
                    doubleArrayOf(),doubleArrayOf(),usesSpellingNeighborhoods=true,usesRobustTouch=true,
                    usesKnownAlternatives=true,allowsValidWordCorrections=true,minimal=simple)
            }
            require(scope in setOf("boundary", "context_revision"))
            require(value.optString("correction_scope", "boundary") == scope)
            val trial=value.optBoolean("internal_trial",false)
            val head=value.optString("ranker_schema")=="native-v6-spelling-head-lm-v1"
            val known=head || value.optString("ranker_schema")=="native-v5-spelling-known-lm-v1"
            val robust=known || value.optString("ranker_schema")=="native-v4-spelling-lm-v1"
            val spelling=robust || value.optString("ranker_schema")=="native-v3-spelling-lm-v1"
            val wordEvidence=spelling || value.optString("ranker_schema")=="native-v2-word-lm-v1"
            require(value.getInt("schema_version")==1 && (value.getBoolean("heldout_approved") || (allowInternalTrial && trial)))
            if(trial) {
                require(allowInternalTrial && !value.getBoolean("production_approved"))
                require(value.getString("ranker_schema") in setOf("native-v2-raw-sum-v1","native-v2-word-lm-v1","native-v3-spelling-lm-v1","native-v4-spelling-lm-v1","native-v5-spelling-known-lm-v1","native-v6-spelling-head-lm-v1"))
                require(dictionarySha256!=null && value.getString("dictionary_sha256")==dictionarySha256)
            }
            if(wordEvidence) require(trial && validitySha256!=null && value.getString("validity_sha256")==validitySha256)
            if(known) require(value.getString("candidate_scope")=="lm_words_plus_literal_v1")
            else require(!value.has("candidate_scope"))
            val contextual=scope=="context_revision"
            require(value.getString("feature_schema")==if(head) {
                if(contextual) "typing-v6-spelling-head-revision-twentyone-features" else "typing-v6-spelling-head-eighteen-features"
            } else if(spelling) {
                if(contextual) "typing-v5-spelling-revision-seventeen-features" else "typing-v5-spelling-fourteen-features"
            } else if(wordEvidence) {
                if(contextual) "typing-v4-word-revision-seventeen-features" else "typing-v4-word-fourteen-features"
            } else if(contextual) "typing-v3-context-revision-nine-features" else "typing-v2-six-features")
            fun array(name:String,n:Int)=value.getJSONArray(name).let { a ->
                require(a.length()==n); DoubleArray(n) { a.getDouble(it).also { v -> require(v.isFinite()) } }
            }
            val size=if(head) {if(contextual) 21 else 18} else if(wordEvidence) {if(contextual) 17 else 14} else if(contextual) 9 else 6
            val weights=array("weights",size+1);val means=array("means",size);val scales=array("scales",size)
            require(scales.all { it>0 })
            val risk=value.optString("decision_policy")=="contextual-risk-v1"
            if(risk) require(trial && wordEvidence && value.getString("valid_word_policy")=="contextual")
            else require(!value.has("decision_policy") && !value.has("valid_word_policy"))
            val thresholds=listOf("spelling","valid_word","short_word","spacing").associateWith {
                value.getJSONObject("thresholds").getDouble(it).also { p -> require(p in (if(risk) 0.5 else if(scope=="context_revision") 0.999 else 0.99)..1.0) }
            }
            val familiar=value.has("literal_familiarity")
            if(familiar) require(risk && value.getString("literal_familiarity")=="dictionary_or_single_lm_token_v1")
            val consensus=if(value.has("legacy_consensus_threshold")) value.getDouble("legacy_consensus_threshold").also {
                require(risk && familiar && it.isFinite() && it>=0.0 && it<1.0)
            } else null
            require(value.getString("model_sha256").matches(Regex("[0-9a-f]{64}")) && value.getString("model_sha256")==modelSha256)
            require(value.has("calibration_edges")==value.has("calibration_values"))
            val edges=if(value.has("calibration_edges")) array("calibration_edges",value.getJSONArray("calibration_edges").length()) else doubleArrayOf()
            val probabilities=if(edges.isNotEmpty()) array("calibration_values",edges.size) else doubleArrayOf()
            if(value.has("calibration_edges")) {
                require(edges.size in 1..1024 && edges.all { it in 0.0..1.0 } && probabilities.all { it in 0.0..1.0 })
                require((1 until edges.size).all { edges[it]>edges[it-1] && probabilities[it]>=probabilities[it-1] })
            }
            val rankWeights=if(wordEvidence) array("rank_weights",if(head) 8 else if(spelling) 6 else 4).also {require(it[0]>=0.0)} else null
            val forest=if(value.has("confidence_forest")) {
                require(wordEvidence && trial && value.optString("confidence_schema")=="typing-boosted-trees-v1")
                TypingConfidenceForest.load(value.getJSONObject("confidence_forest"),size)
            } else null
            return ReplacementPolicy(weights,means,scales,thresholds,scope,edges,probabilities,rankWeights,spelling,robust,known,forest,head,risk,familiar,consensus)
        }
    }
}

internal interface TypingLearning {
    fun bonus(word: String): Double = 0.0
    fun rejected(original: String, replacement: String): Boolean = false
    fun selected(original: String, replacement: String, centers: FloatArray, points: FloatArray) {}
    fun reject(original: String, replacement: String) {}
    fun adjust(points: FloatArray): FloatArray = points
    fun clear() {}
    object None : TypingLearning
}

/** One revision at a time on the coordinator's worker; no editor access here. */
internal class JointTypingEngine(
    private val baseline: EnglishCandidateEngine,
    private val decode: (TypingRequest, String) -> JSONObject,
    private val scorer: ContextScorer?, private val policy: ReplacementPolicy?,
    private val learning: TypingLearning = TypingLearning.None,
    private val revisionPolicy: ReplacementPolicy? = null,
    private val preserveBoundaryCorrections: Boolean = false,
    private val literalWords: (String)->Boolean = {false},
) : EnglishCandidateEngine, AutoCloseable {
    init {
        require(policy==null || policy.correctionScope=="boundary")
        require(revisionPolicy==null || revisionPolicy.correctionScope=="context_revision")
    }
    private val session = sessions.incrementAndGet()
    private var revision=0L
    var lastResult: TypingResult? = null
        private set
    override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction {
        return predictWithPolicy(word,centers,points,left,right,policy)
    }
    override fun predictObserved(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
        predictWithPolicy(word.lowercase(Locale.ROOT).replace('’','\''),centers,points,left,right,policy,word)
    override val supportsContextRevision get() = revisionPolicy != null
    override val allowsQuickCodeCorrections get()=policy?.allowsValidWordCorrections==true
    override fun reconsider(word: String, centers: FloatArray, points: FloatArray, left: String, right: String): EnglishPrediction =
        if(revisionPolicy==null) EnglishPrediction() else predictWithPolicy(word,centers,points,left,right,revisionPolicy)
    override fun reconsiderObserved(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
        if(revisionPolicy==null) EnglishPrediction() else predictWithPolicy(word.lowercase(Locale.ROOT).replace('’','\''),centers,points,left,right,revisionPolicy,word)

    private fun predictWithPolicy(word: String, centers: FloatArray, points: FloatArray, left: String, right: String,
                                  activePolicy: ReplacementPolicy?,observedWord:String=word): EnglishPrediction {
        val request=TypingRequest(session,++revision,word,centers,learning.adjust(points),left,right)
        val previous=left.takeIf { it.endsWith(' ') }?.dropLast(1)?.takeLastWhile { it in 'a'..'z' }.orEmpty()
        val json=decode(request,previous)
        check(json.getInt("schemaVersion")==2 && json.getLong("session")==session && json.getLong("revision")==revision)
        val array=json.getJSONArray("hypotheses")
        val hypotheses=(0 until array.length()).map { i -> array.getJSONObject(i).let { h ->
            TypingHypothesis(h.getString("text"),h.getDouble("spatialScore"),h.getDouble("lexicalScore"),
                h.getInt("edits"),h.getInt("completion"),h.getInt("replaceBefore"),h.getBoolean("literal"))
        }}
        // Preserve literal and spacing alternatives in the bounded scoring pool.
        val eligible=if(activePolicy?.usesKnownAlternatives==true) {
            val known=runCatching {scorer?.knownSequences(hypotheses.map {it.text})}.getOrNull()
            if(known==null || known.size!=hypotheses.size) {
                lastResult=TypingResult(session,revision,hypotheses,reason="word_support_unavailable")
                return EnglishPrediction(known=literalWords(observedWord),reason="word_support_unavailable")
            }
            hypotheses.filterIndexed {i,h -> h.literal || known[i]}
        } else hypotheses
        val reserved=eligible.filter { it.literal || ' ' in it.text || it.replaceBefore>word.length }.take(8)
        val pool=(reserved+eligible).distinctBy { it.text to it.replaceBefore }.take(32)
        val wordEvidence=if(activePolicy?.usesWordEvidence==true)
            runCatching {scorer?.wordEvidence(left,right,pool.map {it.text})}.getOrNull() else null
        val context=if(activePolicy?.usesWordEvidence==true) wordEvidence?.map {it.score}?.takeUnless {
            activePolicy.usesMinimalPolicy && it.any {score->score==null || !score.isFinite()}}
            else runCatching { scorer?.scores(left,right,pool.map { it.text }) }.getOrNull()
        val initialContext=if(activePolicy?.correctionScope=="context_revision" && !activePolicy.usesMinimalPolicy) {
            if(activePolicy.usesWordEvidence) runCatching {scorer?.wordEvidence(left,"",pool.map {it.text})?.map {it.score}}.getOrNull()
            else runCatching { scorer?.scores(left,"",pool.map { it.text }) }.getOrNull()
        } else null
        val scored=pool.mapIndexed { i,h -> h.copy(contextScore=context?.getOrNull(i)?.takeIf { it.isFinite() } ?: 0.0,
            personalScore=learning.bonus(h.text),initialContextScore=initialContext?.getOrNull(i)?.takeIf { it.isFinite() },
            encodedTokens=wordEvidence?.getOrNull(i)?.encodedTokens,standardWord=literalWords(h.text))
            .copy(spellingLogProbability=wordEvidence?.getOrNull(i)?.spellingLogProbability,
                spellingCharacters=wordEvidence?.getOrNull(i)?.spellingCharacters)
            .let {activePolicy?.rank(it) ?: it} }.sortedWith(compareByDescending<TypingHypothesis> { it.score }.thenBy { it.text })
        val exhausted=json.getBoolean("budgetExhausted")
        val result=if (context==null || activePolicy==null) TypingResult(session,revision,scored,
            reason=if(context==null) "context_model_unavailable" else "calibration_required",budgetExhausted=exhausted)
        else activePolicy.decide(request,scored,exhausted)
        val supported=pool.withIndex().filter { context?.getOrNull(it.index)?.isFinite()==true }
            .map { it.value.text to it.value.replaceBefore }.toSet()
        val protectedLiteral=literalWords(observedWord) && activePolicy?.allowsValidWordCorrections!=true
        val safe=result.replacement?.takeUnless {protectedLiteral}?.takeIf { (it.text to it.replaceBefore) in supported &&
            pool.firstOrNull { h -> h.literal }?.let { literal -> (literal.text to literal.replaceBefore) in supported }==true }
            ?.takeUnless { learning.rejected(word,it.text) }
        lastResult=result.copy(replacement=safe,reason=if(protectedLiteral) "valid_literal_preserved"
            else if(result.replacement!=null && safe==null) "previously_rejected" else result.reason)
        TypingDiagnostics.update(lastResult!!.reason,points.any { it.isFinite() },scored.size,revision)
        val visible=(listOfNotNull(scored.find { it.literal })+scored).distinctBy { it.text }.take(9)
        // Retain the original rollout's fallback only with its original score
        // policy. A word-evidence policy's abstention must remain authoritative.
        val consensus=activePolicy?.legacyConsensusThreshold
        val literal=scored.firstOrNull {it.literal}
        val candidate=scored.firstOrNull {!it.literal && it.completion<=1 && it.text.matches(Regex("[a-z]+(?:'[a-z]+)*"))}
        val consensusEligible=consensus!=null && !literalWords(observedWord) && !exhausted &&
            candidate!=null && literal!=null && candidate.score>literal.score && result.confidence?.let {it.isFinite() && it>=consensus}==true
        if(safe==null && !protectedLiteral && preserveBoundaryCorrections && activePolicy?.correctionScope=="boundary" &&
            (!activePolicy.usesWordEvidence || consensusEligible)) {
            val prior=baseline.predict(word,centers,points,left,right)
            val replacement=prior.correction?.takeUnless { learning.rejected(word,it) }
            if(replacement!=null && (!activePolicy.usesWordEvidence || candidate?.text==replacement)) {
                lastResult=result.copy(reason="existing_boundary_correction",confidence=null)
                TypingDiagnostics.update("existing_boundary_correction",points.any { it.isFinite() },scored.size,revision)
                val rankedCandidates=visible.map { EnglishCandidate(it.text,it.score,-it.spatialScore,it.edits,it.completion) }
                val candidates=(listOfNotNull(rankedCandidates.firstOrNull { it.word==word },
                    prior.candidates.firstOrNull { it.word==replacement })+rankedCandidates).distinctBy { it.word }
                return EnglishPrediction(candidates.take(9),replacement,prior.plausiblePrefix,prior.known,
                    word.length,"existing_boundary_correction",null,
                    visible.associate { it.text to it.replaceBefore }+(replacement to word.length))
            }
        }
        return EnglishPrediction(visible.map { EnglishCandidate(it.text,it.score,-it.spatialScore,it.edits,it.completion) },
            safe?.text,scored.any { !it.literal },protectedLiteral || (scored.find { it.literal }?.lexicalScore?.let { it>0 } ?: false),
            safe?.replaceBefore ?: word.length,lastResult!!.reason,result.confidence,
            visible.associate { it.text to it.replaceBefore },
            reconsiderKnown=activePolicy?.allowsValidWordCorrections==true,
            requiredConfidence=activePolicy?.requiredConfidence(word,(literal?.lexicalScore ?: 0.0)>0.0 ||
                (activePolicy.usesModelFamiliarity && literal?.encodedTokens==1)) ?: .999)
    }
    override fun rankCandidates(left:String,right:String,candidates:List<String>)=baseline.rankCandidates(left,right,candidates)
    override fun selected(word:String,replacement:String,centers:FloatArray,points:FloatArray)=learning.selected(word,replacement,centers,points)
    override fun rejected(word:String,replacement:String)=learning.reject(word,replacement)
    override fun close() { scorer?.close() }
    companion object { private val sessions=java.util.concurrent.atomic.AtomicLong() }
}
