package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedSuggestionScenarioTest {
    private class Token : SuggestionRequest {
        var cancelled = false
        override fun cancel() { cancelled = true }
        override fun ensureActive() { if (cancelled) throw CancellationException() }
    }
    private class Engine : UnifiedSuggestionEngine {
        var previewResult: ((String) -> JoinedSuggestions)? = null
        val previewCalls = mutableListOf<String>()
        override val supportsPreview get() = previewResult != null
        override fun preview(request: SuggestionRequest, version: Int, code: String, left: String, right: String,
            englishEnabled: Boolean): JoinedSuggestions {
            request.ensureActive(); previewCalls += code
            return checkNotNull(previewResult)(code)
        }
        var boundaryResult: ((String) -> EnglishPrediction)? = null
        val boundaryCalls = mutableListOf<Pair<String, String>>()
        override val supportsIndependentBoundary get() = boundaryResult != null
        override fun boundary(request: SuggestionRequest, code: String, left: String): EnglishPrediction {
            request.ensureActive()
            boundaryCalls += code to left
            return checkNotNull(boundaryResult)(code)
        }
        val tokens = mutableListOf<Token>()
        val calls = mutableListOf<String>()
        val contexts = mutableListOf<String>()
        var duringLookup: (String) -> Unit = {}
        override fun newRequest() = Token().also(tokens::add)
        override fun compose(request: SuggestionRequest, version: Int, code: String, left: String, right: String,
                             englishEnabled: Boolean): JoinedSuggestions {
            calls += code
            contexts += left
            duringLookup(code)
            val alternative = if (code.isEmpty()) "next" else "${code}候選"
            return JoinedSuggestions(chinese = if (version == 0) emptyList() else listOf(alternative),
                display = listOf(alternative, code).filter { it.isNotEmpty() },
                english = EnglishPrediction(candidates = listOf(EnglishCandidate(alternative, 0.0, 0.0, 0, 0))),
                codeLength = code.length)
        }
    }
    private class Worker : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue += block }
        fun drain(scheduler: TestCoroutineScheduler) {
            while (queue.isNotEmpty()) { queue.removeFirst().run(); scheduler.runCurrent() }
        }
    }

    @Test fun rapidTypingShowsCurrentLiteralAndRejectsStaleRowsWithoutAnEmptyFrame() = runTest {
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum)) {
            val engine = Engine(); val worker = Worker()
            KeyboardScenario(initialMode = mode, clipboardText = null,
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                englishDispatcher = worker, unifiedEngine = engine).use { s ->
                val rows = mutableListOf<List<String>>()
                val observed = if (mode == KeyboardInputMode.Quick) s.quick.joinedCandidates else s.english!!.candidates
                val collector = observed.onEach { rows += it.map { c -> c.text } }
                    .launchIn(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)))
                s.start(); s.type("a"); runCurrent(); worker.drain(testScheduler)
                assertEquals(listOf("a"), engine.calls)
                val previous = observed.value.map { it.text }
                rows.clear()
                s.type("bc"); runCurrent()
                assertEquals(listOf("abc"), observed.value.map { it.text })
                assertTrue(engine.tokens.dropLast(1).all { it.cancelled })
                // Previous labels cannot commit after the current literal appears.
                s.choose(previous.first())
                assertEquals("", s.editor.text); assertEquals("abc", s.editor.composing)
                worker.drain(testScheduler)
                assertEquals(listOf("a", "abc"), engine.calls)
                assertTrue(rows.none { it.isEmpty() })
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                assertTrue(observed.value.any { it.text == "abc候選" })
                s.choose("abc候選"); runCurrent()
                assertEquals("abc候選", s.editor.text); assertEquals("", s.editor.composing)
                collector.cancel()
            }
        }
    }

    @Test fun longCompositionDoesNotLeakIntoTheLeftContextWindow() = runTest {
        val engine = Engine(); val worker = Worker()
        val raw = "hion".repeat(150)
        KeyboardScenario(initialText = "我想", clipboardText = null,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            englishDispatcher = worker, unifiedEngine = engine).use { s ->
            s.start(); s.type(raw); runCurrent(); worker.drain(testScheduler)
            assertEquals(listOf(raw), engine.calls)
            assertEquals(listOf("我想"), engine.contexts)
            assertEquals("我想", s.editor.text); assertEquals(raw, s.editor.composing)
            s.choose(raw)
            assertEquals("我想$raw", s.editor.text); assertEquals("", s.editor.composing)
        }
    }

    @Test fun cancellingRunningNonCooperativeLookupRejectsItsResultAndKeepsNewComposition() = runTest {
        val engine = Engine(); val worker = Worker()
        KeyboardScenario(clipboardText = null,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            englishDispatcher = worker, unifiedEngine = engine).use { s ->
            s.start(); s.type("a"); runCurrent()
            engine.duringLookup = { code -> if (code == "a") s.type("b") }
            worker.drain(testScheduler)
            assertEquals(listOf("a", "ab"), engine.calls)
            assertEquals("", s.editor.text); assertEquals("ab", s.editor.composing)
            assertEquals(listOf("ab候選", "ab"), s.snapshot().quickCandidates.map { it.text })
            assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
        }
    }

    @Test fun fastPreviewsUpdateThroughoutTypingWhileFullRankingCannotRun() = runTest {
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum)) {
            val ui = StandardTestDispatcher(testScheduler); val worker = Worker()
            val engine = Engine().apply { previewResult = { code -> JoinedSuggestions(
                chinese = listOf("${code}預覽"), display = listOf("${code}預覽", code),
                english = EnglishPrediction(candidates = listOf(EnglishCandidate("${code}preview", 0.0, 0.0, 0, 1))),
                codeLength = code.length) } }
            KeyboardScenario(initialMode = mode, clipboardText = null,
                scope = CoroutineScope(SupervisorJob() + ui), englishDispatcher = worker,
                previewDispatcher = ui, unifiedEngine = engine).use { s ->
                s.start()
                var code = ""
                for (char in "hion") {
                    val stale = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates.map { it.text }
                        else s.snapshot().englishCandidates.map { it.text }
                    code += char; s.type(char.toString())
                    val literal = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates
                        else s.snapshot().englishCandidates
                    assertEquals(listOf(code), literal.map { it.text })
                    stale.firstOrNull()?.let(s::choose)
                    assertEquals("", s.editor.text); assertEquals(code, s.editor.composing)
                    runCurrent()
                    val displayed = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates
                        else s.snapshot().englishCandidates
                    assertTrue(displayed.any { it.text == if (mode == KeyboardInputMode.Quick) "${code}預覽" else "${code}preview" })
                    assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                }
                assertEquals(listOf("h", "hi", "hio", "hion"), engine.previewCalls)
                assertTrue(engine.calls.isEmpty())
                worker.drain(testScheduler)
                assertEquals(listOf("hion"), engine.calls)
                assertEquals("", s.editor.text); assertEquals("hion", s.editor.composing)
            }
        }
    }

    @Test fun latePreviewCannotReplaceFinishedRanking() = runTest {
        val ui = StandardTestDispatcher(testScheduler); val worker = Worker()
        val engine = Engine().apply { previewResult = { code -> JoinedSuggestions(display = listOf("late", code)) } }
        KeyboardScenario(clipboardText = null, scope = CoroutineScope(SupervisorJob() + ui),
            englishDispatcher = ui, previewDispatcher = worker, unifiedEngine = engine).use { s ->
            s.start(); s.type("hion"); runCurrent()
            val final = s.snapshot().quickCandidates
            worker.drain(testScheduler)
            assertEquals(final, s.snapshot().quickCandidates)
            assertFalse(s.snapshot().quickCandidates.any { it.text == "late" })
        }
    }

    @Test fun sessionHideCancelsPendingRequestsAndRejectsLateResults() = runTest {
        val engine = Engine(); val worker = Worker()
        KeyboardScenario(clipboardText = null,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            englishDispatcher = worker, unifiedEngine = engine).use { s ->
            s.start(); s.type("a"); runCurrent()
            engine.duringLookup = { s.hide() }
            worker.drain(testScheduler)
            assertTrue(engine.tokens.all { it.cancelled })
            assertTrue(s.snapshot().quickCandidates.isEmpty())
            assertEquals("a", s.editor.text); assertEquals("", s.editor.composing)
        }
    }

    @Test fun contextChangesReplacePendingNextWordRequests() = runTest {
        val engine = Engine(); val worker = Worker()
        KeyboardScenario(clipboardText = null,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            englishDispatcher = worker, unifiedEngine = engine).use { s ->
            s.start(); runCurrent()
            val token = engine.tokens.last()
            s.editor.text = "new context "
            s.controller.onSelectionChanged(); runCurrent()
            assertTrue(token.cancelled)
            worker.drain(testScheduler)
            assertEquals(listOf(""), engine.calls)
            assertEquals(listOf("next"), s.snapshot().quickCandidates.map { it.text })
            assertEquals("new context ", s.editor.text)
            assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
        }
    }

    @Test fun boundaryTimeoutCancelsSharedInferenceAndKeepsFollowingKeysInOrder() = runTest {
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum)) {
            val engine = Engine(); val worker = Worker()
            KeyboardScenario(initialMode = mode, clipboardText = null,
                scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                englishDispatcher = worker, unifiedEngine = engine).use { s ->
                s.start(); s.type("teh"); runCurrent()
                val boundaryToken = engine.tokens.last()
                s.press(KeyboardKeyKind.Space); s.type("x"); runCurrent()
                advanceTimeBy(41); runCurrent()
                assertTrue(boundaryToken.cancelled)
                assertEquals("teh ", s.editor.text); assertEquals("x", s.editor.composing)
                worker.drain(testScheduler)
                assertEquals(listOf("x"), engine.calls)
                assertEquals("teh ", s.editor.text); assertEquals("x", s.editor.composing)
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
            }
        }
    }

    @Test fun disabledQuickCorrectionDoesNotWaitForChineseInferenceAtSpace() = runTest {
        val engine = Engine(); val worker = Worker()
        KeyboardScenario(clipboardText = null,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            englishDispatcher = worker, unifiedEngine = engine).use { s ->
            s.autocorrectLayouts = emptySet()
            s.start(); s.type("abc"); runCurrent()
            s.press(KeyboardKeyKind.Space)
            assertEquals("abc ", s.editor.text); assertEquals("", s.editor.composing)
            worker.drain(testScheduler)
            assertEquals("abc ", s.editor.text)
        }
    }

    @Test fun immediateSpaceUsesIndependentEnglishWhileUnifiedWorkerIsOccupied() = runTest {
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum)) for (version in listOf(3, 5)) {
            val engine = Engine().apply {
                boundaryResult = { word -> EnglishPrediction(
                    candidates = listOf(EnglishCandidate("know", 0.0, 0.0, 1, 0)),
                    correction = if (word == "kniw") "know" else null, replaceBefore = word.length) }
            }
            val worker = Worker()
            val ui = StandardTestDispatcher(testScheduler)
            KeyboardScenario(initialText = "do you ", initialMode = mode, clipboardText = null,
                scope = CoroutineScope(SupervisorJob() + ui), englishDispatcher = worker,
                boundaryDispatcher = ui, unifiedEngine = engine).use { s ->
                s.quickVersion = version
                s.start(); s.type("kniw"); runCurrent()
                val abandoned = engine.tokens.last()
                s.press(KeyboardKeyKind.Space); s.type("whay"); runCurrent()
                assertTrue(abandoned.cancelled)
                assertEquals(listOf("kniw" to "do you "), engine.boundaryCalls)
                assertEquals("do you know ", s.editor.text)
                assertEquals("whay", s.editor.composing)
                assertTrue(engine.calls.isEmpty())
                worker.drain(testScheduler)
                assertEquals("do you know ", s.editor.text)
                assertEquals("whay", s.editor.composing)
            }
        }
    }

    @Test fun independentBoundaryStillTimesOutAndCannotRewriteFollowingInput() = runTest {
        val engine = Engine().apply { boundaryResult = { EnglishPrediction(correction = "know") } }
        val worker = Worker(); val boundaryWorker = Worker()
        KeyboardScenario(initialMode = KeyboardInputMode.Quick, clipboardText = null,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            englishDispatcher = worker, boundaryDispatcher = boundaryWorker, unifiedEngine = engine).use { s ->
            s.start(); s.type("kniw"); runCurrent()
            s.press(KeyboardKeyKind.Space); s.type("x"); runCurrent()
            val token = engine.tokens.last()
            advanceTimeBy(41); runCurrent()
            assertTrue(token.cancelled)
            assertEquals("kniw ", s.editor.text); assertEquals("x", s.editor.composing)
            boundaryWorker.drain(testScheduler); worker.drain(testScheduler)
            assertTrue(engine.boundaryCalls.isEmpty())
            assertEquals("kniw ", s.editor.text); assertEquals("x", s.editor.composing)
        }
    }

    @Test fun independentBoundaryCorrectionSupportsImmediateUndoAndLiteralRecommit() = runTest {
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum)) {
            val engine = Engine().apply { boundaryResult = { word -> EnglishPrediction(
                correction = "know", replaceBefore = word.length) } }
            val ui = StandardTestDispatcher(testScheduler)
            KeyboardScenario(initialText = "do you ", initialMode = mode, clipboardText = null,
                scope = CoroutineScope(SupervisorJob() + ui), englishDispatcher = Worker(),
                boundaryDispatcher = ui, unifiedEngine = engine).use { s ->
                s.start(); s.type("kniw"); s.press(KeyboardKeyKind.Space); runCurrent()
                assertEquals("do you know ", s.editor.text)
                s.press(KeyboardKeyKind.Delete); runCurrent()
                assertEquals("do you ", s.editor.text); assertEquals("kniw", s.editor.composing)
                s.press(KeyboardKeyKind.Space); runCurrent()
                assertEquals("do you kniw ", s.editor.text)
            }
        }
    }

    @Test fun layoutRoundTripRestartsCancelledIdleLookupEvenWithUnchangedContext() = runTest {
        val engine = Engine(); val worker = Worker()
        KeyboardScenario(clipboardText = null,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            englishDispatcher = worker, unifiedEngine = engine).use { s ->
            s.start(); runCurrent()
            val first = engine.tokens.last()
            s.mode(KeyboardInputMode.AlphaNum); runCurrent()
            assertTrue(first.cancelled)
            s.mode(KeyboardInputMode.Quick); runCurrent()
            worker.drain(testScheduler)
            assertEquals(listOf(""), engine.calls)
            assertEquals(listOf("next"), s.snapshot().quickCandidates.map { it.text })
            assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
        }
    }

    @Test fun quickNextWordSelectionPreservesBothWordSeparators() = runTest {
        val engine = Engine(); val worker = Worker()
        KeyboardScenario(initialText = "hello", clipboardText = null,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            englishDispatcher = worker, unifiedEngine = engine).use { s ->
            s.editor.right = "world"
            s.start(); runCurrent(); worker.drain(testScheduler)
            s.choose("next"); runCurrent()
            assertEquals("hello next ", s.editor.text)
            assertEquals("world", s.editor.right)
            assertEquals("", s.editor.composing)
        }
    }

    @Test fun unifiedJniPreservesCorrectionDecisionsAndRanksBilingualCandidates() {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        NativeQuickEngine.fromAssets({ File("src/main/assets/$it").readBytes() }).use { quick ->
            val engine = NativeSharedSuggestions(quick::englishContextHandle)
            val legacy = SharedLmEnglishEngine(quick::englishContextHandle)
            for (left in listOf("", "I ", "let me know if ")) {
                for (typed in listOf("gwme", "teh", "hou", "dont", "continue", "exmaole", "inthe", "tyoes")) {
                    val expected = legacy.predictObserved(typed, floatArrayOf(), floatArrayOf(), left, "")
                    engine.newRequest().use { token ->
                        assertEquals("$left/$typed preview", expected, engine.preview(token, 0, typed, left, "", true).english)
                    }
                    engine.newRequest().use { token ->
                        assertEquals("$left/$typed boundary", expected, engine.boundary(token, typed, left))
                    }
                    engine.newRequest().use { token ->
                        val result = engine.compose(token, 0, typed, left, "", true)
                        assertEquals("$left/$typed correction", expected.correction, result.english.correction)
                        assertEquals("$left/$typed candidates", expected.candidates, result.english.candidates)
                    }
                }
            }
            for (version in listOf(3, 5)) {
                engine.newRequest().use { token ->
                    val result = engine.compose(token, version, "humo", "", "", true)
                    assertTrue("兒歌 remains generated", "兒歌" in result.chinese)
                    assertTrue("Coherent phrase order: ${result.display}", result.display.indexOf("自歌") < 0 || result.display.indexOf("兒歌") < result.display.indexOf("自歌"))
                }
                engine.newRequest().use { token ->
                    val result = engine.compose(token, version, "df", "I use ", "", true)
                    assertEquals(quick.previewCandidates(version, "df", "I use ", "").map { it.text }, result.chinese)
                    assertEquals(result.chinese, result.display.take(result.chinese.size))
                }
            }
        }
    }

    @Test fun unifiedControllerCorrectsAtBoundaryAndUndoPreservesLiteralOnBothLayouts() {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum)) {
            val quick = NativeQuickEngine.fromAssets({ File("src/main/assets/$it").readBytes() })
            KeyboardScenario(initialMode = mode, clipboardText = null, quickEngine = quick,
                englishEngine = SharedLmEnglishEngine(quick::englishContextHandle),
                unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle)).use { s ->
                s.start(); s.type("gwme")
                assertEquals("gwme", s.editor.composing)
                val candidates = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                assertTrue(candidates.any { it.text == "game" })
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                s.press(KeyboardKeyKind.Space)
                assertEquals("game ", s.editor.text); assertEquals("", s.editor.composing)
                s.press(KeyboardKeyKind.Delete)
                assertEquals("", s.editor.text); assertEquals("gwme", s.editor.composing)
                s.press(KeyboardKeyKind.Space)
                assertEquals("gwme ", s.editor.text)

                s.editor.text = ""
                s.editor.composing = ""
                s.type("thinl")
                s.press(KeyboardKeyKind.Space)
                assertEquals("think ", s.editor.text)
            }
        }
    }

    @Test fun reportedSentenceCorrectsWithoutPausesWhileSuggestionWorkerIsOccupiedThroughJni() = runTest {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum)) for (version in listOf(3, 5)) {
            val worker = Worker(); val ui = StandardTestDispatcher(testScheduler)
            val quick = NativeQuickEngine.fromAssets({ File("src/main/assets/$it").readBytes() })
            val native = NativeSharedSuggestions(quick::englishContextHandle)
            val decisions = mutableListOf<String>()
            val observed = object : UnifiedSuggestionEngine by native {
                override fun boundary(request: SuggestionRequest, code: String, left: String) =
                    native.boundary(request, code, left).also { decisions += "$left|$code -> ${it.correction}" }
            }
            KeyboardScenario(initialMode = mode, clipboardText = null, quickEngine = quick,
                scope = CoroutineScope(SupervisorJob() + ui), englishDispatcher = worker, boundaryDispatcher = ui,
                englishEngine = SharedLmEnglishEngine(quick::englishContextHandle),
                unifiedEngine = observed).use { s ->
                s.quickVersion = version
                s.start()
                for (word in "do you kniw whay you are talking about".split(' ')) {
                    s.type(word); s.press(KeyboardKeyKind.Space)
                }
                runCurrent()
                assertEquals("$mode Quick$version $decisions", "do you know what you are talking about ", s.editor.text)
                assertEquals("", s.editor.composing)
                worker.drain(testScheduler)
                assertEquals("do you know what you are talking about ", s.editor.text)
            }
        }
    }

    @Test fun nativePreviewAndFullRowsRankProblemWithChineseFromTheFirstPublication() = runTest {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        for (version in listOf(3, 5)) {
            val ui = StandardTestDispatcher(testScheduler); val worker = Worker()
            val quick = NativeQuickEngine.fromAssets({ File("src/main/assets/$it").readBytes() })
            KeyboardScenario(clipboardText = null, quickEngine = quick,
                scope = CoroutineScope(SupervisorJob() + ui), englishDispatcher = worker, previewDispatcher = ui,
                unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle)).use { s ->
                s.quickVersion = version; s.start(); s.type("problem"); runCurrent()
                val preview = s.snapshot().quickCandidates.map { it.text }
                assertEquals("Quick$version $preview", "problem", preview.first())
                assertTrue(preview.any { word -> word.any { it.code > 127 } })
                assertEquals("", s.editor.text); assertEquals("problem", s.editor.composing)
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                worker.drain(testScheduler)
                assertEquals("problem", s.snapshot().quickCandidates.first().text)
                s.choose("problem"); runCurrent()
                assertEquals("problem", s.editor.text); assertEquals("", s.editor.composing)
            }
        }
    }

    @Test fun nativeCompositionHasNoLeftover128KeyBridgeLimit() {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        NativeQuickEngine.fromAssets({ File("src/main/assets/$it").readBytes() }).use { quick ->
            val engine = NativeSharedSuggestions(quick::englishContextHandle)
            val code = "z".repeat(129)
            for (version in listOf(3, 5)) {
                engine.newRequest().use { request ->
                    val preview = engine.preview(request, version, code, "", "", true)
                    assertEquals(code.length, preview.codeLength)
                    assertTrue(code in preview.display)
                }
                engine.newRequest().use { request ->
                    val result = engine.compose(request, version, code, "", "", true)
                    assertEquals(code.length, result.codeLength)
                    assertTrue(code in result.display)
                }
            }
        }
    }

    @Test fun nativeLongChineseSelectionConsumesAllKeysOnBothQuickVersions() = runTest {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        for (version in listOf(3, 5)) {
            val ui = StandardTestDispatcher(testScheduler); val worker = Worker()
            val quick = NativeQuickEngine.fromAssets({ File("src/main/assets/$it").readBytes() })
            KeyboardScenario(clipboardText = null, quickEngine = quick,
                scope = CoroutineScope(SupervisorJob() + ui), englishDispatcher = worker, previewDispatcher = ui,
                unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle)).use { s ->
                s.quickVersion = version; s.start(); s.type("hion".repeat(4)); runCurrent()
                assertEquals("", s.editor.text); assertEquals("hion".repeat(4), s.editor.composing)
                assertTrue(s.quick.candidates.value.any { it.text.codePointCount(0, it.text.length) == 8 })
                worker.drain(testScheduler)
                val phrase = s.quick.candidates.value.first { it.text.codePointCount(0, it.text.length) == 8 }.text
                assertEquals("", s.editor.text); assertEquals("hion".repeat(4), s.editor.composing)
                s.choose(phrase); runCurrent()
                assertEquals(phrase, s.editor.text); assertEquals("", s.editor.composing)
            }
        }
    }

    @Test fun joinedChineseCompositionBeyondSixCharactersStaysWholeAndSelectable() = runTest {
        val ui = StandardTestDispatcher(testScheduler); val worker = Worker()
        val raw = "hionhionhionhionhion"
        val phrase = "我們我們我們我們我們"
        val engine = Engine().apply { previewResult = { code -> JoinedSuggestions(
            chinese = listOf(phrase), display = listOf(phrase, code),
            codeLength = code.length) } }
        KeyboardScenario(clipboardText = null, scope = CoroutineScope(SupervisorJob() + ui),
            englishDispatcher = worker, previewDispatcher = ui, unifiedEngine = engine).use { s ->
            s.start()
            raw.forEach { s.type(it.toString()); runCurrent() }
            assertEquals("", s.editor.text); assertEquals(raw, s.editor.composing)
            assertEquals(listOf(phrase, raw), s.snapshot().quickCandidates.map { it.text })
            assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
            s.choose(phrase); runCurrent()
            assertEquals(phrase, s.editor.text); assertEquals("", s.editor.composing)
            worker.drain(testScheduler)
            assertEquals(phrase, s.editor.text)
        }
    }

    @Test fun nativeRequestCanBeCancelledDuringInferenceAndNextRequestStillWorks() {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        NativeQuickEngine.fromAssets({ File("src/main/assets/$it").readBytes() }).use { quick ->
            val engine = NativeSharedSuggestions(quick::englishContextHandle)
            val token = engine.newRequest()
            val started = CountDownLatch(1); val finished = CountDownLatch(1)
            val cancelled = AtomicBoolean(false)
            var failure: Throwable? = null
            val thread = Thread {
                try {
                    started.countDown()
                    engine.compose(token, 3, "humohumohumo", "我想唱", "", true)
                } catch (_: CancellationException) { cancelled.set(true) }
                catch (error: Throwable) { failure = error }
                finally { token.close(); finished.countDown() }
            }
            thread.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            Thread.sleep(10)
            token.cancel()
            assertTrue("Cancelled native search stops promptly", finished.await(2, TimeUnit.SECONDS))
            thread.join()
            failure?.let { throw AssertionError("Native cancellation failed", it) }
            assertTrue(cancelled.get())
            engine.newRequest().use { next ->
                val prediction = engine.compose(next, 0, "gwme", "", "", true).english
                val expected = SharedLmEnglishEngine(quick::englishContextHandle)
                    .predictObserved("gwme", floatArrayOf(), floatArrayOf(), "", "")
                assertEquals("Cancellation must not corrupt the next CNN context", expected, prediction)
            }
        }
    }
}
