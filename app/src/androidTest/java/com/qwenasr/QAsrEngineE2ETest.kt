package com.qwenasr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class QAsrEngineE2ETest {

    companion object {
        private const val TAG = "QAsrEngineE2ETest"
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageName = context.packageName
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        
        Log.i(TAG, "Granting MANAGE_EXTERNAL_STORAGE permission programmatically to $packageName")
        val cmd = "appops set $packageName MANAGE_EXTERNAL_STORAGE allow"
        val pfd = uiAutomation.executeShellCommand(cmd)
        pfd.close() // Close descriptor to ensure command completes execution
        
        Thread.sleep(500) // Allow propagation delay
    }

    @Test
    fun testASRModelLoadAndTranscription() {
        Log.i(TAG, "Starting QAsrEngine E2E Integration Test")
        
        val modelDir = "/sdcard/Download/qwen-asr-int8dec"
        val audioFile = "/sdcard/Download/yue.wav"

        // 1. Check if model and audio files exist on the device
        val modelDirFile = File(modelDir)
        assertTrue("Model directory does not exist on device: $modelDir", modelDirFile.exists())
        assertTrue("Model directory is not a folder: $modelDir", modelDirFile.isDirectory)

        val wavFile = File(audioFile)
        assertTrue("Test audio file does not exist on device: $audioFile", wavFile.exists())

        // 2. Initialize QAsrEngine
        val engine = QAsrEngine()
        Log.i(TAG, "Engine instance created, loading model...")

        // 3. Load Model (use 1 thread to match the baseline benchmark)
        val loadSuccess = engine.loadModel(modelDir, 1)
        assertTrue("Failed to load native Qwen ASR model from $modelDir", loadSuccess)

        try {
            // 4. Configure engine
            assertTrue("Failed to set Cantonese ASR language", engine.setLanguage("Cantonese"))
            engine.setPrompt("音频是粤语，可能夹杂英语")
            engine.setSegmentSec(0f) // disable segmenting for whole-file benchmark

            // 5. Read WAV file and convert to float direct ByteBuffer
            Log.i(TAG, "Parsing WAV file: $audioFile")
            val (directBuffer, numSamples) = loadWavToDirectBuffer(File(audioFile))
            
            // Print first 20 floats to verify correct parsing
            val floatBuf = directBuffer.asFloatBuffer()
            val samplePrintout = StringBuilder()
            for (i in 0 until minOf(20, numSamples)) {
                samplePrintout.append(String.format("%.6f ", floatBuf.get(i)))
            }
            Log.i(TAG, "First 20 audio samples: $samplePrintout")
            Log.i(TAG, "Audio loaded: $numSamples samples (duration: ${numSamples / 16000.0}s)")

            // 6. Transcribe PCM
            Log.i(TAG, "Running transcription...")
            val startTime = System.currentTimeMillis()
            val text = engine.transcribePcmDirect(directBuffer, numSamples)
            val durationMs = System.currentTimeMillis() - startTime

            // 7. Verify results
            assertNotNull("Transcription result was null", text)
            Log.i(TAG, "==================================================")
            Log.i(TAG, "TRANSCRIPTION RESULT:")
            Log.i(TAG, text ?: "")
            Log.i(TAG, "Transcription completed in ${durationMs / 1000.0} seconds")
            Log.i(TAG, "==================================================")

            assertTrue("Transcription result was empty", !text.isNullOrBlank())

        } finally {
            // 8. Clean up native allocations
            Log.i(TAG, "Freeing native model context...")
            engine.free()
        }
    }

    private fun loadWavToDirectBuffer(wavFile: File): Pair<ByteBuffer, Int> {
        val bytes = wavFile.readBytes()
        if (bytes.size < 44) {
            throw IllegalArgumentException("Invalid WAV file: size is less than 44 bytes header")
        }

        // Skip 44-byte WAV header
        val audioBytes = bytes.copyOfRange(44, bytes.size)
        val numSamples = audioBytes.size / 2

        // Allocate direct ByteBuffer of size numSamples * 4 bytes (since float is 4 bytes)
        val buffer = ByteBuffer.allocateDirect(numSamples * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        val floatBuffer = buffer.asFloatBuffer()
        for (i in 0 until numSamples) {
            // Read 16-bit short from raw bytes in little endian
            val low = audioBytes[i * 2].toInt() and 0xFF
            val high = audioBytes[i * 2 + 1].toInt()
            val sampleShort = ((high shl 8) or low).toShort()
            val sampleFloat = sampleShort.toFloat() / 32768.0f
            floatBuffer.put(sampleFloat)
        }
        buffer.rewind()
        return Pair(buffer, numSamples)
    }
}
