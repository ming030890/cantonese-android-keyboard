package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognitionEngine
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardToolbarContent
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandwritingSynchronizationScenarioTest {
    @Test fun strokesSkipRasterPreprocessingAndEmptyResultsCanBeRetried() = runTest {
        var calls = 0
        var preprocessingCalls = 0
        val engine = object : HandwritingRecognitionEngine {
            override fun modelInputPixels(pixels: ByteArray, width: Int, height: Int): ByteArray {
                preprocessingCalls++
                return ByteArray(48 * 48)
            }
            override fun predict(pixels: ByteArray, width: Int, height: Int, topK: Int): List<HandwritingCandidate> =
                error("Stroke input must use stroke recognition")
            override fun predictStrokes(strokes: List<List<com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint>>,
                canvasWidth: Float, canvasHeight: Float, topK: Int, prevChar: String?): List<HandwritingCandidate> =
                if (++calls == 1) emptyList() else listOf(HandwritingCandidate("你", 0))
            override fun close() {}
        }
        KeyboardScenario(initialMode = KeyboardInputMode.Handwriting, clipboardText = null,
            scope = backgroundScope, handwritingFactory = { editor, session, visible ->
                HandwritingInputCoordinator(editor = editor, currentSession = session,
                    isInputViewVisible = visible, stopVoiceInput = {}, coroutineScope = backgroundScope,
                    recognitionEngine = engine, recognitionDispatcher = StandardTestDispatcher(testScheduler))
            }).use { s ->
            s.start()
            val canvas = s.snapshot().handwritingSession
            fun submit() = s.controller.recognizeHandwriting(
                listOf(listOf(com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint(10f, 10f, 0))),
                100f, 100f, byteArrayOf(), 100, 100)
            s.controller.onHandwritingStrokeStarted(true)
            submit(); advanceTimeBy(1_000); runCurrent()
            assertEquals("", s.editor.text + s.editor.composing)
            assertEquals(canvas, s.snapshot().handwritingSession)
            assertNotNull(s.snapshot().handwritingError)
            assertTrue(s.snapshot().handwritingCandidates.isEmpty())
            assertEquals(KeyboardToolbarContent.HandwritingError, s.snapshot().toolbar())
            assertFalse(s.snapshot().handwritingBusy)
            s.controller.onHandwritingStrokeStarted(false)
            submit(); advanceTimeBy(1_000); runCurrent()
            assertEquals(0, preprocessingCalls)
            assertEquals(2, calls)
            assertNull(s.snapshot().handwritingError)
            assertEquals("你", s.editor.composing)
            assertEquals(canvas + 1, s.snapshot().handwritingSession)
        }
    }

    @Test fun resultAndCanvasResetStayTogetherAcrossContinuedStrokesAndTwoCharacters() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val engine = object : HandwritingRecognitionEngine {
            override fun predict(pixels: ByteArray, width: Int, height: Int, topK: Int) =
                listOf(HandwritingCandidate(if (pixels[0] == 1.toByte()) "你" else "好", 0))
            override fun close() {}
        }
        KeyboardScenario(initialMode = KeyboardInputMode.Handwriting, clipboardText = null,
            scope = backgroundScope, handwritingFactory = { editor, session, visible ->
                HandwritingInputCoordinator(editor = editor, currentSession = session,
                    isInputViewVisible = visible, stopVoiceInput = {}, coroutineScope = backgroundScope,
                    recognitionEngine = engine, recognitionDispatcher = worker)
            }).use { s ->
            s.start()
            fun submit(value: Byte) = s.controller.recognizeHandwriting(
                emptyList(), 1f, 1f, byteArrayOf(value), 1, 1)
            val initialCanvas = s.snapshot().handwritingSession
            s.controller.onHandwritingStrokeStarted(true)
            submit(1)
            advanceTimeBy(999); runCurrent()
            assertEquals("", s.editor.text)
            assertEquals(initialCanvas, s.snapshot().handwritingSession)
            assertTrue(s.snapshot().handwritingBusy)
            // A further stroke extends the same character and restarts the idle window.
            s.controller.onHandwritingStrokeStarted(false)
            submit(1)
            advanceTimeBy(999); runCurrent()
            assertEquals("", s.editor.text)
            assertEquals(initialCanvas, s.snapshot().handwritingSession)
            advanceTimeBy(1); runCurrent()
            assertEquals("你", s.editor.composing)
            assertEquals(initialCanvas + 1, s.snapshot().handwritingSession)
            assertEquals(listOf("你"), s.snapshot().handwritingCandidates.map { it.text })
            assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
            assertFalse(s.snapshot().handwritingBusy)
            s.controller.onHandwritingStrokeStarted(true)
            submit(2)
            advanceTimeBy(1_000); runCurrent()
            assertEquals("你好", s.editor.text + s.editor.composing)
            assertEquals("好", s.editor.composing)
            assertEquals(initialCanvas + 2, s.snapshot().handwritingSession)
            s.choose("好")
            assertEquals("你好", s.editor.text)
            assertEquals("", s.editor.composing)
            // Closing a session cancels pending text and canvas updates.
            s.controller.onHandwritingStrokeStarted(true)
            submit(1)
            s.hide()
            val hiddenCanvas = s.snapshot().handwritingSession
            advanceTimeBy(2_000); runCurrent()
            assertEquals("你好", s.editor.text)
            assertEquals(hiddenCanvas, s.snapshot().handwritingSession)
        }
    }
}
