package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugAudioStore private constructor(
    private val outputFile: File,
    private val outputStream: FileOutputStream
) {
    private var bytesWritten = 0

    fun writePcm16(buffer: ByteBuffer, byteCount: Int) {
        if (byteCount <= 0) return
        val copy = ByteArray(byteCount)
        val duplicate = buffer.duplicate()
        duplicate.position(0)
        duplicate.limit(byteCount)
        duplicate.get(copy)
        outputStream.write(copy)
        bytesWritten += byteCount
    }

    fun closeAndKeepLatest() {
        try {
            outputStream.channel.position(0)
            outputStream.write(wavHeader(bytesWritten))
        } finally {
            outputStream.close()
        }
        pruneOldRecordings(outputFile.parentFile)
    }

    fun closeAndDelete() {
        runCatching { outputStream.close() }
        runCatching { outputFile.delete() }
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
        private const val MAX_RECORDINGS = 100
        private const val WAV_HEADER_BYTES = 44
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16

        fun createIfEnabled(context: Context): DebugAudioStore? {
            if (!DebugAudioSettings.isStoreDebugAudioEnabled(context)) return null

            return try {
                val externalMusicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: run {
                        Log.w(TAG, "External files directory is unavailable")
                        return null
                    }
                val directory = File(externalMusicDir, DIRECTORY_NAME)
                if (!directory.exists() && !directory.mkdirs()) {
                    Log.w(TAG, "Failed to create debug audio directory: ${directory.absolutePath}")
                    return null
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val file = File(directory, "recording_$timestamp.wav")
                val stream = FileOutputStream(file)
                stream.write(ByteArray(WAV_HEADER_BYTES))
                DebugAudioStore(file, stream)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to open debug audio recording", e)
                null
            }
        }

        private fun pruneOldRecordings(directory: File?) {
            val files = directory
                ?.listFiles { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            files.drop(MAX_RECORDINGS).forEach { file ->
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete old debug audio file: ${file.absolutePath}")
                }
            }
        }
    }
}
