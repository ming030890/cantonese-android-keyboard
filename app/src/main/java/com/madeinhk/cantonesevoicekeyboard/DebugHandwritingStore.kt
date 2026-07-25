package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugHandwritingCandidate(
    val text: String,
    val rank: Int,
)

data class DebugHandwritingRecord(
    val id: String,
    val directory: File,
    val originalImageFile: File?,
    /** Exact preprocessed image passed to the model; name retained for UI compatibility. */
    val inputImageFile: File,
    val createdAtMillis: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val width: Int,
    val height: Int,
    val candidates: List<DebugHandwritingCandidate>,
    val modelTop1: String,
    val chosenCharacter: String,
    val correctedCharacter: String,
    val isBad: Boolean,
    val isAnnotated: Boolean,
    val isSynced: Boolean,
    val inferenceCompleted: Boolean,
)

object DebugHandwritingStore {
    private const val TAG = "DebugHandwritingStore"
    private const val DIRECTORY_NAME = "debug_handwriting"
    private const val METADATA_FILE_NAME = "recording.json"
    private const val LEGACY_INPUT_IMAGE_FILE_NAME = "input.png"
    private const val ORIGINAL_IMAGE_FILE_NAME = "original.png"
    private const val MODEL_INPUT_IMAGE_FILE_NAME = "model_input.png"
    private const val MAX_RECORDINGS = 100

    fun capture(
        context: Context,
        originalPixels: ByteArray,
        originalWidth: Int,
        originalHeight: Int,
        modelInputPixels: ByteArray,
        modelInputWidth: Int,
        modelInputHeight: Int,
    ): String? {
        if (!DebugHandwritingSettings.isStoreDebugHandwritingEnabled(context)) return null
        if (!isValidImage(originalPixels, originalWidth, originalHeight)) return null
        if (!isValidImage(modelInputPixels, modelInputWidth, modelInputHeight)) return null

        var createdDirectory: File? = null
        return runCatching {
            val root = recordsRoot(context) ?: return null
            val createdAt = System.currentTimeMillis()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(Date(createdAt))
            val id = "handwriting_$timestamp"
            val directory = File(root, id)
            check(directory.mkdirs()) { "Could not create ${directory.absolutePath}" }
            createdDirectory = directory
            writeGrayscalePng(
                file = File(directory, ORIGINAL_IMAGE_FILE_NAME),
                pixels = originalPixels,
                width = originalWidth,
                height = originalHeight,
            )
            writeGrayscalePng(
                file = File(directory, MODEL_INPUT_IMAGE_FILE_NAME),
                pixels = modelInputPixels,
                width = modelInputWidth,
                height = modelInputHeight,
            )
            val metadata = JSONObject()
                .put("schema_version", 2)
                .put("recording_id", id)
                .put("created_at_ms", createdAt)
                .put("original_image_file", ORIGINAL_IMAGE_FILE_NAME)
                .put("original_width", originalWidth)
                .put("original_height", originalHeight)
                .put("original_pixel_encoding", "black_ink_0_white_background_255")
                .put("model_input_image_file", MODEL_INPUT_IMAGE_FILE_NAME)
                .put("model_input_width", modelInputWidth)
                .put("model_input_height", modelInputHeight)
                .put("model_input_pixel_encoding", "ink_intensity_255_background_0")
                // Retain these aliases for older dataset consumers.
                .put("input_image_file", MODEL_INPUT_IMAGE_FILE_NAME)
                .put("width", modelInputWidth)
                .put("height", modelInputHeight)
                .put("candidates", JSONArray())
                .put("model_top1", "")
                .put("chosen_character", "")
                .put("corrected_character", "")
                .put("is_bad", false)
                .put("is_annotated", false)
                .put("synced", false)
                .put("inference_completed", false)
            writeJsonAtomically(File(directory, METADATA_FILE_NAME), metadata)
            pruneOldRecords(root)
            id
        }.onFailure {
            createdDirectory?.deleteRecursively()
            Log.w(TAG, "Failed to save handwriting sample", it)
        }.getOrNull()
    }

    fun saveInferenceResult(
        context: Context,
        recordingId: String,
        candidates: List<DebugHandwritingCandidate>,
    ): Boolean {
        val top1 = candidates.firstOrNull()?.text.orEmpty()
        return updateMetadata(context, recordingId) { metadata ->
            val chosen = metadata.optString("chosen_character", "")
            metadata.put("candidates", candidatesJson(candidates))
                .put("model_top1", top1)
                .put("corrected_character", chosen.ifBlank { top1 })
                .put("inference_completed", true)
                .put("synced", false)
        }
    }

    fun list(context: Context): List<DebugHandwritingRecord> {
        val root = recordsRoot(context) ?: return emptyList()
        return root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .mapNotNull(::readRecord)
            .sortedByDescending { it.createdAtMillis }
    }

    fun markChosen(context: Context, recordingId: String, character: String): Boolean =
        updateMetadata(context, recordingId) { metadata ->
            val baseline = character.ifBlank { metadata.optString("model_top1", "") }
            metadata.put("chosen_character", character)
                .put("corrected_character", baseline)
                .put("is_annotated", false)
                .put("synced", false)
        }

