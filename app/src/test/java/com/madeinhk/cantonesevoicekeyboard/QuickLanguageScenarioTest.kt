package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.quick.*
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickLanguageScenarioTest {
    private val chinese = QuickLanguageEvidence(0.001f, 0.999f, 0.0001f, 0.1f)
    private val english = QuickLanguageEvidence(0.99f, 0.01f, 0.1f, 0.001f)
    private val uncertain = QuickLanguageEvidence(0.45f, 0.55f, 0.03f, 0.04f)

    @Test fun lmEvidenceReplacesDictionaryProtectionAndLengthAlone() {
        for (evidence in listOf(chinese, english, uncertain, null)) {
            KeyboardScenario(englishEngine = ScenarioEnglishEngine()).use { s ->
                s.engine.lookup = { _, code, _, _ -> listOf(QuickCandidate(if (code.length >= 6) "我哋去食飯啦" else "你", 0)) }
                s.engine.classify = { _, _, _, _ -> evidence }
                s.start(); s.type("keyboard")
                assertEquals(if (evidence == chinese) "我哋去食飯啦" else "", s.editor.text)
                assertEquals(if (evidence == chinese) "rd" else "keyboard", s.editor.composing)
                assertFalse(s.snapshot().quickCandidates.isEmpty())
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
            }
        }
    }

    @Test fun unknownEnglishBeyondTwelveKeysAndChineseSelectionKeepEveryKey() {
        val raw = "tonysunusualname"
        KeyboardScenario(englishEngine = ScenarioEnglishEngine()).use { s ->
            s.engine.lookup = { _, _, _, _ -> listOf(QuickCandidate("我哋去食飯啦", 0)) }
            s.engine.classify = { _, _, _, _ -> english }
            s.start(); s.type(raw)
            assertEquals("", s.editor.text); assertEquals(raw, s.editor.composing)
            assertTrue(s.snapshot().quickCandidates.any { it.text == raw })
            s.choose("我哋去食飯啦")
            assertEquals("我哋去食飯啦", s.editor.text)
            assertEquals(raw.drop(12), s.editor.composing)
            s.press(KeyboardKeyKind.Space)
            assertEquals("我哋去食飯啦${raw.drop(12)} ", s.editor.text)
        }
    }

    @Test fun pendingAndStaleModelResultsNeverForceConversion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        KeyboardScenario(englishEngine = ScenarioEnglishEngine(), scope = CoroutineScope(SupervisorJob() + dispatcher),
            lookupDispatcher = dispatcher).use { s ->
            s.engine.lookup = { version, _, _, _ -> listOf(QuickCandidate(if (version == 3) "我哋去食飯啦" else "你", 0)) }
            s.engine.classify = { _, _, _, _ -> chinese }
            s.start(); s.type("keyboard")
            assertEquals("", s.editor.text); assertEquals("keyboard", s.editor.composing)
            s.quick.setVersion(5); advanceUntilIdle(); s.type("x"); advanceUntilIdle()
            assertEquals("", s.editor.text); assertEquals("keyboardx", s.editor.composing)
            assertEquals("你", s.quick.candidates.value.first().text)
        }
    }

    @Test fun confidentChineseAfterPendingLookupRetainsUnconsumedSuffix() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        KeyboardScenario(englishEngine = ScenarioEnglishEngine(), scope = CoroutineScope(SupervisorJob() + dispatcher),
            lookupDispatcher = dispatcher).use { s ->
            s.engine.lookup = { _, _, _, _ -> listOf(QuickCandidate("我哋去食飯啦", 0)) }
            s.engine.classify = { _, _, _, _ -> chinese }
            s.start(); s.type("abcdefghijklmn")
            assertEquals("", s.editor.text); advanceUntilIdle()
            s.type("o"); advanceUntilIdle()
            assertEquals("我哋去食飯啦", s.editor.text); assertEquals("mno", s.editor.composing)
            s.press(KeyboardKeyKind.Space); advanceUntilIdle()
            assertEquals("我哋去食飯啦mno ", s.editor.text)
        }
    }

    @Test fun longBilingualSnapshotKeepsAllTextsAndSelection() {
        val snapshot = KeyboardScenarios.longMixedCandidates()
        assertEquals(listOf("keyboard", "我想去食飯", "candidates", "應該可以", "configuration", "香港人", "keyboadr"),
            snapshot.quickCandidates.map { it.text })
        assertEquals(KeyboardToolbarContent.Candidates, snapshot.toolbar())
    }

    @Test fun failedLanguageAssessmentKeepsCandidatesAndRawInput() {
        KeyboardScenario(englishEngine = ScenarioEnglishEngine()).use { s ->
            s.engine.lookup = { _, _, _, _ -> listOf(QuickCandidate("我哋去食飯啦", 0)) }
            s.engine.classify = { _, _, _, _ -> error("Unavailable language probabilities") }
            s.start(); s.type("unknownname")
            assertEquals("", s.editor.text); assertEquals("unknownname", s.editor.composing)
            assertTrue(s.snapshot().quickCandidates.any { it.text == "我哋去食飯啦" })
            assertTrue(s.snapshot().quickCandidates.any { it.text == "unknownname" })
        }
    }
}
