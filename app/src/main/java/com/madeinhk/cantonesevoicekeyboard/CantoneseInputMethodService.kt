package com.madeinhk.cantonesevoicekeyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardAction
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardScreen
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CantoneseInputMethodService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "CantoneseIME"
        private const val SAMPLE_RATE = 16000
        private const val PROMPT_TEXT_BEFORE_CURSOR_CHARS = 160
        private const val PROMPT_TEXT_AFTER_CURSOR_CHARS = 80
        private const val INLINE_SUGGESTION_MIN_WIDTH_DP = 72
        private const val INLINE_SUGGESTION_HEIGHT_DP = 40
        private const val INLINE_SUGGESTION_MAX_COUNT = 3
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
    @Volatile
    private var qAsrEngine: QAsrEngine? = null
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

    private val _keyboardAction = MutableStateFlow(KeyboardAction.Enter)
    val keyboardAction = _keyboardAction.asStateFlow()

    private val _keyboardInputMode = MutableStateFlow(KeyboardInputMode.Voice)
    val keyboardInputMode = _keyboardInputMode.asStateFlow()

    private val _inlineSuggestionViews = MutableStateFlow<List<View>>(emptyList())
    val inlineSuggestionViews = _inlineSuggestionViews.asStateFlow()
    private var inlineSuggestionsGeneration = 0L

    private var audioRecordJob: Job? = null
    @Volatile
    private var activeAudioRecord: AudioRecord? = null
    @Volatile
    private var isRecording = false

    override fun onCreate() {
        val startedAt = SystemClock.elapsedRealtime()
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        Log.i(TAG, "onCreate completed in ${SystemClock.elapsedRealtime() - startedAt}ms")
    }

    override fun onCreateInputView(): View {
        val startedAt = SystemClock.elapsedRealtime()
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        applyNavigationBarAppearance()

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@CantoneseInputMethodService)
            setViewTreeViewModelStoreOwner(this@CantoneseInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@CantoneseInputMethodService)
            setContent {
                CantoneseVoiceKeyboardTheme {
                    KeyboardScreen(
                        uiState = uiState,
                        volumeLevel = volumeLevel,
                        keyboardAction = keyboardAction,
                        keyboardInputMode = keyboardInputMode,
                        inlineSuggestionViews = inlineSuggestionViews,
                        onToggleRecording = { toggleRecording() },
                        onDeleteSurroundingText = { deleteSurroundingText() },
                        onCommitText = { text -> commitText(text) },
                        onSendSpace = { sendSpace() },
                        onSendEnter = { sendEnter() },
                        onOpenSettings = { openSettingsApp() },
                        onEmojiPicked = { emoji -> sendEmoji(emoji) }
                    )
                }
            }
        }
        Log.i(TAG, "onCreateInputView completed in ${SystemClock.elapsedRealtime() - startedAt}ms")
        return composeView
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        _keyboardAction.value = keyboardActionFor(info)
        val inputMode = keyboardInputModeFor(info)
        _keyboardInputMode.value = inputMode
        inputViewSession++
        clearInlineSuggestions()
        if (!restarting) {
            stopRecordingInternal(discard = true)
            _volumeLevel.value = 0f
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        val startedAt = SystemClock.elapsedRealtime()
        super.onStartInputView(info, restarting)
        _keyboardAction.value = keyboardActionFor(info)
        val inputMode = keyboardInputModeFor(info)
        _keyboardInputMode.value = inputMode
        clearInlineSuggestions()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        isInputViewVisible = true
        // Re-apply in case the system day/night mode changed while hidden.
        applyNavigationBarAppearance()
        val session = ++inputViewSession
        ensureModelLoadingStarted(session)
        Log.i(TAG, "onStartInputView completed in ${SystemClock.elapsedRealtime() - startedAt}ms")
    }

    /**
     * Matches the navigation-bar icon tint to the keyboard's theme so the system
     * IME-switch ("globe") button and gesture handle stay visible: dark icons on
     * the light-theme keyboard, light icons on the dark-theme keyboard.
     */
    private fun applyNavigationBarAppearance() {
        val w = window?.window ?: return
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(w, w.decorView).isAppearanceLightNavigationBars = !isNight
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
            val loadStartedAt = SystemClock.elapsedRealtime()
            try {
                // The model ships as fast-follow Play Asset Delivery packs. This
                // suspends until the pack is downloaded if it is not already present.
                val resolveStartedAt = SystemClock.elapsedRealtime()
                val modelDir = ModelAssetPack.resolveModelDir(this@CantoneseInputMethodService)
                Log.i(
                    TAG,
                    "Model directory resolve completed in ${SystemClock.elapsedRealtime() - resolveStartedAt}ms"
                )
                if (modelDir == null) {
                    Log.w(TAG, "Model asset packs unavailable")
                    _uiState.value = KeyboardUiState.Error(getString(R.string.error_model_not_found))
                    return@launch
                }

                Log.i(TAG, "Loading model from: ${modelDir.absolutePath}")
                val success = engineMutex.withLock {
                    val engine = getOrCreateAsrEngine()
                    val nativeLoadStartedAt = SystemClock.elapsedRealtime()
                    val loaded = engine.loadModel(modelDir.absolutePath, 1)
                    Log.i(
                        TAG,
                        "Native model load completed in ${SystemClock.elapsedRealtime() - nativeLoadStartedAt}ms"
                    )
                    if (loaded) {
                        engine.setLanguage("Cantonese")
                        engine.setChineseVariant("")
                        engine.setPrompt("")
                        engine.setSegmentSec(0.0f)
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
                            qAsrEngine?.free()
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
            } finally {
                Log.i(TAG, "ASR load job completed in ${SystemClock.elapsedRealtime() - loadStartedAt}ms")
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
        clearInlineSuggestions()
        finishInputSession(releaseModel = false)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        clearInlineSuggestions()
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
                    qAsrEngine?.free()
                }
                isModelLoaded = false
                _uiState.value = KeyboardUiState.Idle
            } else if (!releaseModel && finishingSession == inputViewSession) {
                engineMutex.withLock {
                    if (isModelLoaded) {
                        qAsrEngine?.setPrompt("")
                    }
                }
                _uiState.value = KeyboardUiState.Idle
            }
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        if (
            oldSelStart != newSelStart ||
            oldSelEnd != newSelEnd ||
            candidatesStart != candidatesEnd
        ) {
            clearInlineSuggestions()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        inputViewSession++
        clearInlineSuggestions()
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
        clearInlineSuggestions()
        stopRecordingInternal(discard = true)
        
        // Final safety cleanup
        serviceScope.launch(Dispatchers.Default) {
            listOfNotNull(audioRecordJob, transcriptionJob, modelLoadingJob).joinAll()
            engineMutex.withLock {
                qAsrEngine?.free()
            }
            isModelLoaded = false
            serviceJob.cancel()
        }
    }

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        clearInlineSuggestions()
        val spec = InlinePresentationSpec.Builder(
            Size(dpToPx(INLINE_SUGGESTION_MIN_WIDTH_DP), dpToPx(INLINE_SUGGESTION_HEIGHT_DP)),
            Size(getMaxWidth().coerceAtLeast(dpToPx(INLINE_SUGGESTION_MIN_WIDTH_DP)), dpToPx(INLINE_SUGGESTION_HEIGHT_DP))
        )
            .setStyle(uiExtras)
            .build()

        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(INLINE_SUGGESTION_MAX_COUNT)
            .build()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            clearInlineSuggestions()
            return true
        }

        val generation = inlineSuggestionsGeneration
        val inflatedViews = arrayOfNulls<View>(suggestions.size)
        val size = Size(
            getMaxWidth().coerceAtLeast(dpToPx(INLINE_SUGGESTION_MIN_WIDTH_DP)),
            dpToPx(INLINE_SUGGESTION_HEIGHT_DP)
        )
        suggestions.forEachIndexed { index, suggestion ->
            inflateInlineSuggestion(suggestion, size) { view ->
                if (generation != inlineSuggestionsGeneration || !isInputViewVisible) {
                    return@inflateInlineSuggestion
                }
                (view.parent as? ViewGroup)?.removeView(view)
                inflatedViews[index] = view
                _inlineSuggestionViews.value = inflatedViews.filterNotNull()
            }
        }
        return true
    }

    private fun inflateInlineSuggestion(
        suggestion: InlineSuggestion,
        size: Size,
        onInflated: (InlineContentView) -> Unit
    ) {
        try {
            suggestion.inflate(this, size, mainExecutor, onInflated)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to inflate inline suggestion", t)
        }
    }

    private fun clearInlineSuggestions() {
        inlineSuggestionsGeneration++
        if (_inlineSuggestionViews.value.isNotEmpty()) {
            _inlineSuggestionViews.value = emptyList()
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun getOrCreateAsrEngine(): QAsrEngine {
        qAsrEngine?.let { return it }
        val startedAt = SystemClock.elapsedRealtime()
        return QAsrEngine().also {
            qAsrEngine = it
            Log.i(TAG, "QAsrEngine created in ${SystemClock.elapsedRealtime() - startedAt}ms")
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
        val engine = qAsrEngine ?: run {
            Log.e(TAG, "Cannot start recording: ASR engine is unavailable")
            _uiState.value = KeyboardUiState.Error(getString(R.string.error_failed_initialize_asr))
            return
        }
        val cursorPrompt = buildCursorContextPrompt(currentInputConnection)
        engine.setPrompt(cursorPrompt)

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

        val listener = object : QAsrEngine.VadListener {
            override fun onTranscript(segmentId: Long, text: String, isFinal: Boolean) {
                serviceScope.launch(Dispatchers.Main.immediate) {
                    if (
                        isInputViewVisible &&
                        session == inputViewSession &&
                        text.trim().isNotEmpty()
                    ) {
                        currentInputConnection?.commitText(text, 1)
                    }
                }
            }
        }

        val vadStarted = engine.startVadSession(listener)
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
            val debugAudioStore = DebugAudioStore.createIfEnabled(this@CantoneseInputMethodService)

            Log.i(TAG, "Recording started and mic is live...")

            try {
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
                        debugAudioStore?.writePcm16(directPcm16Buffer, bytesRead)
                        directPcm16Buffer.position(0)
                        engine.pushPcm16Chunk(directPcm16Buffer, bytesRead)

                        // Calculate real-time RMS for waveform visuals
                        val rms = if (samplesRead > 0) Math.sqrt(sumSquare / samplesRead) else 0.0
                        val normalizedVolume = (rms * 3.0).toFloat().coerceIn(0f, 1f) // boost visually
                        _volumeLevel.value = normalizedVolume
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "Error reading audio data: $bytesRead")
                        break
                    }
                }
                debugAudioStore?.closeAndKeepLatest()
            } catch (e: Exception) {
                Log.w(TAG, "Error while recording audio", e)
                debugAudioStore?.closeAndDelete()
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
                    qAsrEngine?.stopVadSession(finalize = false)
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
                qAsrEngine?.stopVadSession(finalize = true)
            }

            withContext(Dispatchers.Main) {
                _uiState.value = KeyboardUiState.Idle
            }
            transcriptionJob = null
        }
    }

    private fun buildCursorContextPrompt(connection: InputConnection?): String {
        if (connection == null) return ""

        runCatching {
            connection.getExtractedText(ExtractedTextRequest(), 0)
        }.getOrNull()?.let { extractedText ->
            val text = extractedText.text?.toString().orEmpty()
            val selectionStart = extractedText.selectionStart
            val selectionEnd = extractedText.selectionEnd
            if (text.isNotEmpty() && selectionStart >= 0 && selectionEnd >= 0) {
                val contextStart = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
                val contextEnd = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
                return buildPromptFromContext(
                    before = text.substring(0, contextStart),
                    after = text.substring(contextEnd)
                )
            }
        }

        val before = runCatching {
            connection.getTextBeforeCursor(PROMPT_TEXT_BEFORE_CURSOR_CHARS, 0)
        }.getOrNull()
            ?.toString()
            .orEmpty()

        val after = runCatching {
            connection.getTextAfterCursor(PROMPT_TEXT_AFTER_CURSOR_CHARS, 0)
        }.getOrNull()
            ?.toString()
            .orEmpty()

        return buildPromptFromContext(before, after)
    }

    private fun buildPromptFromContext(before: String, after: String): String {
        val normalizedBefore = before
            .normalizePromptWhitespace()
            .takeLast(PROMPT_TEXT_BEFORE_CURSOR_CHARS)
        val normalizedAfter = after
            .normalizePromptWhitespace()
            .take(PROMPT_TEXT_AFTER_CURSOR_CHARS)

        return when {
            normalizedBefore.isEmpty() && normalizedAfter.isEmpty() -> ""
            normalizedAfter.isEmpty() -> "前文：$normalizedBefore"
            normalizedBefore.isEmpty() -> "後文：$normalizedAfter"
            else -> "前文：$normalizedBefore\n後文：$normalizedAfter"
        }
    }

    private fun String.normalizePromptWhitespace(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun deleteSurroundingText() {
        clearInlineSuggestions()
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
        clearInlineSuggestions()
        currentInputConnection?.commitText(" ", 1)
    }

    private fun commitText(text: String) {
        clearInlineSuggestions()
        currentInputConnection?.commitText(text, 1)
    }

    private fun sendEnter() {
        clearInlineSuggestions()
        val connection = currentInputConnection
        if (connection != null) {
            val editorInfo = currentInputEditorInfo
            val actionId = editorInfo?.actionId ?: 0
            val imeAction = editorInfo.standardImeAction()
            if (actionId != 0) {
                connection.performEditorAction(actionId)
            } else if (imeAction != EditorInfo.IME_ACTION_NONE) {
                connection.performEditorAction(imeAction)
            } else {
                connection.commitText("\n", 1)
            }
        }
    }

    private fun keyboardActionFor(editorInfo: EditorInfo?): KeyboardAction =
        when (editorInfo.standardImeAction()) {
            EditorInfo.IME_ACTION_GO -> KeyboardAction.Go
            EditorInfo.IME_ACTION_SEARCH -> KeyboardAction.Search
            EditorInfo.IME_ACTION_SEND -> KeyboardAction.Send
            EditorInfo.IME_ACTION_NEXT -> KeyboardAction.Next
            EditorInfo.IME_ACTION_PREVIOUS -> KeyboardAction.Previous
            EditorInfo.IME_ACTION_DONE -> KeyboardAction.Done
            else -> KeyboardAction.Enter
        }

    private fun EditorInfo?.standardImeAction(): Int {
        if (this == null) return EditorInfo.IME_ACTION_NONE
        if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
            return EditorInfo.IME_ACTION_NONE
        }
        return imeOptions and EditorInfo.IME_MASK_ACTION
    }

    private fun keyboardInputModeFor(editorInfo: EditorInfo?): KeyboardInputMode {
        val inputType = editorInfo?.inputType ?: return KeyboardInputMode.Voice
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val inputVariation = inputType and InputType.TYPE_MASK_VARIATION

        return when {
            inputVariation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> KeyboardInputMode.AlphaNum
            inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE ||
                inputClass == InputType.TYPE_CLASS_DATETIME -> KeyboardInputMode.Number
            inputClass == InputType.TYPE_CLASS_TEXT && inputVariation in passwordVariations ->
                KeyboardInputMode.AlphaNum
            else -> KeyboardInputMode.Voice
        }
    }

    private val passwordVariations = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    )

    private fun sendEmoji(emoji: String) {
        clearInlineSuggestions()
        currentInputConnection?.commitText(emoji, 1)
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
