package com.madeinhk.cantonesevoicekeyboard

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** A shell-launchable text field: inference and insertion run in the real IME service. */
class VoiceBenchmarkActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resultFile by lazy { File(filesDir, "voice-benchmark/result.json") }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        val editor = EditText(this).apply {
            hint = "Voice latency benchmark"
            showSoftInputOnFocus = true
        }
        setContentView(editor)
        editor.requestFocus()
        resultFile.parentFile!!.mkdirs()
        resultFile.delete()
        val requestAt = SystemClock.elapsedRealtimeNanos()
        VoiceLatencyTrace.event("benchmark_launch")
        editor.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        scope.launch {
            try {
                val nativeHash = withContext(Dispatchers.IO) {
                    val apks = listOf(applicationInfo.sourceDir) + (applicationInfo.splitSourceDirs?.toList() ?: emptyList())
                    apks.firstNotNullOf { path ->
                        ZipFile(path).use { zip ->
                            zip.getEntry("lib/arm64-v8a/libqwen_asr.so")?.let { entry ->
                                val digest = MessageDigest.getInstance("SHA-256")
                                zip.getInputStream(entry).use { input ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        digest.update(buffer, 0, count)
                                    }
                                }
                                digest.digest().joinToString("") { "%02x".format(it) }
                            }
                        }
                    }
                }
                val pcm = withContext(Dispatchers.IO) {
                    File(filesDir, "voice-benchmark/input.pcm").readBytes()
                }
                val voice = withTimeout(30_000) {
                    while (VoiceInputCoordinator.debugInstance?.debugVisible != true) delay(50)
                    checkNotNull(VoiceInputCoordinator.debugInstance)
                }
                // Let the initial editor/IME binding settle before simulating the microphone tap.
                delay(200)

                val restartDelaysStr = intent.getStringExtra("restart_delays")
                val singleRestartDelay = intent.getIntExtra("restart_delay_ms", -1)
                val restartDelays: List<Long>? = when {
                    !restartDelaysStr.isNullOrBlank() -> restartDelaysStr.split(",").mapNotNull { it.trim().toLongOrNull() }
                    singleRestartDelay >= 0 -> listOf(singleRestartDelay.toLong())
                    else -> null
                }

                val results = JSONArray()
                if (restartDelays != null) {
                    // Restart benchmark mode: test overlapping start at specified delays
                    for ((index, delayMs) in restartDelays.withIndex()) {
                        val initialTextLength = editor.length()
                        val runAt = SystemClock.elapsedRealtimeNanos()
                        val result = withTimeout(180_000) {
                            voice.replayRestartForBenchmark(pcm, pcm, delayMs)
                        }
                        result.put("trial", index + 1)
                            .put("total_time_ms", (SystemClock.elapsedRealtimeNanos() - runAt) / 1e6)
                            .put("text", editor.text.toString().drop(initialTextLength))
                        results.put(result)
                        VoiceLatencyTrace.event("benchmark_restart_run", "result" to result)
                        delay(300)
                    }
                } else {
                    // Sequential runs mode
                    repeat(intent.getIntExtra("runs", 2).coerceIn(1, 10)) { index ->
                        val initialTextLength = editor.length()
                        val runAt = SystemClock.elapsedRealtimeNanos()
                        val result = withTimeout(180_000) { voice.replayForBenchmark(pcm) }
                        result.put("run", index + 1)
                            .put("request_to_idle_ms", (SystemClock.elapsedRealtimeNanos() - runAt) / 1e6)
                            .put("launch_to_idle_ms", (SystemClock.elapsedRealtimeNanos() - requestAt) / 1e6)
                            .put("text", editor.text.toString().drop(initialTextLength))
                        results.put(result)
                        VoiceLatencyTrace.event("benchmark_run", "result" to result)
                        delay(300)
                    }
                }

                resultFile.writeText(JSONObject()
                    .put("status", "ok")
                    .put(if (restartDelays != null) "restart_trials" else "runs", results)
                    .put("package", packageName)
                    .put("packaged_native_sha256", nativeHash)
                    .put("process_start_to_activity_ms", requestAt / 1e6 - android.os.Process.getStartElapsedRealtime())
                    .put("store_debug_audio", DebugAudioSettings.isStoreDebugAudioEnabled(this@VoiceBenchmarkActivity))
                    .toString(2))
            } catch (error: Throwable) {
                VoiceLatencyTrace.event("benchmark_error", "error" to error.toString())
                resultFile.writeText(JSONObject().put("status", "error").put("error", error.toString()).toString())
            }
        }
    }
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
