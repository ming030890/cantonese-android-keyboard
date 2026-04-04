package com.example.cantonesevoiceinput

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.cantonesevoiceinput.inference.NativeQwenAsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.max

class VoiceImeService : InputMethodService() {
    private var statusBadge: TextView? = null
    private var helperText: TextView? = null
    private var micButton: Button? = null

    private var localEngine: NativeQwenAsrEngine? = null
    private var engineError: String? = null
    private var streamedTranscript = ""

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var isRecording = false

    @Volatile
    private var capturedSampleCount = 0

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var transcribeJob: Job? = null
    private var engineInitJob: Job? = null
    private var liveTranscriptionSession: NativeQwenAsrEngine.LiveSession? = null

    override fun onCreate() {
        super.onCreate()
        ensureEngineReadyIfNeeded()
    }

    override fun onDestroy() {
        isRecording = false
        stopAndReleaseAudioRecord()
        liveTranscriptionSession?.finishInput()

        val pendingEngine = localEngine
        localEngine = null
        val pendingTranscribe = transcribeJob

        runCatching {
            serviceScope.cancel()
        }

        if (pendingEngine != null && pendingTranscribe?.isActive == true) {
            Thread {
                runCatching {
                    runBlocking {
                        pendingTranscribe.join()
                    }
                }
                runCatching {
                    pendingEngine.close()
                }
            }.start()
        } else {
            pendingEngine?.close()
        }
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.input_view, null)
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val gestureBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            v.setPadding(baseLeft, baseTop, baseRight, baseBottom + max(navBottom, gestureBottom))
            insets
        }
        ViewCompat.requestApplyInsets(view)

        statusBadge = view.findViewById(R.id.statusBadge)
        helperText = view.findViewById(R.id.helperText)
        micButton = view.findViewById(R.id.btnMic)

        view.findViewById<Button>(R.id.btnMic).setOnClickListener { onMicPressed() }
        view.findViewById<Button>(R.id.btnSpace).setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }
        view.findViewById<Button>(R.id.btnBackspace).setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
        view.findViewById<Button>(R.id.btnEnter).setOnClickListener {
            currentInputConnection?.commitText("\n", 1)
        }

        renderEngineState()
        return view
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (isRecording) {
            stopRecordingAndFinalizeTranscription()
        }
        super.onFinishInputView(finishingInput)
    }

    private fun ensureEngineReadyIfNeeded() {
        if (localEngine != null || engineInitJob?.isActive == true) {
            return
        }

        engineError = null
        engineInitJob = serviceScope.launch {
            renderInlineState(R.string.state_preparing, R.string.helper_preparing)

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val modelDir = ModelAssetInstaller.ensureInstalled(this@VoiceImeService)
                    NativeQwenAsrEngine(
                        modelDir = modelDir,
                        language = "Cantonese",
                        threads = 0,
                        segmentSec = 30f,
                        searchSec = 3f,
                        pastTextConditioning = true,
                        streamChunkSec = 2f,
                        streamRollbackTokens = 5,
                        streamUnfixedChunks = 2,
                        streamMaxNewTokens = 32
                    )
                }
            }

            result
                .onSuccess { engine ->
                    localEngine = engine
                    engineError = null
                }
                .onFailure { throwable ->
                    localEngine = null
                    engineError = throwable.message ?: "unknown"
                }

            renderEngineState()
        }
    }

    private fun renderEngineState() {
        when {
            localEngine != null -> {
                renderInlineState(R.string.state_ready, R.string.helper_ready)
            }

            engineInitJob?.isActive == true -> {
                renderInlineState(R.string.state_preparing, R.string.helper_preparing)
            }

            engineError != null -> {
                renderInlineState(R.string.state_unavailable, R.string.helper_unavailable)
            }

            else -> {
                renderInlineState(R.string.state_preparing, R.string.helper_preparing)
            }
        }

        updateMicButtonUi()
    }

    private fun onMicPressed() {
        ensureEngineReadyIfNeeded()

        val engine = localEngine
        if (engine == null) {
            if (engineInitJob?.isActive == true) {
                renderInlineState(R.string.state_preparing, R.string.helper_preparing)
            } else {
                renderInlineState(R.string.state_unavailable, R.string.helper_unavailable)
            }
            return
        }

        if (!hasMicPermission()) {
            renderInlineState(R.string.state_permission, R.string.helper_permission)
            return
        }

        if (isRecording) {
            stopRecordingAndFinalizeTranscription()
            return
        }

        if (transcribeJob?.isActive == true) {
            renderInlineState(R.string.state_working, R.string.helper_busy)
            return
        }

        startRecording(engine)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(engine: NativeQwenAsrEngine) {
        val sampleRate = 16_000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize <= 0) {
            renderInlineState(R.string.state_unavailable, R.string.helper_microphone_unavailable)
            return
        }

        val bufferSize = max(minBufferSize, sampleRate)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            renderInlineState(R.string.state_unavailable, R.string.helper_microphone_unavailable)
            return
        }

        streamedTranscript = ""
        val liveSession = runCatching {
            engine.createLiveTranscriptionSession(forceLanguage = true) { partialText ->
                if (partialText.isBlank()) {
                    return@createLiveTranscriptionSession
                }
                serviceScope.launch(Dispatchers.Main.immediate) {
                    commitStreamingDelta(partialText)
                }
            }
        }.getOrElse { throwable ->
            recorder.release()
            engineError = throwable.message ?: "stream init failed"
            renderInlineState(R.string.state_unavailable, R.string.helper_unavailable)
            return
        }

        liveTranscriptionSession = liveSession
        capturedSampleCount = 0
        audioRecord = recorder
        isRecording = true
        updateMicButtonUi()
        renderInlineState(R.string.state_listening, R.string.helper_listening)

        transcribeJob = serviceScope.launch(Dispatchers.Default) {
            val result = runCatching {
                liveSession.runBlocking()
            }

            if (liveTranscriptionSession === liveSession) {
                liveTranscriptionSession = null
            }
            liveSession.close()

            withContext(Dispatchers.Main.immediate) {
                result
                    .onSuccess { text ->
                        if (text.isBlank() && streamedTranscript.isBlank()) {
                            renderInlineState(R.string.state_try_again, R.string.helper_no_speech)
                        } else {
                            commitFinalTranscript(text)
                            renderInlineState(R.string.state_inserted, R.string.helper_inserted)
                        }
                    }
                    .onFailure { throwable ->
                        engineError = throwable.message ?: "transcription failed"
                        renderInlineState(R.string.state_unavailable, R.string.helper_unavailable)
                    }

                transcribeJob = null
                updateMicButtonUi()
            }
        }

        recorder.startRecording()
        recordingJob = serviceScope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(2048)
            while (isActive && isRecording) {
                val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    val floatChunk = FloatArray(read)
                    for (i in 0 until read) {
                        floatChunk[i] = shortBuffer[i] / 32768.0f
                    }
                    capturedSampleCount += read
                    liveSession.appendPcm16kMono(floatChunk)
                } else if (read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION) {
                    break
                }
            }
        }
    }

    private fun stopRecordingAndFinalizeTranscription() {
        isRecording = false
        updateMicButtonUi()
        stopAndReleaseAudioRecord()

        val readJob = recordingJob
        recordingJob = null
        val liveSession = liveTranscriptionSession

        serviceScope.launch {
            readJob?.join()

            if (capturedSampleCount <= 0) {
                renderInlineState(R.string.state_try_again, R.string.helper_no_speech)
            } else {
                renderInlineState(R.string.state_finishing, R.string.helper_finishing)
            }
            liveSession?.finishInput()
        }
    }

    private fun stopAndReleaseAudioRecord() {
        val recorder = audioRecord ?: return
        audioRecord = null
        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        }
        runCatching { recorder.release() }
    }

    private fun updateMicButtonUi() {
        val button = micButton ?: return
        if (isRecording) {
            button.text = getString(R.string.action_stop)
            button.setBackgroundResource(R.drawable.bg_mic_active)
            button.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
            return
        }

        button.text = getString(R.string.action_mic)
        button.setBackgroundResource(R.drawable.bg_mic_idle)
        button.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun renderInlineState(labelRes: Int, helperRes: Int) {
        statusBadge?.setText(labelRes)
        helperText?.setText(helperRes)
    }

    private fun commitStreamingDelta(partialText: String) {
        val delta = when {
            partialText == streamedTranscript -> ""
            partialText.startsWith(streamedTranscript) -> partialText.removePrefix(streamedTranscript)
            else -> ""
        }

        if (delta.isNotEmpty()) {
            currentInputConnection?.commitText(delta, 1)
        }
        if (partialText.startsWith(streamedTranscript) || streamedTranscript.isEmpty()) {
            streamedTranscript = partialText
        }
    }

    private fun commitFinalTranscript(text: String) {
        val delta = when {
            text.isBlank() -> ""
            streamedTranscript.isBlank() -> text
            text == streamedTranscript -> ""
            text.startsWith(streamedTranscript) -> text.removePrefix(streamedTranscript)
            else -> ""
        }

        if (delta.isNotEmpty()) {
            currentInputConnection?.commitText(delta, 1)
        }
        if (text.isNotBlank()) {
            streamedTranscript = text
        }
    }
}
