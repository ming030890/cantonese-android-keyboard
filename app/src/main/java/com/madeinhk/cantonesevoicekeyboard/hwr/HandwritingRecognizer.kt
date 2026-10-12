package com.madeinhk.cantonesevoicekeyboard.hwr

import android.content.Context
import android.util.Log

data class HandwritingCandidate(
    val text: String,
    val rank: Int,
)

data class StrokePoint(
    val x: Float,
    val y: Float,
    val timeMs: Long,
)

class HandwritingRecognitionException(message: String) : RuntimeException(message)

interface HandwritingRecognitionEngine : AutoCloseable {
    fun setLanguageContext(left: String, right: String) {}
    fun modelInputPixels(pixels: ByteArray, width: Int, height: Int): ByteArray? = null

    fun predict(
        pixels: ByteArray,
        width: Int,
        height: Int,
        topK: Int = 10,
    ): List<HandwritingCandidate>

    fun predictStrokes(
        strokes: List<List<StrokePoint>>,
        canvasWidth: Float,
        canvasHeight: Float,
        topK: Int = 10,
        prevChar: String? = null,
    ): List<HandwritingCandidate> = emptyList()
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
        private const val LM_ASSET = "handwriting/lm_csr.bin"

        @Volatile
        private var nativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("cantonese_engine")
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
                val lm = runCatching {
                    appContext.assets.open(LM_ASSET).use { it.readBytes() }
                }.getOrNull() ?: ByteArray(0)
                val neural = runCatching { appContext.assets.open("language_model/model.cng").use { it.readBytes() } }.getOrElse { ByteArray(0) }
                val fusion = if (neural.isEmpty()) ByteArray(0) else runCatching {
                    appContext.assets.open("language_model/handwriting_fusion.json").use { it.readBytes() }
                }.getOrElse { ByteArray(0) }
                if (fusion.isNotEmpty()) {
                    val calibration = org.json.JSONObject(fusion.toString(Charsets.UTF_8))
                    check(calibration.getInt("version") == 1) { "Unsupported handwriting fusion version" }
                    fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    check(calibration.getString("neural_model_sha256") == sha(neural)) { "Fusion calibration LM mismatch" }
                    val inputs = calibration.getJSONObject("inputs")
                    for ((name, bytes) in listOf("handwriting_model_sha256" to model, "labels_sha256" to labels, "counts_sha256" to lm)) {
                        check(inputs.getString(name) == sha(bytes)) { "Fusion calibration $name mismatch" }
                    }
                }
                val handle = nativeCreate(model, labels, lm, neural, fusion)
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
        private external fun nativeCreate(modelBytes: ByteArray, labelBytes: ByteArray, lmBytes: ByteArray, neuralBytes: ByteArray, fusionBytes: ByteArray): Long

        @JvmStatic
        private external fun nativeSetLanguageContext(handle: Long, left: String, right: String)

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

        @JvmStatic
        private external fun nativePreprocess(
            handle: Long,
            pixels: ByteArray,
            width: Int,
            height: Int,
        ): ByteArray?

        @JvmStatic
        private external fun nativePredictStrokes(
            handle: Long,
            flatPoints: FloatArray,
            strokeCounts: IntArray,
            canvasWidth: Float,
            canvasHeight: Float,
            topK: Int,
            prevChar: String?,
        ): Array<String>?
    }

    @Synchronized
    override fun setLanguageContext(left: String, right: String) {
        if (nativeHandle != 0L) nativeSetLanguageContext(nativeHandle, left, right)
    }

    @Synchronized
    override fun modelInputPixels(pixels: ByteArray, width: Int, height: Int): ByteArray {
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
        return nativePreprocess(handle, pixels, width, height)
            ?: throw HandwritingRecognitionException("Native handwriting preprocessing failed")
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
    override fun predictStrokes(
        strokes: List<List<StrokePoint>>,
        canvasWidth: Float,
        canvasHeight: Float,
        topK: Int,
        prevChar: String?,
    ): List<HandwritingCandidate> {
        val handle = nativeHandle
        if (handle == 0L) {
            throw HandwritingRecognitionException("The handwriting recognizer is closed")
        }
        if (strokes.isEmpty() || canvasWidth <= 0f || canvasHeight <= 0f) {
            return emptyList()
        }
        if (topK <= 0) {
            throw HandwritingRecognitionException("topK must be positive")
        }
        val totalPoints = strokes.sumOf { it.size }
        if (totalPoints == 0) return emptyList()

        Log.i(TAG, "predictStrokes: ${strokes.size} strokes, total points=$totalPoints, canvas=${canvasWidth}x${canvasHeight}, prevChar=$prevChar")

        val flatPoints = FloatArray(totalPoints * 3)
        val strokeCounts = IntArray(strokes.size)
        var offset = 0
        strokes.forEachIndexed { strokeIdx, stroke ->
            strokeCounts[strokeIdx] = stroke.size
            for (pt in stroke) {
                flatPoints[offset++] = pt.x
                flatPoints[offset++] = pt.y
                flatPoints[offset++] = pt.timeMs.toFloat()
            }
        }

        val predictions = nativePredictStrokes(
            handle,
            flatPoints,
            strokeCounts,
            canvasWidth,
            canvasHeight,
            topK,
            prevChar,
        ) ?: throw HandwritingRecognitionException("Native handwriting stroke inference failed")

        Log.i(TAG, "predictStrokes result: ${predictions.joinToString(", ")}")

        runCatching {
            val file = java.io.File("/sdcard/Android/data/com.madeinhk.cantonesevoicekeyboard/files/last_input_strokes.json")
            file.parentFile?.mkdirs()
            val json = org.json.JSONObject()
            json.put("canvas_width", canvasWidth)
            json.put("canvas_height", canvasHeight)
            val strokesArray = org.json.JSONArray()
            for (stroke in strokes) {
                val ptsArray = org.json.JSONArray()
                for (pt in stroke) {
                    val p = org.json.JSONObject()
                    p.put("x", pt.x)
                    p.put("y", pt.y)
                    p.put("t", pt.timeMs)
                    ptsArray.put(p)
                }
                strokesArray.put(ptsArray)
            }
            json.put("strokes", strokesArray)
            val predsArray = org.json.JSONArray()
            for (p in predictions) predsArray.put(p)
            json.put("predictions", predsArray)
            file.writeText(json.toString(2))
            Log.i(TAG, "Saved debug strokes to ${file.absolutePath}")
        }

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
