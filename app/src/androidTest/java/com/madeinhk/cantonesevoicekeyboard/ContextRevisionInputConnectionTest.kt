package com.madeinhk.cantonesevoicekeyboard

import android.view.View
import android.content.Intent
import android.content.Context
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.quick.QuickInputCoordinator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real EditText/InputConnection; fixed model output tests editor mechanics only. */
@RunWith(AndroidJUnit4::class)
class ContextRevisionInputConnectionTest {
    @Test fun packagedCantonesePhraseReachesARealInputConnection() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val model=withContext(Dispatchers.Default) {NativeQuickEngine.load(context)}
        val intent=Intent(context,ImePreviewActivity::class.java).putExtra("showIme",false)
        ActivityScenario.launch<ImePreviewActivity>(intent).use {activity->
            lateinit var field:EditText;lateinit var coordinator:QuickInputCoordinator
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            activity.onActivity {
                field=checkNotNull(findEditor(it.findViewById(android.R.id.content)))
                field.setText("");field.requestFocus();field.setSelection(0)
                val info=EditorInfo();val connection=checkNotNull(field.onCreateInputConnection(info))
                coordinator=QuickInputCoordinator({model},ImeEditor({connection},{info}),scope=scope)
            }
            try {
                withContext(Dispatchers.Main) {"hirdgiovoe".forEach {coordinator.onKey(it.toString())}}
                withTimeout(5000) {
                    while(withContext(Dispatchers.Main) {coordinator.candidates.value.none {it.text=="我哋去食飯"}}) delay(5)
                }
                withContext(Dispatchers.Main) {
                    assertEquals("hirdgiovoe",field.text.toString())
                    coordinator.selectCandidate("我哋去食飯")
                    assertEquals("我哋去食飯",field.text.toString())
                    assertEquals(-1,BaseInputConnection.getComposingSpanStart(field.text))
                    assertEquals(field.length(),field.selectionStart)
                }
            } finally {withContext(Dispatchers.Main) {coordinator.close()}}
        }
    }
    @Test fun actualInternalProfileCorrectsAtSpaceAndUndoRestoresTypedWord() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val engine=withContext(Dispatchers.Default) {InternalTypingProfile.load(context,{0L},TypingLearning.None)}
        val intent=Intent(context,ImePreviewActivity::class.java).putExtra("showIme",false)
        ActivityScenario.launch<ImePreviewActivity>(intent).use { activity ->
            lateinit var field:EditText;lateinit var coordinator:EnglishInputCoordinator
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            activity.onActivity {
                field=checkNotNull(findEditor(it.findViewById(android.R.id.content)))
                field.setText("could you please let me ");field.requestFocus();field.setSelection(field.length())
                val info=EditorInfo();val connection=checkNotNull(field.onCreateInputConnection(info))
                coordinator=EnglishInputCoordinator(ImeEditor({connection},{info}),{engine},scope)
            }
            try {
                for(letter in "kbow") {
                    withContext(Dispatchers.Main) {coordinator.type(letter.toString(),null)}
                    delay(20)
                }
                // This checks editor integration after a real lookup completes.
                // Bounded timeout/rapid-input behavior has separate scenarios.
                withTimeout(2000) {
                    while(withContext(Dispatchers.Main) {coordinator.candidates.value.none {it.text=="know"}}) delay(5)
                }
                withContext(Dispatchers.Main) {
                    coordinator.settleBoundary();coordinator.finish(" ",correct=true)
                    assertEquals("could you please let me know ",field.text.toString())
                    assertTrue(coordinator.delete())
                    assertEquals("could you please let me kbow",field.text.toString())
                    assertEquals("kbow",coordinator.word)
                    coordinator.reset();field.setText("");field.setSelection(0)
                }
                for(word in listOf("typo","was")) {
                    for(letter in word) {
                        withContext(Dispatchers.Main) {coordinator.type(letter.toString(),null)}
                        delay(20)
                    }
                    withTimeout(2000) {
                        while(withContext(Dispatchers.Main) {coordinator.candidates.value.size<2}) delay(5)
                    }
                    withContext(Dispatchers.Main) {coordinator.settleBoundary();coordinator.finish(" ",correct=true)}
                }
                delay(80)
                withContext(Dispatchers.Main) {assertEquals("typo was ",field.text.toString())}
            } finally {withContext(Dispatchers.Main) {coordinator.close()}}
        }
    }

    private fun findEditor(view: View): EditText? = when(view) {
        is EditText -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findEditor(view.getChildAt(it)) }
        else -> null
    }
    private class Model(private val confident: Boolean): EnglishCandidateEngine {
        override val supportsContextRevision=true
        override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=EnglishPrediction()
        override fun reconsider(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=
            if(word=="teh" && "meeting " in right) EnglishPrediction(correction="the",confidence=if(confident) .9999 else .9,replaceBefore=3)
            else EnglishPrediction()
    }
    @Test fun realInputConnectionRevisesEarlierWordAndUndoKeepsLaterText() = runBlocking {
        runSequence(confident=true)
    }
    @Test fun realInputConnectionLeavesUncertainWordsUntouched() = runBlocking {
        runSequence(confident=false)
    }
    private suspend fun runSequence(confident: Boolean) {
        val intent=Intent(ApplicationProvider.getApplicationContext<Context>(),ImePreviewActivity::class.java).putExtra("showIme",false)
        ActivityScenario.launch<ImePreviewActivity>(intent).use { activity ->
            lateinit var field:EditText;lateinit var coordinator:EnglishInputCoordinator
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            activity.onActivity {
                field=checkNotNull(findEditor(it.findViewById(android.R.id.content)))
                field.setText("");field.requestFocus();field.setSelection(0)
                val info=EditorInfo();val connection=checkNotNull(field.onCreateInputConnection(info))
                coordinator=EnglishInputCoordinator(ImeEditor({connection},{info}),{Model(confident)},scope)
            }
            try {
                withContext(Dispatchers.Main) {
                    "teh".forEach { coordinator.type(it.toString(),null) }
                    coordinator.settleBoundary();coordinator.finish(" ",correct=true)
                    assertEquals("teh ",field.text.toString())
                    "meeting".forEach { coordinator.type(it.toString(),null) }
                    coordinator.settleBoundary();coordinator.finish(" ",correct=true)
                }
                // Give the bounded asynchronous task a real main-looper turn.
                withTimeout(2000) {
                    while(withContext(Dispatchers.Main) { confident && field.text.toString()!="the meeting " }) delay(5)
                }
                if(!confident) delay(80) // Beyond the 40ms revision deadline, with a real looper turn.
                withContext(Dispatchers.Main) {
                    assertEquals(if(confident) "the meeting " else "teh meeting ",field.text.toString())
                    assertEquals(-1,BaseInputConnection.getComposingSpanStart(field.text))
                    if(confident) {
                        assertTrue(coordinator.delete())
                        assertEquals("teh meeting ",field.text.toString())
                        assertEquals(field.length(),field.selectionStart)
                        assertEquals(-1,BaseInputConnection.getComposingSpanStart(field.text))
                    }
                }
            } finally { withContext(Dispatchers.Main) { coordinator.close() } }
        }
    }
}
