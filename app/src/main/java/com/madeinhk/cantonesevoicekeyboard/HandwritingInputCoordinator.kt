package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.util.Log
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingInferenceIdleDelayMillis
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognitionEngine
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognitionException
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognitionQueue
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class HandwritingInputCoordinator(
    private val context: Context? = null,
    private val editor: ImeEditor,
    private val currentSession: () -> Long,
    private val isInputViewVisible: () -> Boolean,
    private val stopVoiceInput: () -> Unit,
    private val predictionProvider: ((left: String, right: String, limit: Int) -> List<HandwritingCandidate>)? = null,
    coroutineScope: CoroutineScope? = null,
    private val synchronousPredictionsForTest: Boolean = false,
) {
    companion object {
        private const val TAG = "CantoneseIME"
        private const val TOP_K = 10
    }

    private data class Request(
        val generation: Long,
        val characterId: Long,
        val version: Long,
        val strokes: List<List<com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint>>,
        val canvasWidth: Float,
        val canvasHeight: Float,
        val pixels: ByteArray,
        val width: Int,
        val height: Int,
    )

    private val internalJob = if (coroutineScope == null) SupervisorJob() else null
    private val scope = coroutineScope ?: CoroutineScope(internalJob!! + Dispatchers.Main.immediate)
    @Volatile private var recognizer: HandwritingRecognitionEngine? = null
    private var loadingJob: Job? = null
    @Volatile private var debugRecordingId: String? = null
    private val queue = HandwritingRecognitionQueue<Request>(
        scope = scope,
        idleDelayMillis = HandwritingInferenceIdleDelayMillis,
        process = { _, request -> recognizeQueued(request) },
    )
    private var generation = 0L
    private var characterId = 0L
    private var requestVersion = 0L
    private val latestVersions = mutableMapOf<Long, Long>()
    private var composition: String? = null
    private var isShowingPredictions = false
    private var predictionJob: Job? = null
    private var predictionRequestId = 0L

    private val _candidates = MutableStateFlow<List<HandwritingCandidate>>(emptyList())
    val candidates = _candidates.asStateFlow()
    private val _recognizing = MutableStateFlow(false)
    val recognizing = _recognizing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun onInputStarted() {
        cancelAll()
        characterId = 0L
        clearState()
    }

    fun onInputViewStarted(session: Long) {
        ensureLoadingStarted(session)
    }

    fun recognize(
        strokes: List<List<com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint>>,
        canvasWidth: Float,
        canvasHeight: Float,
        pixels: ByteArray,
        width: Int,
        height: Int,
    ) {
        if (!isInputViewVisible() || (strokes.isEmpty() && pixels.isEmpty())) return
        cancelPredictions()
        val version = ++requestVersion
        latestVersions[characterId] = version
        _error.value = null
        _recognizing.value = true
        queue.submit(
            characterId,
            Request(generation, characterId, version, strokes, canvasWidth, canvasHeight, pixels, width, height),
        )
    }

    fun onStrokeStarted(startsNewCharacter: Boolean) {
        if (!isInputViewVisible()) return
        stopVoiceInput()
        cancelPredictions()
        if (startsNewCharacter) {
            if (composition != null) accept()
            characterId++
            clearState()
        } else {
            invalidate(characterId)
            _recognizing.value = false
        }
    }

    fun selectCandidate(text: String) {
        if (text.isEmpty()) return
        if (isShowingPredictions) {
            cancelPredictions()
            fun wordChar(c: Char?) = c != null && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9')
            val prefix = if (wordChar(text.firstOrNull()) &&
                wordChar(editor.getTextBeforeCursor(1)?.lastOrNull())) " " else ""
            val suffix = if (wordChar(text.lastOrNull()) &&
                wordChar(editor.getTextAfterCursor(1)?.firstOrNull())) " " else ""
            editor.commitText(prefix + text + suffix)
            refreshPredictions()
        } else {
            accept(text)
            refreshPredictions()
        }
    }

    fun hasActiveInput(): Boolean =
        composition != null ||
            (!isShowingPredictions && _candidates.value.isNotEmpty()) ||
            _recognizing.value ||
            queue.jobsSnapshot().isNotEmpty()

    fun refreshPredictions() {
        if (!isInputViewVisible() || hasActiveInput()) return
        val provider = predictionProvider ?: return
        cancelPredictions()
        val id = ++predictionRequestId
        val left = editor.getTextBeforeCursor(512).orEmpty()
        val right = editor.getTextAfterCursor(256).orEmpty()
        fun lookup(): List<HandwritingCandidate> = runCatching {
            provider(left, right, TOP_K)
        }.onFailure { Log.e(TAG, "Prediction query failed", it) }.getOrDefault(emptyList())

        if (synchronousPredictionsForTest) {
            val result = lookup()
            if (isInputViewVisible() && id == predictionRequestId && !hasActiveInput()) {
                if (result.isNotEmpty()) {
                    isShowingPredictions = true
                    _candidates.value = result
                } else {
                    cancelPredictions()
                }
            }
            return
        }

        predictionJob = scope.launch {
            val result = withContext(Dispatchers.Default) { lookup() }
            if (isInputViewVisible() && id == predictionRequestId && !hasActiveInput()) {
                if (result.isNotEmpty()) {
                    isShowingPredictions = true
                    _candidates.value = result
                } else {
                    cancelPredictions()
                }
            }
        }
    }

    private fun cancelPredictions() {
        predictionJob?.cancel()
        predictionJob = null
        predictionRequestId++
        if (isShowingPredictions) {
            isShowingPredictions = false
            _candidates.value = emptyList()
        }
    }

    fun accept(replacement: String? = null) {
        cancelPredictions()
        if (!hasActiveInput() && replacement == null) return
        val accepted = replacement ?: composition
        cancelAll()
        if (accepted != null) {
            editor.setComposingText(accepted)
            editor.finishComposingText()
            markChosen(debugRecordingId, accepted)
        }
        clearState()
    }

    fun discard() {
        cancelPredictions()
        if (!hasActiveInput()) return
        cancelAll()
        if (composition != null) {
            editor.setComposingText("")
            editor.finishComposingText()
        }
        clearState()
    }

    fun finish(releaseModel: Boolean, session: Long) {
        val jobs = listOfNotNull(loadingJob) + queue.jobsSnapshot()
        discard()
        cancelAll()
        if (releaseModel) {
            loadingJob?.cancel()
            loadingJob = null
        }
        scope.launch(Dispatchers.Default) {
            jobs.joinAll()
            if (releaseModel && !isInputViewVisible() && session == currentSession()) {
                closeRecognizer()
            }
        }
    }

    fun destroy() {
        val jobs = listOfNotNull(loadingJob) + queue.jobsSnapshot()
        cancelAll()
        cancelPredictions()
        jobs.forEach(Job::cancel)
        scope.launch(Dispatchers.Default) {
            jobs.joinAll()
            closeRecognizer()
            internalJob?.cancel()
        }
    }

    private fun ensureLoadingStarted(session: Long) {
        if (!isInputViewVisible() || recognizer != null || loadingJob?.isActive == true) return
        _error.value = null
        val load = scope.launch(Dispatchers.Default) {
            val loaded = context?.let { HandwritingRecognizer.load(it) }
            if (!isInputViewVisible() || session != currentSession()) {
                loaded?.close()
            } else if (loaded == null) {
                _error.value = context?.getString(R.string.error_handwriting_model_unavailable) ?: "Handwriting model unavailable"
            } else {
                synchronized(this@HandwritingInputCoordinator) {
                    recognizer = loaded
                }
            }
        }
        loadingJob = load
        load.invokeOnCompletion {
            if (loadingJob === load) loadingJob = null
            if (
                recognizer == null &&
                isInputViewVisible() &&
                session != currentSession()
            ) {
                scope.launch { ensureLoadingStarted(currentSession()) }
            }
        }
    }

    private suspend fun recognizeQueued(request: Request) {
        try {
            loadingJob?.join()
            if (!isLatest(request) || !isInputViewVisible()) return
            val activeRecognizer = recognizer
            if (activeRecognizer == null) {
                if (request.characterId == characterId) {
                    _error.value = context?.getString(R.string.error_handwriting_model_unavailable) ?: "Handwriting model unavailable"
                    _candidates.value = emptyList()
                }
                return
            }
            val leftContext = editor.getTextBeforeCursor(512).orEmpty()
            val rightContext = editor.getTextAfterCursor(256).orEmpty()
            val result = withContext(Dispatchers.Default) {
                activeRecognizer.setLanguageContext(leftContext, rightContext)
                val modelPixels = runCatching {
                    activeRecognizer.modelInputPixels(
                        request.pixels,
                        request.width,
                        request.height,
                    )
                }.getOrNull() ?: ByteArray(48 * 48)
                val recordingId = context?.let {
                    DebugHandwritingStore.capture(
                        it,
                        request.pixels,
                        request.width,
                        request.height,
                        modelPixels,
                        48,
                        48,
                    )
                }
                val predictions = if (request.strokes.isNotEmpty() && request.canvasWidth > 0f && request.canvasHeight > 0f) {
                    val prevChar = leftContext.takeLast(1).takeIf { it.isNotBlank() }
                    activeRecognizer.predictStrokes(
                        request.strokes,
                        request.canvasWidth,
                        request.canvasHeight,
                        TOP_K,
                        prevChar,
                    )
                } else {
                    activeRecognizer.predict(
                        request.pixels,
                        request.width,
                        request.height,
                        TOP_K,
                    )
                }
                if (recordingId != null) {
                    context?.let { ctx ->
                        DebugHandwritingStore.saveInferenceResult(
                            ctx,
                            recordingId,
                            predictions.map { candidate ->
                                DebugHandwritingCandidate(candidate.text, candidate.rank)
                            },
                        )
                    }
                }
                recordingId to predictions
            }
            if (!isLatest(request) || !isInputViewVisible()) return
            val (recordingId, predictions) = result
            val topCandidate = predictions.firstOrNull()?.text
            if (request.characterId < characterId) {
                latestVersions.remove(request.characterId)
                if (topCandidate != null) {
                    editor.setComposingText(topCandidate)
                    editor.finishComposingText()
                    markChosen(recordingId, topCandidate)
                }
                return
            }
            if (request.characterId != characterId) return
            debugRecordingId = recordingId
            isShowingPredictions = false
            _candidates.value = predictions
            if (topCandidate != null) {
                composition = topCandidate
                editor.setComposingText(topCandidate)
            } else {
                discard()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Handwriting recognition failed", error)
            if (isLatest(request) && request.characterId == characterId) {
                _error.value = context?.getString(R.string.error_handwriting_recognition_failed) ?: "Handwriting recognition failed"
                _candidates.value = emptyList()
            }
        } finally {
            if (isLatest(request) && request.characterId == characterId) {
                _recognizing.value = false
            }
        }
    }

    private fun isLatest(request: Request): Boolean =
        request.generation == generation &&
            latestVersions[request.characterId] == request.version

    private fun invalidate(id: Long) {
        latestVersions.remove(id)
        queue.cancel(id)
    }

    private fun cancelAll(): List<Job> {
        generation++
        latestVersions.clear()
        return queue.cancelAll()
    }

    private fun markChosen(recordingId: String?, text: String) {
        recordingId ?: return
        val ctx = context ?: return
        scope.launch(Dispatchers.IO) {
            DebugHandwritingStore.markChosen(ctx, recordingId, text)
        }
    }

    private fun clearState() {
        cancelPredictions()
        composition = null
        _candidates.value = emptyList()
        _recognizing.value = false
        _error.value = null
        debugRecordingId = null
    }

    private fun closeRecognizer() {
        val recognizerToClose = synchronized(this) {
            recognizer.also { recognizer = null }
        }
        recognizerToClose?.close()
    }
}
