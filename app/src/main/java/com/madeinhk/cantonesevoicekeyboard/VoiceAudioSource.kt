package com.madeinhk.cantonesevoicekeyboard

import android.media.AudioRecord
import android.os.SystemClock
import java.nio.ByteBuffer

/** The replay substitutes only capture; chunk processing, JNI, VAD and editor stay shared. */
internal interface VoiceAudioSource {
    fun read(buffer: ByteBuffer): Int
    fun stop()
    fun release()
}

internal class MicrophoneVoiceAudioSource(private val recorder: AudioRecord) : VoiceAudioSource {
    override fun read(buffer: ByteBuffer) =
        recorder.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
    override fun stop() = recorder.stop()
    override fun release() = recorder.release()
}

internal class ReplayVoiceAudioSource(
    private val pcm: ByteArray,
    private val nowNanos: () -> Long = VoiceClock::nanoTime,
    private val waitNanos: (Long) -> Unit = { remaining ->
        Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
    },
) : VoiceAudioSource {
    private var offset = 0
    private val startedAt = nowNanos()
    @Volatile private var stopped = false
    override fun read(buffer: ByteBuffer): Int {
        if (stopped || offset == pcm.size) return -1
        val count = minOf(buffer.capacity(), pcm.size - offset)
        // AudioRecord returns after capture. Absolute deadlines avoid adding JNI time to pacing.
        val deadline = startedAt + (offset + count) * 1_000_000_000L / 32000
        while (!stopped) {
            val remaining = deadline - nowNanos()
            if (remaining <= 0) break
            waitNanos(remaining)
        }
        if (stopped) return -1
        buffer.put(pcm, offset, count)
        // AudioRecord.read(ByteBuffer, ...) leaves position unchanged. Match that contract.
        buffer.position(0)
        offset += count
        return count
    }
    override fun stop() { stopped = true }
    override fun release() = Unit
}
