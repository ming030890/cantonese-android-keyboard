package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine
import com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate
import com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

class SharedLmEnglishScenarioTest {
    private fun fake() = SharedLmEnglishEngine({ 7L }, { handle, _, _ ->
        assertEquals(7L, handle)
        listOf("hello", "help", "the")
    }, { _, _, _, words -> words }, { _,_,typed ->
        EnglishPrediction(candidates=listOf("hello","help","the").filter {it.startsWith(typed,true)}
            .map {EnglishCandidate(it,0.0,0.0,0,(it.length-typed.length).coerceAtLeast(0))})
    })

    @Test fun uncertainSpellingsSurviveEveryBoundaryInBothLayouts() {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            for (boundary in listOf(" ", ".", "\n")) {
                KeyboardScenario(initialMode=mode, englishEngine=fake()).use { s ->
                    s.engine.lookup={_,_,_,_->emptyList()}
                    s.start(); s.type("teh")
                    assertEquals("teh",s.editor.composing)
                    when(boundary) {
                        " " -> s.press(KeyboardKeyKind.Space)
                        "\n" -> s.press(KeyboardKeyKind.Enter)
                        else -> s.type(boundary)
                    }
                    assertEquals("teh$boundary",s.editor.text)
                    assertEquals("",s.editor.composing)
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun pendingUncertainLookupCommitsLiterallyWithoutLateRewrite() = kotlinx.coroutines.test.runTest {
        val dispatcher=kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=fake(),jointDecoder=true,
            scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+dispatcher),
            lookupDispatcher=dispatcher).use { s ->
            s.start();s.type("teh");s.press(KeyboardKeyKind.Space)
            testScheduler.advanceUntilIdle()
            assertEquals("teh ",s.editor.text)
        }
    }

    @Test fun prefixSuggestionsRequireExplicitSelection() {
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=fake()).use { s ->
            s.start();s.type("hel")
            assertEquals("",s.editor.text)
            assertEquals("hel",s.editor.composing)
            assertEquals(listOf("hel","hello","help"),s.snapshot().englishCandidates.map {it.text})
            assertEquals(KeyboardToolbarContent.Candidates,s.snapshot().toolbar())
            s.choose("hello")
            assertEquals("hello",s.editor.text)
            assertEquals("",s.editor.composing)
        }
    }

    @Test fun nextWordSelectionUsesTheSharedEngineWithoutComposition() {
        KeyboardScenario(initialText="I said ",initialMode=KeyboardInputMode.AlphaNum,englishEngine=fake()).use { s ->
            s.start()
            assertEquals(listOf("hello","help","the"),s.snapshot().englishCandidates.map {it.text})
            s.choose("hello")
            assertEquals("I said hello",s.editor.text)
            assertEquals("",s.editor.composing)
        }
    }

    private fun correcting() = SharedLmEnglishEngine({1L},{_,_,_->emptyList()},
        {_,_,_,words->words}, {_,_,typed->
            EnglishPrediction(candidates=listOf(EnglishCandidate("you",-1.0,2.0,1,0)),
                correction=if(typed=="yiu") "you" else null,reason="shared_lm_spelling")
        })

