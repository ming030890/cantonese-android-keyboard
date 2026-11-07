package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.quick.*
import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickPersonalizationScenarioTest {
    @Test fun tapsLearnOnceAcrossSessionsAndPredictionsRecallThePhrase() {
        val p = QuickPersonalization()
        repeat(2) { index ->
            KeyboardScenario(initialText = "我想", personalization = p).use { s ->
                s.engine.lookup = { _, _, _, _ -> listOf(QuickCandidate("你", 0), QuickCandidate("食飯", 1)) }
                s.type("of")
                assertEquals("你", s.quick.candidates.value.first().text) // One selection is insufficient.
                s.press(KeyboardKeyKind.Candidate, "食飯")
                assertEquals("我想食飯", s.editor.text)
                assertEquals(listOf("食飯"), s.editor.commits)
                if (index == 0) assertEquals(listOf("你", "食飯"), p.rank(3, "of", "我想", listOf("你", "食飯")))
            }
        }
        KeyboardScenario(initialText = "我想", personalization = p).use { s ->
            s.quick.refreshPredictions()
            assertEquals("食飯", s.quick.candidates.value.first().text)
            s.type("of")
            // Learning never invents a candidate for a code when the dictionary doesn't return it.
            assertFalse(s.quick.candidates.value.any { it.text == "食飯" })
        }
    }

    @Test fun protectedEditorAndLiteralTypingDoNotRecordOrApplyHistory() {
        val p = QuickPersonalization()
        repeat(2) { p.record(3, "of", "係", "") }
        KeyboardScenario(personalization = p).use { s ->
            s.editor.personalizedLearningAllowed = false
            s.type("of")
            assertEquals("你", s.quick.candidates.value.first().text)
            s.press(KeyboardKeyKind.Candidate, "你")
            s.editor.text = ""
            s.type("of")
            s.press(KeyboardKeyKind.Candidate, "你")
            s.editor.personalizedLearningAllowed = true
            assertEquals(listOf("係", "你"), p.rank(3, "of", "", listOf("你", "係")))
            p.clear()
            s.type("of"); s.press(KeyboardKeyKind.Space)
            assertEquals(listOf("你", "係"), p.rank(3, "of", "", listOf("你", "係")))
        }
    }

    @Test fun settingsChangesRefreshTheActiveCandidateRow() = runTest {
        val history = QuickPersonalization()
        repeat(2) { history.record(3, "of", "係", "") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        KeyboardScenario(personalization = history,
            scope = CoroutineScope(SupervisorJob() + dispatcher), lookupDispatcher = dispatcher).use { s ->
            runCurrent()
            s.type("of"); runCurrent()
            assertEquals("係", s.quick.candidates.value.first().text)
            history.enabled = false; runCurrent()
            assertEquals("你", s.quick.candidates.value.first().text)
            history.enabled = true; runCurrent()
            assertEquals("係", s.quick.candidates.value.first().text)
            history.clear(); runCurrent()
            assertEquals("你", s.quick.candidates.value.first().text)
            assertEquals("of", s.editor.composing)
            assertEquals("", s.editor.text)
        }
    }

    @Test fun historyChangesDoNotInterruptEnglishComposition() = runTest {
        val history = QuickPersonalization()
        val dispatcher = StandardTestDispatcher(testScheduler)
        KeyboardScenario(personalization = history, initialMode = KeyboardInputMode.AlphaNum,
            englishEngine = ScenarioEnglishEngine(), scope = CoroutineScope(SupervisorJob() + dispatcher),
            lookupDispatcher = dispatcher, englishDispatcher = dispatcher).use { s ->
            runCurrent()
            s.type("hello"); runCurrent()
            history.clear(); runCurrent()
            assertEquals("hello", s.english!!.word)
            assertEquals("hello", s.editor.composing)
            assertEquals("", s.editor.text)
        }
    }

    @Test fun productionUnifiedPreviewThenFullThenTapLearnsExactlyOnce() = runTest {
        val history = QuickPersonalization()
        val fullQueue = ArrayDeque<Runnable>()
        val fullWorker = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { fullQueue += block }
        }
        val engine = object : UnifiedSuggestionEngine {
            override val supportsPreview = true
            override fun newRequest() = object : SuggestionRequest {
                private var cancelled = false
                override fun cancel() { cancelled = true }
                override fun ensureActive() { if (cancelled) throw CancellationException() }
            }
            override fun compose(request: SuggestionRequest, version: Int, code: String, left: String, right: String,
                englishEnabled: Boolean) = JoinedSuggestions(chinese = listOf("你", "係"),
                display = listOf("你", code, "係"), codeLength = code.length)
            override fun preview(request: SuggestionRequest, version: Int, code: String, left: String, right: String,
                englishEnabled: Boolean) = compose(request, version, code, left, right, englishEnabled)
        }
        repeat(2) { iteration ->
            val dispatcher = StandardTestDispatcher(testScheduler)
            KeyboardScenario(personalization = history, unifiedEngine = engine,
                scope = CoroutineScope(SupervisorJob() + dispatcher), englishDispatcher = fullWorker,
                previewDispatcher = dispatcher).use { s ->
                s.type("of")
                runCurrent()
                assertEquals("你", s.quick.joinedCandidates.value.first().text)
                while (fullQueue.isNotEmpty()) { fullQueue.removeFirst().run(); runCurrent() }
                assertEquals("你", s.quick.joinedCandidates.value.first().text)
                s.choose("係")
                assertEquals("係", s.editor.text)
                if (iteration == 0) assertEquals(listOf("你", "係"), history.rank(3, "of", "", listOf("你", "係")))
            }
            while (fullQueue.isNotEmpty()) { fullQueue.removeFirst().run(); runCurrent() }
        }
        assertEquals(listOf("係", "你"), history.rank(3, "of", "", listOf("你", "係")))
    }

    @Test fun unifiedPreviewAndFullApplySameRankingAndIgnoreStalePublication() = runTest {
        val p = QuickPersonalization()
        repeat(2) { p.record(3, "of", "係", "我") }
        KeyboardScenario(initialText = "我", personalization = p).use { s ->
            val requests = mutableListOf<QuickSuggestionInput>()
            s.quick.onCandidateRequest = { requests += it }
            s.type("of")
            val input = requests.last()
            val result = JoinedSuggestions(chinese = listOf("你", "係"), display = listOf("你", "of", "係", "office"), codeLength = 2)
            repeat(2) {
                s.quick.publishJoined(input, result)
                assertEquals(listOf("係", "of", "你", "office"), s.quick.joinedCandidates.value.map { it.text })
            }
            s.type("a")
            s.quick.publishJoined(input, result)
            assertFalse(s.quick.canSelectJoined("係"))
            p.clear()
            val current = requests.last()
            s.quick.publishJoined(current, result)
            assertEquals(result.display, s.quick.joinedCandidates.value.map { it.text })
        }
    }
}
