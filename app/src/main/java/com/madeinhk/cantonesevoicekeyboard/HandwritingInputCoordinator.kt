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
    private val context: Context,
    private val editor: ImeEditor,
    private val currentSession: () -> Long,
    private val isInputViewVisible: () -> Boolean,
    private val stopVoiceInput: () -> Unit,
) {
    companion object {
        private const val TAG = "CantoneseIME"
        private const val TOP_K = 10
    }

    private data class Request(
        val generation: Long,
        val characterId: Long,
        val version: Long,
        val pixels: ByteArray,
        val width: Int,
        val height: Int,
    )

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
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

    fun recognize(pixels: ByteArray, width: Int, height: Int) {
        if (!isInputViewVisible() || pixels.isEmpty()) return
        val version = ++requestVersion
        latestVersions[characterId] = version
        _error.value = null
        _recognizing.value = true
        queue.submit(
            characterId,
            Request(generation, characterId, version, pixels, width, height),
        )
    }

    fun onStrokeStarted(startsNewCharacter: Boolean) {
        if (!isInputViewVisible()) return
        stopVoiceInput()
        if (startsNewCharacter) {
            if (composition != null || _candidates.value.isNotEmpty()) accept()
            characterId++
            clearState()
        } else {
            invalidate(characterId)
            _recognizing.value = false
        }
    }

    fun selectCandidate(text: String) {
        if (text.isNotEmpty()) accept(text)
    }

    fun hasActiveInput(): Boolean =
        composition != null ||
            _candidates.value.isNotEmpty() ||
            _recognizing.value ||
            queue.jobsSnapshot().isNotEmpty()

    fun accept(replacement: String? = null) {
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
        jobs.forEach(Job::cancel)
        scope.launch(Dispatchers.Default) {
            jobs.joinAll()
            closeRecognizer()
            job.cancel()
        }
    }

    private fun ensureLoadingStarted(session: Long) {
        if (!isInputViewVisible() || recognizer != null || loadingJob?.isActive == true) return
        _error.value = null
        val load = scope.launch(Dispatchers.Default) {
            val loaded = HandwritingRecognizer.load(context)
            if (!isInputViewVisible() || session != currentSession()) {
                loaded?.close()
            } else if (loaded == null) {
                _error.value = context.getString(R.string.error_handwriting_model_unavailable)
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
                    _error.value = context.getString(R.string.error_handwriting_model_unavailable)
                    _candidates.value = emptyList()
                }
                return
            }
            val result = withContext(Dispatchers.Default) {
                val modelPixels = activeRecognizer.modelInputPixels(
                    request.pixels,
                    request.width,
                    request.height,
                ) ?: throw HandwritingRecognitionException("Native handwriting preprocessing failed")
                val recordingId = DebugHandwritingStore.capture(
                    context,
                    request.pixels,
                    request.width,
                    request.height,
                    modelPixels,
                    48,
                    48,
                )
                val predictions = activeRecognizer.predict(
                    request.pixels,
                    request.width,
                    request.height,
                    TOP_K,
                )
                recordingId?.let {
                    DebugHandwritingStore.saveInferenceResult(
                        context,
                        it,
                        predictions.map { candidate ->
                            DebugHandwritingCandidate(candidate.text, candidate.rank)
                        },
                    )
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
                _error.value = context.getString(R.string.error_handwriting_recognition_failed)
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
        scope.launch(Dispatchers.IO) {
            DebugHandwritingStore.markChosen(context, recordingId, text)
        }
    }

    private fun clearState() {
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
