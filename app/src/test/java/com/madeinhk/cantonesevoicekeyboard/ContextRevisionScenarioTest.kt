package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class ContextRevisionScenarioTest {
    private class Engine(var confidence: Double=0.9995): EnglishCandidateEngine {
        val requests=mutableListOf<Pair<String,String>>()
        val rejected=mutableListOf<Pair<String,String>>()
        val touches=mutableListOf<Pair<FloatArray,FloatArray>>()
        override val supportsContextRevision=true
        override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
            EnglishPrediction(listOf(EnglishCandidate(word,0.0,0.0,0,0),EnglishCandidate("the",1.0,1.0,1,0)))
        override fun reconsider(word:String,centers:FloatArray,points:FloatArray,left:String,right:String):EnglishPrediction {
            requests+=word to right
            touches+=centers.copyOf() to points.copyOf()
            return if(word=="teh" && right.contains("meeting ")) EnglishPrediction(correction="the",confidence=confidence,replaceBefore=3)
                else EnglishPrediction()
        }
        override fun rejected(word:String,replacement:String) { rejected+=word to replacement }
    }

    @Test fun uncertainWordWaitsForCompletedContextThenUndoRestoresItOnBothLayouts() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        for(mode in listOf(KeyboardInputMode.AlphaNum,KeyboardInputMode.Quick)) {
            val engine=Engine()
            KeyboardScenario(initialMode=mode,englishEngine=engine,jointDecoder=true,
                scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
                s.start();s.type("teh");advanceUntilIdle()
                assertEquals("teh",s.editor.composing)
                assertEquals("teh",(if(mode==KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates).first().text)
                assertEquals(KeyboardToolbarContent.Candidates,s.snapshot().toolbar())
                s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                assertEquals("teh ",s.editor.text);assertTrue(engine.requests.isEmpty())
                s.type("meeting");advanceUntilIdle()
                assertEquals("teh ",s.editor.text);assertEquals("meeting",s.editor.composing)
                s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                assertEquals("the meeting ",s.editor.text);assertEquals("",s.editor.composing)
                assertTrue(engine.requests.contains("teh" to " meeting "))
                assertTrue(s.english!!.candidates.value.isEmpty())
                s.press(KeyboardKeyKind.Delete);advanceUntilIdle()
                assertEquals("teh meeting ",s.editor.text);assertEquals("",s.editor.composing)
                assertEquals(listOf("teh" to "the"),engine.rejected)
                s.type("today");s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                assertEquals("teh meeting today ",s.editor.text)
            }
        }
    }

    @Test fun weakLaterEvidenceKeepsLiteralAndAnUnfamiliarCompanyName() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);val engine=Engine(0.998)
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine,jointDecoder=true,
            scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
            s.start();s.type("teh");s.press(KeyboardKeyKind.Space);advanceUntilIdle()
            s.type("meeting");s.press(KeyboardKeyKind.Space);advanceUntilIdle()
            assertEquals("teh meeting ",s.editor.text)
            s.type("Xentara");s.press(KeyboardKeyKind.Space);advanceUntilIdle()
            s.type("hired");s.press(KeyboardKeyKind.Space);advanceUntilIdle()
            assertEquals("teh meeting Xentara hired ",s.editor.text)
        }
    }

    @Test fun typingHideAndCursorMovementInvalidateQueuedRevisions() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        for(action in listOf("type","hide","cursor","replace","prefix")) {
            KeyboardScenario(initialText="We saw ",initialMode=KeyboardInputMode.AlphaNum,englishEngine=Engine(),jointDecoder=true,
                scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
                s.start();s.type("teh");s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                s.type("meeting");advanceUntilIdle();s.press(KeyboardKeyKind.Space)
                // Boundary commits synchronously with an already settled lookup;
                // the revision task has not run on the injected worker yet.
                when(action) {
                    "type" -> s.type("x")
                    "hide" -> s.hide()
                    "cursor" -> { s.editor.selectionOverride=0 to 0;s.controller.onSelectionChanged(0,0,-1) }
                    "replace" -> s.editor.text="external edit "
                    "prefix" -> s.editor.text=s.editor.text.replace("We saw ","We ate ")
                }
                advanceUntilIdle()
                assertTrue(s.editor.text.contains("teh ") || action=="replace")
            }
        }
    }

    @Test fun explicitSelectionPunctuationAndDisabledPreferenceDoNotRevisit() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        for(action in listOf("selected","punctuation","disabled")) {
            val engine=Engine()
            KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine,jointDecoder=true,
                scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
                s.start();s.type("teh");advanceUntilIdle()
                when(action) {
                    "selected" -> { s.choose("teh");s.press(KeyboardKeyKind.Space) }
                    "punctuation" -> s.type(".")
                    "disabled" -> { s.autocorrectLayouts=emptySet();s.press(KeyboardKeyKind.Space) }
                }
                advanceUntilIdle();s.type("meeting");s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                assertFalse(s.editor.text.startsWith("the "))
                assertTrue(engine.requests.none { it.first=="teh" })
            }
        }
    }

    @Test fun boundaryCalibrationCannotAuthorizeLaterRevision() {
        val artifact=JSONObject().put("correction_scope","boundary")
        try { ReplacementPolicy.load(artifact.toString(),"0".repeat(64),scope="context_revision");fail("Wrong calibration scope") }
        catch(_:IllegalArgumentException) {}
    }

    private fun contextualPolicy(threshold:Double=.999,mapped:Boolean=false): ReplacementPolicy {
        val artifact=JSONObject().put("schema_version",1).put("heldout_approved",true)
            .put("correction_scope","context_revision").put("feature_schema","typing-v3-context-revision-nine-features")
            .put("model_sha256","0".repeat(64))
            .put("weights",org.json.JSONArray(listOf(20.0)+List(9){0.0}))
            .put("means",org.json.JSONArray(List(9){0.0})).put("scales",org.json.JSONArray(List(9){1.0}))
            .put("thresholds",JSONObject().apply { listOf("spelling","valid_word","short_word","spacing").forEach { put(it,threshold) } })
        if(mapped) artifact.put("calibration_edges",org.json.JSONArray(listOf(.25,.75,1.0)))
            .put("calibration_values",org.json.JSONArray(listOf(.1,.9,1.0)))
        return ReplacementPolicy.load(artifact.toString(),"0".repeat(64),scope="context_revision")
    }
    @Test fun laterRevisionRequiresNewEvidenceEvenWhenClassifierIsOverconfident() {
        val policy=contextualPolicy()
        val request=TypingRequest(1,1,"teh",floatArrayOf(),floatArrayOf(),""," meeting ")
        val literal=TypingHypothesis("teh",0.0,0.0,0,0,3,true,contextScore=-2.0,initialContextScore=-2.0)
        val same=TypingHypothesis("the",-1.0,5.0,1,0,3,false,contextScore=-1.0,initialContextScore=-1.0)
        val unchanged=policy.decide(request,listOf(literal,same),false)
        assertNull(unchanged.replacement);assertEquals("no_new_context_evidence",unchanged.reason)
        assertEquals("the",policy.decide(request,listOf(literal,same.copy(contextScore=.5)),false).replacement?.text)
        assertNull(policy.decide(request,listOf(literal,same.copy(contextScore=.5,initialContextScore=null)),false).replacement)
        assertNull(policy.decide(request.copy(right=""),listOf(literal,same.copy(contextScore=.5)),false).replacement)
    }
    @Test fun disabledCorrectionCategoryStaysDisabledAtExactlyOneProbability() {
        val request=TypingRequest(1,1,"teh",floatArrayOf(),floatArrayOf(),""," meeting ")
        val literal=TypingHypothesis("teh",0.0,0.0,0,0,3,true,contextScore=-2.0,initialContextScore=-2.0)
        val corrected=TypingHypothesis("the",-1.0,5.0,1,0,3,false,contextScore=.5,initialContextScore=-1.0)
        val enabled=contextualPolicy(mapped=true).decide(request,listOf(literal,corrected),false)
        assertEquals(1.0,enabled.confidence!!,0.0);assertNotNull(enabled.replacement)
        assertNull(contextualPolicy(threshold=1.0,mapped=true).decide(request,listOf(literal,corrected),false).replacement)
    }

    @Test fun failedModelScoresCannotBecomeTheHighestCalibratedProbability() {
        val policy=contextualPolicy(mapped=true)
        val request=TypingRequest(1,1,"teh",floatArrayOf(),floatArrayOf(),""," meeting ")
        val literal=TypingHypothesis("teh",0.0,0.0,0,0,3,true,contextScore=-2.0,initialContextScore=-2.0)
        val corrected=TypingHypothesis("the",-1.0,5.0,1,0,3,false,contextScore=.5,initialContextScore=-1.0)
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            for(bad in listOf(corrected.copy(contextScore=value),corrected.copy(spatialScore=value),
                    corrected.copy(lexicalScore=value),corrected.copy(personalScore=value))) {
                val result=policy.decide(request,listOf(literal,bad),false)
                assertNull(result.replacement);assertEquals("invalid_score",result.reason)
            }
        }
    }

    @Test fun reconsiderationUsesOriginalTouchesAfterFollowingWordChangesGeometry() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);val engine=Engine()
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine,jointDecoder=true,
            scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
            val originalCenters=FloatArray(52) { it.toFloat() }
            s.start()
            "teh".forEachIndexed { i,c -> s.pressWithTouch(c.toString(),EnglishTouchEvidence(i.toFloat(),0.25f,originalCenters)) }
            advanceUntilIdle();s.press(KeyboardKeyKind.Space);advanceUntilIdle()
            "meeting".forEach { s.pressWithTouch(it.toString(),EnglishTouchEvidence(9f,9f,FloatArray(52) { 100f })) }
            advanceUntilIdle();s.press(KeyboardKeyKind.Space);advanceUntilIdle()
            assertEquals("the meeting ",s.editor.text)
            assertArrayEquals(originalCenters,engine.touches.first().first,0f)
            assertArrayEquals(floatArrayOf(0f,0.25f,1f,0.25f,2f,0.25f),engine.touches.first().second,0f)
        }
    }
}
