package com.madeinhk.cantonesevoicekeyboard

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VoiceLatencyTraceTest {
    @After fun reset() { VoiceLatencyTrace.configure(false) }

    @Test fun ordinarySessionDoesNotCollectWithoutOptIn() {
        VoiceLatencyTrace.configure(false)
        val trace = VoiceLatencyTrace(false)
        trace.mark("capture_start")
        trace.audio(2048, VoiceClock.nanoTime(), 1)
        trace.editor("test", VoiceClock.nanoTime())
        assertEquals(0, trace.summary().length())
    }

    @Test fun optedInDebugSessionCollectsMetrics() {
        VoiceLatencyTrace.configure(true)
        val trace = VoiceLatencyTrace(false)
        trace.mark("capture_start")
        trace.audio(2048, VoiceClock.nanoTime(), 1)
        assertEquals(64.0, trace.summary().getDouble("audio_ms"), 0.0)
        assertTrue(trace.summary().has("capture_start"))
    }
}
