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

/** Real packaged models/JNI and production input controller; in-memory OS/editor ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportedEatinfPipelineTest {
    private val assets=File(requireNotNull(System.getProperty("bilingual.assets","src/main/assets")))
    private fun read(path:String)=File(assets,path).readBytes()
    private fun loadLibrary() {
        if(System.getProperty("bilingual.assets")!=null)
            assertTrue("Acceptance pipeline must compile the enabled internal profile",BuildConfig.INTERNAL_TYPING_TRIAL)
        val library=System.getProperty("english.native.library")
        assumeNotNull("Actual JNI required; opt in with -PenglishNativeLibrary",library)
        System.load(requireNotNull(library))
    }
    private fun english(quick:NativeQuickEngine)=InternalTypingProfile.fromAssets(::read,
        if(File(assets,"english/validity/valid_words.txt").exists()) "english/validity/valid_words.txt" else "english/validity/en_US.dic",
        quick::englishContextHandle,TypingLearning.None)
    private fun emit(name:String,expected:String,actual:String,quality:Boolean=false) {
        println("BILINGUAL_E2E "+JSONObject().put("name",name).put("expected",expected).put("actual",actual)
            .put("passed",expected==actual).put("quality_regression",quality))
    }

    private fun centerTap(letter:Char):EnglishTouchEvidence {
        val centers=FloatArray(52)
        listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEachIndexed {row,letters->letters.forEachIndexed {column,c->
            centers[(c-'a')*2]=column+listOf(0f,.5f,1.5f)[row];centers[(c-'a')*2+1]=row.toFloat()
        }}
        val at=(letter.lowercaseChar()-'a')*2
        return EnglishTouchEvidence(centers[at],centers[at+1],centers)
    }

    @Test fun reportedEatinfSentenceReachesEditorThroughProductionPipeline() = runTest {
        loadLibrary();val dispatcher=StandardTestDispatcher(testScheduler)
        val failures=mutableListOf<String>()
        for(mode in listOf(KeyboardInputMode.AlphaNum,KeyboardInputMode.Quick))
            for(rapid in listOf(false,true)) for(touch in listOf(false,true)) {
                val quick=NativeQuickEngine.fromAssets(::read);val engine=english(quick)
                KeyboardScenario(initialMode=mode,quickEngine=quick,englishEngine=engine,jointDecoder=true,
                    scope=CoroutineScope(SupervisorJob()+dispatcher),lookupDispatcher=dispatcher).use {s ->
                    s.start()
                    for(c in "i like eatinf food .") {
                        if(c==' ') s.press(KeyboardKeyKind.Space)
                        else if(touch && c in 'a'..'z') s.pressWithTouch(c.toString(),centerTap(c))
                        else s.type(c.toString())
                        if(!rapid) advanceUntilIdle()
                    }
                    advanceUntilIdle()
                    val name="reported-eatinf/$mode/rapid=$rapid/touch=$touch"
                    val expected="i like eating food ."
                    emit(name,expected,s.editor.text,quality=true)
                    assertEquals("$name has no remaining composition","",s.editor.composing)
                    if(s.editor.text!=expected) failures+="$name: ${s.editor.text}"
                }
                advanceUntilIdle()
            }
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }

}
