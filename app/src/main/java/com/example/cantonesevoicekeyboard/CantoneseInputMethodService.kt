package com.example.cantonesevoicekeyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.cantonesevoicekeyboard.R
import com.example.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme
import com.example.cantonesevoicekeyboard.ui.keyboard.KeyboardScreen
import com.qwenasr.QAsrEngine
import kotlinx.coroutines.CancellationException
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class CantoneseInputMethodService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "CantoneseIME"
        private const val SAMPLE_RATE = 16000
        private const val MAX_RECORD_SECONDS = 30
        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_RECORD_SECONDS
        private const val FLOAT_SIZE = 4
        private const val DIRECT_BUFFER_SIZE = MAX_SAMPLES * FLOAT_SIZE
        private const val MAX_PROMPT_CHARS = 160
    }

    data class AutoSendConfig(
        val silenceTimeoutMs: Long = 700,
        val speechThresholdRms: Float = 0.001f,
        val preRollMs: Long = 300,
        val minUtteranceMs: Long = 500,
        val maxUtteranceMs: Long = 30_000,
        val appendSpaceBetweenUtterances: Boolean = true,
    )

    private data class FrozenAudioSegment(
        val buffer: ByteBuffer,
        val samples: Int,
        val session: Long,
    )

    // Lifecycle, ViewModel, SavedState bridges for Compose support
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // ASR Engine & Audio State
    private val qAsrEngine = QAsrEngine()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val engineMutex = Mutex()
    private var isModelLoaded = false
    private var modelLoadingJob: Job? = null
    private var asrWorkerJob: Job? = null
    private var inputViewSession = 0L
    @Volatile
    private var isInputViewVisible = false

    private lateinit var directAudioBuffer: ByteBuffer
    private var totalSamplesRecorded = 0
    private val autoSendConfig = AutoSendConfig()
    private var previousUtterancePrompt: String = ""
    private var speechActive = false
    private var speechStartSample = 0
    private var lastSpeechSample = 0
    private val pendingSegments = Channel<FrozenAudioSegment>(capacity = Channel.UNLIMITED)
    private val pendingSegmentCount = AtomicInteger(0)

    // UI States
    private val _uiState = MutableStateFlow<KeyboardUiState>(KeyboardUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _volumeLevel = MutableStateFlow(0f)
    val volumeLevel = _volumeLevel.asStateFlow()

    private var audioRecordJob: Job? = null
    @Volatile
    private var activeAudioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Pre-allocate Direct ByteBuffer for zero-copy samples sharing with native side
        directAudioBuffer = ByteBuffer.allocateDirect(DIRECT_BUFFER_SIZE).order(ByteOrder.nativeOrder())
    }

    override fun onCreateInputView(): View {
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@CantoneseInputMethodService)
            setViewTreeViewModelStoreOwner(this@CantoneseInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@CantoneseInputMethodService)
            setContent {
                CantoneseVoiceKeyboardTheme {
                    KeyboardScreen(
                        uiState = uiState,
                        volumeLevel = volumeLevel,
                        onToggleRecording = { toggleRecording() },
                        onDeleteSurroundingText = { deleteSurroundingText() },
                        onSendSpace = { sendSpace() },
                        onSendEnter = { sendEnter() },
                        onSwitchKeyboard = { switchKeyboard() },
                        onOpenSettings = { openSettingsApp() }
                    )
                }
            }
        }
        return composeView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        isInputViewVisible = true
        val session = ++inputViewSession
        
        // Dynamically load the model asynchronously when keyboard opens
        if (!isModelLoaded && modelLoadingJob == null) {
            _uiState.value = KeyboardUiState.LoadingModel
            modelLoadingJob = serviceScope.launch(Dispatchers.Default) {
                val externalDir = getExternalFilesDir(null)
                if (externalDir == null) {
                    _uiState.value = KeyboardUiState.Error(getString(R.string.error_external_storage_unavailable))
                    modelLoadingJob = null
                    return@launch
                }
                
                val modelDir = File(externalDir, "qwen-asr-int8dec")
                if (!modelDir.exists()) {
                    Log.w(TAG, "Model directory not found at: ${modelDir.absolutePath}")
                    _uiState.value = KeyboardUiState.Error(getString(R.string.error_model_not_found))
                    modelLoadingJob = null
                    return@launch
                }
                
                Log.i(TAG, "Loading model from: ${modelDir.absolutePath}")
                val success = engineMutex.withLock {
                    val loaded = qAsrEngine.loadModel(modelDir.absolutePath, 1)
                    if (loaded) {
                        qAsrEngine.setLanguage("yue")
                        qAsrEngine.setPrompt("")
                        qAsrEngine.setSegmentSec(0.0f)
                    }
                    loaded
                }
                if (success) {
                    if (isInputViewVisible && session == inputViewSession) {
                        isModelLoaded = true
                        _uiState.value = KeyboardUiState.Idle
                        Log.i(TAG, "Model loaded successfully!")
                    } else {
                        Log.i(TAG, "Model loaded after keyboard hidden; freeing immediately.")
                        engineMutex.withLock {
                            qAsrEngine.free()
                        }
                        isModelLoaded = false
                    }
                } else {
                    _uiState.value = KeyboardUiState.Error(getString(R.string.error_failed_initialize_asr))
                    Log.e(TAG, "Failed to load model!")
                }
                modelLoadingJob = null
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        isInputViewVisible = false
        val finishingSession = inputViewSession

        // Cancel active recording or loading jobs if active
        stopRecordingInternal(discard = true)
        modelLoadingJob?.cancel()
        modelLoadingJob = null
        asrWorkerJob?.cancel()

        // Reclaim RAM optimization: free model allocations when keyboard is hidden
        serviceScope.launch(Dispatchers.Default) {
            audioRecordJob?.join()
            asrWorkerJob?.join()
            if (!isInputViewVisible && finishingSession == inputViewSession) {
                Log.i(TAG, "Freeing model to reclaim RAM...")
                engineMutex.withLock {
                    qAsrEngine.free()
                }
                isModelLoaded = false
                _uiState.value = KeyboardUiState.Idle
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        isInputViewVisible = false
        stopRecordingInternal(discard = true)
        
        // Final safety cleanup
        serviceScope.launch(Dispatchers.Default) {
            asrWorkerJob?.cancel()
            listOfNotNull(audioRecordJob, asrWorkerJob, modelLoadingJob).joinAll()
            engineMutex.withLock {
                qAsrEngine.free()
            }
            isModelLoaded = false
            serviceJob.cancel()
        }
    }

    private fun toggleRecording() {
        if (_uiState.value is KeyboardUiState.Error) {
            return
        }

        // Check recording permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _uiState.value = KeyboardUiState.Error(getString(R.string.error_recording_permission_required))
            return
        }

        if (isRecording) {
            stopRecordingInternal(discard = false)
        } else {
            startRecordingInternal()
        }
    }

    private fun startRecordingInternal() {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters for AudioRecord setup.")
            _uiState.value = KeyboardUiState.Error(getString(R.string.error_audio_format_failed))
            return
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Audio recording security exception", e)
            _uiState.value = KeyboardUiState.Error(getString(R.string.error_audio_permission_missing))
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize.")
            _uiState.value = KeyboardUiState.Error(getString(R.string.error_microphone_init_failed))
            audioRecord.release()
            return
        }

        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            _uiState.value = KeyboardUiState.Error(getString(R.string.error_failed_access_microphone))
            audioRecord.release()
            return
        }

        isRecording = true
        activeAudioRecord = audioRecord
        _uiState.value = KeyboardUiState.Recording
        totalSamplesRecorded = 0
        previousUtterancePrompt = ""
        resetVadState()
        drainPendingSegments()
        ensureAsrWorker()

        audioRecordJob = serviceScope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(1024)
            val floatBuffer = directAudioBuffer.asFloatBuffer()
            floatBuffer.clear()

            Log.i(TAG, "Recording started and mic is live...")

            while (isRecording) {
                val readShorts = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (readShorts > 0) {
                    var sumSquare = 0.0
                    val chunkStartSample = totalSamplesRecorded
                    for (i in 0 until readShorts) {
                        val sample = shortBuffer[i]
                        val floatSample = sample / 32768.0f
                        floatBuffer.put(totalSamplesRecorded % MAX_SAMPLES, floatSample)
                        totalSamplesRecorded++
                        sumSquare += floatSample * floatSample
                    }

                    // Calculate real-time RMS for waveform visuals
                    val rms = if (readShorts > 0) Math.sqrt(sumSquare / readShorts) else 0.0
                    val normalizedVolume = (rms * 3.0).toFloat().coerceIn(0f, 1f) // boost visually
                    _volumeLevel.value = normalizedVolume
                    updateVad(rms.toFloat(), chunkStartSample, readShorts)
                } else if (readShorts < 0) {
                    Log.e(TAG, "Error reading audio data: $readShorts")
                    break
                }
            }

            try {
                audioRecord.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord", e)
            } finally {
                audioRecord.release()
                if (activeAudioRecord === audioRecord) {
                    activeAudioRecord = null
                }
            }
            Log.i(TAG, "Recording finished. Recorded samples: $totalSamplesRecorded")
        }
    }

    private fun stopRecordingInternal(discard: Boolean) {
        val recorderJob = audioRecordJob
        if (!isRecording && recorderJob == null) return
        if (!discard) {
            flushActiveSpeechSegment(force = true)
        }
        isRecording = false
        try {
            activeAudioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting AudioRecord stop", e)
        }
        _volumeLevel.value = 0f

        if (discard) {
            _uiState.value = KeyboardUiState.Idle
            asrWorkerJob?.cancel()
            drainPendingSegments()
            resetVadState()
            serviceScope.launch(Dispatchers.IO) {
                recorderJob?.join()
                if (audioRecordJob === recorderJob) {
                    audioRecordJob = null
                }
            }
            return
        }

        _uiState.value = KeyboardUiState.Transcribing
        serviceScope.launch(Dispatchers.Default) {
            recorderJob?.join()
            if (audioRecordJob === recorderJob) {
                audioRecordJob = null
            }
            if (pendingSegmentCount.get() == 0) {
                _uiState.value = KeyboardUiState.Idle
            }
        }
    }

    private fun ensureAsrWorker() {
        val existingWorker = asrWorkerJob
        if (existingWorker?.isActive == true) return

        asrWorkerJob = serviceScope.launch(Dispatchers.Default) {
            try {
                for (segment in pendingSegments) {
                    try {
                        transcribeFrozenSegment(segment)
                    } finally {
                        val remainingSegments = pendingSegmentCount.updateAndGet { count ->
                            (count - 1).coerceAtLeast(0)
                        }
                        if (!isRecording && remainingSegments == 0) {
                            withContext(Dispatchers.Main) {
                                _uiState.value = KeyboardUiState.Idle
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "ASR worker failed", e)
                withContext(Dispatchers.Main) {
                    if (isInputViewVisible) {
                        _uiState.value = KeyboardUiState.Error(getString(R.string.error_failed_initialize_asr))
                    }
                }
            }
        }
    }

    private suspend fun transcribeFrozenSegment(segment: FrozenAudioSegment) {
        if (segment.samples <= 0) return

        Log.i(TAG, "Starting transcription of ${segment.samples} frozen samples...")
        val result = engineMutex.withLock {
            qAsrEngine.setPrompt(previousUtterancePrompt)
            qAsrEngine.transcribePcmDirect(segment.buffer, segment.samples)
        }
        Log.i(TAG, "Transcription complete. Result: $result")

        val finalText = result?.trim().orEmpty()

        if (finalText.isEmpty()) return

        withContext(Dispatchers.Main) {
            if (isInputViewVisible && segment.session == inputViewSession) {
                val textToCommit = if (
                    autoSendConfig.appendSpaceBetweenUtterances &&
                    previousUtterancePrompt.isNotEmpty()
                ) {
                    " $finalText"
                } else {
                    finalText
                }
                currentInputConnection?.commitText(textToCommit, 1)
                previousUtterancePrompt = finalText.takeLast(MAX_PROMPT_CHARS)
            }
        }
    }

    private fun updateVad(rms: Float, chunkStartSample: Int, readSamples: Int) {
        val chunkEndSampleExclusive = chunkStartSample + readSamples
        val isSpeech = rms >= autoSendConfig.speechThresholdRms
        val silenceSamples = msToSamples(autoSendConfig.silenceTimeoutMs)
        val maxUtteranceSamples = msToSamples(autoSendConfig.maxUtteranceMs)

        if (isSpeech) {
            if (!speechActive) {
                speechActive = true
                speechStartSample = max(0, chunkStartSample - msToSamples(autoSendConfig.preRollMs))
                Log.d(TAG, "Speech started at sample $speechStartSample")
            }
            lastSpeechSample = chunkEndSampleExclusive
        }

        if (!speechActive) return

        val speechDuration = chunkEndSampleExclusive - speechStartSample
        val silenceDuration = chunkEndSampleExclusive - lastSpeechSample
        if (speechDuration >= maxUtteranceSamples || (!isSpeech && silenceDuration >= silenceSamples)) {
            flushActiveSpeechSegment(force = false)
        }
    }

    private fun flushActiveSpeechSegment(force: Boolean) {
        if (!speechActive) return

        val segmentEndSample = if (force) totalSamplesRecorded else lastSpeechSample
        val segmentStartSample = speechStartSample.coerceAtLeast(max(0, segmentEndSample - MAX_SAMPLES))
        val segmentSamples = segmentEndSample - segmentStartSample
        val minUtteranceSamples = msToSamples(autoSendConfig.minUtteranceMs)

        if (segmentSamples >= minUtteranceSamples) {
            freezeSegment(segmentStartSample, segmentSamples)
        } else {
            Log.i(TAG, "Discarding short utterance: $segmentSamples samples")
        }

        speechActive = false
        speechStartSample = 0
        lastSpeechSample = 0
    }

    private fun freezeSegment(startSample: Int, sampleCount: Int) {
        val frozenBuffer = ByteBuffer
            .allocateDirect(sampleCount * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
        val liveFloatBuffer = directAudioBuffer.asFloatBuffer()
        val frozenFloatBuffer = frozenBuffer.asFloatBuffer()

        for (offset in 0 until sampleCount) {
            val sourceIndex = (startSample + offset) % MAX_SAMPLES
            frozenFloatBuffer.put(liveFloatBuffer.get(sourceIndex))
        }
        frozenBuffer.position(0)
        frozenBuffer.limit(sampleCount * FLOAT_SIZE)

        val segment = FrozenAudioSegment(
            buffer = frozenBuffer,
            samples = sampleCount,
            session = inputViewSession,
        )
        pendingSegmentCount.incrementAndGet()
        val result = pendingSegments.trySend(segment)
        if (result.isSuccess) {
            Log.i(TAG, "Queued frozen utterance: $sampleCount samples")
            ensureAsrWorker()
        } else {
            pendingSegmentCount.decrementAndGet()
            Log.e(TAG, "Failed to queue frozen utterance", result.exceptionOrNull())
        }
    }

    private fun resetVadState() {
        speechActive = false
        speechStartSample = 0
        lastSpeechSample = 0
    }

    private fun drainPendingSegments() {
        while (true) {
            pendingSegments.tryReceive().getOrNull() ?: break
        }
        pendingSegmentCount.set(0)
    }

    private fun msToSamples(ms: Long): Int {
        return ((SAMPLE_RATE * ms) / 1000L).toInt()
    }

    private fun deleteSurroundingText() {
        currentInputConnection?.deleteSelectionOrPreviousCharacter()
    }

    private fun InputConnection.deleteSelectionOrPreviousCharacter() {
        val selectedText = getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            commitText("", 1)
        } else {
            deleteSurroundingText(1, 0)
        }
    }

    private fun sendSpace() {
        currentInputConnection?.commitText(" ", 1)
    }

    private fun sendEnter() {
        val connection = currentInputConnection
        if (connection != null) {
            val editorInfo = currentInputEditorInfo
            val actionId = editorInfo?.actionId ?: 0
            if (actionId != 0) {
                connection.performEditorAction(actionId)
            } else {
                connection.commitText("\n", 1)
            }
        }
    }

    private fun switchKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val binder: IBinder? = window?.window?.attributes?.token
        if (binder != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                imm.switchToNextInputMethod(binder, false)
            }
        }
    }

    private fun openSettingsApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}

sealed interface KeyboardUiState {
    object Idle : KeyboardUiState
    object LoadingModel : KeyboardUiState
    object Recording : KeyboardUiState
    object Transcribing : KeyboardUiState
    data class Error(val message: String) : KeyboardUiState
}
