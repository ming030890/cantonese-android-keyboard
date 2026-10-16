package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Exact staged internal dictionary, models, JNI and fitted policies; no Android Context. */
class InternalTypingRealEngineTest {
    @Test fun replayReportedSentenceThroughTheEnabledProfile() {
        val library=System.getProperty("english.native.library")
        assumeNotNull("Host JNI property required",library)
        System.load(requireNotNull(library))
        fun bytes(path:String)=File("src/main/assets/$path").readBytes()
        fun hash(path:String)=MessageDigest.getInstance("SHA-256").digest(bytes(path)).joinToString(""){"%02x".format(it)}
        val native=NativeEnglishEngine::class.java
        native.getDeclaredMethod("nativeLoad",ByteArray::class.java).apply{isAccessible=true}.invoke(null,bytes("english/en.bin"))
        native.getDeclaredMethod("nativeLoadV2",ByteArray::class.java).apply{isAccessible=true}.invoke(null,bytes("english/en-v2.bin"))
        fun quick(path:String):NativeQuickEngine {
            val create=NativeQuickEngine::class.java.getDeclaredMethod("nativeCreate",ByteArray::class.java,ByteArray::class.java,
                ByteArray::class.java,ByteArray::class.java,ByteArray::class.java).apply{isAccessible=true}
            val handle=create.invoke(null,bytes("quick/quick3.bin"),bytes("quick/quick5.bin"),bytes(path),byteArrayOf(),bytes("handwriting/labels.txt")) as Long
            return NativeQuickEngine::class.java.declaredConstructors.single{it.parameterCount==1}.apply{isAccessible=true}.newInstance(handle) as NativeQuickEngine
        }
        quick("language_model/model.cng").use { old -> quick("typing/model.cng").use { model ->
            val baseline=native.declaredConstructors.single{it.parameterCount==1}.apply{isAccessible=true}.newInstance({old.englishContextHandle()}) as NativeEnglishEngine
            fun policy(path:String,scope:String)=ReplacementPolicy.load(bytes(path).decodeToString(),hash("typing/model.cng"),scope,
                allowInternalTrial=true,dictionarySha256=hash("english/en-v2.bin"))
            val decode=native.getDeclaredMethod("nativeDecodeV2",String::class.java,FloatArray::class.java,FloatArray::class.java,
                String::class.java,java.lang.Long.TYPE,java.lang.Long.TYPE).apply{isAccessible=true}
            JointTypingEngine(baseline,{r,previous->JSONObject(decode.invoke(null,r.word,r.centers,r.points,previous,r.session,r.revision) as String)},
                NativeEnglishEngine.sharedContextScorer(model::englishContextHandle),policy("typing/calibration.json","boundary"),
                revisionPolicy=policy("typing/revision_calibration.json","context_revision"),preserveBoundaryCorrections=true,
                literalWords=File("src/main/assets/english/validity/en_US.dic").reader().use {EnglishLiteralLexicon.fromReader(it)}::contains).use { engine ->
                val centers=FloatArray(52)
                listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEachIndexed { row,letters -> letters.forEachIndexed { column,c ->
                    centers[(c-'a')*2]=column+listOf(0f,.5f,1.5f)[row];centers[(c-'a')*2+1]=row.toFloat()
                }}
                for(touch in listOf(false,true)) for((word,left,right) in listOf(
                    Triple("srill","",""),Triple("srill",""," a "),Triple("srill",""," a lor "),
                    Triple("lor","still a ",""),Triple("lor","still a "," od "),Triple("lor","srill a ",""),
                    Triple("od","still a lot ",""),Triple("od","still a lot "," type "),
                    Triple("typo","",""),Triple("typo","srill a lor od ",""),Triple("typo","and ",""),
                    Triple("typo","and "," was "))) {
                    val points=if(touch) word.flatMap {c->listOf(centers[(c-'a')*2],centers[(c-'a')*2+1])}.toFloatArray() else floatArrayOf()
                    val result=if(right.isEmpty()) engine.predict(word,if(touch) centers else floatArrayOf(),points,left,right)
                        else engine.reconsider(word,if(touch) centers else floatArrayOf(),points,left,right)
                    assertNotEquals("calibration_required",result.reason)
                    println("PHONE_V51 touch=$touch input='$left[$word]$right' correction=${result.correction} reason=${result.reason} confidence=${result.confidence} candidates=${result.candidates.map{it.word}}")
                    if(word=="typo") {
                        assertNull("Correctly typed word was replaced: $result",result.correction)
                        assertTrue(result.known)
                    }
                }
            }
        }}
    }
}
