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
    val inputImageFile: File,
    val createdAtMillis: Long,
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
    private const val INPUT_IMAGE_FILE_NAME = "input.png"
    private const val MAX_RECORDINGS = 100

    fun capture(
        context: Context,
        pixels: ByteArray,
        width: Int,
        height: Int,
    ): String? {
        if (!DebugHandwritingSettings.isStoreDebugHandwritingEnabled(context)) return null
        if (width <= 0 || height <= 0 || pixels.size != width * height) return null

        return runCatching {
            val root = recordsRoot(context) ?: return null
            val createdAt = System.currentTimeMillis()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(Date(createdAt))
            val id = "handwriting_$timestamp"
            val directory = File(root, id)
            check(directory.mkdirs()) { "Could not create ${directory.absolutePath}" }
            val imageFile = File(directory, INPUT_IMAGE_FILE_NAME)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val colors = IntArray(pixels.size) { index ->
                val value = pixels[index].toInt() and 0xff
                Color.rgb(value, value, value)
            }
            bitmap.setPixels(colors, 0, width, 0, 0, width, height)
            imageFile.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not encode handwriting bitmap"
                }
            }
            bitmap.recycle()
            val metadata = JSONObject()
                .put("schema_version", 1)
                .put("recording_id", id)
                .put("created_at_ms", createdAt)
                .put("input_image_file", INPUT_IMAGE_FILE_NAME)
                .put("width", width)
                .put("height", height)
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
        }.onFailure { Log.w(TAG, "Failed to save handwriting sample", it) }.getOrNull()
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
        val image = File(directory, INPUT_IMAGE_FILE_NAME)
        if (!image.isFile) return null
        val metadataFile = File(directory, METADATA_FILE_NAME)
        val metadata = if (metadataFile.isFile) JSONObject(metadataFile.readText()) else JSONObject()
        val candidates = metadata.optJSONArray("candidates")?.let(::readCandidates).orEmpty()
        DebugHandwritingRecord(
            id = directory.name,
            directory = directory,
            inputImageFile = image,
            createdAtMillis = metadata.optLong("created_at_ms", directory.lastModified()),
            width = metadata.optInt("width", 0),
            height = metadata.optInt("height", 0),
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
