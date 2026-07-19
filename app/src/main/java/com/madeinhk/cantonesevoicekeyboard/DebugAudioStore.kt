package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugRecording(
    val id: String,
    val directory: File,
    val inputAudioFile: File,
    val createdAtMillis: Long,
    val durationMillis: Long,
    val modelTranscript: String,
    val correctedTranscript: String,
    val isBad: Boolean,
    val isAnnotated: Boolean,
    val isSynced: Boolean,
    val inferenceCompleted: Boolean,
    val vadSegments: List<DebugVadSegment>,
    val vadSummary: DebugVadSummary?
)

data class DebugVadSegment(
    val id: Long,
    val audioFile: File,
    val startMillis: Long,
    val endMillis: Long,
    val sampleCount: Long,
    val endReason: String,
    val scoreMin: Float,
    val scoreMean: Float,
    val scoreMax: Float,
    val transcript: String
)

data class DebugVadSummary(
    val positiveThreshold: Float,
    val negativeThreshold: Float,
    val minimumSilenceMillis: Long,
    val speechPaddingMillis: Long,
    val samplesReceived: Long,
    val samplesEnqueuedForAsr: Long,
    val queueOverflowCount: Long
)

class DebugAudioStore private constructor(
    val recordingId: String,
    val directory: File,
    private val outputFile: File,
    private val outputStream: FileOutputStream,
    private val createdAtMillis: Long
) {
    private var bytesWritten = 0
    private var inputClosed = false

    @Synchronized
    fun writePcm16(buffer: ByteBuffer, byteCount: Int) {
        if (byteCount <= 0 || inputClosed) return
        val copy = ByteArray(byteCount)
        val duplicate = buffer.duplicate()
        duplicate.position(0)
        duplicate.limit(byteCount)
        duplicate.get(copy)
        outputStream.write(copy)
        bytesWritten += byteCount
    }

    @Synchronized
    fun closeInput() {
        if (inputClosed) return
        try {
            outputStream.channel.position(0)
            outputStream.write(wavHeader(bytesWritten))
        } finally {
            outputStream.close()
            inputClosed = true
        }
    }

    fun finish(modelTranscript: String) {
        closeInput()
        val metadata = JSONObject()
            .put("schema_version", 1)
            .put("recording_id", recordingId)
            .put("created_at_ms", createdAtMillis)
            .put("sample_rate_hz", SAMPLE_RATE)
            .put("channels", CHANNELS)
            .put("bits_per_sample", BITS_PER_SAMPLE)
            .put("input_audio_file", outputFile.name)
            .put("input_samples", bytesWritten / 2)
            .put("duration_ms", bytesWritten.toLong() * 1000L / (SAMPLE_RATE * 2L))
            .put("model_transcript", modelTranscript)
            .put("corrected_transcript", modelTranscript)
            .put("is_bad", false)
            .put("is_annotated", false)
            .put("synced", false)
            .put("inference_completed", true)
        writeJsonAtomically(File(directory, METADATA_FILE_NAME), metadata)
        writeTranscriptFiles(directory, modelTranscript, modelTranscript)
        pruneOldRecordings(directory.parentFile)
    }

    fun closeAndDelete() {
        runCatching { outputStream.close() }
        inputClosed = true
        directory.deleteRecursively()
    }

    private fun wavHeader(dataBytes: Int): ByteArray {
        val totalDataLen = dataBytes + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

        return ByteBuffer.allocate(WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt(totalDataLen)
            .put("WAVE".toByteArray(Charsets.US_ASCII))
            .put("fmt ".toByteArray(Charsets.US_ASCII))
            .putInt(16)
            .putShort(1)
            .putShort(CHANNELS.toShort())
            .putInt(SAMPLE_RATE)
            .putInt(byteRate)
            .putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
            .putShort(BITS_PER_SAMPLE.toShort())
            .put("data".toByteArray(Charsets.US_ASCII))
            .putInt(dataBytes)
            .array()
    }

    companion object {
        private const val TAG = "DebugAudioStore"
        private const val DIRECTORY_NAME = "debug_audio"
        private const val METADATA_FILE_NAME = "recording.json"
        private const val MODEL_TRANSCRIPT_FILE_NAME = "model_transcript.txt"
        private const val CORRECTED_TRANSCRIPT_FILE_NAME = "corrected_transcript.txt"
        private const val MAX_RECORDINGS = 100
        private const val WAV_HEADER_BYTES = 44
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16

        fun createIfEnabled(context: Context): DebugAudioStore? {
            if (!DebugAudioSettings.isStoreDebugAudioEnabled(context)) return null

            return try {
                val root = recordingsRoot(context) ?: return null
                val createdAt = System.currentTimeMillis()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(createdAt))
                val recordingId = "recording_$timestamp"
                val directory = File(root, recordingId)
                if (!directory.mkdirs()) {
                    Log.w(TAG, "Failed to create debug recording directory: ${directory.absolutePath}")
                    return null
                }
                val file = File(directory, "input.wav")
                val stream = FileOutputStream(file)
                stream.write(ByteArray(WAV_HEADER_BYTES))
                DebugAudioStore(recordingId, directory, file, stream, createdAt)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to open debug audio recording", e)
                null
            }
        }

        fun list(context: Context): List<DebugRecording> {
            val root = recordingsRoot(context) ?: return emptyList()
            migrateLegacyRecordings(root)
            return root.listFiles().orEmpty().mapNotNull { entry ->
                when {
                    entry.isDirectory -> readRecording(entry)
                    entry.isFile && entry.extension.equals("wav", ignoreCase = true) ->
                        DebugRecording(
                            id = entry.nameWithoutExtension,
                            directory = root,
                            inputAudioFile = entry,
                            createdAtMillis = entry.lastModified(),
                            durationMillis = wavDurationMillis(entry),
                            modelTranscript = "",
                            correctedTranscript = "",
                            isBad = false,
                            isAnnotated = false,
                            isSynced = false,
                            inferenceCompleted = false,
                            vadSegments = emptyList(),
                            vadSummary = null
                        )
                    else -> null
                }
            }.sortedByDescending { it.createdAtMillis }
        }

        fun markBad(context: Context, recordingId: String, isBad: Boolean = true): Boolean =
            updateMetadata(context, recordingId) {
                it.put("is_bad", isBad)
                    .put("is_annotated", false)
                    .put("synced", false)
            }

        fun saveAnnotation(
            context: Context,
            recordingId: String,
            transcript: String,
            isBad: Boolean
        ): Boolean {
            val fullyAnnotated = !isBad || (
                transcript.isNotBlank() &&
                    transcript.trim() != readModelTranscript(context, recordingId).trim()
                )
            val updated = updateMetadata(context, recordingId) { metadata ->
                metadata.put("corrected_transcript", transcript)
                    .put("is_bad", isBad)
                    .put("is_annotated", fullyAnnotated)
                    .put("synced", false)
            }
            if (!updated) return false
            return writeCorrectedTranscript(context, recordingId, transcript)
        }

        private fun readModelTranscript(context: Context, recordingId: String): String = runCatching {
            val root = recordingsRoot(context) ?: return ""
            val metadata = File(File(root, recordingId), METADATA_FILE_NAME)
            if (!metadata.isFile) "" else JSONObject(metadata.readText()).optString("model_transcript", "")
        }.getOrDefault("")

        fun updateCorrectedTranscript(context: Context, recordingId: String, transcript: String): Boolean {
            val updated = updateMetadata(context, recordingId) { metadata ->
                metadata.put("corrected_transcript", transcript).put("synced", false)
            }
            if (!updated) return false
            return writeCorrectedTranscript(context, recordingId, transcript)
        }

        fun saveInferenceResult(context: Context, recordingId: String, modelTranscript: String): Boolean {
            var correctedTranscript = modelTranscript
            val updated = updateMetadata(context, recordingId) { metadata ->
                correctedTranscript = metadata.optString("corrected_transcript", "")
                    .ifBlank { modelTranscript }
                metadata.put("model_transcript", modelTranscript)
                    .put("corrected_transcript", correctedTranscript)
                    .put("inference_completed", true)
                    .put("synced", false)
            }
            if (!updated) return false
            return runCatching {
                val root = recordingsRoot(context) ?: return false
                val directory = File(root, recordingId)
                File(directory, MODEL_TRANSCRIPT_FILE_NAME).writeText(modelTranscript)
                File(directory, CORRECTED_TRANSCRIPT_FILE_NAME).writeText(correctedTranscript)
                true
            }.onFailure { Log.w(TAG, "Failed to write inference result for $recordingId", it) }
                .getOrDefault(false)
        }

        private fun writeCorrectedTranscript(context: Context, recordingId: String, transcript: String): Boolean {
            return runCatching {
                val root = recordingsRoot(context) ?: return false
                File(File(root, recordingId), CORRECTED_TRANSCRIPT_FILE_NAME).writeText(transcript)
                true
            }.onFailure { Log.w(TAG, "Failed to write corrected transcript for $recordingId", it) }
                .getOrDefault(false)
        }

        fun markSynced(context: Context, recordingId: String, synced: Boolean): Boolean =
            updateMetadata(context, recordingId) { it.put("synced", synced) }

        private fun updateMetadata(
            context: Context,
            recordingId: String,
            transform: (JSONObject) -> JSONObject
        ): Boolean = runCatching {
            val root = recordingsRoot(context) ?: return false
            val directory = File(root, recordingId)
            val file = File(directory, METADATA_FILE_NAME)
            if (!file.isFile) return false
            writeJsonAtomically(file, transform(JSONObject(file.readText())))
            true
        }.onFailure { Log.w(TAG, "Failed to update $recordingId", it) }.getOrDefault(false)

        private fun readRecording(directory: File): DebugRecording? = runCatching {
            val inputFile = File(directory, "input.wav")
            if (!inputFile.isFile) return null
            val metadataFile = File(directory, METADATA_FILE_NAME)
            val metadata = if (metadataFile.isFile) JSONObject(metadataFile.readText()) else JSONObject()
            val modelTranscript = metadata.optString("model_transcript", "")
            val correctedTranscript = metadata.optString("corrected_transcript", modelTranscript)
            val vadSegments = readVadSegments(directory)
            DebugRecording(
                id = directory.name,
                directory = directory,
                inputAudioFile = inputFile,
                createdAtMillis = metadata.optLong("created_at_ms", directory.lastModified()),
                durationMillis = metadata.optLong("duration_ms", wavDurationMillis(inputFile)),
                modelTranscript = modelTranscript,
                correctedTranscript = correctedTranscript,
                isBad = metadata.optBoolean("is_bad", false),
                isAnnotated = metadata.optBoolean("is_annotated", false),
                isSynced = metadata.optBoolean("synced", false),
                inferenceCompleted = metadata.optBoolean(
                    "inference_completed",
                    modelTranscript.isNotBlank() || File(directory, "vad_session.json").isFile
                ),
                vadSegments = vadSegments,
                vadSummary = readVadSummary(directory)
            )
        }.onFailure { Log.w(TAG, "Failed to read ${directory.absolutePath}", it) }.getOrNull()

        fun deleteRecording(context: Context, recordingId: String): Boolean = runCatching {
            val root = recordingsRoot(context) ?: return false
            val directory = File(root, recordingId)
            if (directory.canonicalFile.parentFile != root.canonicalFile || !directory.isDirectory) {
                return false
            }
            directory.deleteRecursively()
        }.onFailure { Log.w(TAG, "Failed to delete synced recording $recordingId", it) }
            .getOrDefault(false)

        private fun readVadSegments(directory: File): List<DebugVadSegment> {
            val metadataFile = File(directory, "vad_segments.jsonl")
            if (!metadataFile.isFile) return emptyList()
            return metadataFile.useLines { lines ->
                lines.mapNotNull { line ->
                    runCatching {
                        val json = JSONObject(line)
                        val audioFile = File(directory, json.getString("audio_file"))
                        if (!audioFile.isFile) return@runCatching null
                        DebugVadSegment(
                            id = json.getLong("segment_id"),
                            audioFile = audioFile,
                            startMillis = json.getLong("start_ms"),
                            endMillis = json.getLong("end_ms"),
                            sampleCount = json.getLong("sample_count"),
                            endReason = json.optString("end_reason", "unknown"),
                            scoreMin = json.optDouble("vad_score_min", 0.0).toFloat(),
                            scoreMean = json.optDouble("vad_score_mean", 0.0).toFloat(),
                            scoreMax = json.optDouble("vad_score_max", 0.0).toFloat(),
                            transcript = if (json.isNull("transcript")) {
                                ""
                            } else {
                                json.optString("transcript", "")
                            }
                        )
                    }.getOrNull()
                }.toList()
            }
        }

        private fun readVadSummary(directory: File): DebugVadSummary? = runCatching {
            val file = File(directory, "vad_session.json")
            if (!file.isFile) return null
            val json = JSONObject(file.readText())
            DebugVadSummary(
                positiveThreshold = json.getDouble("positive_threshold").toFloat(),
                negativeThreshold = json.getDouble("negative_threshold").toFloat(),
                minimumSilenceMillis = json.getLong("minimum_silence_ms"),
                speechPaddingMillis = json.getLong("speech_padding_ms"),
                samplesReceived = json.getLong("samples_received"),
                samplesEnqueuedForAsr = json.getLong("samples_enqueued_for_asr"),
                queueOverflowCount = json.getLong("queue_overflow_count")
            )
        }.getOrNull()

        private fun recordingsRoot(context: Context): File? {
            val externalMusicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: run {
                    Log.w(TAG, "External files directory is unavailable")
                    return null
                }
            return File(externalMusicDir, DIRECTORY_NAME).also { root ->
                if (!root.exists() && !root.mkdirs()) {
                    Log.w(TAG, "Failed to create debug audio directory: ${root.absolutePath}")
                }
            }.takeIf { it.isDirectory }
        }

        private fun writeTranscriptFiles(directory: File, model: String, corrected: String) {
            File(directory, MODEL_TRANSCRIPT_FILE_NAME).writeText(model)
            File(directory, CORRECTED_TRANSCRIPT_FILE_NAME).writeText(corrected)
        }

        private fun migrateLegacyRecordings(root: File) {
            root.listFiles { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
                .orEmpty()
                .forEach { legacyFile ->
                    val createdAt = legacyFile.lastModified()
                    val directory = File(root, legacyFile.nameWithoutExtension)
                    if (!directory.mkdir()) return@forEach
                    val inputFile = File(directory, "input.wav")
                    if (!legacyFile.renameTo(inputFile)) {
                        directory.delete()
                        return@forEach
                    }
                    val durationMillis = wavDurationMillis(inputFile)
                    val metadata = JSONObject()
                        .put("schema_version", 1)
                        .put("recording_id", directory.name)
                        .put("created_at_ms", createdAt)
                        .put("sample_rate_hz", SAMPLE_RATE)
                        .put("channels", CHANNELS)
                        .put("bits_per_sample", BITS_PER_SAMPLE)
                        .put("input_audio_file", inputFile.name)
                        .put("duration_ms", durationMillis)
                        .put("model_transcript", "")
                        .put("corrected_transcript", "")
                        .put("is_bad", false)
                        .put("is_annotated", false)
                        .put("synced", false)
                        .put("inference_completed", false)
                    writeJsonAtomically(File(directory, METADATA_FILE_NAME), metadata)
                    writeTranscriptFiles(directory, "", "")
                }
        }

        private fun writeJsonAtomically(file: File, json: JSONObject) {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(json.toString(2))
            if (!temporary.renameTo(file)) {
                file.writeText(json.toString(2))
                temporary.delete()
            }
        }

        private fun wavDurationMillis(file: File): Long =
            ((file.length() - WAV_HEADER_BYTES).coerceAtLeast(0L) * 1000L) / (SAMPLE_RATE * 2L)

        private fun pruneOldRecordings(root: File?) {
            val entries = root?.listFiles().orEmpty()
                .filter { it.isDirectory || it.extension.equals("wav", ignoreCase = true) }
                .sortedByDescending { it.lastModified() }
            entries.drop(MAX_RECORDINGS).forEach { entry ->
                val deleted = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
                if (!deleted) Log.w(TAG, "Failed to delete old debug recording: ${entry.absolutePath}")
            }
        }
    }
}
