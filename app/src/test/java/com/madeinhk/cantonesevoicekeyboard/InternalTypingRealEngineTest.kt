package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/** Exact staged internal dictionary, models, JNI and fitted policies; no Android Context. */
class InternalTypingRealEngineTest {
    @Test fun replayReportedSentenceThroughTheEnabledProfile() {
        val library=System.getProperty("english.native.library")
        assumeNotNull("Host JNI property required",library)
        System.load(requireNotNull(library))
        fun bytes(path:String)=File("src/main/assets/$path").readBytes()
        NativeQuickEngine.fromAssets(::bytes).use { quick ->
            NativeQuickEngine.fromAssets(::bytes,"typing/model.cng").use { model ->
                val scorer=NativeEnglishEngine.sharedContextScorer(model::englishContextHandle)
                assertEquals(listOf(true,false,true,false),scorer.knownSequences(listOf("still","qzqzqzqz","of them","qxzzqqx")))
                val wordScores=requireNotNull(scorer.wordEvidence("","",listOf("srill","still")))
                assertTrue(wordScores[0].encodedTokens>1)
                assertEquals(1,wordScores[1].encodedTokens)
                assertTrue("Whole-word evidence must favor the actual spelling",wordScores[0].score!!<wordScores[1].score!!)
            }
            val validity=if(File("src/main/assets/english/validity/valid_words.txt").exists())
                "english/validity/valid_words.txt" else "english/validity/en_US.dic"
            val engine=InternalTypingProfile.fromAssets(::bytes,validity,quick::englishContextHandle,TypingLearning.None) as JointTypingEngine
            engine.use {
                val centers=FloatArray(52)
                listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEachIndexed { row,letters -> letters.forEachIndexed { column,c ->
                    centers[(c-'a')*2]=column+listOf(0f,.5f,1.5f)[row];centers[(c-'a')*2+1]=row.toFloat()
                }}
                for(touch in listOf(false,true)) for((word,left,right) in listOf(
                    Triple("srill","",""),Triple("srill",""," a "),Triple("srill",""," a lor "),
                    Triple("lor","still a ",""),Triple("lor","still a "," od "),Triple("lor","srill a ",""),
                    Triple("od","still a lot ",""),Triple("od","still a lot "," type "),
                    Triple("typo","",""),Triple("typo","srill a lor od ",""),Triple("typo","and ",""),
                    Triple("typo","and "," was "),
                    Triple("itertate","you should ",""),Triple("jt","you should itertate until ",""),
                    Triple("ks","you should itertate until jt ",""),Triple("fized","none kf them are ",""),
                    Triple("examplwa","btw more ",""),Triple("rhis","btw more examplwa in ",""),
                    Triple("snerenxe","btw more examplwa in rhis ",""),Triple("kf","none ",""),
                    Triple("tou","what have ",""),Triple("nrw","what have tou done ",""),
                    Triple("isolare","",""),Triple("roor","isolare ",""),
                    Triple("fiving","btw i keep ",""),Triple("exmapela","btw i keep fiving you more ",""),
                    Triple("pleade",""," provide "),Triple("kbow","could you please let me ",""))) {
                    val points=if(touch) word.flatMap {c->listOf(centers[(c-'a')*2],centers[(c-'a')*2+1])}.toFloatArray() else floatArrayOf()
                    val started=System.nanoTime()
                    val result=if(right.isEmpty()) engine.predict(word,if(touch) centers else floatArrayOf(),points,left,right)
                        else engine.reconsider(word,if(touch) centers else floatArrayOf(),points,left,right)
                    val elapsedMs=(System.nanoTime()-started)/1_000_000.0
                    assertNotEquals("calibration_required",result.reason)
                    // Deliberately uncapped: distinguish policy abstention from
                    // the coordinator discarding an otherwise correct result.
                    println("STAGED_PROFILE_UNCAPPED touch=$touch elapsedMs=$elapsedMs input='$left[$word]$right' correction=${result.correction} reason=${result.reason} confidence=${result.confidence} candidates=${result.candidates.map{it.word}}")
                    if(word=="typo") {
                        assertNull("Correctly typed word was replaced: $result",result.correction)
                        assertTrue(result.known)
                    }
                }
            }
        }
    }
}
