package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Decision mechanics only; these synthetic coefficients do not qualify a model. */
class WordEvidencePolicyTest {
    private val model="0".repeat(64)
    private val dictionary="1".repeat(64)
    private val validity="2".repeat(64)
    private fun artifact()=JSONObject().put("schema_version",1).put("heldout_approved",false)
        .put("production_approved",false).put("internal_trial",true).put("correction_scope","boundary")
        .put("feature_schema","typing-v4-word-fourteen-features").put("ranker_schema","native-v2-word-lm-v1")
        .put("model_sha256",model).put("dictionary_sha256",dictionary).put("validity_sha256",validity)
        .put("weights",JSONArray(listOf(20.0)+List(14){0.0}))
        .put("means",JSONArray(List(14){0.0})).put("scales",JSONArray(List(14){1.0}))
        .put("rank_weights",JSONArray(listOf(.5,2.0,.15,0.0)))
        .put("thresholds",JSONObject().put("spelling",.99).put("valid_word",.99).put("short_word",.99).put("spacing",1.0))
    private fun load(value:JSONObject=artifact(),hash:String?=validity)=ReplacementPolicy.load(value.toString(),model,
        allowInternalTrial=true,dictionarySha256=dictionary,validitySha256=hash)

    @Test fun changedWordEvidenceCannotUseOldCalibrationOrAnotherValiditySource() {
        for(hash in listOf(null,"3".repeat(64))) {
            try {load(hash=hash);fail("Unbound validity accepted")} catch(_:IllegalArgumentException) {}
        }
        try {load(artifact().put("feature_schema","typing-v2-six-features"));fail("Old calibration accepted")}
        catch(_:IllegalArgumentException) {}
        assertTrue(load().usesWordEvidence)
    }

    @Test fun dictionaryMembershipIsNotAnUnconditionalVetoInTheWordPolicy() {
        val policy=load()
        val request=TypingRequest(1,1,"od",floatArrayOf(),floatArrayOf(),"still a lot ","")
        val literal=policy.rank(TypingHypothesis("od",0.0,2.6,0,0,2,true,contextScore=-32.0,encodedTokens=2))
        val replacement=policy.rank(TypingHypothesis("of",-1.2,5.0,1,0,2,false,contextScore=-.13,encodedTokens=1,standardWord=true))
        assertEquals("of",policy.decide(request,listOf(literal,replacement),false).replacement?.text)
        assertEquals("word_evidence_unavailable",policy.decide(request,listOf(literal.copy(encodedTokens=null),replacement),false).reason)
        assertNull(policy.decide(request,listOf(literal,replacement),true).replacement)
        assertNull(policy.decide(request,listOf(literal,replacement.copy(contextScore=Double.NaN)),false).replacement)
    }

    @Test fun forestConfidenceRequiresItsSchemaAndOverridesTheLinearCoefficients() {
        val value=artifact().put("weights",JSONArray(listOf(-20.0)+List(14){0.0}))
            .put("confidence_forest",JSONObject("""{"features":14,"intercept":20,"learning_rate":0.5,"trees":[
                {"feature":[-2],"threshold":[-2],"left":[-1],"right":[-1],"value":[0]}]}"""))
        try {load(value);fail("Forest silently used linear confidence")} catch(_:IllegalArgumentException) {}
        value.put("confidence_schema","typing-boosted-trees-v1")
        val policy=load(value)
        val request=TypingRequest(1,1,"ks",floatArrayOf(),floatArrayOf(),"it ","")
        val literal=policy.rank(TypingHypothesis("ks",0.0,0.0,0,0,2,true,contextScore=-20.0,encodedTokens=2))
        val replacement=policy.rank(TypingHypothesis("is",-1.0,5.0,1,0,2,false,contextScore=-1.0,encodedTokens=1,standardWord=true))
        assertEquals("is",policy.decide(request,listOf(literal,replacement),false).replacement?.text)
    }

    @Test fun legacyTokenMeansCannotStandInForWordScores() {
        val scorer=object:ContextScorer {
            override fun scores(left:String,right:String,candidates:List<String>)=listOf(-2.0,-1.0)
        }
        val baseline=object:EnglishCandidateEngine {
            override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=EnglishPrediction(correction="of")
        }
        val decode={r:TypingRequest,_:String -> JSONObject().put("schemaVersion",2).put("session",r.session)
            .put("revision",r.revision).put("budgetExhausted",false).put("hypotheses",JSONArray(listOf(
                JSONObject().put("text",r.word).put("spatialScore",0.0).put("lexicalScore",0.0).put("edits",0)
                    .put("completion",0).put("replaceBefore",r.word.length).put("literal",true),
                JSONObject().put("text","of").put("spatialScore",-1.0).put("lexicalScore",5.0).put("edits",1)
                    .put("completion",0).put("replaceBefore",r.word.length).put("literal",false)))) }
        JointTypingEngine(baseline,decode,scorer,load(),preserveBoundaryCorrections=true).use {engine ->
            val result=engine.predict("od",floatArrayOf(),floatArrayOf(),"a lot ","")
            assertNull(result.correction);assertEquals("context_model_unavailable",result.reason)
        }
    }

