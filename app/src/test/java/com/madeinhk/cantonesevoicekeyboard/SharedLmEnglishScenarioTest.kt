package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine
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
    }, { _, _, _, words -> words })

    @Test fun literalTyposSurviveEveryBoundaryInBothLayouts() {
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
    @Test fun rapidBoundaryCommitsLiterallyBeforeTheEngineLoads() = kotlinx.coroutines.test.runTest {
        val dispatcher=kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=fake(),jointDecoder=true,
            scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+dispatcher),
            lookupDispatcher=dispatcher).use { s ->
            s.start();s.type("teh");s.press(KeyboardKeyKind.Space)
            assertEquals("teh ",s.editor.text)
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
            val result=english.predict("hel",floatArrayOf(),floatArrayOf(),"I said ","")
            assertFalse(result.candidates.isEmpty())
            assertTrue(result.candidates.all {it.word.startsWith("hel",true)})
            assertNull(result.correction)
            assertFalse(english.supportsAutomaticCorrection)
            assertFalse(quick.candidates(3,"a","我","").isEmpty())
            assertEquals(setOf("hello","你好"),english.rankCandidates("我想講 ","",listOf("hello","你好")).toSet())
            KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=english).use { s ->
                s.start();s.type("teh");s.press(KeyboardKeyKind.Space)
                assertEquals("teh ",s.editor.text)
            }
        }
    }
}
