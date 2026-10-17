package com.madeinhk.cantonesevoicekeyboard.english

import org.json.JSONObject
import kotlin.math.exp

/** One linguistic score, one typing channel, and a small fitted acceptance rule. */
internal class MinimalTypingPolicy private constructor(
    val typingScale: Double, val threshold: Double,
    private val weights: DoubleArray, private val means: DoubleArray, private val scales: DoubleArray,
    private val featureNames:List<String>,
    val nonWordThreshold: Double = threshold,
    val minMargin: Double = 0.0,
) {
    fun sameRule(other:MinimalTypingPolicy)=typingScale==other.typingScale && threshold==other.threshold &&
        nonWordThreshold==other.nonWordThreshold && minMargin==other.minMargin &&
        weights.contentEquals(other.weights) && means.contentEquals(other.means) && scales.contentEquals(other.scales) && featureNames==other.featureNames

    fun rank(h: TypingHypothesis) = h.copy(rankingScore=h.contextScore+typingScale*h.spatialScore)

    fun decide(request: TypingRequest, hypotheses: List<TypingHypothesis>, exhausted: Boolean, scope: String): TypingResult {
        val ranked=hypotheses.sortedWith(compareByDescending<TypingHypothesis> {it.score}.thenBy {it.text})
        fun stop(reason:String)=TypingResult(request.session,request.revision,ranked,reason=reason,budgetExhausted=exhausted)
        if(exhausted) return stop("search_budget")
        if(ranked.any {!it.contextScore.isFinite() || !it.spatialScore.isFinite() || !it.score.isFinite() ||
                    it.encodedTokens==null || it.encodedTokens !in 1..64}) return stop("word_evidence_unavailable")
        if(scope=="context_revision" && request.right.isBlank()) return stop("no_following_context")
        val literal=ranked.firstOrNull {it.literal} ?: return stop("literal_unavailable")
        val alternatives=ranked.filter {!it.literal && it.completion==0 && it.replaceBefore==request.word.length &&
            it.text.matches(Regex("[a-z]+(?:'[a-z]+)*"))}
        val top=alternatives.firstOrNull() ?: return stop("no_correction_candidate")
        val margin=top.score-literal.score
        if(margin<=0.0) return stop("literal_preferred")
        val rival=alternatives.getOrNull(1) ?: literal
        val features=featureNames.map {name->when(name) {
            "score_over_original"->margin
            "score_over_rival"->top.score-rival.score
            "lm_over_original"->top.contextScore-literal.contextScore
            "typing_over_original"->top.spatialScore-literal.spatialScore
            "winner_lm"->top.contextScore
            "observed_length"->request.word.length.toDouble()
            "score_over_original_per_character"->margin/request.word.length.coerceAtLeast(1)
            "score_over_rival_per_character"->(top.score-rival.score)/request.word.length.coerceAtLeast(1)
            else->error("Validated minimal feature missing")
        }}.toDoubleArray()
        val logit=weights[0]+features.indices.sumOf {weights[it+1]*(features[it]-means[it])/scales[it]}
        if(!logit.isFinite()) return stop("invalid_confidence")
        val probability=1.0/(1.0+exp(-logit.coerceIn(-40.0,40.0)))
        val activeThreshold=if(literal.standardWord) threshold else nonWordThreshold
        val accepted=activeThreshold<1.0 && probability>=activeThreshold && margin>=minMargin
        return TypingResult(request.session,request.revision,ranked,if(accepted) top else null,probability,
            if(accepted) "confident_lm_typing" else "uncertain_lm_typing")
    }

    companion object {
        fun load(value:JSONObject,model:String,dictionary:String?,validity:String?,scope:String,allowTrial:Boolean):MinimalTypingPolicy {
            require(allowTrial && value.getBoolean("internal_trial") && !value.getBoolean("heldout_approved") &&
                !value.getBoolean("production_approved"))
            require(value.getInt("schema_version")==1 && value.getString("correction_scope")==scope &&
                scope in setOf("boundary","context_revision"))
            val v8=value.getString("ranker_schema")=="native-v8-lm-typing-v1"
            require((v8 || value.getString("ranker_schema")=="native-v7-lm-typing-v1") &&
                value.getString("feature_schema")==if(v8) "typing-v8-simple-evidence" else "typing-v7-two-margins")
            require(value.getString("candidate_scope")=="lm_words_plus_literal_v1" && value.getBoolean("same_policy_for_right_context"))
            val featureNames=if(v8) value.getJSONArray("feature_names").let {a->List(a.length()) {a.getString(it)}}
                else listOf("score_over_original","score_over_rival")
            require(featureNames in listOf(listOf("score_over_original","score_over_rival"),
                listOf("score_over_original_per_character","score_over_rival_per_character"),
                listOf("score_over_original","score_over_rival","observed_length"),
                listOf("lm_over_original","typing_over_original","score_over_rival"),
                listOf("lm_over_original","typing_over_original","score_over_rival","winner_lm")))
            require(model.matches(Regex("[0-9a-f]{64}")) && value.getString("model_sha256")==model && dictionary!=null &&
                value.getString("dictionary_sha256")==dictionary && validity!=null && value.getString("validity_sha256")==validity)
            // Reject mixed schemas instead of silently retaining a second decision path.
            require(listOf("confidence_forest","calibration_edges","calibration_values","rank_weights","thresholds",
                "literal_familiarity","legacy_consensus_threshold").none(value::has))
            fun array(name:String,size:Int)=value.getJSONArray(name).let {a->
                require(a.length()==size);DoubleArray(size) {a.getDouble(it).also {v->require(v.isFinite())}}
            }
            val weights=array("weights",featureNames.size+1);val means=array("means",featureNames.size);val scales=array("scales",featureNames.size)
            require(scales.all {it>0} && featureNames.indices.all {featureNames[it]=="observed_length" || weights[it+1]>=0})
            val beta=value.getDouble("typing_scale");val threshold=value.getDouble("threshold")
            require(beta.isFinite() && beta in 0.0..100.0 && threshold.isFinite() && threshold in .5..1.0)
            val nonWordThreshold=if(value.has("non_word_threshold")) value.getDouble("non_word_threshold").also {
                require(it.isFinite() && it in 0.1..1.0)
            } else threshold
            val minMargin=if(value.has("min_margin")) value.getDouble("min_margin").also {
                require(it.isFinite() && it >= 0.0)
            } else 0.0
            return MinimalTypingPolicy(beta,threshold,weights,means,scales,featureNames,nonWordThreshold,minMargin)
        }
    }
}
