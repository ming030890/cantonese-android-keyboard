package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.MotionEvent
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.HandwritingBitmap
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.PromptedInkSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class PromptedHandwritingRecord(
    val id: String,
    val directory: File,
    val readyToSync: Boolean,
    val synced: Boolean,
)

internal object PromptedHandwritingStore {
    private const val DIRECTORY = "prompted_handwriting_eval"
    private const val SAMPLE_JSON = "sample.json"
    private const val STROKES_JSON = "strokes.json"
    private const val ORIGINAL_PNG = "original.png"
    private const val MODEL_INPUT_PNG = "model_input.png"
    @Volatile private var cachedModelHash: String? = null

    fun capture(
        context: Context,
        character: String,
        classId: Int,
        inventoryHash: String,
        snapshot: PromptedInkSnapshot,
        bitmap: HandwritingBitmap,
        date: LocalDate = LocalDate.now(),
    ): String {
        require(character.codePointCount(0, character.length) == 1)
        require(!snapshot.isEmpty && snapshot.canvasSize.width > 0 && snapshot.canvasSize.height > 0)
        val root = root(context)
        val id = UUID.randomUUID().toString()
        val temporary = File(root, ".$id.tmp")
        val target = File(root, id)
        check(temporary.mkdirs()) { "Could not create prompted sample" }
        try {
            val original = File(temporary, ORIGINAL_PNG)
            writeGrayscalePng(original, bitmap)
            val strokesFile = File(temporary, STROKES_JSON)
            strokesFile.writeText(strokesJson(snapshot).toString(2))
            val createdAt = System.currentTimeMillis()
            val files = JSONObject()
                .put(ORIGINAL_PNG, sha256(original))
                .put(STROKES_JSON, sha256(strokesFile))
            val metadata = JSONObject()
                .put("schema_version", 1)
                .put("collection", "prompted_handwriting_eval")
                .put("sample_id", id)
                .put("prompted_character", character)
                .put("class_id", classId)
                .put("split", "eval")
                .put("writer_id", PromptedHandwritingProgress.writerId(context))
                .put("inventory_sha256", inventoryHash)
                .put("model_sha256", modelHash(context))
                .put("created_at_ms", createdAt)
                .put("challenge_local_date", date.toString())
                .put("timezone_id", ZoneId.systemDefault().id)
                .put("app_version", appVersion(context))
                .put("android_sdk", Build.VERSION.SDK_INT)
                .put("device_manufacturer", Build.MANUFACTURER)
                .put("device_model", Build.MODEL)
                .put("density_dpi", context.resources.displayMetrics.densityDpi)
                .put("orientation", context.resources.configuration.orientation)
                .put("canvas_width", bitmap.width)
                .put("canvas_height", bitmap.height)
                .put("stroke_width_px", com.madeinhk.cantonesevoicekeyboard.ui.keyboard
                    .handwritingStrokeWidthPx(bitmap.width.toFloat(), bitmap.height.toFloat()))
                .put("original_pixel_encoding", "black_ink_0_white_background_255")
                .put("original_image_file", ORIGINAL_PNG)
                .put("model_input_image_file", JSONObject.NULL)
                .put("stroke_file", STROKES_JSON)
                .put("stroke_count", snapshot.strokes.size)
                .put("point_count", snapshot.pointCount)
                .put("duration_ms", snapshot.strokes.flatten().maxOfOrNull { it.elapsedMillis } ?: 0L)
                .put("quality", qualityJson(snapshot))
                .put("inference_status", "pending")
                .put("predictions", JSONArray())
                .put("expected_rank", JSONObject.NULL)
                .put("ready_to_sync", false)
                .put("synced", false)
                .put("files_sha256", files)
            writeJsonAtomically(File(temporary, SAMPLE_JSON), metadata)
            check(temporary.renameTo(target)) { "Could not commit prompted sample" }
            return id
        } catch (error: Throwable) {
            temporary.deleteRecursively()
            throw error
        }
    }

    fun finalizeInference(
        context: Context,
        recordId: String,
        modelInput: ByteArray?,
        modelWidth: Int = 48,
        modelHeight: Int = 48,
        predictions: List<HandwritingCandidate> = emptyList(),
        error: String? = null,
    ): Boolean = update(context, recordId) { directory, metadata ->
        val files = metadata.optJSONObject("files_sha256") ?: JSONObject()
        if (modelInput != null && modelInput.size == modelWidth * modelHeight) {
            val modelFile = File(directory, MODEL_INPUT_PNG)
            writeGrayscalePng(modelFile, HandwritingBitmap(modelInput, modelWidth, modelHeight))
            files.put(MODEL_INPUT_PNG, sha256(modelFile))
            metadata.put("model_input_image_file", MODEL_INPUT_PNG)
                .put("model_input_width", modelWidth)
                .put("model_input_height", modelHeight)
                .put("model_input_pixel_encoding", "ink_intensity_255_background_0")
        }
        val expected = metadata.getString("prompted_character")
        val candidates = JSONArray()
        predictions.forEach { candidates.put(JSONObject().put("text", it.text).put("rank", it.rank)) }
        val expectedRank = predictions.firstOrNull { it.text == expected }?.rank
        metadata.put("predictions", candidates)
            .put("expected_rank", expectedRank ?: JSONObject.NULL)
            .put("inference_status", if (error == null) "complete" else "unavailable")
            .put("inference_error", error ?: JSONObject.NULL)
            .put("ready_to_sync", true)
            .put("files_sha256", files)
    }

