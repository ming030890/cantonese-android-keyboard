package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContextRevisionScenarioTest {
    @Test fun unsupportedRevisionDoesNotReadEditorOrScheduleWorkerTasks() = runTest {
        val editor = MemoryKeyboardEditor("teh meeting ")
        var reads = 0
        val observedEditor = object : KeyboardEditor by editor {
            override fun getTextBeforeCursor(length: Int): String {
                reads++
                return editor.getTextBeforeCursor(length)
            }
        }
        val engine = object : EnglishCandidateEngine {
            override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String) = EnglishPrediction()
        }
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                fail("An engine without revision support must not schedule revision inference")
            }
        }
        val window = ContextRevisionWindow(observedEditor, { engine }, this, worker, { true }, {})
        window.boundary("teh", "teh", " ", "", floatArrayOf(), floatArrayOf(), true)
        window.boundary("meeting", "meeting", " ", "teh ", floatArrayOf(), floatArrayOf(), true)
        advanceUntilIdle()
        assertEquals(0, reads)
        assertEquals("teh meeting ", editor.text)
    }

    @Test fun knownWordCanWaitForFourFollowingWordsWhenItsPolicyAllowsReconsideration() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val engine=object:EnglishCandidateEngine {
            override val supportsContextRevision=true
            override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
                EnglishPrediction(known=true,reconsiderKnown=true)
            override fun reconsider(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
                if(word=="form" && right.trim()=="you again this morning")
                    EnglishPrediction(correction="from",confidence=.98,requiredConfidence=.975,replaceBefore=4)
                else EnglishPrediction()
        }
        KeyboardScenario(initialText="we heard ",initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine,scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use {s->
            s.start()
            for(word in listOf("form","you","again","this")) {s.type(word);s.press(KeyboardKeyKind.Space);advanceUntilIdle()}
            assertEquals("we heard form you again this ",s.editor.text)
            s.type("morning");s.press(KeyboardKeyKind.Space);advanceUntilIdle()
            assertEquals("we heard from you again this morning ",s.editor.text)
            s.press(KeyboardKeyKind.Delete);advanceUntilIdle()
            assertEquals("we heard form you again this morning ",s.editor.text)
        }
    }
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
            KeyboardScenario(initialMode=mode,englishEngine=engine,scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
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
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine,scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
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
            KeyboardScenario(initialText="We saw ",initialMode=KeyboardInputMode.AlphaNum,englishEngine=Engine(),scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
                s.start();s.type("teh");s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                s.type("meeting");advanceUntilIdle();s.press(KeyboardKeyKind.Space)
                // Rapid typing queues behind the bounded revision. Lifecycle
                // and external-editor changes must still invalidate the edit.
                when(action) {
                    "type" -> s.type("x")
                    "hide" -> s.hide()
                    "cursor" -> { s.editor.selectionOverride=0 to 0;s.controller.onSelectionChanged(0,0,-1) }
                    "replace" -> s.editor.text="external edit "
                    "prefix" -> s.editor.text=s.editor.text.replace("We saw ","We ate ")
                }
                advanceUntilIdle()
                if(action=="type") {
                    assertEquals("We saw the meeting ",s.editor.text);assertEquals("x",s.editor.composing)
                } else assertTrue(s.editor.text.contains("teh ") || action=="replace")
            }
        }
    }

    @Test fun rapidMultiwordTypingRetainsOtherPendingTyposAfterAnEarlierRevision() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val engine=object:EnglishCandidateEngine {
            override val supportsContextRevision=true
            override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=EnglishPrediction()
            override fun reconsider(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=when {
                word=="teh" && "meting " in right -> EnglishPrediction(correction="the",confidence=.9999,replaceBefore=3)
                word=="meting" && left=="the " && "starts " in right -> EnglishPrediction(correction="meeting",confidence=.9999,replaceBefore=6)
                else -> EnglishPrediction()
            }
        }
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine,scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use {s ->
            s.start()
            for(word in listOf("teh","meting","starts")) {s.type(word);s.press(KeyboardKeyKind.Space)}
            advanceUntilIdle()
            assertEquals("the meeting starts ",s.editor.text);assertEquals("",s.editor.composing)
            s.press(KeyboardKeyKind.Delete);advanceUntilIdle()
            assertEquals("the meting starts ",s.editor.text)
        }
    }

    @Test fun explicitSelectionPunctuationAndDisabledPreferenceDoNotRevisit() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        for(action in listOf("selected","punctuation","disabled")) {
            val engine=Engine()
            KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine,scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
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

    @Test fun reconsiderationUsesOriginalTouchesAfterFollowingWordChangesGeometry() = runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);val engine=Engine()
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine,scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use { s ->
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
