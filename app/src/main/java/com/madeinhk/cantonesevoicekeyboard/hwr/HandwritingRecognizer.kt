package com.madeinhk.cantonesevoicekeyboard.hwr

import android.content.Context
import android.util.Log

data class HandwritingCandidate(
    val text: String,
    val rank: Int,
)

class HandwritingRecognitionException(message: String) : RuntimeException(message)

interface HandwritingRecognitionEngine : AutoCloseable {
    fun predict(
        pixels: ByteArray,
        width: Int,
        height: Int,
        topK: Int = 10,
    ): List<HandwritingCandidate>
}

/**
 * Kotlin ownership/lifecycle wrapper around the fixed-topology Rust model.
 * One instance is used by the IME service and is never called concurrently.
 */
class HandwritingRecognizer private constructor(
    private var nativeHandle: Long,
) : HandwritingRecognitionEngine {
    companion object {
        private const val TAG = "HandwritingRecognizer"
        private const val MODEL_ASSET = "handwriting/model.safetensors"
        private const val LABELS_ASSET = "handwriting/labels.txt"

        @Volatile
        private var nativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("stust_handwriting_inference")
                nativeLibraryLoaded = true
            } catch (error: UnsatisfiedLinkError) {
                Log.e(TAG, "Handwriting native library is unavailable", error)
            }
        }

        fun load(context: Context): HandwritingRecognizer? {
            if (!nativeLibraryLoaded) return null
            return runCatching {
                val appContext = context.applicationContext
                val model = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
                val labels = appContext.assets.open(LABELS_ASSET).use { it.readBytes() }
                val handle = nativeCreate(model, labels)
                if (handle == 0L) {
                    null
                } else {
                    HandwritingRecognizer(handle)
                }
            }.onFailure {
                Log.e(TAG, "Failed to load handwriting model", it)
            }.getOrNull()
        }

        @JvmStatic
        private external fun nativeCreate(modelBytes: ByteArray, labelBytes: ByteArray): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativePredict(
            handle: Long,
            pixels: ByteArray,
            width: Int,
            height: Int,
            topK: Int,
        ): Array<String>?
    }

    @Synchronized
    override fun predict(pixels: ByteArray, width: Int, height: Int, topK: Int): List<HandwritingCandidate> {
        val handle = nativeHandle
        if (handle == 0L) {
            throw HandwritingRecognitionException("The handwriting recognizer is closed")
        }
        if (
            pixels.isEmpty() ||
            width <= 0 ||
            height <= 0 ||
            pixels.size.toLong() != width.toLong() * height.toLong()
        ) {
            throw HandwritingRecognitionException("Invalid handwriting bitmap dimensions")
        }
        if (topK <= 0) {
            throw HandwritingRecognitionException("topK must be positive")
        }
        val predictions = nativePredict(handle, pixels, width, height, topK)
            ?: throw HandwritingRecognitionException("Native handwriting inference failed")
        return predictions.mapIndexed { rank, text ->
            HandwritingCandidate(text = text, rank = rank)
        }
    }

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) nativeDestroy(handle)
    }
}
