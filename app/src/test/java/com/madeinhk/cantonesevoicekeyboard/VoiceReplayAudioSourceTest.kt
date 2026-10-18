package com.madeinhk.cantonesevoicekeyboard

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class VoiceReplayAudioSourceTest {
    @Test fun replayMatchesAudioRecordBufferContractAndCaptureClock() {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val pcm = ByteArray(4098) { (it % 127).toByte() }
        val source = ReplayVoiceAudioSource(pcm, { clock }, { waits.add(it); clock += it })
        val buffer = ByteBuffer.allocateDirect(2048)
        val received = ArrayList<Byte>()
        repeat(3) {
            buffer.clear()
            val count = source.read(buffer)
            // The production RMS reader duplicates this buffer and immediately reads shorts.
            assertEquals(0, buffer.position())
            repeat(count) { received.add(buffer.get()) }
            clock += 10_000_000 // JNI/recording work must not accumulate into capture pacing.
        }
        assertArrayEquals(pcm, received.toByteArray())
        assertEquals(64_000_000L, waits[0])
        assertEquals(54_000_000L, waits[1])
        assertEquals(-1, source.read(buffer))
    }
    @Test fun stoppedReplayDoesNotFeedMoreAudio() {
        val source = ReplayVoiceAudioSource(ByteArray(2048), { 0L }, { error("must not wait") })
        source.stop()
        assertEquals(-1, source.read(ByteBuffer.allocateDirect(2048)))
    }
}
