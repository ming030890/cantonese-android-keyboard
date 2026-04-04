package com.example.cantonesevoiceinput.inference

import java.io.Closeable
import java.io.File

/**
 * JNI wrapper over native qwen-asr C runtime.
 * Supports full transcription and streaming transcription modes.
 */
class NativeQwenAsrEngine(
    modelDir: File,
    language: String = "Cantonese",
    threads: Int = 4,
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
        return nativeTranscribeStream(handle, samples, forceLanguage)
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

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
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
    private external fun nativeTranscribeStream(handle: Long, pcm16kMono: FloatArray, forceLanguage: Boolean): String
    private external fun nativeTranscribeStreamProfiled(
        handle: Long,
        pcm16kMono: FloatArray,
        forceLanguage: Boolean
    ): NativeTranscriptionTrace
    private external fun nativeGetGlobalLastError(): String

    companion object {
        init {
            System.loadLibrary("qwen_asr_jni")
        }
    }
}
