package com.madeinhk.cantonesevoicekeyboard.english

import android.content.Context
import com.madeinhk.cantonesevoicekeyboard.BuildConfig
import com.madeinhk.cantonesevoicekeyboard.ModelAssetPack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Local JSONL episode queue and background Wi-Fi LAN uploader to the Mac mini
 * iterative training daemon for Internal Testing Track builds.
 */
internal object AutocorrectTelemetryStore {
    private const val DIR_NAME = "autocorrect_telemetry"
    private const val FILE_NAME = "pending.jsonl"
    private const val MAX_PENDING_LINES = 2000
    private const val SYNC_COOLDOWN_MS = 15_000L

    internal val DEFAULT_ENDPOINTS = listOf(
        "http://192.168.1.243:8765/api/v1/autocorrect-episodes",
        "http://Tonys-Mac-mini-2.local:8765/api/v1/autocorrect-episodes",
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AutocorrectTelemetry").apply { isDaemon = true }
    }
    private val lastSyncAttemptMs = AtomicLong(0L)
    private val purgedForPublicRelease = AtomicBoolean(false)

    private fun purgeLegacyDataIfDisabled(context: Context?) {
        if (context == null || !purgedForPublicRelease.compareAndSet(false, true)) return
        val appContext = context.applicationContext ?: context
        executor.execute {
            runCatching {
                File(appContext.filesDir, DIR_NAME).takeIf { it.exists() }?.deleteRecursively()
            }
        }
    }

    fun record(context: Context?, episode: AutocorrectEpisode) {
        if (!BuildConfig.INTERNAL_TRACK) {
            purgeLegacyDataIfDisabled(context)
            return
        }
        if (context == null) return
        val appContext = context.applicationContext ?: context
        val line = episode.toJson().toString()
        executor.execute {
            runCatching {
                val file = pendingFile(appContext)
                appendLineBounded(file, line, MAX_PENDING_LINES)
                maybeSyncOnExecutor(appContext, file)
            }
        }
    }

    fun flushIfOnWifi(context: Context?) {
        if (!BuildConfig.INTERNAL_TRACK) {
            purgeLegacyDataIfDisabled(context)
            return
        }
        if (context == null) return
        val appContext = context.applicationContext ?: context
        executor.execute {
            runCatching {
                val file = pendingFile(appContext)
                maybeSyncOnExecutor(appContext, file, forceAttempt = true)
            }
        }
    }

    private fun pendingFile(context: Context): File =
        File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)

    internal fun appendLineBounded(file: File, line: String, maxLines: Int = MAX_PENDING_LINES) {
        file.parentFile?.mkdirs()
        val existing = if (file.exists()) {
            file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val combined = (existing + line).takeLast(maxLines)
        file.writeText(combined.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
    }

    private fun maybeSyncOnExecutor(context: Context, file: File, forceAttempt: Boolean = false) {
        if (!file.exists() || file.length() == 0L) return
        val now = System.currentTimeMillis()
        val last = lastSyncAttemptMs.get()
        if (!forceAttempt && now - last < SYNC_COOLDOWN_MS) return
        if (!ModelAssetPack.hasUnmeteredNetwork(context)) return
        lastSyncAttemptMs.set(now)
        syncFileToEndpoints(file, DEFAULT_ENDPOINTS)
    }

    internal fun syncFileToEndpoints(
        file: File,
        endpoints: List<String>,
        connectTimeoutMs: Int = 1500,
        readTimeoutMs: Int = 3000,
    ): Boolean {
        if (!file.exists()) return false
        val lines = file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
        if (lines.isEmpty()) return false

        val episodesArray = JSONArray()
        for (line in lines) {
            runCatching { JSONObject(line) }.getOrNull()?.let { episodesArray.put(it) }
        }
        if (episodesArray.length() == 0) {
            file.delete()
            return false
        }

        val payload = JSONObject().apply {
            put("schema", 1)
            put("version_code", BuildConfig.VERSION_CODE)
            put("version_name", BuildConfig.VERSION_NAME)
            put("episodes", episodesArray)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        for (endpoint in endpoints) {
            val uploaded = runCatching {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(payload) }
                    conn.responseCode in 200..299
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)

            if (uploaded) {
                file.delete()
                return true
            }
        }
        return false
    }
}
