package com.qwenasr

import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer

class QAsrEngine {
    interface VadListener {
        fun onTranscriptDelta(textDelta: String)
        fun onTranscriptComplete()
        fun onTranscriptError()
    }

    companion object {
        private const val TAG = "QAsrEngine"

        init {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                System.loadLibrary("qwen_asr")
                Log.i(
                    TAG,
                    "Successfully loaded qwen_asr native library in ${SystemClock.elapsedRealtime() - startedAt}ms"
                )
            } catch (e: UnsatisfiedLinkError) {
                Log.e(
                    TAG,
                    "Failed to load qwen_asr native library after ${SystemClock.elapsedRealtime() - startedAt}ms",
                    e
                )
            }
        }
    }

    private var nativeHandle: Long = 0
    private var vadSessionHandle: Long = 0

    /**
     * Initializes the Qwen ASR model from a directory.
     * @param modelDir Absolute path to the model folder.
     * @param nThreads Execution thread count (defaults to CPU cores if <= 0).
     * @return True if initialized successfully, false otherwise.
     */
    fun loadModel(modelDir: String, nThreads: Int): Boolean {
        synchronized(this) {
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
    }

    /**
     * Transcribe direct byte buffer contents without copying memory.
     * @param directBuffer A Direct ByteBuffer containing 16kHz mono float PCM samples.
     * @param sizeInSamples Number of float samples in the buffer.
     * @return Transcribed Cantonese text, or null if transcription failed.
     */
    fun transcribePcmDirect(directBuffer: ByteBuffer, sizeInSamples: Int): String? {
        synchronized(this) {
            val handle = nativeHandle
            if (handle == 0L) {
                Log.e(TAG, "Cannot transcribe: model is not loaded")
                return null
            }
            if (!directBuffer.isDirect) {
                Log.e(TAG, "Cannot transcribe: buffer is not a direct ByteBuffer")
                return null
            }
            return nativeTranscribePcmDirect(handle, directBuffer, sizeInSamples)
        }
    }

    fun startVadSession(listener: VadListener, saveAudioDir: String? = null): Boolean {
        synchronized(this) {
            val handle = nativeHandle
            if (handle == 0L) {
                Log.e(TAG, "Cannot start VAD session: model is not loaded")
                return false
            }
            if (vadSessionHandle != 0L) {
                stopVadSession(finalize = false)
            }
            vadSessionHandle = nativeStartVadSession(handle, listener, saveAudioDir)
            return vadSessionHandle != 0L
        }
    }

    fun pushPcm16Chunk(directBuffer: ByteBuffer, byteCount: Int): Int {
        if (!directBuffer.isDirect) {
            Log.e(TAG, "Cannot push audio: buffer is not a direct ByteBuffer")
            return -1
        }
        synchronized(this) {
            val session = vadSessionHandle
            if (session == 0L) {
                return 0
            }
            return nativePushPcm16(session, directBuffer, byteCount)
        }
    }

    fun stopVadSession(finalize: Boolean) {
        val session = synchronized(this) {
            val current = vadSessionHandle
            vadSessionHandle = 0L
            current
        }
        if (session != 0L) {
            nativeStopVadSession(session, finalize)
        }
    }

    /**
     * Set transcription language.
     * @param language Target language string (e.g. "Cantonese", "Chinese", "English").
     * @return True if the language was accepted, false otherwise.
     */
    fun setLanguage(language: String): Boolean {
        synchronized(this) {
            if (nativeHandle != 0L) {
                val success = nativeSetLanguage(nativeHandle, language)
                if (!success) {
                    Log.e(TAG, "Invalid ASR language: $language")
                }
                return success
            } else {
                Log.e(TAG, "Cannot set language: model is not loaded")
                return false
            }
        }
    }

    /**
     * Set Chinese output variant conversion.
     * @param variant BCP-47 style variant such as "zh-HK", "zh-Hant", or "" to disable.
     * @return True if the variant was accepted, false otherwise.
     */
    fun setChineseVariant(variant: String): Boolean {
        synchronized(this) {
            if (nativeHandle != 0L) {
                val success = nativeSetChineseVariant(nativeHandle, variant)
                if (!success) {
                    Log.e(TAG, "Invalid Chinese variant: $variant")
                }
                return success
            } else {
                Log.e(TAG, "Cannot set Chinese variant: model is not loaded")
                return false
            }
        }
    }

    /**
     * Set transcription prompt to guide context/style.
     * @param prompt Guided context prompt string.
     */
    fun setPrompt(prompt: String) {
        synchronized(this) {
            if (nativeHandle != 0L) {
                nativeSetPrompt(nativeHandle, prompt)
            } else {
                Log.e(TAG, "Cannot set prompt: model is not loaded")
            }
        }
    }

    /**
     * Set transcription segment seconds.
     * @param sec Duration of segment in seconds.
     */
    fun setSegmentSec(sec: Float) {
        synchronized(this) {
            if (nativeHandle != 0L) {
                nativeSetSegmentSec(nativeHandle, sec)
            } else {
                Log.e(TAG, "Cannot set segment seconds: model is not loaded")
            }
        }
    }

    /**
     * Free native memory allocations and release native engine resources.
     */
    fun free() {
        synchronized(this) {
            stopVadSession(finalize = false)
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
    private external fun nativeStartVadSession(handle: Long, listener: VadListener, saveAudioDir: String?): Long
    private external fun nativePushPcm16(sessionHandle: Long, buffer: ByteBuffer, byteCount: Int): Int
    private external fun nativeStopVadSession(sessionHandle: Long, finalize: Boolean)
    private external fun nativeSetLanguage(handle: Long, language: String): Boolean
    private external fun nativeSetChineseVariant(handle: Long, variant: String): Boolean
    private external fun nativeSetPrompt(handle: Long, prompt: String)
    private external fun nativeSetSegmentSec(handle: Long, sec: Float)
    private external fun nativeFree(handle: Long)
}
