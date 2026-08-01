package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.math.ceil

/** Runs the sealed Drive samples through the installed APK's JNI recognizer. */
@RunWith(AndroidJUnit4::class)
class OnDeviceKeyboardEvalTest {
    @Test
    fun runPromptedKeyboardEvalThroughInstalledJni() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val input = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "keyboard-eval-samples.zip",
        )
        assertTrue("missing pushed eval ZIP: ${input.absolutePath}", input.isFile)

        val modelBytes = context.assets.open("handwriting/model.safetensors").use { it.readBytes() }
        val labels = context.assets.open("handwriting/labels.txt")
            .bufferedReader()
            .readLines()
            .filter(String::isNotEmpty)
        val modelSha256 = sha256(modelBytes)
        val recognizer = HandwritingRecognizer.load(context)
        assertNotNull("installed APK recognizer failed to load", recognizer)
        val engine = requireNotNull(recognizer)

        var samples = 0
        var top1 = 0
        var top4 = 0
        var top10 = 0
        var modelInputParity = 0
        var capturedTop10Exact = 0
        val predictMillis = mutableListOf<Double>()
        ZipFile(input).use { archive ->
            val metadataEntries = archive.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith("/sample.json") }
                .sortedBy { it.name }
                .toList()
            assertEquals(101, metadataEntries.size)
            for (metadataEntry in metadataEntries) {
                val prefix = metadataEntry.name.removeSuffix("/sample.json")
                val metadata = JSONObject(archive.getInputStream(metadataEntry).use { it.reader().readText() })
                assertEquals("complete", metadata.getString("inference_status"))
                assertEquals(modelSha256, metadata.getString("model_sha256"))
                assertEquals(labels.size, 13_065)

                val originalEntry = archive.getEntry("$prefix/original.png")
                val modelInputEntry = archive.getEntry("$prefix/model_input.png")
                assertNotNull(originalEntry)
                assertNotNull(modelInputEntry)
                val bitmap = BitmapFactory.decodeStream(archive.getInputStream(requireNotNull(originalEntry)))
                assertNotNull(bitmap)
                val image = requireNotNull(bitmap)
                try {
                    val pixels = image.toGrayscalePixels()
                    val nativeInput = engine.modelInputPixels(pixels, image.width, image.height)
                    val recordedInput = BitmapFactory.decodeStream(archive.getInputStream(requireNotNull(modelInputEntry)))
                    assertNotNull(recordedInput)
                    val recordedPixels = requireNotNull(recordedInput).toGrayscalePixels()
                    if (nativeInput.contentEquals(recordedPixels)) modelInputParity += 1
                    requireNotNull(recordedInput).recycle()

                    val started = System.nanoTime()
                    val predictions = engine.predict(pixels, image.width, image.height, 10)
                    predictMillis += (System.nanoTime() - started) / 1_000_000.0
                    val predictedTexts = predictions.map { it.text }
                    val expectedText = metadata.getString("prompted_character")
                    val rank = predictedTexts.indexOf(expectedText)
                    if (rank == 0) top1++
                    if (rank in 0..3) top4++
                    if (rank in 0..9) top10++

                    val captured = metadata.getJSONArray("predictions").toStringList()
                    if (predictedTexts == captured) capturedTop10Exact++
                    samples++
                } finally {
                    image.recycle()
                }
            }
        }
        engine.close()

        val sortedMillis = predictMillis.sorted()
        val report = JSONObject()
            .put("model_sha256", modelSha256)
            .put("labels", labels.size)
            .put("samples", samples)
            .put("top1", top1.toDouble() / samples)
            .put("top4", top4.toDouble() / samples)
            .put("top10", top10.toDouble() / samples)
            .put("model_input_parity", modelInputParity.toDouble() / samples)
            .put("captured_ordered_top10_exact_samples", capturedTop10Exact)
            .put("predict_mean_ms", predictMillis.average())
            .put("predict_p95_ms", sortedMillis[(ceil(sortedMillis.size * 0.95).toInt() - 1).coerceAtLeast(0)])
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "on-device-eval.json")
        output.writeText(report.toString(2))
        println("ON_DEVICE_EVAL ${report}")
        assertEquals(101, samples)
        assertEquals(101, capturedTop10Exact)
    }

    private fun Bitmap.toGrayscalePixels(): ByteArray {
        val argb = IntArray(width * height)
        getPixels(argb, 0, width, 0, 0, width, height)
        return ByteArray(argb.size) { index -> ((argb[index] shr 16) and 0xff).toByte() }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getJSONObject(it).getString("text") }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
