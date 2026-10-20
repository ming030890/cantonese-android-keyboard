package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the installed app's production model and JNI exports. */
@RunWith(AndroidJUnit4::class)
class SharedLmEnglishInstrumentedTest {
    @Test fun sharedModelOffersCompletionsAndCorrectsWithoutLegacyAssets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NativeQuickEngine.load(context).use { quick ->
            val english = SharedLmEnglishEngine(quick::englishContextHandle)
            fun predict(word: String) = english.predict(word, floatArrayOf(), floatArrayOf(), "", "")
            assertEquals("the", predict("teh").correction)
            assertNull(predict("Xentara").correction)
            assertNull(predict("varely").correction)
            assertTrue(predict("varely").candidates.any { it.word == "barely" })
            val prefix = predict("hel")
            assertNull(prefix.correction)
            assertTrue(prefix.candidates.any { it.word == "hello" })
            val knownMistap=english.predict("hou",floatArrayOf(),floatArrayOf(),"let me know if ","")
            assertTrue(knownMistap.known)
            assertEquals("you",knownMistap.correction)
            assertNull(english.predict("don't",floatArrayOf(),floatArrayOf(),"I ","").correction)
            assertFalse(quick.previewCandidates(3, "df", "", "").isEmpty())
        }
    }
}
