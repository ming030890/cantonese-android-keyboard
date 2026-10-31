package com.madeinhk.cantonesevoicekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CursorWordCorrectionScenarioTest {
    @Test fun typingDismissesManualAlternativesWithAutomaticCorrectionOff() {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            KeyboardScenario(initialText = "Fix teh word.", initialMode = mode,
                englishEngine = ScenarioEnglishEngine()).use { s ->
                s.autocorrectLayouts = emptySet()
                s.start(); s.moveCursor(5)
                assertTrue(s.english!!.inspectingWord)
                s.type("x")
                assertFalse(s.english.inspectingWord)
                assertEquals("Fix txeh word.", s.editor.text + s.editor.composing + s.editor.right)
                val rows = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                assertFalse(rows.any { it.text == "the" })
            }
        }
    }

    @Test fun manualCorrectionWorksWithAutomaticCorrectionOffAndWhenOpeningInsideWord() = runTest {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            for (openInsideWord in listOf(false, true)) {
                KeyboardScenario(initialText = if (openInsideWord) "Fix t" else "Fix teh word.", initialMode = mode,
                    clipboardText = "clipboard", englishEngine = ScenarioEnglishEngine(),
                    scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                    englishDispatcher = StandardTestDispatcher(testScheduler)).use { s ->
                    s.autocorrectLayouts = emptySet()
                    if (openInsideWord) s.editor.right = "eh word."
                    s.start()
                    if (!openInsideWord) s.moveCursor(5)
                    runCurrent()
                    assertEquals("", s.editor.composing)
                    val rows = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                    assertEquals(listOf("teh", "the"), rows.map { it.text })
                    assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                    assertNotNull(s.snapshot().clipboard)
                    assertEquals("Fix teh word.", s.editor.text + s.editor.right)
                    s.choose("the"); runCurrent()
                    assertEquals("Fix the word.", s.editor.text + s.editor.right)
                    assertEquals("", s.editor.composing)
                }
            }
        }
    }

    @Test fun nativeManualCapitalizationReplacesWordUnderCursorAcrossLayouts() {
        val library = System.getProperty("english.native.library")
        org.junit.Assume.assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        for ((mode, version) in listOf(KeyboardInputMode.AlphaNum to 3,
            KeyboardInputMode.Quick to 3, KeyboardInputMode.Quick to 5)) {
            val quick = com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine.fromAssets(
                read = { java.io.File("src/main/assets/$it").readBytes() })
            KeyboardScenario(initialText = "how are you", initialMode = mode, quickEngine = quick,
                englishEngine = SharedLmEnglishEngine(quick::englishContextHandle),
                unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle)).use { s ->
                s.quickVersion = version
                s.autocorrectLayouts = emptySet()
                s.start(); s.moveCursor(1)
                val rows = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                assertTrue("$mode/$version offers explicit capitalization", rows.any { it.text == "How" })
                assertEquals("how are you", s.editor.text + s.editor.right)
                s.choose("How")
                assertEquals("How are you", s.editor.text + s.editor.right)
                assertEquals("", s.editor.composing)
            }
        }
    }

    @Test fun inspectingAndReplacingWholeWordPreservesBothSidesAcrossLayouts() = runTest {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            val engine = ScenarioEnglishEngine()
            KeyboardScenario(initialText = "Fix teh word.", initialMode = mode, clipboardText = null,
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), englishDispatcher = StandardTestDispatcher(testScheduler),
                englishEngine = engine).use { s ->
                s.start(); s.moveCursor(5); runCurrent()
                assertEquals("Fix t", s.editor.text)
                assertEquals("eh word.", s.editor.right)
                assertEquals("", s.editor.composing)
                val row = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                assertEquals(listOf("teh", "the"), row.map { it.text })
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                assertEquals("teh", engine.requests.last().word)
                assertEquals("Fix ", engine.requests.last().left)
                assertEquals(" word.", engine.requests.last().right)
                s.choose("the"); runCurrent()
                assertEquals("Fix the", s.editor.text)
                assertEquals(" word.", s.editor.right)
                assertEquals("", s.editor.composing)
            }
        }
    }

    @Test fun inspectionTemporarilyShowsCorrectionsAheadOfClipboardWithoutDiscardingIt() {
        KeyboardScenario(initialText = "Fix teh word.", initialMode = KeyboardInputMode.AlphaNum,
            englishEngine = ScenarioEnglishEngine()).use { s ->
            s.start(); s.moveCursor(5)
            assertNotNull(s.snapshot().clipboard)
            assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
            s.moveCursor(8)
            assertNotNull(s.snapshot().clipboard)
            s.moveCursor(13)
            assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
        }
    }

    @Test fun wordEdgesAndCapitalizationUseTheCompleteWord() {
        for (position in listOf(4, 5, 7)) {
            KeyboardScenario(initialText = "Fix TEH word.", initialMode = KeyboardInputMode.AlphaNum,
                clipboardText = null, englishEngine = object : EnglishCandidateEngine {
                    override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String) =
                        EnglishPrediction(candidates = listOf(EnglishCandidate("the", 1.0, 1.0, 1, 0)))
                }).use { s ->
                s.start(); s.moveCursor(position)
                assertEquals(listOf("TEH", "THE"), s.snapshot().englishCandidates.map { it.text })
                s.choose("THE")
                assertEquals("Fix THE word.", s.editor.text + s.editor.right)
            }
        }
    }

    @Test fun cursorMoveRejectsPendingAndStaleSuggestions() = runTest {
        KeyboardScenario(initialText = "Fix teh word.", initialMode = KeyboardInputMode.AlphaNum,
            clipboardText = null, scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            englishDispatcher = StandardTestDispatcher(testScheduler), englishEngine = ScenarioEnglishEngine()).use { s ->
            s.start(); s.moveCursor(5); s.moveCursor(8); runCurrent()
            assertFalse(s.snapshot().englishCandidates.any { it.text == "the" })
            s.choose("the")
            assertEquals("Fix teh word.", s.editor.text + s.editor.right)
            s.moveCursor(5); runCurrent()
            // An editor can change without first delivering its selection callback.
            s.editor.right = "xx word."
            s.choose("the")
            assertEquals("Fix txx word.", s.editor.text + s.editor.right)
        }
    }

    @Test fun selectionsAndProtectedFieldsDoNotInspect() {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            KeyboardScenario(initialText = "Fix teh word.", initialMode = mode,
                clipboardText = null, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.moveCursor(5, 7)
                assertFalse(s.english!!.inspectingWord)
                s.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD })
                s.moveCursor(5)
                assertFalse(s.english.inspectingWord)
            }
        }
    }

    @Test fun punctuationAndSpaceEditLiterallyWithoutApplyingInspectedCorrection() {
        for (key in listOf(KeyboardKeyKind.Space, KeyboardKeyKind.Enter, KeyboardKeyKind.Delete)) {
            KeyboardScenario(initialText = "Fix teh word.", initialMode = KeyboardInputMode.AlphaNum,
                clipboardText = null, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.moveCursor(5); s.press(key)
                assertFalse(s.english!!.inspectingWord)
                assertFalse(s.editor.text.contains("the"))
            }
        }
    }

    @Test fun unifiedBackendInspectsEnglishWithoutQuickDecoding() = runTest {
        val engine = object : UnifiedSuggestionEngine {
            override fun newRequest() = object : SuggestionRequest {
                override fun cancel() = Unit
                override fun ensureActive() = Unit
            }
            override fun compose(request: SuggestionRequest, version: Int, code: String,
                left: String, right: String, englishEnabled: Boolean): JoinedSuggestions {
                if (code.isEmpty()) return JoinedSuggestions()
                assertEquals(0, version); assertEquals("teh", code)
                assertEquals("Fix ", left); assertEquals(" word.", right)
                assertTrue(englishEnabled)
                return JoinedSuggestions(english = EnglishPrediction(candidates =
                    listOf(EnglishCandidate("the", 1.0, 1.0, 1, 0))))
            }
        }
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            KeyboardScenario(initialText = "Fix teh word.", initialMode = mode, clipboardText = null,
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), englishDispatcher = StandardTestDispatcher(testScheduler),
                unifiedEngine = engine).use { s ->
                s.start(); s.moveCursor(5); runCurrent(); s.choose("the"); runCurrent()
                assertEquals("Fix the word.", s.editor.text + s.editor.right)
            }
        }
    }
}
