package com.madeinhk.cantonesevoicekeyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.qwenasr.QAsrEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class VoiceInputCoordinator(
    private val context: Context? = null,
    private val editor: KeyboardEditor,
    private val currentSession: () -> Long,
    private val isInputViewVisible: () -> Boolean,
) : VoiceSession {
    companion object {
        private const val TAG = "CantoneseIME"
        private const val SAMPLE_RATE = 16000
        private const val ASR_WORKER_THREADS = 1
        // Only populated in debug builds; the debug activity drives the actual IME instance.
        internal var debugInstance: VoiceInputCoordinator? = null
            private set
    }

    init { if (BuildConfig.DEBUG) debugInstance = this }

    private var latency: VoiceLatencyTrace? = null

    internal val debugVisible: Boolean get() = isInputViewVisible()

    private class CoordinatorSession(
        val sessionToken: Long,
        val generation: Long,
        val editorSession: Long,
        val trace: VoiceLatencyTrace,
        val audioSource: VoiceAudioSource,
        val debugStore: DebugAudioStore?,
        val debugTranscript: StringBuffer,
        val audioChannel: Channel<ByteArray>,
        val stopDeferred: CompletableDeferred<Boolean>,
        val recorderStopped: CompletableDeferred<Unit>,
    ) {
        var audioJob: Job? = null
        var engineJob: Job? = null
    }

    internal suspend fun replayForBenchmark(pcm: ByteArray): JSONObject {
        check(BuildConfig.DEBUG)
        val requestedAt = VoiceClock.nanoTime()
        check(!recording && activeSession?.engineJob?.isActive != true)
        require(pcm.isNotEmpty() && pcm.size % 2 == 0)
        ensureModelLoadingStarted(currentSession())
        modelLoadingJob?.join()
        check(modelLoaded) { "ASR model is not ready: ${_state.value}" }
        val readyAt = VoiceClock.nanoTime()
        start(pcm)
        check(recording) { "Voice session failed to start" }
        val session = checkNotNull(activeSession)
        session.recorderStopped.await()
        stop(discard = false)?.join()
        context?.let { session.trace.recordContext(it) }
        val result = session.trace.summary()
        val modelWaitMs = (readyAt - requestedAt) / 1e6
        result.put("model_wait_ms", modelWaitMs)
        if (result.has("editor_text")) {
            result.put("request_to_first_text_ms", modelWaitMs + result.getDouble("editor_text"))
        }
        check(result.getDouble("audio_ms") * 32 == pcm.size.toDouble() && result.has("feed_end")) {
            "Replay did not feed the complete audio file: $result"
        }
        check(!result.has("editor_skipped")) { "Editor session changed during replay: $result" }
        check(result.getInt("rejected_chunks") == 0) { "Replay dropped audio chunks" }
        return result
    }

    internal suspend fun replayRestartForBenchmark(
        pcm1: ByteArray,
        pcm2: ByteArray,
        delayMs: Long,
    ): JSONObject {
        check(BuildConfig.DEBUG)
        val requestedAt = VoiceClock.nanoTime()
        check(!recording && activeSession?.engineJob?.isActive != true)
        require(pcm1.isNotEmpty() && pcm1.size % 2 == 0)
        require(pcm2.isNotEmpty() && pcm2.size % 2 == 0)
        ensureModelLoadingStarted(currentSession())
        modelLoadingJob?.join()
        check(modelLoaded) { "ASR model is not ready: ${_state.value}" }
        val readyAt = VoiceClock.nanoTime()

        // --- Query 1 ---
        val q1StartAt = VoiceClock.nanoTime()
        start(pcm1)
        check(recording) { "Query 1 failed to start" }
        val session1 = checkNotNull(activeSession)
        session1.recorderStopped.await()
        val stop1Job = checkNotNull(stop(discard = false))

        // Inter-query delay before requesting query 2
        if (delayMs > 0) {
            kotlinx.coroutines.delay(delayMs)
        }

        // --- Query 2 ---
        val q2StartAt = VoiceClock.nanoTime()
        start(pcm2)
        check(recording) { "Query 2 failed to start" }
        val session2 = checkNotNull(activeSession)
        session2.recorderStopped.await()
        val stop2Job = checkNotNull(stop(discard = false))

        // Await both queries to complete
        stop1Job.join()
        stop2Job.join()

        context?.let { session1.trace.recordContext(it) }
        context?.let { session2.trace.recordContext(it) }
        val result1 = session1.trace.summary()
        val result2 = session2.trace.summary()

        val modelWaitMs = (readyAt - requestedAt) / 1e6
        result1.put("model_wait_ms", modelWaitMs)
        result1.put("request_to_idle_ms", (VoiceClock.nanoTime() - q1StartAt) / 1e6)
        result2.put("request_to_idle_ms", (VoiceClock.nanoTime() - q2StartAt) / 1e6)
        if (result2.has("editor_text")) {
            result2.put("request_to_first_text_ms", result2.getDouble("editor_text"))
        }

        check(result1.getDouble("audio_ms") * 32 == pcm1.size.toDouble() && result1.has("feed_end")) {
            "Query 1 incomplete audio: $result1"
        }
        check(result2.getDouble("audio_ms") * 32 == pcm2.size.toDouble() && result2.has("feed_end")) {
            "Query 2 incomplete audio: $result2"
        }
        check(result1.getInt("rejected_chunks") == 0 && result2.getInt("rejected_chunks") == 0) {
            "Audio chunks rejected during restart replay"
        }
        check(!result1.has("editor_skipped") && !result2.has("editor_skipped")) {
            "Editor skipped during restart replay"
        }

        val q1LockReleased = result1.optJSONObject("milestone_uptimes_ms")?.optDouble("engine_lock_released", -1.0) ?: -1.0
        val q2EngineAvailable = result2.optJSONObject("milestone_uptimes_ms")?.optDouble("engine_available", -1.0) ?: -1.0
        val overlapPrevented = q1LockReleased >= 0.0 && q2EngineAvailable >= 0.0 && q1LockReleased <= q2EngineAvailable

        return JSONObject()
            .put("restart_delay_ms", delayMs)
            .put("overlap_prevention_verified", overlapPrevented)
            .put("query1_stop_to_idle_ms", result1.optDouble("stop_to_idle_ms", 0.0))
            .put("query2_first_text_ms", result2.optDouble("editor_text", -1.0))
            .put("query2_engine_wait_ms", result2.optDouble("capture_to_engine_available_ms", 0.0))
            .put("query_1", result1)
            .put("query_2", result2)
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val engineMutex = Mutex()
    @Volatile private var engine: QAsrEngine? = null
    private var modelLoaded = false
    private var modelLoadingJob: Job? = null
    private var generation = 0L
    private var sessionCounter = 0L
    @Volatile private var activeSession: CoordinatorSession? = null
    @Volatile private var recording = false

    private val _state = MutableStateFlow<KeyboardUiState>(KeyboardUiState.Idle)
    override val state = _state.asStateFlow()
    private val _volume = MutableStateFlow(0f)
    override val volume = _volume.asStateFlow()

    override fun onInputViewStarted(session: Long) = ensureModelLoadingStarted(session)

    override fun toggle() {
        val error = _state.value as? KeyboardUiState.Error
        if (error != null) {
            if (error.canRetryModelDownload) ensureModelLoadingStarted(currentSession())
            return
        }
        if (
            context != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = KeyboardUiState.Error(
                context.getString(R.string.error_recording_permission_required),
            )
            return
        }
        if (recording) stop(discard = false) else start()
    }

    override fun interrupt() {
        val voiceState = _state.value
        val session = activeSession
        if (
            !recording &&
            session?.engineJob?.isActive != true &&
            voiceState != KeyboardUiState.Recording &&
            voiceState != KeyboardUiState.Transcribing
        ) return
        generation++
        editor.finishComposingText()
        if (recording || voiceState == KeyboardUiState.Recording) {
            stop(discard = true)
        } else {
            session?.stopDeferred?.complete(true)
            _volume.value = 0f
            _state.value = KeyboardUiState.Idle
        }
    }

    override fun stopForHandwriting() = interrupt()

    override fun isRecording(): Boolean = recording

    override fun finish(releaseModel: Boolean, session: Long) {
        val stopJob = stop(discard = true)
        val active = activeSession
        val jobs = listOfNotNull(
            active?.audioJob,
            active?.engineJob,
            modelLoadingJob,
            stopJob,
        )
        if (releaseModel) {
            modelLoadingJob?.cancel()
        }
        scope.launch(Dispatchers.Default) {
            jobs.joinAll()
            if (releaseModel && !isInputViewVisible() && session == currentSession()) {
                engineMutex.withLock { engine?.free() }
                modelLoaded = false
                _state.value = KeyboardUiState.Idle
            } else if (!releaseModel && session == currentSession()) {
                engineMutex.withLock { if (modelLoaded) engine?.setPrompt("") }
                _state.value = KeyboardUiState.Idle
            }
        }
    }

    override fun destroy() {
        if (debugInstance === this) debugInstance = null
        val stopJob = stop(discard = true)
        val active = activeSession
        val jobs = listOfNotNull(active?.audioJob, active?.engineJob, modelLoadingJob, stopJob)
        jobs.forEach(Job::cancel)
        scope.launch(Dispatchers.Default) {
            jobs.joinAll()
            engineMutex.withLock { engine?.free() }
            modelLoaded = false
            job.cancel()
        }
    }

    private fun ensureModelLoadingStarted(session: Long) {
        if (!isInputViewVisible()) {
            _state.value = KeyboardUiState.Idle
            return
        }
        if (modelLoaded) {
            _state.value = KeyboardUiState.Idle
            return
        }
        if (modelLoadingJob?.isCompleted == false) {
            return
        }
        _state.value = KeyboardUiState.LoadingModel
        val load = scope.launch(Dispatchers.Default) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                VoiceLatencyTrace.event("model_resolve_begin")
                val modelDir = context?.let { ctx ->
                    ModelAssetPack.resolveModelDir(ctx) {
                        _state.value = KeyboardUiState.DownloadingModel
                    }
                }
                if (modelDir == null) {
                    _state.value = KeyboardUiState.Error(
                        context?.getString(R.string.error_model_not_found) ?: "Model not found",
                        canRetryModelDownload = true,
                    )
                    return@launch
                }
                VoiceLatencyTrace.event("model_resolve_end")
                _state.value = KeyboardUiState.LoadingModel
                VoiceLatencyTrace.event("model_load_begin")
                val success = engineMutex.withLock {
                    val activeEngine = getOrCreateEngine()
                    val loaded = activeEngine.loadModel(modelDir.absolutePath, ASR_WORKER_THREADS)
                    if (loaded) {
                        activeEngine.setLanguage("Cantonese")
                        activeEngine.setChineseVariant("")
                        activeEngine.setPrompt("")
                        activeEngine.setSegmentSec(0.0f)
                    }
                    loaded
                }
                VoiceLatencyTrace.event("model_load_end", "success" to success)
                if (success) {
                    if (isInputViewVisible()) {
                        modelLoaded = true
                        _state.value = KeyboardUiState.Idle
                    } else {
                        engineMutex.withLock { engine?.free() }
                        modelLoaded = false
                        _state.value = KeyboardUiState.Idle
                    }
                } else {
                    _state.value = KeyboardUiState.Error(
                        context?.getString(R.string.error_failed_initialize_asr) ?: "Failed to initialize ASR",
                    )
                }
            } catch (error: CancellationException) {
                if (session == currentSession() && isInputViewVisible() && !modelLoaded) {
                    _state.value = KeyboardUiState.Idle
                }
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Unexpected error while loading ASR model", error)
                if (isInputViewVisible()) {
                    _state.value = KeyboardUiState.Error(
                        context?.getString(R.string.error_failed_initialize_asr) ?: "Failed to initialize ASR",
                    )
                }
            } finally {
                Log.i(TAG, "ASR load job completed in ${SystemClock.elapsedRealtime() - startedAt}ms")
            }
        }
        modelLoadingJob = load
        load.invokeOnCompletion { cause ->
            if (modelLoadingJob === load) modelLoadingJob = null
            if (cause is CancellationException && isInputViewVisible() && !modelLoaded) {
                scope.launch { ensureModelLoadingStarted(currentSession()) }
            }
        }
    }

    internal var engineOverride: QAsrEngine? = null
    internal fun setModelLoadedForTest(loaded: Boolean) {
        modelLoaded = loaded
    }

    private fun getOrCreateEngine(): QAsrEngine =
        engineOverride ?: engine ?: QAsrEngine().also { engine = it }

    internal fun startForTest(replayPcm: ByteArray? = null) = start(replayPcm)
    internal fun stopForTest(discard: Boolean = false): Job? = stop(discard)

    private fun start(replayPcm: ByteArray? = null) {
        if (recording || !modelLoaded) return
        val activeEngine = getOrCreateEngine()

        val trace = VoiceLatencyTrace(replayPcm != null).also { latency = it }
        trace.mark("capture_start")

        val audioSource: VoiceAudioSource = if (replayPcm != null) {
            ReplayVoiceAudioSource(replayPcm)
        } else {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                _state.value = KeyboardUiState.Error(
                    context?.getString(R.string.error_audio_format_failed) ?: "Audio format failed",
                )
                return
            }
            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 2,
                )
            } catch (error: SecurityException) {
                _state.value = KeyboardUiState.Error(
                    context?.getString(R.string.error_audio_permission_missing) ?: "Audio permission missing",
                )
                return
            }
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                _state.value = KeyboardUiState.Error(
                    context?.getString(R.string.error_microphone_init_failed) ?: "Microphone init failed",
                )
                return
            }
            try {
                audioRecord.startRecording()
            } catch (error: IllegalStateException) {
                audioRecord.release()
                _state.value = KeyboardUiState.Error(
                    context?.getString(R.string.error_failed_access_microphone) ?: "Failed to access microphone",
                )
                return
            }
            MicrophoneVoiceAudioSource(audioRecord)
        }

        recording = true
        _state.value = KeyboardUiState.Recording

        val debugStore = context?.let { DebugAudioStore.createIfEnabled(it) }
        val debugTranscript = StringBuffer()
        val sessionEditorId = currentSession()
        val voiceGeneration = ++generation
        val sessionToken = ++sessionCounter
        val audioChannel = Channel<ByteArray>(capacity = 256)
        val stopDeferred = CompletableDeferred<Boolean>()
        val recorderStopped = CompletableDeferred<Unit>()

        val session = CoordinatorSession(
            sessionToken = sessionToken,
            generation = voiceGeneration,
            editorSession = sessionEditorId,
            trace = trace,
            audioSource = audioSource,
            debugStore = debugStore,
            debugTranscript = debugTranscript,
            audioChannel = audioChannel,
            stopDeferred = stopDeferred,
            recorderStopped = recorderStopped,
        )
        activeSession = session

        // 1. Audio Capture Job on IO dispatcher: captures immediately into bounded channel
        val audioJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteBuffer.allocateDirect(2048).order(ByteOrder.nativeOrder())
            try {
                while (recording && activeSession === session) {
                    buffer.clear()
                    val bytesRead = audioSource.read(buffer)
                    if (bytesRead > 0) {
                        val samplesRead = bytesRead / 2
                        var sumSquare = 0.0
                        val volumeBuffer = buffer.duplicate().order(ByteOrder.nativeOrder())
                        repeat(samplesRead) {
                            val sample = volumeBuffer.getShort() / 32768.0f
                            sumSquare += sample * sample
                        }
                        buffer.position(0)
                        debugStore?.writePcm16(buffer, bytesRead)
                        buffer.position(0)
                        val chunk = ByteArray(bytesRead)
                        buffer.get(chunk)
                        val sent = audioChannel.trySend(chunk)
                        if (!sent.isSuccess) {
                            audioChannel.send(chunk)
                        }
                        val rms = if (samplesRead > 0) {
                            Math.sqrt(sumSquare / samplesRead)
                        } else 0.0
                        if (activeSession === session) {
                            _volume.value = (rms * 3.0).toFloat().coerceIn(0f, 1f)
                        }
                    } else if (bytesRead < 0) break
                }
                trace.mark("feed_end")
                debugStore?.closeInput()
            } catch (error: Exception) {
                Log.w(TAG, "Error while recording audio", error)
                debugStore?.closeAndDelete()
            } finally {
                audioChannel.close()
                trace.mark("recorder_stopped")
                recorderStopped.complete(Unit)
                runCatching { audioSource.stop() }
                audioSource.release()
            }
        }
        session.audioJob = audioJob

        // 2. Engine Processing Job on Default dispatcher: owns the engine exclusively
        val engineJob = scope.launch(Dispatchers.Default) {
            trace.mark("engine_lock_wait_begin")
            engineMutex.withLock {
                trace.mark("engine_lock_acquired")
                trace.mark("engine_available")

                // If discarded before engine acquired, skip execution
                if (stopDeferred.isCompleted && stopDeferred.await()) {
                    trace.mark("native_stop_return", "discarded_early" to true)
                    debugStore?.closeAndDelete()
                    trace.mark("engine_lock_released")
                    return@withLock
                }

                activeEngine.setPrompt("")
                activeEngine.preloadEncoder()
                trace.mark("encoder_preload_return")

                val listener = object : QAsrEngine.VadListener {
                    override fun onTranscriptDelta(textDelta: String) {
                        if (stopDeferred.isCompleted && stopDeferred.getCompleted()) return
                        debugTranscript.append(textDelta)
                        trace.mark("stable_delta", "chars" to textDelta.length)
                    }

                    override fun onHypothesisChanged(text: String) {
                        if (stopDeferred.isCompleted && stopDeferred.getCompleted()) return
                        val callbackAt = VoiceClock.nanoTime()
                        trace.mark("hypothesis_callback", "chars" to text.length)
                        scope.launch {
                            if (
                                isInputViewVisible() &&
                                sessionEditorId == currentSession() &&
                                !(stopDeferred.isCompleted && stopDeferred.getCompleted())
                            ) {
                                editor.setComposingText(text)
                                trace.editor(text, callbackAt)
                            } else {
                                trace.mark("editor_skipped", "visible" to isInputViewVisible(),
                                    "session" to currentSession(), "expected_session" to sessionEditorId)
                            }
                        }
                    }

                    override fun onTranscriptComplete() {
                        trace.mark("utterance_complete")
                        scope.launch {
                            if (
                                isInputViewVisible() &&
                                sessionEditorId == currentSession() &&
                                !(stopDeferred.isCompleted && stopDeferred.getCompleted())
                            ) {
                                editor.finishComposingText()
                            }
                        }
                    }

                    override fun onTranscriptError() {
                        scope.launch {
                            if (
                                isInputViewVisible() &&
                                sessionEditorId == currentSession() &&
                                !(stopDeferred.isCompleted && stopDeferred.getCompleted())
                            ) {
                                editor.setComposingText("")
                                editor.finishComposingText()
                            }
                        }
                    }
                }

                if (!activeEngine.startVadSession(listener, debugStore?.directory?.absolutePath)) {
                    trace.mark("session_start_failed")
                    runCatching { audioSource.stop() }
                    debugStore?.closeAndDelete()
                    if (voiceGeneration == generation) {
                        _state.value = KeyboardUiState.Idle
                    }
                    trace.mark("engine_lock_released")
                    return@withLock
                }
                trace.mark("session_ready")

                val directBuffer = ByteBuffer.allocateDirect(2048).order(ByteOrder.nativeOrder())
                for (chunk in audioChannel) {
                    directBuffer.clear()
                    directBuffer.put(chunk)
                    directBuffer.position(0)
                    val pushAt = VoiceClock.nanoTime()
                    val accepted = activeEngine.pushPcm16Chunk(directBuffer, chunk.size)
                    trace.audio(chunk.size, pushAt, accepted)
                    check(accepted >= 0) { "Native audio queue rejected PCM" }
                }

                // Audio input feed completed; await stop decision
                val discard = stopDeferred.await()
                trace.mark("native_stop_call", "discard" to discard)
                activeEngine.stopVadSession(finalize = !discard)
                trace.mark("native_stop_return")

                if (debugStore != null) {
                    if (!discard) {
                        runCatching { debugStore.finish(debugTranscript.toString()) }
                    } else {
                        debugStore.closeAndDelete()
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!discard && isInputViewVisible() && sessionEditorId == currentSession()) {
                        editor.finishComposingText()
                        trace.mark("last_editor_update")
                    }
                    if (voiceGeneration == generation) {
                        _state.value = KeyboardUiState.Idle
                        trace.mark("idle")
                    }
                }
                trace.mark("engine_lock_released")
            }
        }
        session.engineJob = engineJob
    }

    private fun stop(discard: Boolean): Job? {
        val session = activeSession ?: return null
        if (!recording && session.engineJob?.isActive != true) return null
        val trace = session.trace
        trace.mark("stop_requested", "discard" to discard)
        recording = false
        runCatching { session.audioSource.stop() }
        _volume.value = 0f
        if (!discard) {
            _state.value = KeyboardUiState.Transcribing
        }
        session.stopDeferred.complete(discard)
        return session.engineJob
    }
}
