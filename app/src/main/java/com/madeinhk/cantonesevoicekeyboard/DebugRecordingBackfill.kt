package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import com.qwenasr.QAsrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DebugBackfillResult(val completed: Int, val failed: Int)

object DebugRecordingBackfill {
    private const val WAV_HEADER_BYTES = 44L
    private const val CHUNK_BYTES = 8192

    suspend fun runMissing(
        context: Context,
        recordings: List<DebugRecording>,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): DebugBackfillResult {
        val missing = recordings.filterNot { it.inferenceCompleted }
        if (missing.isEmpty()) return DebugBackfillResult(0, 0)
        val modelDir = ModelAssetPack.resolveModelDir(context)
            ?: return DebugBackfillResult(0, missing.size)

        return withContext(Dispatchers.Default) {
            val engine = QAsrEngine()
            var completed = 0
            var failed = 0
            try {
                val optimalThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                if (!engine.loadModel(modelDir.absolutePath, optimalThreads)) {
                    return@withContext DebugBackfillResult(0, missing.size)
                }
                engine.setLanguage("Cantonese")
                engine.setChineseVariant("")
                engine.setPrompt("")
                engine.setSegmentSec(0f)

                missing.forEach { recording ->
                    val success = runCatching { transcribeRecording(context, engine, recording) }
                        .getOrDefault(false)
                    if (success) completed++ else failed++
                    onProgress(completed + failed, missing.size)
                }
                DebugBackfillResult(completed, failed)
            } finally {
                engine.free()
            }
        }
    }

    private fun transcribeRecording(
        context: Context,
        engine: QAsrEngine,
        recording: DebugRecording
    ): Boolean {
        recording.directory.listFiles().orEmpty().forEach { file ->
            if (
                file.name == "vad_segments.jsonl" ||
                file.name == "vad_session.json" ||
                (file.name.startsWith("model_input_seg") && file.extension == "wav")
            ) {
                file.delete()
            }
        }

        val transcript = StringBuffer()
        val listener = object : QAsrEngine.VadListener {
            override fun onTranscriptDelta(textDelta: String) {
                transcript.append(textDelta)
            }

            override fun onTranscriptComplete() = Unit
            override fun onTranscriptError() = Unit
        }
        if (!engine.startVadSession(listener, recording.directory.absolutePath)) return false

        return try {
            FileInputStream(recording.inputAudioFile).use { input ->
                input.channel.position(WAV_HEADER_BYTES)
                val bytes = ByteArray(CHUNK_BYTES)
                val direct = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.nativeOrder())
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    if (count == 0) continue
                    direct.clear()
                    direct.put(bytes, 0, count)
                    direct.position(0)
                    if (engine.pushPcm16Chunk(direct, count) < 0) {
                        error("Native VAD rejected audio")
                    }
                }
            }
            engine.stopVadSession(finalize = true)
            DebugAudioStore.saveInferenceResult(context, recording.id, transcript.toString())
        } catch (error: Throwable) {
            engine.stopVadSession(finalize = false)
            false
        }
    }
}
