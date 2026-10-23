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
        val tokens = mutableListOf<Token>()
        val calls = mutableListOf<String>()
        var duringLookup: (String) -> Unit = {}
        override fun newRequest() = Token().also(tokens::add)
        override fun compose(request: SuggestionRequest, version: Int, code: String, left: String, right: String,
                             englishEnabled: Boolean): JoinedSuggestions {
            calls += code
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

    @Test fun rapidTypingCancelsObsoleteJobsAndReplacesTheRowOnceWithoutAnEmptyFrame() = runTest {
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
                assertEquals(previous, observed.value.map { it.text })
                assertTrue(engine.tokens.dropLast(1).all { it.cancelled })
                // Old labels may stay visible while work is pending, but cannot commit.
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
