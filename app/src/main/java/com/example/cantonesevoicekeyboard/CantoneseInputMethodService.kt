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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CantoneseInputMethodService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "CantoneseIME"
        private const val SAMPLE_RATE = 16000
    }

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
    private var transcriptionJob: Job? = null
    private var inputViewSession = 0L
    @Volatile
    private var isInputViewVisible = false

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

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        inputViewSession++
        if (!restarting) {
            stopRecordingInternal(discard = true)
            _volumeLevel.value = 0f
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        isInputViewVisible = true
        val session = ++inputViewSession
        ensureModelLoadingStarted(session)
    }

    private fun ensureModelLoadingStarted(session: Long = inputViewSession) {
        if (!isInputViewVisible) {
            _uiState.value = KeyboardUiState.Idle
            return
        }
        if (isModelLoaded) {
            _uiState.value = KeyboardUiState.Idle
            return
        }

        val existingJob = modelLoadingJob
        if (existingJob?.isActive == true) {
            _uiState.value = KeyboardUiState.LoadingModel
            return
        }
        modelLoadingJob = null
        _uiState.value = KeyboardUiState.LoadingModel

        val loadingJob = serviceScope.launch(Dispatchers.Default) {
            try {
                val externalDir = getExternalFilesDir(null)
                if (externalDir == null) {
                    _uiState.value = KeyboardUiState.Error(getString(R.string.error_external_storage_unavailable))
                    return@launch
                }
                
                val modelDir = File(externalDir, "qwen-asr-int8dec")
                if (!modelDir.exists()) {
                    Log.w(TAG, "Model directory not found at: ${modelDir.absolutePath}")
                    _uiState.value = KeyboardUiState.Error(getString(R.string.error_model_not_found))
                    return@launch
                }
                
                Log.i(TAG, "Loading model from: ${modelDir.absolutePath}")
                val success = engineMutex.withLock {
                    val loaded = qAsrEngine.loadModel(modelDir.absolutePath, 1)
                    if (loaded) {
                        qAsrEngine.setLanguage("Cantonese")
                        qAsrEngine.setPrompt("")
                        qAsrEngine.setSegmentSec(0.0f)
                    }
                    loaded
                }
                if (success) {
                    if (isInputViewVisible) {
                        isModelLoaded = true
                        _uiState.value = KeyboardUiState.Idle
                        Log.i(TAG, "Model loaded successfully!")
                    } else {
                        Log.i(TAG, "Model loaded after keyboard hidden; freeing immediately.")
                        engineMutex.withLock {
                            qAsrEngine.free()
                        }
                        isModelLoaded = false
                        _uiState.value = KeyboardUiState.Idle
                    }
                } else {
                    _uiState.value = KeyboardUiState.Error(getString(R.string.error_failed_initialize_asr))
                    Log.e(TAG, "Failed to load model!")
                }
            } catch (e: CancellationException) {
                if (session == inputViewSession && isInputViewVisible && !isModelLoaded) {
                    _uiState.value = KeyboardUiState.Idle
                }
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected error while loading ASR model", t)
                if (isInputViewVisible) {
                    _uiState.value = KeyboardUiState.Error(getString(R.string.error_failed_initialize_asr))
                }
            }
        }
        modelLoadingJob = loadingJob
        loadingJob.invokeOnCompletion {
            if (modelLoadingJob === loadingJob) {
                modelLoadingJob = null
            }
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        inputViewSession++
        finishInputSession(releaseModel = false)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        isInputViewVisible = false
        finishInputSession(releaseModel = true)
    }

    private fun finishInputSession(releaseModel: Boolean) {
        val finishingSession = inputViewSession

        // Cancel active recording or loading jobs if active
        stopRecordingInternal(discard = true)
        if (releaseModel) {
            modelLoadingJob?.cancel()
            modelLoadingJob = null
        }

        // Reclaim RAM optimization: free model allocations when keyboard is hidden
        serviceScope.launch(Dispatchers.Default) {
            audioRecordJob?.join()
            transcriptionJob?.join()
            if (releaseModel && !isInputViewVisible && finishingSession == inputViewSession) {
                Log.i(TAG, "Freeing model to reclaim RAM...")
                engineMutex.withLock {
                    qAsrEngine.free()
                }
                isModelLoaded = false
                _uiState.value = KeyboardUiState.Idle
            } else if (!releaseModel && finishingSession == inputViewSession) {
                engineMutex.withLock {
                    if (isModelLoaded) {
                        qAsrEngine.setPrompt("")
                    }
                }
                _uiState.value = KeyboardUiState.Idle
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        inputViewSession++
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        isInputViewVisible = false
        finishInputSession(releaseModel = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        isInputViewVisible = false
        stopRecordingInternal(discard = true)
        
        // Final safety cleanup
        serviceScope.launch(Dispatchers.Default) {
            listOfNotNull(audioRecordJob, transcriptionJob, modelLoadingJob).joinAll()
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
        if (!isModelLoaded) {
            Log.w(TAG, "Cannot start recording: ASR model is not loaded")
            return
        }

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
        val session = inputViewSession
        val committedSegmentIds = mutableSetOf<Long>()
        var lastCommittedTranscript = ""

        val listener = object : QAsrEngine.VadListener {
            override fun onTranscript(segmentId: Long, text: String, isFinal: Boolean) {
                serviceScope.launch(Dispatchers.Main.immediate) {
                    val normalizedText = normalizeTranscriptForDedup(text)
                    if (
                        isInputViewVisible &&
                        session == inputViewSession &&
                        normalizedText.isNotEmpty() &&
                        committedSegmentIds.add(segmentId) &&
                        normalizedText != lastCommittedTranscript
                    ) {
                        lastCommittedTranscript = normalizedText
                        currentInputConnection?.commitText(text, 1)
                    }
                }
            }
        }

        val vadStarted = qAsrEngine.startVadSession(listener)
        if (!vadStarted) {
            Log.e(TAG, "Failed to start native VAD session.")
            isRecording = false
            try {
                audioRecord.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord after VAD start failure", e)
            }
            audioRecord.release()
            activeAudioRecord = null
            _uiState.value = KeyboardUiState.Idle
            return
        }

        audioRecordJob = serviceScope.launch(Dispatchers.IO) {
            val directPcm16Buffer = ByteBuffer
                .allocateDirect(2048)
                .order(ByteOrder.nativeOrder())

            Log.i(TAG, "Recording started and mic is live...")

            while (isRecording) {
                directPcm16Buffer.clear()
                val bytesRead = audioRecord.read(
                    directPcm16Buffer,
                    directPcm16Buffer.capacity(),
                    AudioRecord.READ_BLOCKING
                )
                if (bytesRead > 0) {
                    val samplesRead = bytesRead / 2
                    var sumSquare = 0.0
                    val volumeBuffer = directPcm16Buffer.duplicate().order(ByteOrder.nativeOrder())
                    volumeBuffer.position(0)
                    for (i in 0 until samplesRead) {
                        val sample = volumeBuffer.getShort()
                        val floatSample = sample / 32768.0f
                        sumSquare += floatSample * floatSample
                    }
                    directPcm16Buffer.position(0)
                    qAsrEngine.pushPcm16Chunk(directPcm16Buffer, bytesRead)

                    // Calculate real-time RMS for waveform visuals
                    val rms = if (samplesRead > 0) Math.sqrt(sumSquare / samplesRead) else 0.0
                    val normalizedVolume = (rms * 3.0).toFloat().coerceIn(0f, 1f) // boost visually
                    _volumeLevel.value = normalizedVolume
                } else if (bytesRead < 0) {
                    Log.e(TAG, "Error reading audio data: $bytesRead")
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
            Log.i(TAG, "Recording finished.")
        }
    }

    private fun stopRecordingInternal(discard: Boolean) {
        val recorderJob = audioRecordJob
        if (!isRecording && recorderJob == null) return
        isRecording = false
        try {
            activeAudioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting AudioRecord stop", e)
        }
        _volumeLevel.value = 0f

        if (discard) {
            _uiState.value = KeyboardUiState.Idle
            serviceScope.launch(Dispatchers.IO) {
                recorderJob?.join()
                engineMutex.withLock {
                    qAsrEngine.stopVadSession(finalize = false)
                }
                if (audioRecordJob === recorderJob) {
                    audioRecordJob = null
                }
            }
            return
        }

        _uiState.value = KeyboardUiState.Transcribing

        transcriptionJob = serviceScope.launch(Dispatchers.Default) {
            recorderJob?.join()
            if (audioRecordJob === recorderJob) {
                audioRecordJob = null
            }
            engineMutex.withLock {
                qAsrEngine.stopVadSession(finalize = true)
            }

            withContext(Dispatchers.Main) {
                _uiState.value = KeyboardUiState.Idle
            }
            transcriptionJob = null
        }
    }

    private fun normalizeTranscriptForDedup(text: String): String =
        text.trim().replace(Regex("\\s+"), " ")

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