    fun saveAnnotation(
        context: Context,
        recordingId: String,
        correction: String,
        isBad: Boolean,
    ): Boolean {
        val updated = updateMetadata(context, recordingId) { metadata ->
            val baseline = metadata.optString("chosen_character", "")
                .ifBlank { metadata.optString("model_top1", "") }
            val normalized = correction.trim()
            val annotated = normalized.isNotBlank() && (!isBad || normalized != baseline.trim())
            metadata.put("corrected_character", correction)
                .put("is_bad", isBad)
                .put("is_annotated", annotated)
                .put("synced", false)
        }
        return updated
    }

    fun markSynced(context: Context, recordingId: String, synced: Boolean): Boolean =
        updateMetadata(context, recordingId) { it.put("synced", synced) }

    fun delete(context: Context, recordingId: String): Boolean = runCatching {
        val root = recordsRoot(context) ?: return false
        val directory = File(root, recordingId)
        if (directory.canonicalFile.parentFile != root.canonicalFile || !directory.isDirectory) {
            return false
        }
        directory.deleteRecursively()
    }.onFailure { Log.w(TAG, "Failed to delete $recordingId", it) }.getOrDefault(false)

    private fun readRecord(directory: File): DebugHandwritingRecord? = runCatching {
        val metadataFile = File(directory, METADATA_FILE_NAME)
        val metadata = if (metadataFile.isFile) JSONObject(metadataFile.readText()) else JSONObject()
        val modelInputFileName = metadata.optString(
            "model_input_image_file",
            metadata.optString("input_image_file", LEGACY_INPUT_IMAGE_FILE_NAME),
        )
        val image = File(directory, modelInputFileName)
        if (!image.isFile) return null
        val originalImage = metadata.optString("original_image_file", "")
            .takeIf(String::isNotBlank)
            ?.let { File(directory, it) }
            ?.takeIf(File::isFile)
        val candidates = metadata.optJSONArray("candidates")?.let(::readCandidates).orEmpty()
        DebugHandwritingRecord(
            id = directory.name,
            directory = directory,
            originalImageFile = originalImage,
            inputImageFile = image,
            createdAtMillis = metadata.optLong("created_at_ms", directory.lastModified()),
            originalWidth = metadata.optInt("original_width", 0),
            originalHeight = metadata.optInt("original_height", 0),
            width = metadata.optInt("model_input_width", metadata.optInt("width", 0)),
            height = metadata.optInt("model_input_height", metadata.optInt("height", 0)),
            candidates = candidates,
            modelTop1 = metadata.optString("model_top1", candidates.firstOrNull()?.text.orEmpty()),
            chosenCharacter = metadata.optString("chosen_character", ""),
            correctedCharacter = metadata.optString(
                "corrected_character",
                metadata.optString("model_top1", "")
            ),
            isBad = metadata.optBoolean("is_bad", false),
            isAnnotated = metadata.optBoolean("is_annotated", false),
            isSynced = metadata.optBoolean("synced", false),
            inferenceCompleted = metadata.optBoolean("inference_completed", true),
        )
    }.onFailure { Log.w(TAG, "Failed to read ${directory.absolutePath}", it) }.getOrNull()

    private fun candidatesJson(candidates: List<DebugHandwritingCandidate>): JSONArray =
        JSONArray().also { array ->
            candidates.forEach { candidate ->
                array.put(JSONObject().put("text", candidate.text).put("rank", candidate.rank))
            }
        }

    private fun isValidImage(pixels: ByteArray, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() == pixels.size.toLong()

    private fun writeGrayscalePng(
        file: File,
        pixels: ByteArray,
        width: Int,
        height: Int,
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val colors = IntArray(pixels.size) { index ->
                val value = pixels[index].toInt() and 0xff
                Color.rgb(value, value, value)
            }
            bitmap.setPixels(colors, 0, width, 0, 0, width, height)
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not encode ${file.name}"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun readCandidates(array: JSONArray): List<DebugHandwritingCandidate> =
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { candidate ->
                DebugHandwritingCandidate(
                    text = candidate.optString("text", ""),
                    rank = candidate.optInt("rank", index),
                )
            }
        }

    private fun updateMetadata(
        context: Context,
        recordingId: String,
        transform: (JSONObject) -> JSONObject,
    ): Boolean = runCatching {
        val root = recordsRoot(context) ?: return false
        val directory = File(root, recordingId)
        val file = File(directory, METADATA_FILE_NAME)
        if (!file.isFile) return false
        writeJsonAtomically(file, transform(JSONObject(file.readText())))
        true
    }.onFailure { Log.w(TAG, "Failed to update $recordingId", it) }.getOrDefault(false)

    private fun recordsRoot(context: Context): File? {
        val root = File(context.getExternalFilesDir(null) ?: return null, DIRECTORY_NAME)
        if (!root.exists() && !root.mkdirs()) return null
        return root.takeIf(File::isDirectory)
    }

    private fun pruneOldRecords(root: File) {
        root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .sortedByDescending { it.lastModified() }
            .drop(MAX_RECORDINGS)
            .forEach { it.deleteRecursively() }
    }

    private fun writeJsonAtomically(file: File, json: JSONObject) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.toString(2))
        if (!temporary.renameTo(file)) {
            file.writeText(json.toString(2))
            temporary.delete()
        }
    }
}
