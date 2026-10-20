package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Controlled scheduling, not a device-speed benchmark. Exercises real key/controller paths. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutocorrectDeadlineScenarioTest {
    private class DelayedWorker(val scope:CoroutineScope,val delayMs:Long):CoroutineDispatcher() {
        override fun dispatch(context:CoroutineContext,block:Runnable) {
            scope.launch {delay(delayMs);block.run()}
        }
    }

    @Test fun boundaryDeadlineDependsOnRemainingWorkAtSpace() = runTest {
        for(mode in listOf(KeyboardInputMode.AlphaNum,KeyboardInputMode.Quick))
            for(latency in listOf(10L,60L)) for(pauseBeforeSpace in listOf(0L,80L)) {
                val ui=StandardTestDispatcher(testScheduler);val scope=CoroutineScope(SupervisorJob()+ui)
                val engine=object:EnglishCandidateEngine {
                    override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
                        EnglishPrediction(correction=if(word=="teh") "the" else null,replaceBefore=word.length)
                }
                try {
                    KeyboardScenario(initialMode=mode,englishEngine=engine,scope=scope,
                        lookupDispatcher=DelayedWorker(scope,latency)).use {s ->
                        s.start();s.type("teh");runCurrent()
                        advanceTimeBy(pauseBeforeSpace);runCurrent()
                        s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                        val expected=if(latency<40 || pauseBeforeSpace>=latency) "the " else "teh "
                        assertEquals("$mode latency=$latency pause=$pauseBeforeSpace",expected,s.editor.text)
                        s.type("x");advanceUntilIdle()
                        assertEquals(expected,s.editor.text);assertEquals("x",s.editor.composing)
                    }
                } finally {scope.cancel();runCurrent()}
            }
    }

    @Test fun laterContextDeadlineCanDiscardAnOtherwiseConfidentCorrection() = runTest {
        for(latency in listOf(10L,60L)) {
            val ui=StandardTestDispatcher(testScheduler);val scope=CoroutineScope(SupervisorJob()+ui)
            val engine=object:EnglishCandidateEngine {
                override val supportsContextRevision=true
                override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=EnglishPrediction()
                override fun reconsider(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
                    EnglishPrediction(correction=if(word=="teh") "the" else null,confidence=.9999,replaceBefore=word.length)
            }
            try {
                KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=engine,scope=scope,lookupDispatcher=DelayedWorker(scope,latency)).use {s ->
                    s.start();s.type("teh");advanceUntilIdle();s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                    s.type("meeting");advanceUntilIdle();s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                    assertEquals(if(latency<40) "the meeting " else "teh meeting ",s.editor.text)
                }
            } finally {scope.cancel();runCurrent()}
        }
    }
}
