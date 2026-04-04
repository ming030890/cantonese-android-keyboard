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
    private var statusText: TextView? = null
    private var transcriptPreview: TextView? = null
    private var micButton: Button? = null

    private var localEngine: NativeQwenAsrEngine? = null
    private var engineError: String? = null

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
            v.setPadding(baseLeft, baseTop, baseRight, baseBottom + navBottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)

        statusText = view.findViewById(R.id.statusText)
        transcriptPreview = view.findViewById(R.id.transcriptPreview)
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
            statusText?.text = getString(R.string.status_model_installing_assets)

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
                    engineError = getString(
                        R.string.status_model_error,
                        throwable.message ?: "unknown"
                    )
                }

            renderEngineState()
        }
    }

    private fun renderEngineState() {
        when {
            localEngine != null -> {
                statusText?.text = getString(R.string.status_idle_local)
                transcriptPreview?.text = getString(R.string.preview_empty)
            }

            engineInitJob?.isActive == true -> {
                statusText?.text = getString(R.string.status_model_installing_assets)
                transcriptPreview?.text = getString(R.string.preview_empty)
            }

            engineError != null -> {
                statusText?.text = engineError
                transcriptPreview?.text = getString(R.string.preview_empty)
            }

            else -> {
                statusText?.text = getString(R.string.status_model_loading)
                transcriptPreview?.text = getString(R.string.preview_empty)
            }
        }

        updateMicButtonUi()
    }

    private fun onMicPressed() {
        ensureEngineReadyIfNeeded()

        val engine = localEngine
        if (engine == null) {
            statusText?.text = if (engineInitJob?.isActive == true) {
                getString(R.string.status_model_installing_assets)
            } else {
                engineError ?: getString(R.string.status_model_assets_missing)
            }
            return
        }

        if (!hasMicPermission()) {
            statusText?.text = getString(R.string.status_permission_required_ime)
            return
        }

        if (isRecording) {
            stopRecordingAndFinalizeTranscription()
            return
        }

        if (transcribeJob?.isActive == true) {
            statusText?.text = getString(R.string.status_busy_transcribing)
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
            statusText?.text = getString(R.string.status_model_error, "AudioRecord init failed")
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
            statusText?.text = getString(R.string.status_model_error, "Microphone unavailable")
            return
        }

        val liveSession = runCatching {
            engine.createLiveTranscriptionSession(forceLanguage = true) { partialText ->
                transcriptPreview?.post {
                    if (partialText.isNotBlank()) {
                        transcriptPreview?.text = partialText
                    }
                }
            }
        }.getOrElse { throwable ->
            recorder.release()
            statusText?.text = getString(
                R.string.status_model_error,
                throwable.message ?: "Live streaming init failed"
            )
            return
        }

        liveTranscriptionSession = liveSession
        capturedSampleCount = 0
        audioRecord = recorder
        isRecording = true
        updateMicButtonUi()
        statusText?.text = getString(R.string.status_listening)
        transcriptPreview?.text = getString(R.string.preview_empty)

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
                        if (text.isBlank()) {
                            statusText?.text = getString(R.string.status_no_speech)
                            transcriptPreview?.text = getString(R.string.preview_empty)
                        } else {
                            currentInputConnection?.commitText(text, 1)
                            statusText?.text = getString(R.string.status_inserted_done)
                            transcriptPreview?.text = text
                        }
                    }
                    .onFailure { throwable ->
                        statusText?.text = getString(
                            R.string.status_model_error,
                            throwable.message ?: "transcription failed"
                        )
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
                statusText?.text = getString(R.string.status_no_speech)
                transcriptPreview?.text = getString(R.string.preview_empty)
            } else {
                val audioSec = capturedSampleCount / 16_000f
                statusText?.text = getString(R.string.status_transcribing_with_length, audioSec)
                if (transcriptPreview?.text.isNullOrBlank()) {
                    transcriptPreview?.text = getString(R.string.preview_transcribing)
                }
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
        button.setBackgroundResource(R.drawable.bg_key)
        button.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
