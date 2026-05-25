package com.qwenasr

import android.util.Log
import java.nio.ByteBuffer

class QAsrEngine {
    companion object {
        private const val TAG = "QAsrEngine"

        init {
            try {
                System.loadLibrary("qwen_asr")
                Log.i(TAG, "Successfully loaded qwen_asr native library")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load qwen_asr native library", e)
            }
        }
    }

    private var nativeHandle: Long = 0

    /**
     * Initializes the Qwen ASR model from a directory.
     * @param modelDir Absolute path to the model folder.
     * @param nThreads Execution thread count (defaults to CPU cores if <= 0).
     * @return True if initialized successfully, false otherwise.
     */
    fun loadModel(modelDir: String, nThreads: Int): Boolean {
        if (nativeHandle != 0L) {
            Log.w(TAG, "Model already loaded. Freeing existing handle first.")
            free()
        }
        nativeHandle = nativeLoadModel(modelDir, nThreads)
        val success = nativeHandle != 0L
        if (success) {
            Log.i(TAG, "Successfully loaded model from $modelDir. Handle: $nativeHandle")
        } else {
            Log.e(TAG, "Failed to load model from $modelDir")
        }
        return success
    }

    /**
     * Transcribe direct byte buffer contents without copying memory.
     * @param directBuffer A Direct ByteBuffer containing 16kHz mono float PCM samples.
     * @param sizeInSamples Number of float samples in the buffer.
     * @return Transcribed Cantonese text, or null if transcription failed.
     */
    fun transcribePcmDirect(directBuffer: ByteBuffer, sizeInSamples: Int): String? {
        if (nativeHandle == 0L) {
            Log.e(TAG, "Cannot transcribe: model is not loaded")
            return null
        }
        if (!directBuffer.isDirect) {
            Log.e(TAG, "Cannot transcribe: buffer is not a direct ByteBuffer")
            return null
        }
        return nativeTranscribePcmDirect(nativeHandle, directBuffer, sizeInSamples)
    }

    /**
     * Set transcription language.
     * @param language Target language string (e.g. "zh" or "yue" or "en").
     */
    fun setLanguage(language: String) {
        if (nativeHandle != 0L) {
            nativeSetLanguage(nativeHandle, language)
        } else {
            Log.e(TAG, "Cannot set language: model is not loaded")
        }
    }

    /**
     * Set transcription prompt to guide context/style.
     * @param prompt Guided context prompt string.
     */
    fun setPrompt(prompt: String) {
        if (nativeHandle != 0L) {
            nativeSetPrompt(nativeHandle, prompt)
        } else {
            Log.e(TAG, "Cannot set prompt: model is not loaded")
        }
    }

    /**
     * Set transcription segment seconds.
     * @param sec Duration of segment in seconds.
     */
    fun setSegmentSec(sec: Float) {
        if (nativeHandle != 0L) {
            nativeSetSegmentSec(nativeHandle, sec)
        } else {
            Log.e(TAG, "Cannot set segment seconds: model is not loaded")
        }
    }

    /**
     * Free native memory allocations and release native engine resources.
     */
    fun free() {
        synchronized(this) {
            val handle = nativeHandle
            if (handle != 0L) {
                nativeHandle = 0L
                Log.i(TAG, "Freeing native model context with handle: $handle")
                nativeFree(handle)
            }
        }
    }

    // Native external methods
    private external fun nativeLoadModel(modelDir: String, nThreads: Int): Long
    private external fun nativeTranscribePcmDirect(handle: Long, buffer: ByteBuffer, size: Int): String?
    private external fun nativeSetLanguage(handle: Long, language: String)
    private external fun nativeSetPrompt(handle: Long, prompt: String)
    private external fun nativeSetSegmentSec(handle: Long, sec: Float)
    private external fun nativeFree(handle: Long)
}
