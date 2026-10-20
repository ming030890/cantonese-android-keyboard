package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.quick.*
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickLanguageScenarioTest {
    @Test fun shortCodePreviewIsSelectableBeforeFullRankingAndRejectsLateResults() = runTest {
        for (selectEarly in listOf(false, true)) {
            val queue = ArrayDeque<Runnable>()
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.addLast(block) }
            }
            var previews = 0
            val engine = object : QuickCandidateEngine {
                override fun previewCandidates(version: Int, code: String, left: String, right: String): List<QuickCandidate> {
                    previews++
                    assertEquals("df", code); assertEquals("I use ", left)
                    return listOf(QuickCandidate("標", 0), QuickCandidate("杯", 1))
                }
                override fun candidates(version: Int, code: String, left: String, right: String) =
                    listOf(QuickCandidate("杯", 0), QuickCandidate("標", 1))
            }
            KeyboardScenario(initialText = "I use ", clipboardText = null,
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                lookupDispatcher = worker, quickEngine = engine).use { s ->
                s.start(); s.type("df"); runCurrent()
                while (previews == 0) { queue.removeFirst().run(); runCurrent() }
                assertEquals(listOf("標", "杯"), s.snapshot().quickCandidates.map { it.text })
                assertEquals("df", s.editor.composing)
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                if (selectEarly) s.choose("標")
                while (queue.isNotEmpty()) { queue.removeFirst().run(); runCurrent() }
                if (selectEarly) {
                    assertEquals("I use 標", s.editor.text)
                    assertEquals("", s.editor.composing)
                    assertTrue(s.snapshot().quickCandidates.isEmpty())
                } else {
                    assertEquals(listOf("杯", "標"), s.snapshot().quickCandidates.map { it.text })
                    assertEquals("I use ", s.editor.text)
                    assertEquals("df", s.editor.composing)
                }
            }
        }
    }

    @Test fun chineseCandidatesAppearWhileEnglishWorkerIsStillPending() = runTest {
        for (code in listOf("df", "d", "dfg")) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val englishScheduler = TestCoroutineScheduler()
            var ranks = 0
            KeyboardScenario(initialText = "I use ", clipboardText = null,
                scope = CoroutineScope(SupervisorJob() + dispatcher), lookupDispatcher = dispatcher,
                englishDispatcher = StandardTestDispatcher(englishScheduler),
                englishEngine = ScenarioEnglishEngine().apply { ranker = { _, _, pool -> ranks++; pool.reversed() } }
            ).use { s ->
                val matches = listOf("標", "杯", "禁")
                s.engine.lookup = { _, _, _, _ -> matches.mapIndexed { i, text -> QuickCandidate(text, i) } }
                s.start(); s.type(code); runCurrent()
                assertEquals("I use ", s.editor.text)
                assertEquals(code, s.editor.composing)
                assertEquals(matches, s.snapshot().quickCandidates.take(3).map { it.text })
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                assertEquals(0, ranks)
                englishScheduler.runCurrent(); runCurrent()
                englishScheduler.runCurrent(); runCurrent()
                if (code.length == 2) {
                    assertEquals(0, ranks)
                    assertEquals(matches + code, s.snapshot().quickCandidates.map { it.text })
                } else assertTrue(ranks > 0)
                s.choose("標")
                assertEquals("I use 標", s.editor.text)
                assertEquals("", s.editor.composing)
            }
        }
    }

    @Test fun twoLetterQuickCodeKeepsChineseBeforeEnglishInEnglishContext() {
        for (code in listOf("df", "DF", "of")) {
            for (selected in listOf("標", code)) {
                KeyboardScenario(initialText = "I use ", englishEngine = ScenarioEnglishEngine().apply {
                    ranker = { _, _, pool -> pool.sortedBy { if (it.all { char -> char.code < 128 }) 0 else 1 } }
                }).use { s ->
                    val matches = listOf("標", "杯", "禁", "紮", "焚", "杰", "棕", "楝", "柬")
                    s.engine.lookup = { _, _, _, _ -> matches.mapIndexed { i, text -> QuickCandidate(text, i) } }
                    s.start(); s.type(code)
                    assertEquals(code, s.editor.composing)
                    assertEquals("I use ", s.editor.text)
                    assertEquals(matches + code, s.snapshot().quickCandidates.map { it.text })
                    assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                    s.choose(selected)
                    assertEquals("I use $selected", s.editor.text)
                    assertEquals("", s.editor.composing)
                }
            }
        }
    }

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