    @Test fun sharedSpellingSuggestionsCorrectOnSpaceAndUndoInBothLayouts() {
        for(mode in listOf(KeyboardInputMode.AlphaNum,KeyboardInputMode.Quick)) {
            KeyboardScenario(initialText="how are ",initialMode=mode,englishEngine=correcting()).use {s->
                s.engine.lookup={_,_,_,_->emptyList()}
                s.start();s.type("yiu")
                val words=if(mode==KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                assertTrue(words.any {it.text=="you"})
                assertEquals(KeyboardToolbarContent.Candidates,s.snapshot().toolbar())
                s.press(KeyboardKeyKind.Space)
                assertEquals("how are you ",s.editor.text)
                s.press(KeyboardKeyKind.Delete)
                assertEquals("how are ",s.editor.text)
                assertEquals("yiu",s.editor.composing)
                s.press(KeyboardKeyKind.Space)
                assertEquals("how are yiu ",s.editor.text)
            }
        }
    }

    @Test fun spellingAlternativeCanAlsoBeSelectedExplicitly() {
        KeyboardScenario(initialText="how are ",initialMode=KeyboardInputMode.AlphaNum,englishEngine=correcting()).use {s->
            s.start();s.type("yiu");s.choose("you")
            assertEquals("how are you",s.editor.text)
            assertEquals("",s.editor.composing)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun pendingSharedCorrectionFinishesBeforeFollowingKeys() = kotlinx.coroutines.test.runTest {
        val dispatcher=kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        KeyboardScenario(initialText="how are ",initialMode=KeyboardInputMode.AlphaNum,englishEngine=correcting(),
            scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+dispatcher),
            lookupDispatcher=dispatcher).use {s->
            s.start();s.type("yiu");s.press(KeyboardKeyKind.Space);s.type("today")
            testScheduler.advanceUntilIdle()
            assertEquals("how are you ",s.editor.text)
            assertEquals("today",s.editor.composing)
        }
    }

    @Test fun actualHandwritingRecognizerAcceptsTheSharedCnnAndFusion() {
        val library=System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary",library)
        System.load(requireNotNull(library))
        val type=com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognizer::class.java
        val create=type.getDeclaredMethod("nativeCreate", *Array(5) {ByteArray::class.java}).apply {isAccessible=true}
        fun read(name:String)=File("src/main/assets/$name").readBytes()
        val handle=create.invoke(null,read("handwriting/model.safetensors"),read("handwriting/labels.txt"),
            byteArrayOf(),read("language_model/model.cng"),read("language_model/handwriting_fusion.json")) as Long
        assertNotEquals(0L,handle)
        val recognizer=type.getDeclaredConstructor(Long::class.javaPrimitiveType).apply {isAccessible=true}
            .newInstance(handle)
        recognizer.use {
            it.setLanguageContext("可以","")
            // Invoke the production JNI directly: Android Log is unavailable in this host JVM.
            val predict=type.getDeclaredMethod("nativePredictStrokes",Long::class.javaPrimitiveType,
                FloatArray::class.java,IntArray::class.java,Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,Int::class.javaPrimitiveType,String::class.java).apply {isAccessible=true}
            val result=predict.invoke(null,handle,floatArrayOf(10f,50f,0f,90f,50f,100f),
                intArrayOf(2),100f,100f,3,null) as Array<*>
            assertEquals(3,result.size)
            assertTrue(result.all {c -> (c as String).isNotBlank()})
        }
    }

    @Test fun actualSharedNativeModelCompletesEnglishAndRanksQuick() {
        val library=System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary",library)
        System.load(requireNotNull(library))
        NativeQuickEngine.fromAssets(read={File("src/main/assets/$it").readBytes()}).use { quick ->
            val english=SharedLmEnglishEngine(quick::englishContextHandle)
            for(word in listOf("wastg","bokeh","Alstom","Xentara")) {
                assertNull("Unexpected automatic correction: $word",english.predict(word,floatArrayOf(),floatArrayOf(),"","").correction)
            }
            val correction=english.predict("yiu",floatArrayOf(),floatArrayOf(),"how are ","")
            assertTrue(correction.candidates.any {it.word.equals("you",true)})
            assertEquals("you",correction.correction?.lowercase())
            assertEquals("barely",english.predict("varely",floatArrayOf(),floatArrayOf(),"","").correction?.lowercase())
            assertEquals("english",english.predict("engloaj",floatArrayOf(),floatArrayOf(),"","").correction?.lowercase())
            assertEquals("the",english.predict("thr",floatArrayOf(),floatArrayOf(),"see all ","").correction?.lowercase())
            assertEquals("rubbish",english.predict("rjbbish",floatArrayOf(),floatArrayOf(),"see all ","").correction?.lowercase())
            assertEquals("trigger",english.predict("troggee",floatArrayOf(),floatArrayOf(),"","").correction?.lowercase())
            val result=english.predict("hel",floatArrayOf(),floatArrayOf(),"I said ","")
            assertFalse(result.candidates.isEmpty())
            assertTrue(result.candidates.any {it.word.equals("hello",true)})
            assertNull(result.correction)
            assertTrue(english.supportsAutomaticCorrection)
            assertFalse(quick.candidates(3,"a","我","").isEmpty())
            val dfCandidates = quick.candidates(3, "df", "", "")
            assertTrue("Expected >128 candidates for df but got ${dfCandidates.size}", dfCandidates.size > 128)
            val ofCandidates = quick.candidates(3, "of", "", "")
            assertTrue("Expected >128 candidates for of but got ${ofCandidates.size}", ofCandidates.size > 128)
            assertEquals(setOf("hello","你好"),english.rankCandidates("我想講 ","",listOf("hello","你好")).toSet())
            KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=english).use { s ->
                s.start();s.type("keyboard");s.press(KeyboardKeyKind.Space)
                assertEquals("keyboard ",s.editor.text)
                s.type("varely");s.press(KeyboardKeyKind.Space)
                assertEquals("keyboard barely ",s.editor.text)
            }
            KeyboardScenario(initialMode=KeyboardInputMode.Quick,quickEngine=quick,englishEngine=english).use { s ->
                s.start();s.type("df")
                val candidates = s.snapshot().quickCandidates
                assertTrue("Expected non-empty candidates for df", candidates.isNotEmpty())
                assertTrue("Expected Chinese candidates for df", candidates.any { c -> c.text.any { it.code in 0x4e00..0x9fff } })
            }
        }
    }
}
