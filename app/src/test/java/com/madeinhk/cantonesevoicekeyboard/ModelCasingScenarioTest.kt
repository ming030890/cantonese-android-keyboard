package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelCasingScenarioTest {
    @Test fun modelCasingIsAvailableForExplicitCursorCorrectionWithAutocorrectOff() = runTest {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        for ((mode, version) in listOf(KeyboardInputMode.AlphaNum to 3,
            KeyboardInputMode.Quick to 3, KeyboardInputMode.Quick to 5)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val quick = NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() })
            val original = "I just got an iPhone from apple."
            KeyboardScenario(initialText = original, initialMode = mode, clipboardText = null,
                quickEngine = quick, englishEngine = SharedLmEnglishEngine(quick::englishContextHandle),
                unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle),
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                englishDispatcher = dispatcher, lookupDispatcher = dispatcher).use { s ->
                s.quickVersion = version; s.autocorrectLayouts = emptySet()
                s.start(); s.moveCursor(original.indexOf("apple") + 2); runCurrent()
                val rows = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                assertTrue(rows.any { it.text == "Apple" })
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                assertEquals(original, s.editor.text + s.editor.right)
                s.choose("Apple"); runCurrent()
                assertEquals("I just got an iPhone from Apple.", s.editor.text + s.editor.right)
                assertEquals("", s.editor.composing)
            }
            runCurrent()
        }
    }

    @Test fun exactReportedSentenceRestoresBrandCasingThroughUnifiedJni() = runTest {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        for ((mode, version) in listOf(KeyboardInputMode.AlphaNum to 3,
            KeyboardInputMode.Quick to 3, KeyboardInputMode.Quick to 5)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val quick = NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() })
            KeyboardScenario(initialMode = mode, clipboardText = null, quickEngine = quick,
                englishEngine = SharedLmEnglishEngine(quick::englishContextHandle),
                unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle),
                autoCapitalizationEnabled = true,
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                englishDispatcher = dispatcher, lookupDispatcher = dispatcher).use { s ->
                s.quickVersion = version
                s.start()
                val words = "i just got an iphone from apple.".split(' ')
                words.forEachIndexed { index, word ->
                    s.type(word); runCurrent()
                    if (index < words.lastIndex) { s.press(KeyboardKeyKind.Space); runCurrent() }
                }
                assertEquals("I just got an iPhone from Apple.", s.editor.text)
                assertEquals("", s.editor.composing)
            }
            runCurrent()
        }
    }

    @Test fun modelCasingSurvivesGenerationRankingAndCommitAcrossLayouts() = runTest {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        val examples = listOf(
            Triple("", "i", "I"),
            Triple("", "a", "A"),
            Triple("", "am", "Am"),
            Triple("", "iphone", "iPhone"),
            Triple("I just got an ", "iphone", "iPhone"),
            Triple("I just got an iPhone from ", "apple", "Apple"),
            Triple("I ate an ", "apple", "apple"),
            Triple("I think I'm ", "Ok", "OK"),
            Triple("Do you want to ", "dac", "dac"),
            Triple("I didn't mean to get you ", "se", "se"),
            Triple("do you have that information ", "yt", "yt"),
            Triple("Hello. ", "iphone", "iPhone"),
            Triple("", "ebay", "eBay"),
        )
        for ((mode, version) in listOf(KeyboardInputMode.AlphaNum to 3,
            KeyboardInputMode.Quick to 3, KeyboardInputMode.Quick to 5)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            for ((left, typed, expected) in examples) {
                val quick = NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() })
                KeyboardScenario(initialText = left, initialMode = mode, clipboardText = null,
                    quickEngine = quick, englishEngine = SharedLmEnglishEngine(quick::englishContextHandle),
                    unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle),
                    autoCapitalizationEnabled = true,
                    scope = CoroutineScope(SupervisorJob() + dispatcher),
                    englishDispatcher = dispatcher, lookupDispatcher = dispatcher).use { s ->
                    s.quickVersion = version
                    s.start(); s.type(typed); runCurrent()
                    assertEquals("$mode/$version literal composition", typed, s.editor.composing)
                    val rows = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                    assertTrue("$mode/$version $left$typed offers $expected", rows.any { it.text == expected })
                    assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                    s.press(KeyboardKeyKind.Space); runCurrent()
                    assertEquals("$mode/$version $left$typed", left + expected + " ", s.editor.text)
                    assertEquals("", s.editor.composing)
                    if (expected != typed) {
                        s.press(KeyboardKeyKind.Delete); runCurrent()
                        assertEquals(left, s.editor.text)
                        assertEquals(typed, s.editor.composing)
                        s.press(KeyboardKeyKind.Space); runCurrent()
                        assertEquals(left + typed + " ", s.editor.text)
                    }
                }
                runCurrent()
            }
        }
    }
}
