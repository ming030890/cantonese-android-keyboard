package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

internal object VoiceClock {
    fun nanoTime(): Long {
        return try {
            SystemClock.elapsedRealtimeNanos()
        } catch (_: Throwable) {
            System.nanoTime()
        }
    }

    fun elapsedRealtime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }
}

/** Monotonic wall times; callback receipt and editor insertion are separate milestones. */
internal class VoiceLatencyTrace(private val replay: Boolean) {
    // Replay explicitly opts host tests into metric collection; log output still
    // requires the debug APK and native adb-property gate.
    private val collect = BuildConfig.DEBUG && (replay || enabled)
    private val start = if (collect) VoiceClock.nanoTime() else 0L
    private val milestones = linkedMapOf<String, Double>()
    private val milestoneUptimes = linkedMapOf<String, Double>()
    private var bytes = 0L
    private var rejected = 0
    private var maxPushMs = 0.0
    private var maxDispatchMs = 0.0
    private var updates = 0
    private var batteryPct = -1f
    private var batteryTempC = -1.0
    private var thermalStatus = -1

    companion object {
        @Volatile private var enabled = false

        internal fun configure(requested: Boolean) {
            enabled = BuildConfig.DEBUG && requested
        }

        fun event(name: String, vararg fields: Pair<String, Any>) {
            if (!enabled) return
            val data = JSONObject().put("event", name)
                .put("uptime_ms", VoiceClock.nanoTime() / 1e6)
            fields.forEach { (key, value) -> data.put(key, value) }
            try {
                Log.i("VoiceLatency", data.toString())
            } catch (_: Throwable) {
                // Host JVM test
            }
        }
    }

    @Synchronized fun mark(name: String, vararg fields: Pair<String, Any>) {
        if (!collect) return
        val now = VoiceClock.nanoTime()
        val elapsed = (now - start) / 1e6
        val uptime = now / 1e6
        milestones.putIfAbsent(name, elapsed)
        milestoneUptimes.putIfAbsent(name, uptime)
        event(name, "session_ms" to elapsed, *fields)
    }

    @Synchronized fun audio(count: Int, pushAt: Long, accepted: Int) {
        if (!collect) return
        if (bytes == 0L) mark("first_audio")
        bytes += count
        if (accepted != 1) rejected++
        maxPushMs = maxOf(maxPushMs, (VoiceClock.nanoTime() - pushAt) / 1e6)
    }

    @Synchronized fun editor(text: String, callbackAt: Long) {
        if (!collect) return
        val now = VoiceClock.nanoTime()
        maxDispatchMs = maxOf(maxDispatchMs, (now - callbackAt) / 1e6)
        updates++
        val elapsed = (now - start) / 1e6
        milestones["last_editor_update"] = elapsed
        milestoneUptimes["last_editor_update"] = now / 1e6
        if (text.isNotEmpty()) mark("editor_text", "chars" to text.length)
    }

    @Synchronized fun recordContext(context: Context) {
        if (!collect) return
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryIntent = context.registerReceiver(null, filter)
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (level >= 0 && scale > 0) batteryPct = (level * 100f) / scale
                if (temp > 0) batteryTempC = temp / 10.0
            }
        } catch (_: Throwable) {}
        try {
            val powerManager = context.getSystemService(PowerManager::class.java)
            if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalStatus = powerManager.currentThermalStatus
            }
        } catch (_: Throwable) {}
    }

    @Synchronized fun summary(): JSONObject {
        if (!collect) return JSONObject()
        val obj = JSONObject(milestones.toMap())
            .put("replay", replay)
            .put("audio_ms", bytes / 32.0)
            .put("rejected_chunks", rejected)
            .put("max_push_ms", maxPushMs)
            .put("max_main_dispatch_ms", maxDispatchMs)
            .put("editor_updates", updates)
            .put("start_uptime_ms", start / 1e6)

        val uptimesObj = JSONObject(milestoneUptimes.toMap())
        obj.put("milestone_uptimes_ms", uptimesObj)

        if (batteryPct >= 0) obj.put("battery_pct", batteryPct.toDouble())
        if (batteryTempC >= 0) obj.put("battery_temp_c", batteryTempC)
        if (thermalStatus >= 0) obj.put("thermal_status", thermalStatus)

        // Directly measured stop-wait components
        val stopReq = milestones["stop_requested"]
        if (stopReq != null) {
            val idle = milestones["idle"] ?: 0.0
            obj.put("stop_to_idle_ms", idle - stopReq)
            milestones["recorder_stopped"]?.let { obj.put("stop_to_recorder_stopped_ms", it - stopReq) }
            milestones["engine_lock_acquired"]?.let { obj.put("stop_to_engine_lock_ms", it - stopReq) }
            milestones["native_stop_return"]?.let { obj.put("stop_to_native_stop_return_ms", it - stopReq) }
            milestones["last_editor_update"]?.let { obj.put("stop_to_last_text_ms", it - stopReq) }
        }

        // Restart / start milestones
        val capStart = milestones["capture_start"] ?: 0.0
        milestones["engine_available"]?.let { obj.put("capture_to_engine_available_ms", it - capStart) }
        milestones["session_ready"]?.let { obj.put("capture_to_session_ready_ms", it - capStart) }
        milestones["editor_text"]?.let { obj.put("capture_to_first_text_ms", it - capStart) }

        return obj
    }
}
