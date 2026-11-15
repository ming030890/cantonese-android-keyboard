package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognitionEngine
import com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardKeyKind
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardToolbarContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandwritingSynchronizationScenarioTest {
    @Test fun strokesSkipRasterPreprocessingAndEmptyResultsCanBeRetriedOrClearedWithBackspace() = runTest {
        var calls = 0
        var preprocessingCalls = 0
        val engine = object : HandwritingRecognitionEngine {
            override fun modelInputPixels(pixels: ByteArray, width: Int, height: Int): ByteArray {
                preprocessingCalls++
                return ByteArray(48 * 48)
            }
            override fun predict(pixels: ByteArray, width: Int, height: Int, topK: Int): List<HandwritingCandidate> =
                error("Stroke input must use stroke recognition")
            override fun predictStrokes(strokes: List<List<StrokePoint>>,
                canvasWidth: Float, canvasHeight: Float, topK: Int, prevChar: String?): List<HandwritingCandidate> =
                when (++calls) {
                    1 -> emptyList()
                    2 -> listOf(HandwritingCandidate("你", 0), HandwritingCandidate("您", 1))
                    else -> emptyList()
                }
            override fun close() {}
        }
        KeyboardScenario(initialText = "我", initialMode = KeyboardInputMode.Handwriting, clipboardText = null,
            scope = backgroundScope, handwritingFactory = { editor, session, visible ->
                HandwritingInputCoordinator(editor = editor, currentSession = session,
                    isInputViewVisible = visible, stopVoiceInput = {}, coroutineScope = backgroundScope,
                    recognitionEngine = engine, recognitionDispatcher = StandardTestDispatcher(testScheduler))
            }).use { s ->
            s.start()
            val canvas = s.snapshot().handwritingSession
            fun submit() = s.controller.recognizeHandwriting(
                listOf(listOf(StrokePoint(10f, 10f, 0))),
                100f, 100f, byteArrayOf(), 100, 100)
            s.controller.onHandwritingStrokeStarted(true)
            submit(); runCurrent()
            assertEquals("我", s.editor.text + s.editor.composing)
            assertEquals(canvas, s.snapshot().handwritingSession)
            assertNotNull(s.snapshot().handwritingError)
            assertNull(s.snapshot().handwritingComposing)
            assertTrue(s.snapshot().handwritingCandidates.isEmpty())
            assertEquals(KeyboardToolbarContent.HandwritingError, s.snapshot().toolbar())
            assertFalse(s.snapshot().handwritingBusy)

            // Continued stroke retries immediately without clearing existing ink.
            s.controller.onHandwritingStrokeStarted(false)
            submit(); runCurrent()
            assertEquals(0, preprocessingCalls)
            assertEquals(2, calls)
            assertNull(s.snapshot().handwritingError)
            assertEquals("你", s.editor.composing)
            assertEquals("你", s.snapshot().handwritingComposing)
            assertEquals(canvas, s.snapshot().handwritingSession)

            // Backspace clears the active ink/composition without deleting prior committed "我".
            s.press(KeyboardKeyKind.Delete)
            assertEquals("我", s.editor.text)
            assertEquals("", s.editor.composing)
            assertNull(s.snapshot().handwritingComposing)
            assertEquals(canvas + 1, s.snapshot().handwritingSession)

            // Failed recognition ink is also cleared by Backspace without deleting "我".
            s.controller.onHandwritingStrokeStarted(true)
            submit(); runCurrent()
            assertNotNull(s.snapshot().handwritingError)
            s.press(KeyboardKeyKind.Delete)
            assertEquals("我", s.editor.text)
            assertNull(s.snapshot().handwritingError)
            assertEquals(canvas + 2, s.snapshot().handwritingSession)
        }
    }

    @Test fun livePerStrokeRecognitionKeepsInkUntilSpaceOrCandidateCommits() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val engine = object : HandwritingRecognitionEngine {
            override fun predict(pixels: ByteArray, width: Int, height: Int, topK: Int) =
                when (pixels[0].toInt()) {
                    1 -> listOf(HandwritingCandidate("亻", 0), HandwritingCandidate("丿", 1))
                    2 -> listOf(HandwritingCandidate("你", 0), HandwritingCandidate("您", 1))
                    else -> listOf(HandwritingCandidate("好", 0), HandwritingCandidate("女子", 1))
                }
            override fun close() {}
        }
        KeyboardScenario(initialMode = KeyboardInputMode.Handwriting, clipboardText = null,
            scope = backgroundScope, handwritingFactory = { editor, session, visible ->
                HandwritingInputCoordinator(editor = editor, currentSession = session,
                    isInputViewVisible = visible, stopVoiceInput = {},
                    predictionProvider = { left, _, _ ->
                        if (left == "你") listOf(HandwritingCandidate("哋", 0)) else emptyList()
                    },
                    synchronousPredictionsForTest = true,
                    coroutineScope = backgroundScope,
                    recognitionEngine = engine, recognitionDispatcher = worker)
            }).use { s ->
            s.start()
            fun submit(value: Byte) = s.controller.recognizeHandwriting(
                emptyList(), 1f, 1f, byteArrayOf(value), 1, 1)
            val initialCanvas = s.snapshot().handwritingSession

            // Stroke 1 updates candidates (keeping #1 in the candidate bar AND on the Spacebar)
            // immediately without a 1s stall or canvas reset.
            s.controller.onHandwritingStrokeStarted(true)
            submit(1)
            runCurrent()
            assertFalse(s.snapshot().handwritingBusy)
            assertEquals("亻", s.editor.composing)
            assertEquals("亻", s.snapshot().handwritingComposing)
            assertEquals(listOf("亻", "丿"), s.snapshot().handwritingCandidates.map { it.text })
            assertEquals(initialCanvas, s.snapshot().handwritingSession)

            // Starting a second stroke before 1s elapses cancels auto-commit, even if drawing
            // the stroke itself takes longer than 1s.
            advanceTimeBy(999); runCurrent()
            assertEquals("亻", s.editor.composing)
            assertEquals(initialCanvas, s.snapshot().handwritingSession)
            s.controller.onHandwritingStrokeStarted(false)
            advanceTimeBy(2_000); runCurrent()
            assertEquals("亻", s.editor.composing)
            assertEquals(initialCanvas, s.snapshot().handwritingSession)

            // Stroke 2 refines the character to "你" on the same canvas.
            submit(2)
            runCurrent()
            assertEquals("你", s.editor.composing)
            assertEquals("你", s.snapshot().handwritingComposing)
            assertEquals(listOf("你", "您"), s.snapshot().handwritingCandidates.map { it.text })
            assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
            assertEquals(initialCanvas, s.snapshot().handwritingSession)

            // Once "你" has been shown for 1 full second with no new stroke, it auto-commits,
            // clears the canvas and Spacebar confirm label, and loads next-word predictions.
            advanceTimeBy(999); runCurrent()
            assertEquals("", s.editor.text)
            assertEquals("你", s.editor.composing)
            assertEquals(initialCanvas, s.snapshot().handwritingSession)

            advanceTimeBy(1); runCurrent()
            assertEquals("你", s.editor.text)
            assertEquals("", s.editor.composing)
            assertNull(s.snapshot().handwritingComposing)
            assertEquals(initialCanvas + 1, s.snapshot().handwritingSession)
            assertEquals(listOf("哋"), s.snapshot().handwritingCandidates.map { it.text })

            // Pressing Space when canvas is idle inserts a literal space.
            s.press(KeyboardKeyKind.Space)
            assertEquals("你 ", s.editor.text)

            // Fast Spacebar confirm before 1s commits immediately without adding an ASCII space.
            s.controller.onHandwritingStrokeStarted(true)
            submit(3)
            runCurrent()
            assertEquals("好", s.editor.composing)
            s.press(KeyboardKeyKind.Space)
            assertEquals("你 好", s.editor.text)
            assertEquals("", s.editor.composing)
            assertNull(s.snapshot().handwritingComposing)
            assertEquals(initialCanvas + 2, s.snapshot().handwritingSession)

            // Browsing candidate pages cancels auto-commit so the user can pick an alternative calmly.
            s.controller.onHandwritingStrokeStarted(true)
            submit(3)
            runCurrent()
            assertEquals("好", s.editor.composing)
            assertEquals(listOf("好", "女子"), s.snapshot().handwritingCandidates.map { it.text })
            s.controller.onHandwritingCandidatePageChanged()
            advanceTimeBy(2_000); runCurrent()
            assertEquals("好", s.editor.composing)
            assertEquals(initialCanvas + 2, s.snapshot().handwritingSession)
            s.choose("女子")
            assertEquals("你 好女子", s.editor.text)
            assertEquals("", s.editor.composing)
            assertNull(s.snapshot().handwritingComposing)
            assertEquals(initialCanvas + 3, s.snapshot().handwritingSession)

            // Tapping the #1 candidate directly in the top candidate bar also commits and resets.
            s.controller.onHandwritingStrokeStarted(true)
            submit(3)
            runCurrent()
            assertEquals("好", s.snapshot().handwritingCandidates.first().text)
            assertEquals("好", s.snapshot().handwritingComposing)
            s.choose("好")
            assertEquals("你 好女子好", s.editor.text)
            assertEquals("", s.editor.composing)
            assertNull(s.snapshot().handwritingComposing)
            assertEquals(initialCanvas + 4, s.snapshot().handwritingSession)

            // Closing a session cancels pending text and canvas updates.
            s.controller.onHandwritingStrokeStarted(true)
            submit(1)
            s.hide()
            val hiddenCanvas = s.snapshot().handwritingSession
            advanceTimeBy(2_000); runCurrent()
            assertEquals("你 好女子好", s.editor.text)
            assertEquals(hiddenCanvas, s.snapshot().handwritingSession)
        }
    }

    @Test fun spaceWhileInferenceInFlightClearsCanvasImmediatelyAndCommitsResultWhenReady() = runTest {
        val gate = CompletableDeferred<Unit>()
        val worker = StandardTestDispatcher(testScheduler)
        val engine = object : HandwritingRecognitionEngine {
            override fun predict(pixels: ByteArray, width: Int, height: Int, topK: Int): List<HandwritingCandidate> {
                runBlocking { gate.await() }
                return listOf(HandwritingCandidate("快", 0))
            }
            override fun close() {}
        }
        KeyboardScenario(initialMode = KeyboardInputMode.Handwriting, clipboardText = null,
            scope = backgroundScope, handwritingFactory = { editor, session, visible ->
                HandwritingInputCoordinator(editor = editor, currentSession = session,
                    isInputViewVisible = visible, stopVoiceInput = {}, coroutineScope = backgroundScope,
                    recognitionEngine = engine, recognitionDispatcher = worker)
            }).use { s ->
            s.start()
            val initialCanvas = s.snapshot().handwritingSession
            s.controller.onHandwritingStrokeStarted(true)
            s.controller.recognizeHandwriting(emptyList(), 1f, 1f, byteArrayOf(1), 1, 1)

            // Tap Space immediately before worker runs: canvas resets right away.
            s.press(KeyboardKeyKind.Space)
            assertEquals(initialCanvas + 1, s.snapshot().handwritingSession)
            assertEquals("", s.editor.text)

            gate.complete(Unit)
            runCurrent()
            assertEquals("快", s.editor.text)
            assertEquals("", s.editor.composing)
        }
    }

    @Test fun immediateMainDispatcherZeroDelayRecognitionDoesNotCrashAndCommitsOnSpace() = runTest {
        val immediateMain = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val worker = StandardTestDispatcher(testScheduler)
        val engine = object : HandwritingRecognitionEngine {
            override fun predict(pixels: ByteArray, width: Int, height: Int, topK: Int): List<HandwritingCandidate> =
                when (pixels[0].toInt()) {
                    1 -> listOf(HandwritingCandidate("一", 0), HandwritingCandidate("丨", 1))
                    else -> listOf(HandwritingCandidate("十", 0), HandwritingCandidate("千", 1))
                }
            override fun close() {}
        }
        try {
            KeyboardScenario(initialMode = KeyboardInputMode.Handwriting, clipboardText = null,
                scope = immediateMain, handwritingFactory = { editor, session, visible ->
                    HandwritingInputCoordinator(editor = editor, currentSession = session,
                        isInputViewVisible = visible, stopVoiceInput = {}, coroutineScope = immediateMain,
                        recognitionEngine = engine, recognitionDispatcher = worker)
                }).use { s ->
                s.start()
                s.controller.onHandwritingStrokeStarted(true)
                s.controller.recognizeHandwriting(emptyList(), 1f, 1f, byteArrayOf(1), 1, 1)
                runCurrent()
                assertEquals("一", s.editor.composing)
                assertEquals("一", s.snapshot().handwritingComposing)
                assertEquals(listOf("一", "丨"), s.snapshot().handwritingCandidates.map { it.text })

                s.controller.onHandwritingStrokeStarted(false)
                s.controller.recognizeHandwriting(emptyList(), 1f, 1f, byteArrayOf(2), 1, 1)
                runCurrent()
                assertEquals("十", s.editor.composing)
                assertEquals("十", s.snapshot().handwritingComposing)
                assertEquals(listOf("十", "千"), s.snapshot().handwritingCandidates.map { it.text })

                s.press(KeyboardKeyKind.Space)
                assertEquals("十", s.editor.text)
                assertEquals("", s.editor.composing)
                assertNull(s.snapshot().handwritingComposing)
            }
        } finally {
            immediateMain.cancel()
        }
    }
}
