package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.NativeEnglishEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

class RobustTouchNativeTest {
    @Test fun actualJniKeepsNeighborPreferenceAtObservedKeyCenters() {
        val library=System.getProperty("english.native.library")
        assumeNotNull("Host JNI required",library)
        System.load(requireNotNull(library))
        val native=NativeEnglishEngine::class.java
        native.getDeclaredMethod("nativeLoadV4",ByteArray::class.java).apply {isAccessible=true}
            .invoke(null,File("src/main/assets/english/en-v2.bin").readBytes())
        val decode=native.getDeclaredMethod("nativeDecodeV4",String::class.java,FloatArray::class.java,FloatArray::class.java,
            String::class.java,java.lang.Long.TYPE,java.lang.Long.TYPE).apply {isAccessible=true}
        val centers=FloatArray(52)
        listOf("qwertyuiop","asdfghjkl","zxcvbnm").forEachIndexed {row,letters ->
            letters.forEachIndexed {column,c ->
                centers[(c-'a')*2]=column+listOf(0f,.5f,1.5f)[row]
                centers[(c-'a')*2+1]=row.toFloat()
            }
        }
        fun scores(points:FloatArray):Map<String,Double> {
            val json=JSONObject(decode.invoke(null,"fized",centers,points,"",1L,1L) as String)
            val hs=json.getJSONArray("hypotheses")
            return (0 until hs.length()).associate {i -> hs.getJSONObject(i).let {it.getString("text") to it.getDouble("spatialScore")}}
        }
        val absent=scores(floatArrayOf())
        val centered=scores("fized".flatMap {c -> listOf(centers[(c-'a')*2],centers[(c-'a')*2+1])}.toFloatArray())
        assertEquals(absent.getValue("fixed"),centered.getValue("fixed"),1e-6)
        assertTrue(centered.getValue("fixed")>centered.getValue("fired"))
    }
}
