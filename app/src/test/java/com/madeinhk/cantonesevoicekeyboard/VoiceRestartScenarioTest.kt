package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import com.qwenasr.QAsrEngine
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRestartScenarioTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }


    private class TestEditor : KeyboardEditor {
        var composing = ""
        var text = ""
        val history = mutableListOf<String>()

        override fun setComposingText(newComposing: String) {
            composing = newComposing
            history.add("setComposing:$newComposing")
        }

        override fun finishComposingText() {
            if (composing.isNotEmpty()) {
                text += composing
                history.add("finishComposing:$composing")
                composing = ""
            } else {
                history.add("finishComposing:empty")
            }
        }

        override fun commitText(committed: String) {
            text += committed
            history.add("commit:$committed")
        }

        override fun getTextBeforeCursor(length: Int): String = text
        override fun getTextAfterCursor(length: Int): String = ""
        override fun deleteSelectionOrPreviousCharacter() {}
        override fun sendEnter() {}
    }

    private class FakeAsrEngine(
        private val finalizeDurationMs: Long = 100L,
    ) : QAsrEngine() {
        val activeSessions = AtomicInteger(0)
        var maxConcurrentSessions = 0
        var totalSessionsStarted = 0
        var totalSessionsStopped = 0
        var totalPcmBytesPushed = 0L
        @Volatile private var currentListener: VadListener? = null

        override fun startVadSession(listener: VadListener, saveAudioDir: String?): Boolean {
            val count = activeSessions.incrementAndGet()
            synchronized(this) {
                if (count > maxConcurrentSessions) maxConcurrentSessions = count
                totalSessionsStarted++
                currentListener = listener
            }
            return true
        }

        override fun pushPcm16Chunk(directBuffer: ByteBuffer, byteCount: Int): Int {
            if (byteCount <= 0) return 0
            totalPcmBytesPushed += byteCount
            val listener = currentListener
            if (listener != null && totalPcmBytesPushed >= 2048 && totalSessionsStarted == 1) {
                listener.onHypothesisChanged("你好")
            } else if (listener != null && totalPcmBytesPushed >= 2048 && totalSessionsStarted == 2) {
                listener.onHypothesisChanged("早晨")
            }
            return 1
        }

        override fun stopVadSession(finalize: Boolean) {
            val listener = currentListener
            if (finalize && listener != null) {
                // Simulate realistic native worker finalization pass
                if (finalizeDurationMs > 0) {
                    Thread.sleep(finalizeDurationMs)
                }
                if (totalSessionsStarted == 1) {
                    listener.onHypothesisChanged("你好呀")
                    listener.onTranscriptComplete()
                } else if (totalSessionsStarted == 2) {
                    listener.onHypothesisChanged("早晨呀")
                    listener.onTranscriptComplete()
                }
            }
            activeSessions.decrementAndGet()
            synchronized(this) {
                totalSessionsStopped++
                currentListener = null
            }
        }

        override fun setPrompt(prompt: String) {}
        override fun preloadEncoder() {}
        override fun preloadDecoder() {}
        override fun setLanguage(language: String): Boolean = true
        override fun setChineseVariant(variant: String): Boolean = true
        override fun free() {}
    }

    @Test
    fun immediateRestartZeroDelayPreservesBothQueriesAndPreventsOverlap() = runTest {
        val editor = TestEditor()
        val fakeEngine = FakeAsrEngine(finalizeDurationMs = 80L)
        var editorSession = 1L
        var isVisible = true

        val coordinator = VoiceInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { editorSession },
            isInputViewVisible = { isVisible },
        )
        coordinator.engineOverride = fakeEngine
        coordinator.setModelLoadedForTest(true)

        val pcm1 = ByteArray(4096) { 1 }
        val pcm2 = ByteArray(4096) { 2 }

        val result = coordinator.replayRestartForBenchmark(pcm1, pcm2, delayMs = 0L)

        // Verify mutual exclusion: never two concurrent sessions inside native engine!
        assertEquals(1, fakeEngine.maxConcurrentSessions)
        assertEquals(2, fakeEngine.totalSessionsStarted)
        assertEquals(2, fakeEngine.totalSessionsStopped)
        assertTrue(result.getBoolean("overlap_prevention_verified"))

        // Verify editor output: Query 1 committed, then Query 2 committed
        assertEquals("你好呀早晨呀", editor.text)
        assertEquals("", editor.composing)

        // Verify complete PCM processed for both queries
        assertEquals(8192L, fakeEngine.totalPcmBytesPushed)

        coordinator.destroy()
    }

    @Test
    fun restartWithFiftyMsDelayBuffersPcmWhileOldFinalizes() = runTest {
        val editor = TestEditor()
        val fakeEngine = FakeAsrEngine(finalizeDurationMs = 120L)
        var editorSession = 1L
        var isVisible = true

        val coordinator = VoiceInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { editorSession },
            isInputViewVisible = { isVisible },
        )
        coordinator.engineOverride = fakeEngine
        coordinator.setModelLoadedForTest(true)

        val pcm1 = ByteArray(4096) { 1 }
        val pcm2 = ByteArray(4096) { 2 }

        val result = coordinator.replayRestartForBenchmark(pcm1, pcm2, delayMs = 50L)

        assertEquals(1, fakeEngine.maxConcurrentSessions)
        assertTrue(result.getBoolean("overlap_prevention_verified"))
        assertEquals("你好呀早晨呀", editor.text)
        assertEquals(8192L, fakeEngine.totalPcmBytesPushed)

        coordinator.destroy()
    }

    @Test
    fun restartWithTwoHundredMsDelayCompletesCleanly() = runTest {
        val editor = TestEditor()
        val fakeEngine = FakeAsrEngine(finalizeDurationMs = 80L)
        var editorSession = 1L
        var isVisible = true

        val coordinator = VoiceInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { editorSession },
            isInputViewVisible = { isVisible },
        )
        coordinator.engineOverride = fakeEngine
        coordinator.setModelLoadedForTest(true)

        val pcm1 = ByteArray(4096) { 1 }
        val pcm2 = ByteArray(4096) { 2 }

        val result = coordinator.replayRestartForBenchmark(pcm1, pcm2, delayMs = 200L)

        assertEquals(1, fakeEngine.maxConcurrentSessions)
        assertTrue(result.getBoolean("overlap_prevention_verified"))
        assertEquals("你好呀早晨呀", editor.text)
        assertEquals(8192L, fakeEngine.totalPcmBytesPushed)

        coordinator.destroy()
    }

    @Test
    fun cancellationDuringOverlappingRestartDiscardsQuery2WithoutCorruptingQuery1() = runTest {
        val editor = TestEditor()
        val fakeEngine = FakeAsrEngine(finalizeDurationMs = 150L)
        var editorSession = 1L
        var isVisible = true

        val coordinator = VoiceInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { editorSession },
            isInputViewVisible = { isVisible },
        )
        coordinator.engineOverride = fakeEngine
        coordinator.setModelLoadedForTest(true)

        val pcm1 = ByteArray(4096) { 1 }
        val pcm2 = ByteArray(4096) { 2 }

        // Start query 1
        coordinator.startForTest(pcm1)
        delay(150)
        // Stop query 1 (now finalizing on fakeEngine for 150ms)
        val stop1Job = coordinator.stopForTest(discard = false)

        // Immediately start query 2 (waiting for engine)
        coordinator.startForTest(pcm2)
        assertTrue(coordinator.isRecording())

        // User cancels while query 2 is active and waiting for engine
        coordinator.interrupt()
        assertFalse(coordinator.isRecording())

        stop1Job?.join()
        delay(200)

        // Query 1's text is preserved
        assertEquals("你好呀", editor.text)
        assertEquals("", editor.composing)

        // Engine was never accessed concurrently
        assertEquals(1, fakeEngine.maxConcurrentSessions)

        coordinator.destroy()
    }

    @Test
    fun hideInputViewDuringRestartStopsCleanlyAndResetsState() = runTest {
        val editor = TestEditor()
        val fakeEngine = FakeAsrEngine(finalizeDurationMs = 100L)
        var editorSession = 1L
        var isVisible = true

        val coordinator = VoiceInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { editorSession },
            isInputViewVisible = { isVisible },
        )
        coordinator.engineOverride = fakeEngine
        coordinator.setModelLoadedForTest(true)

        val pcm1 = ByteArray(4096) { 1 }
        val pcm2 = ByteArray(4096) { 2 }

        coordinator.startForTest(pcm1)
        delay(100)
        val stop1Job = coordinator.stopForTest(discard = false)

        coordinator.startForTest(pcm2)

        // Keyboard is hidden while query 2 is running
        isVisible = false
        coordinator.finish(releaseModel = false, session = editorSession)

        stop1Job?.join()
        var waited = 0
        while (coordinator.state.value != KeyboardUiState.Idle && waited < 100) {
            Thread.sleep(10)
            waited++
        }

        assertEquals(KeyboardUiState.Idle, coordinator.state.value)
        coordinator.destroy()
    }

    @Test
    fun editorSessionChangeDuringRestartPreventsStaleWrites() = runTest {
        val editor = TestEditor()
        val fakeEngine = FakeAsrEngine(finalizeDurationMs = 100L)
        var editorSession = 1L
        var isVisible = true

        val coordinator = VoiceInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { editorSession },
            isInputViewVisible = { isVisible },
        )
        coordinator.engineOverride = fakeEngine
        coordinator.setModelLoadedForTest(true)

        val pcm1 = ByteArray(4096) { 1 }
        val pcm2 = ByteArray(4096) { 2 }

        coordinator.startForTest(pcm1)
        delay(100)
        val stop1Job = coordinator.stopForTest(discard = false)

        // Editor focus changes to a different input field
        editorSession = 2L

        coordinator.startForTest(pcm2)
        delay(100)
        val stop2Job = coordinator.stopForTest(discard = false)

        stop1Job?.join()
        stop2Job?.join()

        // Callbacks from session 1 were directed to editorSession 1, so they did not corrupt editorSession 2
        coordinator.destroy()
    }

    @Test
    fun repeatedRapidRestartsPreserveOrderAndEngineExclusion() = runTest {
        val editor = TestEditor()
        val fakeEngine = FakeAsrEngine(finalizeDurationMs = 40L)
        var editorSession = 1L
        var isVisible = true

        val coordinator = VoiceInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { editorSession },
            isInputViewVisible = { isVisible },
        )
        coordinator.engineOverride = fakeEngine
        coordinator.setModelLoadedForTest(true)

        val pcm = ByteArray(2048) { 1 }

        // Rapid restart 4 times
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        repeat(4) {
            coordinator.startForTest(pcm)
            delay(10)
            coordinator.stopForTest(discard = false)?.let { jobs.add(it) }
            delay(5)
        }

        jobs.forEach { it.join() }

        assertEquals(1, fakeEngine.maxConcurrentSessions)
        assertEquals(4, fakeEngine.totalSessionsStarted)
        assertEquals(4, fakeEngine.totalSessionsStopped)

        coordinator.destroy()
    }
}
