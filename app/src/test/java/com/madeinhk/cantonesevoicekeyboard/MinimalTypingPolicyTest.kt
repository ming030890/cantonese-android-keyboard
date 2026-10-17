package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MinimalTypingPolicyTest {
    private val hash="0".repeat(64)
    private fun artifact(scope:String="boundary")=JSONObject().put("schema_version",1).put("internal_trial",true)
        .put("heldout_approved",false).put("production_approved",false).put("correction_scope",scope)
        .put("ranker_schema","native-v7-lm-typing-v1").put("feature_schema","typing-v7-two-margins")
        .put("candidate_scope","lm_words_plus_literal_v1").put("same_policy_for_right_context",true)
        .put("model_sha256",hash).put("dictionary_sha256",hash).put("validity_sha256",hash)
        .put("typing_scale",1.0).put("threshold",.9).put("weights",JSONArray(listOf(-2.0,1.0,1.0)))
        .put("means",JSONArray(listOf(0.0,0.0))).put("scales",JSONArray(listOf(1.0,1.0)))
    private fun load(value:JSONObject=artifact(),allow:Boolean=true)=ReplacementPolicy.load(value.toString(),hash,
        value.getString("correction_scope"),allowInternalTrial=allow,dictionarySha256=hash,validitySha256=hash)
    private fun word(text:String,literal:Boolean,lm:Double,spatial:Double=0.0)=TypingHypothesis(text,spatial,0.0,
        if(literal) 0 else 1,0,2,literal,contextScore=lm,encodedTokens=1)
    private val request=TypingRequest(1,1,"jt",floatArrayOf(),floatArrayOf(),"until ","")

    @Test fun originalAndCompetingCandidateBothConstrainAcceptance() {
        val policy=load()
        fun decide(lm:Double,rival:Double)=policy.decide(request,listOf(word("jt",true,-8.0),
            word("it",false,lm,-1.0),word("at",false,rival,-1.0)).map(policy::rank),false)
        assertEquals("it",decide(-1.0,-8.0).replacement?.text)
        assertEquals("literal_preferred",decide(-8.0,-9.0).reason)
        assertNull("A close rival must prevent automatic replacement",decide(-5.0,-5.1).replacement)
    }

    @Test fun dictionaryTokenCountAndSpellingHeadCannotAlterMinimalDecision() {
        val policy=load();val input=listOf(word("jt",true,-8.0),word("it",false,-1.0,-1.0))
        val original=policy.decide(request,input.map(policy::rank),false)
        val irrelevant=input.map {it.copy(lexicalScore=999.0,encodedTokens=30,standardWord=true,
            personalScore=999.0,spellingLogProbability=-999.0,spellingCharacters=50)}
        val changed=policy.decide(request,irrelevant.map(policy::rank),false)
        assertEquals(original.replacement?.text,changed.replacement?.text)
        assertEquals(original.confidence,changed.confidence)
        assertEquals(policy.requiredConfidence("jt",true),policy.requiredConfidence("anything",false),0.0)
    }

    @Test fun rightContextUsesTheSameDecisionWithoutInitialScoringOrWordCountFeatures() {
        val boundary=load();val revision=load(artifact("context_revision"))
        assertTrue(boundary.sameMinimalRule(revision))
        assertFalse(boundary.sameMinimalRule(load(artifact("context_revision").put("typing_scale",2.0))))
        val input=listOf(word("jt",true,-8.0),word("it",false,-1.0,-1.0)).map(revision::rank)
        val later=revision.decide(request.copy(right=" is "),input,false)
        assertEquals("it",later.replacement?.text)
        assertEquals(boundary.decide(request,input,false).confidence,later.confidence)
        assertEquals(later.confidence,revision.decide(request.copy(right=" is more context here "),input,false).confidence)
        assertNull(revision.decide(request,input,false).replacement)
    }

    @Test fun unsupportedEvidenceExhaustionSpacingAndCompletionCannotAuthorizeCorrection() {
        val policy=load();val input=listOf(word("jt",true,-8.0),word("it",false,-1.0,-1.0)).map(policy::rank)
        assertNull(policy.decide(request,input,true).replacement)
        assertNull(policy.decide(request,input.map {it.copy(encodedTokens=null)},false).replacement)
        assertNull(policy.decide(request,input.map {it.copy(contextScore=Double.NaN)},false).replacement)
        assertNull(policy.decide(request,input.map {if(it.literal) it else it.copy(completion=1)},false).replacement)
        assertNull(policy.decide(request,input.map {if(it.literal) it else it.copy(replaceBefore=5)},false).replacement)
        assertNull(load(artifact().put("threshold",1.0)).decide(request,input,false).replacement)
    }

    @Test fun schemaRejectsUnboundOrMixedDecisionPaths() {
        for(value in listOf(artifact().put("legacy_consensus_threshold",0.0),artifact().put("confidence_forest",JSONObject()),
            artifact().put("weights",JSONArray(listOf(0.0,-1.0,1.0))),artifact().put("model_sha256","1".repeat(64)),
            artifact().put("feature_schema","typing-v4-word-fourteen-features"))) {
            try {load(value);fail("Invalid minimal policy accepted")} catch(_:IllegalArgumentException) {}
        }
        try {load(allow=false);fail("Trial activated without authorization")} catch(_:IllegalArgumentException) {}
    }

    @Test fun boundedFeatureFamiliesUseOnlyLanguageAndTypingEvidence() {
        val names=listOf("lm_over_original","typing_over_original","score_over_rival","winner_lm")
        val value=artifact().put("ranker_schema","native-v8-lm-typing-v1").put("feature_schema","typing-v8-simple-evidence")
            .put("feature_names",JSONArray(names)).put("weights",JSONArray(listOf(-2.0,1.0,1.0,1.0,0.1)))
            .put("means",JSONArray(List(4){0.0})).put("scales",JSONArray(List(4){1.0}))
        val policy=load(value)
        val input=listOf(word("jt",true,-8.0),word("it",false,-1.0,-1.0)).map(policy::rank)
        val result=policy.decide(request,input,false)
        assertEquals("it",result.replacement?.text)
        val expected=1.0/(1.0+kotlin.math.exp(-(-2.0+7.0-1.0+6.0-.1)))
        assertEquals(expected,requireNotNull(result.confidence),1e-12)
        try {load(value.put("feature_names",JSONArray(listOf("dictionary_frequency"))));fail("Unknown feature accepted")}
        catch(_:IllegalArgumentException) {}
    }

    @Test fun perCharacterMarginsNormalizeOnlyByObservedLength() {
        val value=artifact().put("ranker_schema","native-v8-lm-typing-v1").put("feature_schema","typing-v8-simple-evidence")
            .put("feature_names",JSONArray(listOf("score_over_original_per_character","score_over_rival_per_character")))
        val policy=load(value)
        val input=listOf(word("jt",true,-8.0),word("it",false,-1.0,-1.0)).map(policy::rank)
        val result=policy.decide(request,input,false)
        assertEquals(1.0/(1.0+kotlin.math.exp(-4.0)),requireNotNull(result.confidence),1e-12)
    }

    @Test fun lengthMayCalibrateScoreScaleWhileEvidenceMarginsRemainMonotone() {
        val value=artifact().put("ranker_schema","native-v8-lm-typing-v1").put("feature_schema","typing-v8-simple-evidence")
            .put("feature_names",JSONArray(listOf("score_over_original","score_over_rival","observed_length")))
            .put("weights",JSONArray(listOf(-2.0,1.0,1.0,-1.0))).put("means",JSONArray(List(3){0.0})).put("scales",JSONArray(List(3){1.0}))
        val policy=load(value)
        val input=listOf(word("jt",true,-8.0),word("it",false,-1.0,-1.0)).map(policy::rank)
        assertEquals(1.0/(1.0+kotlin.math.exp(-8.0)),requireNotNull(policy.decide(request,input,false).confidence),1e-12)
        try {load(value.put("weights",JSONArray(listOf(-2.0,-1.0,1.0,-1.0))));fail("Negative evidence coefficient accepted")}
        catch(_:IllegalArgumentException) {}
    }

    @Test fun engineReconsiderationCallsOnlyTheCurrentContextualLmOnce() {
        val scopes=mutableListOf<String>()
        val scorer=object:ContextScorer {
            override fun scores(left:String,right:String,candidates:List<String>)=error("Word API required")
            override fun knownSequences(candidates:List<String>)=candidates.map {true}
            override fun wordEvidence(left:String,right:String,candidates:List<String>):List<WordEvidence> {
                scopes+=right;return candidates.map {WordEvidence(if(it=="it") -1.0 else -8.0,1)}
            }
        }
        val baseline=object:EnglishCandidateEngine {
            override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=error("No legacy fallback")
        }
        val decode={r:TypingRequest,_:String->JSONObject().put("schemaVersion",2).put("session",r.session)
            .put("revision",r.revision).put("budgetExhausted",false).put("hypotheses",JSONArray(listOf("jt","it").map {s->
                JSONObject().put("text",s).put("spatialScore",if(s=="jt") 0.0 else -1.0).put("lexicalScore",0.0)
                    .put("edits",if(s=="jt") 0 else 1).put("completion",0).put("replaceBefore",2).put("literal",s=="jt")
            }))}
        JointTypingEngine(baseline,decode,scorer,load(),revisionPolicy=load(artifact("context_revision")),
            preserveBoundaryCorrections=true,literalWords={true}).use {engine->
            assertEquals("it",engine.reconsider("jt",floatArrayOf(),floatArrayOf(),"until "," is ").correction)
            assertEquals(listOf(" is "),scopes)
        }
    }

    @Test fun nonWordTyposCorrectWhileValidWordsRequireHigherConfidence() {
        val json=artifact().put("threshold",0.95).put("non_word_threshold",0.50).put("min_margin",1.5)
        val policy=load(json)
        val typoInput=listOf(
            TypingHypothesis("teh",0.0,0.0,0,0,3,true,contextScore=-16.0,encodedTokens=1,standardWord=false),
            TypingHypothesis("the",-1.0,0.0,1,0,3,false,contextScore=-4.0,encodedTokens=1,standardWord=true)
        ).map(policy::rank)
        val validWordInput=listOf(
            TypingHypothesis("type",0.0,0.0,0,0,4,true,contextScore=-8.0,encodedTokens=1,standardWord=true),
            TypingHypothesis("typo",-1.0,0.0,1,0,4,false,contextScore=-7.0,encodedTokens=1,standardWord=true)
        ).map(policy::rank)
        val typoResult=policy.decide(TypingRequest(1,1,"teh",floatArrayOf(),floatArrayOf(),"",""),typoInput,false)
        val validResult=policy.decide(TypingRequest(1,1,"type",floatArrayOf(),floatArrayOf(),"",""),validWordInput,false)
        assertEquals("the",typoResult.replacement?.text)
        assertNull("Valid word without overwhelming confidence must remain literal",validResult.replacement)
        assertEquals(0.95,policy.requiredConfidence("type",true),0.001)
        assertEquals(0.50,policy.requiredConfidence("teh",false),0.001)
    }
}
