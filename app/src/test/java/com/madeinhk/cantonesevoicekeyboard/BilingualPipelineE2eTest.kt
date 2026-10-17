package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/** Packaged assets -> production loaders -> JNI -> key receiver/controller -> editor.
 * OS/editor and unrelated voice/handwriting services use scenario ports.
 * Native inference, dictionaries, policies, language routing and candidate ranking are real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BilingualPipelineE2eTest {
    private val assets=File(requireNotNull(System.getProperty("bilingual.assets","src/main/assets")))
    private fun read(path:String)=File(assets,path).readBytes()
    private fun loadLibrary() {
        if(System.getProperty("bilingual.assets")!=null)
            assertTrue("Acceptance pipeline must compile the enabled internal profile",BuildConfig.INTERNAL_TYPING_TRIAL)
        val library=System.getProperty("english.native.library")
        assumeNotNull("Actual JNI required; pipeline runner rejects skipped tests",library)
        System.load(requireNotNull(library))
    }
    private fun english(quick:NativeQuickEngine)=InternalTypingProfile.fromAssets(::read,
        if(File(assets,"english/validity/valid_words.txt").exists()) "english/validity/valid_words.txt" else "english/validity/en_US.dic",
        quick::englishContextHandle,TypingLearning.None)
    private fun emit(name:String,expected:String,actual:String,quality:Boolean=false) {
        println("BILINGUAL_E2E "+JSONObject().put("name",name).put("expected",expected).put("actual",actual)
            .put("passed",expected==actual).put("quality_regression",quality))
    }

    @Test fun englishBoundaryUndoAndUnfamiliarWordsUseThePackagedInternalProfile() = runTest {
        loadLibrary();val dispatcher=StandardTestDispatcher(testScheduler)
        for(mode in listOf(KeyboardInputMode.AlphaNum,KeyboardInputMode.Quick)) for(rapid in listOf(false,true)) for(touch in listOf(false,true)) {
            val quick=NativeQuickEngine.fromAssets(::read);val engine=english(quick)
            KeyboardScenario(initialMode=mode,quickEngine=quick,englishEngine=engine,jointDecoder=true,
                scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use {s ->
                s.start()
                for((typed,expected) in listOf("teh" to "the","helllo" to "hello","wprld" to "world",
                    "typo" to "typo","Xentara" to "Xentara")) {
                    val before=s.editor.text
                    for(letter in typed) {
                        if(touch) s.pressWithTouch(letter.toString(),centerTap(letter)) else s.type(letter.toString())
                        if(!rapid) advanceUntilIdle()
                    }
                    if(!rapid) {
                        val candidates=if(mode==KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                        assertTrue("$mode $typed offers $expected: $candidates",candidates.any {it.text==expected})
                    }
                    s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                    val name="english/$mode/rapid=$rapid/touch=$touch/$typed"
                    emit(name,before+expected+" ",s.editor.text);assertEquals(name,before+expected+" ",s.editor.text)
                    if(typed!=expected) {
                        s.press(KeyboardKeyKind.Delete);advanceUntilIdle()
                        assertEquals(before+typed,s.editor.text+s.editor.composing)
                        s.press(KeyboardKeyKind.Space);advanceUntilIdle()
                        assertEquals("Undo must preserve the user's rejected spelling",before+typed+" ",s.editor.text)
                    }
                }
            }
            advanceUntilIdle()
        }
    }

    private fun centerTap(letter:Char):EnglishTouchEvidence {
        val centers=FloatArray(52)
        listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEachIndexed {row,letters->letters.forEachIndexed {column,c->
            centers[(c-'a')*2]=column+listOf(0f,.5f,1.5f)[row];centers[(c-'a')*2+1]=row.toFloat()
        }}
        val at=(letter.lowercaseChar()-'a')*2
        return EnglishTouchEvidence(centers[at],centers[at+1],centers)
    }

    @Test fun completeCantonesePhraseIsGeneratedRankedAndExplicitlyCommitted() = runTest {
        loadLibrary();val dispatcher=StandardTestDispatcher(testScheduler)
        val quick=NativeQuickEngine.fromAssets(::read);val engine=english(quick)
        KeyboardScenario(initialMode=KeyboardInputMode.Quick,quickEngine=quick,englishEngine=engine,jointDecoder=true,
            scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use {s ->
            s.start();s.type("hirdgiovoe");advanceUntilIdle()
            val offered=s.snapshot().quickCandidates.map {it.text}
            assertTrue("Whole Cantonese phrase must be offered: $offered","我哋去食飯" in offered)
            assertEquals("",s.editor.text)
            assertEquals("hirdgiovoe",s.editor.composing)
            assertEquals("我哋去食飯",quick.candidates(3,"hirdgiovoe","","").first().text)
            s.choose("我哋去食飯");advanceUntilIdle()
            emit("cantonese/whole-phrase","我哋去食飯",s.editor.text)
            assertEquals("我哋去食飯",s.editor.text);assertEquals("",s.editor.composing)
        }
        advanceUntilIdle()
    }

    @Test fun traditionalCantoneseAndMixedEnglishReachTheEditorThroughActualCandidates() = runTest {
        loadLibrary();val dispatcher=StandardTestDispatcher(testScheduler)
        val quick=NativeQuickEngine.fromAssets(::read);val engine=english(quick)
        KeyboardScenario(initialMode=KeyboardInputMode.Quick,quickEngine=quick,englishEngine=engine,jointDecoder=true,
            scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use {s ->
            s.start()
            // Explicit Quick selection: Space must not guess a Chinese word.
            for((code,text) in listOf("hi" to "我","rd" to "哋","gi" to "去","ov" to "食","oe" to "飯")) {
                s.type(code);advanceUntilIdle()
                val offered=s.snapshot().quickCandidates.map {it.text}
                assertTrue("$code must offer $text: $offered",text in offered)
                assertTrue("Literal code must remain selectable",code in offered)
                s.choose(text);advanceUntilIdle()
            }
            emit("cantonese/traditional","我哋去食飯",s.editor.text);assertEquals("我哋去食飯",s.editor.text)
            assertEquals("",s.editor.composing)
            s.press(KeyboardKeyKind.Space);s.type("teh");advanceUntilIdle()
            val offered=s.snapshot().quickCandidates.map {it.text}
            assertTrue("English suggestion in the shared candidate bar: $offered","the" in offered)
            s.press(KeyboardKeyKind.Space);advanceUntilIdle()
            emit("mixed/cantonese-english","我哋去食飯 the ",s.editor.text)
            assertEquals("我哋去食飯 the ",s.editor.text)
            val pool=listOf("我","哋","香港","keyboard","the")
            assertEquals(pool.toSet(),engine.rankCandidates("我哋去食飯 ","",pool).toSet())
        }
        advanceUntilIdle()
    }

    @Test fun reportedSentencesProduceAnExplicitQualityReportRatherThanAMechanismPass() = runTest {
        loadLibrary();val dispatcher=StandardTestDispatcher(testScheduler)
        val fixtures=listOf(
            "you should itertate until jt ks fized" to "you should iterate until it is fixed",
            "btw more examplwa in rhis snerenxe" to "btw more examples in this sentence",
            "none kf them are fized" to "none of them are fixed",
            "srill a lor od type" to "still a lot of type",
            "what have tou done nrw cantonese and eng model" to "what have you done new cantonese and eng model",
            "isolare roor cause" to "isolate root cause",
            "btw i keep fiving you more exmapela" to "btw i keep giving you more examples")
        for(mode in listOf(KeyboardInputMode.AlphaNum,KeyboardInputMode.Quick)) for((typed,expected) in fixtures) {
            val quick=NativeQuickEngine.fromAssets(::read);val engine=english(quick)
            KeyboardScenario(initialMode=mode,quickEngine=quick,englishEngine=engine,jointDecoder=true,
                scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use {s ->
                s.start()
                for(word in typed.split(' ')) {s.type(word);s.press(KeyboardKeyKind.Space);advanceUntilIdle()}
                emit("reported/$mode/$typed",expected+" ",s.editor.text,quality=true)
                assertEquals("No uncommitted text after the final Space","",s.editor.composing)
            }
            advanceUntilIdle()
        }
        // The pipeline runner treats every failed quality row as a failing
        // acceptance gate. The ordinary mechanism suite retains diagnostic data.
    }
}