    fun list(context: Context): List<PromptedHandwritingRecord> =
        root(context).listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { directory ->
                runCatching {
                    val metadata = JSONObject(File(directory, SAMPLE_JSON).readText())
                    PromptedHandwritingRecord(
                        id = directory.name,
                        directory = directory,
                        readyToSync = metadata.optBoolean("ready_to_sync"),
                        synced = metadata.optBoolean("synced"),
                    )
                }.getOrNull()
            }

    fun markSynced(context: Context, recordId: String): Boolean =
        update(context, recordId) { _, metadata -> metadata.put("synced", true) }

    fun deletePayload(context: Context, recordId: String): Boolean {
        val directory = File(root(context), recordId)
        return directory.isDirectory && directory.deleteRecursively()
    }

    private fun update(
        context: Context,
        recordId: String,
        transform: (File, JSONObject) -> JSONObject,
    ): Boolean = runCatching {
        val directory = File(root(context), recordId)
        require(directory.canonicalFile.parentFile == root(context).canonicalFile)
        val file = File(directory, SAMPLE_JSON)
        val metadata = transform(directory, JSONObject(file.readText()))
        writeJsonAtomically(file, metadata)
        true
    }.getOrDefault(false)

    private fun root(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIRECTORY).also { check(it.exists() || it.mkdirs()) }
    }

    private fun strokesJson(snapshot: PromptedInkSnapshot): JSONObject {
        val width = snapshot.canvasSize.width.toFloat()
        val height = snapshot.canvasSize.height.toFloat()
        val strokes = JSONArray()
        snapshot.strokes.forEachIndexed { strokeIndex, stroke ->
            val points = JSONArray()
            stroke.forEach { point ->
                points.put(
                    JSONObject()
                        .put("x_px", point.x)
                        .put("y_px", point.y)
                        .put("x_norm", point.x / width)
                        .put("y_norm", point.y / height)
                        .put("t_ms", point.elapsedMillis)
                        .put("pressure", point.pressure)
                        .put("size", point.size)
                        .put("orientation_rad", point.orientationRadians)
                        .put("tilt_rad", point.tiltRadians ?: JSONObject.NULL)
                        .put("tool_type", toolName(point.toolType))
                )
            }
            strokes.put(JSONObject().put("stroke_index", strokeIndex).put("points", points))
        }
        return JSONObject()
            .put("schema_version", 1)
            .put("coordinate_space", JSONObject()
                .put("origin", "top_left")
                .put("x_direction", "right")
                .put("y_direction", "down")
                .put("canvas_width_px", snapshot.canvasSize.width)
                .put("canvas_height_px", snapshot.canvasSize.height))
            .put("strokes", strokes)
    }

    private fun qualityJson(snapshot: PromptedInkSnapshot): JSONObject {
        val points = snapshot.strokes.flatten()
        val xs = points.map { it.x }
        val ys = points.map { it.y }
        return JSONObject()
            .put("finite_coordinates", points.all { it.x.isFinite() && it.y.isFinite() })
            .put("within_canvas", points.all {
                it.x in 0f..snapshot.canvasSize.width.toFloat() &&
                    it.y in 0f..snapshot.canvasSize.height.toFloat()
            })
            .put("bbox_left", xs.minOrNull() ?: 0f)
            .put("bbox_top", ys.minOrNull() ?: 0f)
            .put("bbox_right", xs.maxOrNull() ?: 0f)
            .put("bbox_bottom", ys.maxOrNull() ?: 0f)
    }

    private fun toolName(toolType: Int): String = when (toolType) {
        MotionEvent.TOOL_TYPE_FINGER -> "finger"
        MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
        MotionEvent.TOOL_TYPE_ERASER -> "eraser"
        MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
        else -> "unknown"
    }

    private fun writeGrayscalePng(file: File, image: HandwritingBitmap) {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            val colors = IntArray(image.pixels.size) { index ->
                val value = image.pixels[index].toInt() and 0xff
                Color.rgb(value, value, value)
            }
            bitmap.setPixels(colors, 0, image.width, 0, 0, image.width, image.height)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeJsonAtomically(file: File, json: JSONObject) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.toString(2))
        check(temporary.renameTo(file) || run {
            file.writeText(json.toString(2))
            temporary.delete()
            true
        })
    }

    private fun sha256(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().toHex()
        }

    private fun appVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"

    private fun modelHash(context: Context): String {
        cachedModelHash?.let { return it }
        return synchronized(this) {
            cachedModelHash ?: context.assets.open("handwriting/model.safetensors").use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().toHex().also { cachedModelHash = it }
            }
        }
    }
}
