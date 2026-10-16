package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardKeyKind
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class TypingDecoderScenarioTest {
    @Test fun rapidBoundaryWaitCommitsOnceBeforeFollowingKeysOnBothLayouts() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        for(mode in listOf(KeyboardInputMode.AlphaNum,KeyboardInputMode.Quick)) {
            val engine=object:EnglishCandidateEngine {
                override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String):EnglishPrediction =
                    EnglishPrediction(correction=if(word=="eduts") "edits" else null)
                override fun rankCandidates(left:String,right:String,candidates:List<String>)=candidates
            }
            KeyboardScenario(initialMode=mode,englishEngine=engine,jointDecoder=true,
                scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
                s.start();s.type("eduts");s.press(KeyboardKeyKind.Space);s.type("next")
                assertEquals("",s.editor.text)
                advanceUntilIdle()
                assertEquals("edits ",s.editor.text);assertEquals("next",s.editor.composing)
                s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                assertEquals("edits next ",s.editor.text)
            }
        }
    }
    @Test fun slowModelTimesOutAndNeverRewritesLaterInput() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        // Suspending loader timing is exercised with a blocked worker dispatcher.
        val blocked=StandardTestDispatcher(kotlinx.coroutines.test.TestCoroutineScheduler())
        val engine=ScenarioEnglishEngine()
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine,jointDecoder=true,
            scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=blocked).use { s ->
            s.start();s.type("teh");s.press(KeyboardKeyKind.Space);s.type("x")
            advanceTimeBy(41);runCurrent()
            assertEquals("teh ",s.editor.text);assertEquals("x",s.editor.composing)
            blocked.scheduler.advanceUntilIdle();advanceUntilIdle()
            assertEquals("teh ",s.editor.text)
        }
    }
    @Test fun hidingFlushesQueuedKeysLiterallyAndCancelsCorrection() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=ScenarioEnglishEngine(),jointDecoder=true,
            scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
            s.start();s.type("teh");s.press(KeyboardKeyKind.Space);s.type("abc");s.hide();advanceUntilIdle()
            assertEquals("teh abc",s.editor.text);assertEquals("",s.editor.composing)
        }
    }
    @Test fun spacingSelectionReplacesOnlyAdjacentFragments() {
        val engine=object:EnglishCandidateEngine {
            override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
                EnglishPrediction(listOf(EnglishCandidate("keyboard",8.0,1.0,1,0)),
                    replacementSpans=mapOf("keyboard" to 9))
            override fun rankCandidates(left:String,right:String,candidates:List<String>)=candidates
        }
        KeyboardScenario(initialText="use key ",initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine).use { s ->
            s.start();s.type("board");s.choose("keyboard")
            assertEquals("use keyboard",s.editor.text)
        }
    }
    @Test fun policyRequiresApprovedCalibrationBoundToExactModel() {
        val artifact=JSONObject().put("schema_version",1).put("heldout_approved",false)
        try { ReplacementPolicy.load(artifact.toString(),"0".repeat(64));fail("Unapproved calibration loaded") } catch(_:IllegalArgumentException) {}
    }
    @Test fun internalTrialNeedsExplicitAuthorizationAndExactDictionary() {
        val model="0".repeat(64);val dictionary="1".repeat(64)
        val artifact=JSONObject().put("schema_version",1).put("heldout_approved",false)
            .put("production_approved",false).put("internal_trial",true).put("correction_scope","boundary")
            .put("feature_schema","typing-v2-six-features").put("ranker_schema","native-v2-raw-sum-v1")
            .put("model_sha256",model).put("dictionary_sha256",dictionary)
            .put("weights",org.json.JSONArray(List(7){0.0})).put("means",org.json.JSONArray(List(6){0.0}))
            .put("scales",org.json.JSONArray(List(6){1.0}))
            .put("thresholds",JSONObject().apply {listOf("spelling","valid_word","short_word","spacing").forEach {put(it,1.0)}})
        fun refused(allowed:Boolean,hash:String?) {
            try {ReplacementPolicy.load(artifact.toString(),model,allowInternalTrial=allowed,dictionarySha256=hash);fail("Wrong trial authorization accepted")}
            catch(_:IllegalArgumentException) {}
        }
        refused(false,dictionary);refused(true,null);refused(true,"2".repeat(64))
        assertEquals("boundary",ReplacementPolicy.load(artifact.toString(),model,allowInternalTrial=true,dictionarySha256=dictionary).correctionScope)
        artifact.put("production_approved",true);refused(true,dictionary)
    }
}
