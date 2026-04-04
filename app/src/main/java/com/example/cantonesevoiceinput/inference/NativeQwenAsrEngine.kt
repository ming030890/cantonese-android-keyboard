package com.example.cantonesevoiceinput.inference

import java.io.Closeable
import java.io.File

fun interface StreamingTokenListener {
    fun onTokenPiece(piece: String)
}

/**
 * JNI wrapper over native qwen-asr C runtime.
 * Supports full transcription and streaming transcription modes.
 */
class NativeQwenAsrEngine(
    modelDir: File,
    language: String = "Cantonese",
    threads: Int = 0,
    segmentSec: Float = 30f,
    searchSec: Float = 3f,
    pastTextConditioning: Boolean = true,
    streamChunkSec: Float = 2f,
    streamRollbackTokens: Int = 5,
    streamUnfixedChunks: Int = 2,
    streamMaxNewTokens: Int = 32
) : Closeable {
    private var handle: Long = 0

    init {
        require(modelDir.exists() && modelDir.isDirectory) {
            "Model directory does not exist: ${modelDir.absolutePath}"
        }

        handle = nativeCreate(modelDir.absolutePath, language, threads)
        check(handle != 0L) {
            val err = nativeGetGlobalLastError().ifBlank { "unknown" }
            "Failed to initialize native qwen-asr context: $err"
        }

        nativeConfigure(
            handle = handle,
            segmentSec = segmentSec,
            searchSec = searchSec,
            pastTextConditioning = pastTextConditioning
        )
        nativeConfigureStreaming(
            handle = handle,
            chunkSec = streamChunkSec,
            rollbackTokens = streamRollbackTokens,
            unfixedChunks = streamUnfixedChunks,
            maxNewTokens = streamMaxNewTokens
        )
    }

    fun transcribePcm16kMono(samples: FloatArray, forceLanguage: Boolean = true): String {
        check(handle != 0L) { "Native engine already closed" }
        if (samples.isEmpty()) {
            return ""
        }
        return nativeTranscribe(handle, samples, forceLanguage)
    }

    fun transcribePcm16kMonoProfiled(
        samples: FloatArray,
        forceLanguage: Boolean = true
    ): NativeTranscriptionTrace {
        check(handle != 0L) { "Native engine already closed" }
        if (samples.isEmpty()) {
            return NativeTranscriptionTrace("", 0, 0L, 0L, 0L)
        }
        return nativeTranscribeProfiled(handle, samples, forceLanguage)
    }

    fun transcribeStreamPcm16kMono(samples: FloatArray, forceLanguage: Boolean = true): String {
        check(handle != 0L) { "Native engine already closed" }
        if (samples.isEmpty()) {
            return ""
        }
        return nativeTranscribeStream(handle, samples, forceLanguage, null)
    }

    fun transcribeStreamPcm16kMono(
        samples: FloatArray,
        forceLanguage: Boolean = true,
        onPartialText: ((String) -> Unit)?
    ): String {
        check(handle != 0L) { "Native engine already closed" }
        if (samples.isEmpty()) {
            return ""
        }
        return nativeTranscribeStream(
            handle,
            samples,
            forceLanguage,
            accumulatingTokenListener(onPartialText)
        )
    }

    fun transcribeStreamPcm16kMonoProfiled(
        samples: FloatArray,
        forceLanguage: Boolean = true
    ): NativeTranscriptionTrace {
        check(handle != 0L) { "Native engine already closed" }
        if (samples.isEmpty()) {
            return NativeTranscriptionTrace("", 0, 0L, 0L, 0L)
        }
        return nativeTranscribeStreamProfiled(handle, samples, forceLanguage)
    }

    fun createLiveTranscriptionSession(
        forceLanguage: Boolean = true,
        onPartialText: ((String) -> Unit)? = null
    ): LiveSession {
        check(handle != 0L) { "Native engine already closed" }
        val liveHandle = nativeCreateLiveSession(
            handle = handle,
            forceLanguage = forceLanguage,
            listener = accumulatingTokenListener(onPartialText)
        )
        check(liveHandle != 0L) {
            val err = nativeGetGlobalLastError().ifBlank { "unknown" }
            "Failed to create live transcription session: $err"
        }
        return LiveSession(this, liveHandle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    private fun accumulatingTokenListener(
        onPartialText: ((String) -> Unit)?
    ): StreamingTokenListener? {
        if (onPartialText == null) {
            return null
        }

        val transcript = StringBuilder()
        return StreamingTokenListener { piece ->
            transcript.append(piece)
            onPartialText(transcript.toString())
        }
    }

    private external fun nativeCreate(modelDir: String, language: String, threads: Int): Long
    private external fun nativeConfigure(
        handle: Long,
        segmentSec: Float,
        searchSec: Float,
        pastTextConditioning: Boolean
    )
    private external fun nativeConfigureStreaming(
        handle: Long,
        chunkSec: Float,
        rollbackTokens: Int,
        unfixedChunks: Int,
        maxNewTokens: Int
    )
    private external fun nativeDestroy(handle: Long)
    private external fun nativeTranscribe(handle: Long, pcm16kMono: FloatArray, forceLanguage: Boolean): String
    private external fun nativeTranscribeProfiled(
        handle: Long,
        pcm16kMono: FloatArray,
        forceLanguage: Boolean
    ): NativeTranscriptionTrace
    private external fun nativeTranscribeStream(
        handle: Long,
        pcm16kMono: FloatArray,
        forceLanguage: Boolean,
        listener: StreamingTokenListener?
    ): String
    private external fun nativeTranscribeStreamProfiled(
        handle: Long,
        pcm16kMono: FloatArray,
        forceLanguage: Boolean
    ): NativeTranscriptionTrace
    private external fun nativeCreateLiveSession(
        handle: Long,
        forceLanguage: Boolean,
        listener: StreamingTokenListener?
    ): Long
    private external fun nativeAppendLiveSessionAudio(handle: Long, pcm16kMono: FloatArray): Boolean
    private external fun nativeFinishLiveSessionInput(handle: Long)
    private external fun nativeRunLiveSession(handle: Long): String
    private external fun nativeDestroyLiveSession(handle: Long)
    private external fun nativeGetGlobalLastError(): String

    class LiveSession internal constructor(
        private val engine: NativeQwenAsrEngine,
        private var handle: Long
    ) : Closeable {
        fun appendPcm16kMono(samples: FloatArray) {
            check(handle != 0L) { "Live transcription session already closed" }
            if (samples.isEmpty()) {
                return
            }
            check(engine.nativeAppendLiveSessionAudio(handle, samples)) {
                val err = engine.nativeGetGlobalLastError().ifBlank { "unknown" }
                "Failed to append live audio: $err"
            }
        }

        fun finishInput() {
            if (handle != 0L) {
                engine.nativeFinishLiveSessionInput(handle)
            }
        }

        fun runBlocking(): String {
            check(handle != 0L) { "Live transcription session already closed" }
            return engine.nativeRunLiveSession(handle).also {
                val err = engine.nativeGetGlobalLastError()
                check(err.isBlank()) { "Live transcription failed: $err" }
            }
        }

        override fun close() {
            if (handle != 0L) {
                engine.nativeDestroyLiveSession(handle)
                handle = 0
            }
        }
    }

    companion object {
        init {
            System.loadLibrary("qwen_asr_jni")
        }
    }
}