    @Test fun knownAlternativePoolRetainsUnknownLiteralAndFailsClosedWithoutSupport() {
        val value=artifact().put("ranker_schema","native-v5-spelling-known-lm-v1")
            .put("feature_schema","typing-v5-spelling-fourteen-features")
            .put("rank_weights",JSONArray(listOf(.5,2.0,.15,1.0,0.0,0.0)))
        try {load(value);fail("Missing pool scope accepted")} catch(_:org.json.JSONException) {}
        value.put("candidate_scope","lm_words_plus_literal_v1")
        val policy=load(value)
        val baseline=object:EnglishCandidateEngine {
            override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=EnglishPrediction(correction="bad")
        }
        val decode={r:TypingRequest,_:String -> JSONObject().put("schemaVersion",2).put("session",r.session).put("revision",r.revision)
            .put("budgetExhausted",false).put("hypotheses",JSONArray(listOf("fized","fixed","qzqzqz").map {text ->
                JSONObject().put("text",text).put("spatialScore",if(text=="fized") 0.0 else -1.0).put("lexicalScore",0.0)
                    .put("edits",if(text=="fized") 0 else 1).put("completion",0).put("replaceBefore",5).put("literal",text=="fized")
            })) }
        val scorer=object:ContextScorer {
            override fun scores(left:String,right:String,candidates:List<String>)=error("Word API required")
            override fun knownSequences(candidates:List<String>)=candidates.map {it=="fixed"}
            override fun wordEvidence(left:String,right:String,candidates:List<String>):List<WordEvidence> {
                assertEquals(listOf("fized","fixed"),candidates)
                return listOf(WordEvidence(-20.0,5),WordEvidence(-1.0,1))
            }
        }
        JointTypingEngine(baseline,decode,scorer,policy,preserveBoundaryCorrections=true).use {engine ->
            val result=engine.predict("fized",floatArrayOf(),floatArrayOf(),"is ","")
            assertEquals("fixed",result.correction)
            assertEquals(setOf("fized","fixed"),result.candidates.map {it.word}.toSet())
        }
        val unavailable=object:ContextScorer {
            override fun scores(left:String,right:String,candidates:List<String>)=emptyList<Double?>()
        }
        JointTypingEngine(baseline,decode,unavailable,policy,preserveBoundaryCorrections=true).use {engine ->
            val result=engine.predict("fized",floatArrayOf(),floatArrayOf(),"is ","")
            assertNull(result.correction);assertEquals("word_support_unavailable",result.reason)
        }
    }

    @Test fun contextualRiskProfileCanCorrectARealWordWithoutRemovingItsEvidenceChecks() {
        val value=artifact().put("decision_policy","contextual-risk-v1").put("valid_word_policy","contextual")
            .put("thresholds",JSONObject().put("spelling",.95).put("short_word",.90).put("valid_word",.975).put("spacing",1.0))
        val policy=load(value)
        assertTrue(policy.allowsValidWordCorrections)
        val baseline=object:EnglishCandidateEngine {
            override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=EnglishPrediction()
        }
        val scorer=object:ContextScorer {
            override fun scores(left:String,right:String,candidates:List<String>)=error("Word evidence required")
            override fun wordEvidence(left:String,right:String,candidates:List<String>)=candidates.map {
                WordEvidence(if(it=="from") -1.0 else -9.0,1)
            }
        }
        val decode={r:TypingRequest,_:String -> JSONObject().put("schemaVersion",2).put("session",r.session).put("revision",r.revision)
            .put("budgetExhausted",false).put("hypotheses",JSONArray(listOf("form","from").map {text->
                JSONObject().put("text",text).put("spatialScore",if(text=="form") 0.0 else -.1).put("lexicalScore",5.0)
                    .put("edits",if(text=="form") 0 else 1).put("completion",0).put("replaceBefore",4).put("literal",text=="form")
            }))}
        JointTypingEngine(baseline,decode,scorer,policy,literalWords={true}).use {engine->
            val result=engine.predict("form",floatArrayOf(),floatArrayOf(),"we heard ","")
            assertEquals("from",result.correction)
            assertTrue(result.reconsiderKnown)
        }
        try {load(value.put("valid_word_policy","anything"));fail("Unspecified decision semantics accepted")}
        catch(_:IllegalArgumentException) {}
        try {load(artifact().put("thresholds",JSONObject().put("spelling",.9).put("short_word",.9).put("valid_word",.9).put("spacing",1.0)))
            fail("Old calibrated profile silently loosened")} catch(_:IllegalArgumentException) {}
    }

    @Test fun learnedSpellingHeadHasSeparateFeatureAndRankShapesAndCannotBeMissing() {
        val value=artifact().put("ranker_schema","native-v6-spelling-head-lm-v1")
            .put("candidate_scope","lm_words_plus_literal_v1").put("feature_schema","typing-v6-spelling-head-eighteen-features")
            .put("rank_weights",JSONArray(listOf(.5,2.0,.15,1.0,0.0,0.0,0.1,0.0)))
            .put("weights",JSONArray(listOf(20.0)+List(18){0.0})).put("means",JSONArray(List(18){0.0})).put("scales",JSONArray(List(18){1.0}))
        val policy=load(value);assertTrue(policy.usesSpellingHead)
        val request=TypingRequest(1,1,"fized",floatArrayOf(),floatArrayOf(),"is ","")
        val literal=TypingHypothesis("fized",0.0,0.0,0,0,5,true,contextScore=-20.0,encodedTokens=5,
            spellingLogProbability=-15.0,spellingCharacters=6)
        val correct=TypingHypothesis("fixed",-.5,5.0,1,0,5,false,contextScore=-1.0,encodedTokens=1,
            spellingLogProbability=-8.0,spellingCharacters=6)
        assertEquals("fixed",policy.decide(request,listOf(literal,correct).map(policy::rank),false).replacement?.text)
        assertEquals("spelling_evidence_unavailable",policy.decide(request,
            listOf(literal,correct.copy(spellingLogProbability=null)).map(policy::rank),false).reason)
    }
}
