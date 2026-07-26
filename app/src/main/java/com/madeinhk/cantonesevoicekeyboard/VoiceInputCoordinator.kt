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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class VoiceInputCoordinator(
    private val context: Context,
    private val editor: ImeEditor,
    private val currentSession: () -> Long,
    private val isInputViewVisible: () -> Boolean,
) {
    companion object {
        private const val TAG = "CantoneseIME"
        private const val SAMPLE_RATE = 16000
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val engineMutex = Mutex()
    @Volatile private var engine: QAsrEngine? = null
    private var modelLoaded = false
    private var modelLoadingJob: Job? = null
    private var transcriptionJob: Job? = null
    private var audioRecordJob: Job? = null
    private var generation = 0L
    @Volatile private var activeAudioRecord: AudioRecord? = null
    @Volatile private var recording = false
    @Volatile private var activeDebugStore: DebugAudioStore? = null
    private var activeDebugTranscript = StringBuffer()

    private val _state = MutableStateFlow<KeyboardUiState>(KeyboardUiState.Idle)
    val state = _state.asStateFlow()
    private val _volume = MutableStateFlow(0f)
    val volume = _volume.asStateFlow()

    fun onInputViewStarted(session: Long) = ensureModelLoadingStarted(session)

    fun toggle() {
        if (_state.value is KeyboardUiState.Error) return
        if (
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

    fun stopForHandwriting() {
        val voiceState = _state.value
        if (
            !recording &&
            voiceState != KeyboardUiState.Recording &&
            voiceState != KeyboardUiState.Transcribing
        ) return
        generation++
        editor.setComposingText("")
        editor.finishComposingText()
        if (recording || voiceState == KeyboardUiState.Recording) {
            stop(discard = true)
        } else {
            _volume.value = 0f
            _state.value = KeyboardUiState.Idle
        }
    }

    fun isRecording(): Boolean = recording

    fun finish(releaseModel: Boolean, session: Long) {
        val stopJob = stop(discard = true)
        val jobs = listOfNotNull(
            audioRecordJob,
            transcriptionJob,
            modelLoadingJob,
            stopJob,
        )
        if (releaseModel) {
            modelLoadingJob?.cancel()
            modelLoadingJob = null
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

    fun destroy() {
        val stopJob = stop(discard = true)
        val jobs = listOfNotNull(audioRecordJob, transcriptionJob, modelLoadingJob, stopJob)
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
        if (modelLoadingJob?.isActive == true) {
            _state.value = KeyboardUiState.LoadingModel
            return
        }
        _state.value = KeyboardUiState.LoadingModel
        val load = scope.launch(Dispatchers.Default) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val modelDir = ModelAssetPack.resolveModelDir(context)
                if (modelDir == null) {
                    _state.value = KeyboardUiState.Error(
                        context.getString(R.string.error_model_not_found),
                    )
                    return@launch
                }
                val success = engineMutex.withLock {
                    val activeEngine = getOrCreateEngine()
                    val loaded = activeEngine.loadModel(modelDir.absolutePath, 1)
                    if (loaded) {
                        activeEngine.setLanguage("Cantonese")
                        activeEngine.setChineseVariant("")
                        activeEngine.setPrompt("")
                        activeEngine.setSegmentSec(0.0f)
                    }
                    loaded
                }
                if (success) {
                    // A loaded model is reusable across editor-session changes. Visibility,
                    // rather than the initiating session token, decides whether to retain it.
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
                        context.getString(R.string.error_failed_initialize_asr),
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
                        context.getString(R.string.error_failed_initialize_asr),
                    )
                }
            } finally {
                Log.i(TAG, "ASR load job completed in ${SystemClock.elapsedRealtime() - startedAt}ms")
            }
        }
        modelLoadingJob = load
        load.invokeOnCompletion { if (modelLoadingJob === load) modelLoadingJob = null }
    }

    private fun getOrCreateEngine(): QAsrEngine =
        engine ?: QAsrEngine().also { engine = it }

    private fun start() {
        if (recording || !modelLoaded) return
        val activeEngine = engine ?: run {
            _state.value = KeyboardUiState.Error(
                context.getString(R.string.error_failed_initialize_asr),
            )
            return
        }
        activeEngine.setPrompt("")
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            _state.value = KeyboardUiState.Error(
                context.getString(R.string.error_audio_format_failed),
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
                context.getString(R.string.error_audio_permission_missing),
            )
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            _state.value = KeyboardUiState.Error(
                context.getString(R.string.error_microphone_init_failed),
            )
            return
        }
        try {
            audioRecord.startRecording()
        } catch (error: IllegalStateException) {
            audioRecord.release()
            _state.value = KeyboardUiState.Error(
                context.getString(R.string.error_failed_access_microphone),
            )
            return
        }
        recording = true
        activeAudioRecord = audioRecord
        _state.value = KeyboardUiState.Recording
        val debugStore = DebugAudioStore.createIfEnabled(context)
        activeDebugStore = debugStore
        val debugTranscript = StringBuffer()
        activeDebugTranscript = debugTranscript
        val session = currentSession()
        val voiceGeneration = ++generation
        var composingTranscript = ""
        val listener = object : QAsrEngine.VadListener {
            override fun onTranscriptDelta(textDelta: String) {
                if (voiceGeneration != generation) return
                debugTranscript.append(textDelta)
                scope.launch {
                    if (
                        isInputViewVisible() &&
                        session == currentSession() &&
                        voiceGeneration == generation &&
                        textDelta.isNotEmpty()
                    ) {
                        composingTranscript += textDelta
                        editor.setComposingText(composingTranscript)
                    }
                }
            }

            override fun onTranscriptComplete() {
                scope.launch {
                    if (
                        isInputViewVisible() &&
                        session == currentSession() &&
                        voiceGeneration == generation
                    ) editor.finishComposingText()
                    composingTranscript = ""
                }
            }

            override fun onTranscriptError() {
                scope.launch {
                    if (
                        isInputViewVisible() &&
                        session == currentSession() &&
                        voiceGeneration == generation
                    ) {
                        editor.setComposingText("")
                        editor.finishComposingText()
                    }
                    composingTranscript = ""
                }
            }
        }
        if (!activeEngine.startVadSession(listener, debugStore?.directory?.absolutePath)) {
            recording = false
            runCatching { audioRecord.stop() }
            audioRecord.release()
            activeAudioRecord = null
            debugStore?.closeAndDelete()
            activeDebugStore = null
            _state.value = KeyboardUiState.Idle
            return
        }
        audioRecordJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteBuffer.allocateDirect(2048).order(ByteOrder.nativeOrder())
            try {
                while (recording) {
                    buffer.clear()
                    val bytesRead = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
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
                        activeEngine.pushPcm16Chunk(buffer, bytesRead)
                        val rms = if (samplesRead > 0) {
                            Math.sqrt(sumSquare / samplesRead)
                        } else 0.0
                        _volume.value = (rms * 3.0).toFloat().coerceIn(0f, 1f)
                    } else if (bytesRead < 0) break
                }
                debugStore?.closeInput()
            } catch (error: Exception) {
                Log.w(TAG, "Error while recording audio", error)
                debugStore?.closeAndDelete()
                if (activeDebugStore === debugStore) activeDebugStore = null
            }
            runCatching { audioRecord.stop() }
            audioRecord.release()
            if (activeAudioRecord === audioRecord) activeAudioRecord = null
        }
    }

    private fun stop(discard: Boolean): Job? {
        val recorder = audioRecordJob
        val debugStore = activeDebugStore
        if (!recording && recorder == null) return null
        recording = false
        runCatching { activeAudioRecord?.stop() }
        _volume.value = 0f
        if (discard) {
            _state.value = KeyboardUiState.Idle
            return scope.launch(Dispatchers.IO) {
                recorder?.join()
                engineMutex.withLock { engine?.stopVadSession(finalize = false) }
                debugStore?.closeAndDelete()
                if (activeDebugStore === debugStore) activeDebugStore = null
                if (audioRecordJob === recorder) audioRecordJob = null
            }
        }
        _state.value = KeyboardUiState.Transcribing
        val stoppedGeneration = generation
        val completedTranscript = activeDebugTranscript
        return scope.launch(Dispatchers.Default) {
            recorder?.join()
            if (audioRecordJob === recorder) audioRecordJob = null
            engineMutex.withLock { engine?.stopVadSession(finalize = true) }
            if (debugStore != null) {
                if (stoppedGeneration == generation) {
                    runCatching { debugStore.finish(completedTranscript.toString()) }
                } else {
                    debugStore.closeAndDelete()
                }
            }
            if (activeDebugStore === debugStore) activeDebugStore = null
            withContext(Dispatchers.Main) {
                if (stoppedGeneration == generation) _state.value = KeyboardUiState.Idle
            }
            transcriptionJob = null
        }.also { transcriptionJob = it }
    }
}
