package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madeinhk.cantonesevoicekeyboard.english.NativeEnglishEngine
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the packaged dictionary and JNI library, without ASR or Play asset packs. */
@RunWith(AndroidJUnit4::class)
class EnglishNativeEngineTest {
    @Test fun packagedDictionaryAndNativeWrapperCorrectClearTypos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = NativeEnglishEngine.load(context)
        for ((word, correction) in listOf("teh" to "the", "helllo" to "hello", "wprld" to "world")) {
            val result = engine.predict(word, floatArrayOf(), floatArrayOf(), "", "")
            assertEquals(word, correction, result.correction)
            assertTrue(result.candidates.size <= 8)
        }
        for (word in listOf("the", "keyboard", "aple")) {
            assertNull(engine.predict(word, floatArrayOf(), floatArrayOf(), "", "").correction)
        }
    }

    @Test fun bilingualContextReusesQuickModelAndOnlyReranksSpellingCandidates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NativeQuickEngine.load(context).use { quick ->
            val baseline = NativeEnglishEngine.load(context).predict("teh", floatArrayOf(), floatArrayOf(), "", "")
            val engine = NativeEnglishEngine.load(context, quick::englishContextHandle)
            val contextual = engine.predict("teh", floatArrayOf(), floatArrayOf(), "我想去", "")
            assertEquals(baseline.candidates.map { it.word }.toSet(), contextual.candidates.map { it.word }.toSet())
            val original = baseline.candidates.associateBy { it.word }
            contextual.candidates.forEach { candidate ->
                val bonus = candidate.score - original.getValue(candidate.word).score
                assertTrue(bonus >= -1e-8 && bonus <= 2.0 + 1e-8)
                assertEquals(original.getValue(candidate.word).editCost, candidate.editCost, 1e-8)
            }
        }
    }
}
