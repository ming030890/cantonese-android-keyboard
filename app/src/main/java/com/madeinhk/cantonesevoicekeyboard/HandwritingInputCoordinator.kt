package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.util.Log
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingAutoCommitDelayMillis
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingBusyIndicatorDelayMillis
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingLiveRecognitionDelayMillis
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognitionEngine
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognitionQueue
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class HandwritingInputCoordinator(
    private val context: Context? = null,
    private val editor: KeyboardEditor,
    private val currentSession: () -> Long,
    private val isInputViewVisible: () -> Boolean,
    private val stopVoiceInput: () -> Unit,
    private val predictionProvider: ((left: String, right: String, limit: Int) -> List<HandwritingCandidate>)? = null,
    coroutineScope: CoroutineScope? = null,
    private val synchronousPredictionsForTest: Boolean = false,
    recognitionEngine: HandwritingRecognitionEngine? = null,
    private val recognitionDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) : HandwritingSession {
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
    override var onRecognitionApplied: () -> Unit = {}
    override var onCharacterCommitted: () -> Unit = {}
    @Volatile private var recognizer: HandwritingRecognitionEngine? = recognitionEngine
    private var loadingJob: Job? = null
    @Volatile private var debugRecordingId: String? = null
    private val queue = HandwritingRecognitionQueue<Request>(
        scope = scope,
        idleDelayMillis = HandwritingLiveRecognitionDelayMillis,
        process = { _, request -> recognizeQueued(request) },
    )
    private var generation = 0L
    private var characterId = 0L
    private var requestVersion = 0L
    private val latestVersions = mutableMapOf<Long, Long>()
    private val pendingSpaceCommits = mutableMapOf<Long, String?>()
    private var hasCanvasInk = false
    private var busyIndicatorJob: Job? = null
    private var autoCommitJob: Job? = null
    private var composition: String? = null
    private var isShowingPredictions = false
    private var predictionJob: Job? = null
    private var predictionRequestId = 0L

    private val _candidates = MutableStateFlow<List<HandwritingCandidate>>(emptyList())
    override val candidates = _candidates.asStateFlow()
    private val _composingCandidate = MutableStateFlow<String?>(null)
    override val composingCandidate = _composingCandidate.asStateFlow()
    private val _recognizing = MutableStateFlow(false)
    override val recognizing = _recognizing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error = _error.asStateFlow()

    override fun onInputStarted() {
        cancelAll()
        hasCanvasInk = false
        characterId = 0L
        clearState()
    }

    override fun onInputViewStarted(session: Long) {
        ensureLoadingStarted(session)
    }

    override fun recognize(
        strokes: List<List<com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint>>,
        canvasWidth: Float,
        canvasHeight: Float,
        pixels: ByteArray,
        width: Int,
        height: Int,
    ) {
        if (!isInputViewVisible() || (strokes.isEmpty() && pixels.isEmpty())) return
        cancelPredictions()
        cancelAutoCommit()
        hasCanvasInk = true
        val version = ++requestVersion
        latestVersions[characterId] = version
        _error.value = null
        scheduleBusyIndicator(characterId, version)
        queue.submit(
            characterId,
            Request(generation, characterId, version, strokes, canvasWidth, canvasHeight, pixels, width, height),
        )
    }

    private fun scheduleBusyIndicator(targetCharacterId: Long, version: Long) {
        cancelBusyIndicator()
        if (_candidates.value.isNotEmpty()) return
        val expectedGeneration = generation
        busyIndicatorJob = scope.launch {
            delay(HandwritingBusyIndicatorDelayMillis)
            if (expectedGeneration == generation &&
                targetCharacterId == characterId &&
                latestVersions[targetCharacterId] == version &&
                _candidates.value.isEmpty()
            ) {
                _recognizing.value = true
            }
        }
    }

    private fun cancelBusyIndicator() {
        busyIndicatorJob?.cancel()
        busyIndicatorJob = null
    }

    private fun scheduleAutoCommit(targetCharacterId: Long) {
        cancelAutoCommit()
        val expectedGeneration = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            delay(HandwritingAutoCommitDelayMillis)
            if (autoCommitJob === currentJob &&
                expectedGeneration == generation &&
                targetCharacterId == characterId &&
                !latestVersions.containsKey(targetCharacterId) &&
                composition != null &&
                isInputViewVisible()
            ) {
                autoCommitJob = null
                accept()
                refreshPredictions()
            }
        }
        autoCommitJob = job
        job.invokeOnCompletion {
            if (autoCommitJob === job) {
                autoCommitJob = null
            }
        }
        job.start()
    }

    private fun cancelAutoCommit() {
        autoCommitJob?.cancel()
        autoCommitJob = null
    }

    override fun onStrokeStarted(startsNewCharacter: Boolean) {
        if (!isInputViewVisible()) return
        stopVoiceInput()
        cancelPredictions()
        cancelBusyIndicator()
        cancelAutoCommit()
        if (startsNewCharacter) {
            if (composition != null || hasCanvasInk) {
                accept()
            } else {
                characterId++
                clearState()
            }
        } else {
            invalidate(characterId)
            _recognizing.value = false
        }
    }

    override fun onCandidatePageChanged() {
        cancelAutoCommit()
    }

    override fun selectCandidate(text: String) {
        if (text.isEmpty()) return
        cancelAutoCommit()
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

    override fun onSpace() {
        cancelPredictions()
        cancelAutoCommit()
        if (!hasActiveInput()) {
            editor.commitText(" ")
            refreshPredictions()
            return
        }
        val activeId = characterId
        if (latestVersions.containsKey(activeId)) {
            pendingSpaceCommits[activeId] = composition
            characterId++
            resetCanvasIfNeeded()
            clearState()
            return
        }
        val accepted = composition
        cancelAll()
        resetCanvasIfNeeded()
        if (accepted != null) {
            editor.setComposingText(accepted)
            editor.finishComposingText()
            markChosen(debugRecordingId, accepted)
            onCharacterCommitted()
        }
        characterId++
        clearState()
        refreshPredictions()
    }

    override fun hasActiveInput(): Boolean =
        hasCanvasInk ||
            composition != null ||
            (!isShowingPredictions && _candidates.value.isNotEmpty()) ||
            _recognizing.value ||
            queue.jobsSnapshot().isNotEmpty()

    override fun refreshPredictions() {
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

    override fun accept(replacement: String?) {
        cancelPredictions()
        if (!hasActiveInput() && replacement == null) return
        val accepted = replacement ?: composition
        cancelAll()
        resetCanvasIfNeeded()
        if (accepted != null) {
            editor.setComposingText(accepted)
            editor.finishComposingText()
            markChosen(debugRecordingId, accepted)
            onCharacterCommitted()
        }
        characterId++
        clearState()
    }

    override fun discard() {
        cancelPredictions()
        if (!hasActiveInput() && pendingSpaceCommits.isEmpty()) return
        cancelAll()
        resetCanvasIfNeeded()
        if (composition != null) {
            editor.setComposingText("")
            editor.finishComposingText()
        }
        characterId++
        clearState()
    }

    private fun resetCanvasIfNeeded() {
        if (hasCanvasInk) {
            hasCanvasInk = false
            onRecognitionApplied()
        }
    }

    override fun finish(releaseModel: Boolean, session: Long) {
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

    override fun destroy() {
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
                _error.value = context?.getString(R.string.error_handwriting_model_unavailable) ?: "Handwriting unavailable. Reopen keyboard."
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
                    _error.value = context?.getString(R.string.error_handwriting_model_unavailable) ?: "Handwriting unavailable. Reopen keyboard."
                    _candidates.value = emptyList()
                }
                return
            }
            val leftContext = editor.getTextBeforeCursor(512).orEmpty()
            val rightContext = editor.getTextAfterCursor(256).orEmpty()
            val result = withContext(recognitionDispatcher) {
                activeRecognizer.setLanguageContext(leftContext, rightContext)
                // Stroke recognition needs no raster. Build capture images only when opted in,
                // on this worker rather than in the pen-up event on the UI thread.
                val recordingId = context?.takeIf {
                    editor.isPersonalizedLearningAllowed() &&
                        DebugHandwritingSettings.isStoreDebugHandwritingEnabled(it)
                }?.let { ctx ->
                    runCatching {
                        val bitmap = if (request.pixels.isNotEmpty()) {
                            com.madeinhk.cantonesevoicekeyboard.ui.keyboard.HandwritingBitmap(
                                request.pixels, request.width, request.height)
                        } else {
                            com.madeinhk.cantonesevoicekeyboard.ui.keyboard.handwritingBitmap(
                                request.strokes, androidx.compose.ui.unit.IntSize(request.width, request.height))
                        }
                        val modelPixels = activeRecognizer.modelInputPixels(
                            bitmap.pixels, bitmap.width, bitmap.height)
                        if (modelPixels == null) null else DebugHandwritingStore.capture(
                            ctx, bitmap.pixels, bitmap.width, bitmap.height, modelPixels, 48, 48)
                    }.onFailure { Log.e(TAG, "Handwriting debug capture failed", it) }.getOrNull()
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
                val hadSpaceCommit = pendingSpaceCommits.containsKey(request.characterId)
                val fallback = pendingSpaceCommits.remove(request.characterId)
                latestVersions.remove(request.characterId)
                val committed = topCandidate ?: fallback
                if (committed != null) {
                    editor.setComposingText(committed)
                    editor.finishComposingText()
                    markChosen(recordingId, committed)
                }
                if (hadSpaceCommit && !hasActiveInput()) {
                    refreshPredictions()
                }
                return
            }
            if (request.characterId != characterId) return
            debugRecordingId = recordingId
            isShowingPredictions = false
            _candidates.value = predictions
            if (topCandidate != null) {
                composition = topCandidate
                _composingCandidate.value = topCandidate
                editor.setComposingText(topCandidate)
                scheduleAutoCommit(request.characterId)
            } else {
                cancelAutoCommit()
                if (composition != null) {
                    composition = null
                    _composingCandidate.value = null
                    editor.setComposingText("")
                    editor.finishComposingText()
                }
                _error.value = context?.getString(R.string.error_handwriting_recognition_failed)
                    ?: "Not recognized. Write it again."
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Handwriting recognition failed", error)
            if (isLatest(request) && request.characterId == characterId) {
                cancelAutoCommit()
                _error.value = context?.getString(R.string.error_handwriting_recognition_failed) ?: "Not recognized. Write it again."
                _candidates.value = emptyList()
                _composingCandidate.value = null
            }
        } finally {
            if (isLatest(request) && request.characterId == characterId) {
                cancelBusyIndicator()
                _recognizing.value = false
                latestVersions.remove(request.characterId)
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
        cancelBusyIndicator()
        cancelAutoCommit()
        latestVersions.clear()
        pendingSpaceCommits.clear()
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
        cancelBusyIndicator()
        cancelAutoCommit()
        composition = null
        _composingCandidate.value = null
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
