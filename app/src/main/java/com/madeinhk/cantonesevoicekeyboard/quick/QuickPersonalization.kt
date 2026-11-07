package com.madeinhk.cantonesevoicekeyboard.quick

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Aggregate explicit choices only. No editor text is persisted. All disk work uses [io]. */
internal class QuickPersonalization(
    private val file: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "quick-history").apply { isDaemon = true } },
    private val capacity: Int = 5_000,
) {
    private data class Key(val version: Int, val code: String, val context: String, val text: String)
    private data class Stat(val count: Int, val weight: Double, val time: Long)
    private val lock = Any()
    private val entries = LinkedHashMap<Key, Stat>()
    private var secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var generation = 0L
    private var revision = 0L
    @Volatile private var loaded = file == null
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()
    @Volatile var enabled: Boolean = true
        set(value) { field = value; _changes.update { it + 1 } }

    init {
        val source = file
        if (source != null) io.execute {
            val loaded = runCatching {
                DataInputStream(source.inputStream().buffered()).use { input ->
                    require(input.readInt() == 1)
                    val key = ByteArray(32).also(input::readFully)
                    val size = input.readInt().also { require(it in 0..capacity) }
                    val rows = LinkedHashMap<Key, Stat>()
                    repeat(size) {
                        val k = Key(input.readInt(), input.readUTF(), input.readUTF(), input.readUTF())
                        val s = Stat(input.readInt(), input.readDouble(), input.readLong())
                        require(k.version in setOf(0, 3, 5) && k.code.length <= 4096 &&
                            (k.context.isEmpty() || k.context.length == 64) && k.text.length <= 4096 && isChinese(k.text) &&
                            s.count > 0 && s.weight.isFinite() && s.weight > 0 && s.time >= 0)
                        rows[k] = s
                    }
                    key to rows
                }
            }.getOrNull()
            synchronized(lock) {
                // Changes before loading finishes take precedence (including Clear).
                if (revision == 0L && loaded != null) { secret = loaded.first; entries.putAll(loaded.second) }
                this.loaded = true
                _changes.update { it + 1 }
            }
        }
    }

    fun record(version: Int, code: String?, text: String, left: String) {
        val expectedGeneration = synchronized(lock) { generation }
        recordChoice(version, code, text, left, expectedGeneration)
    }

    private fun recordChoice(version: Int, code: String?, text: String, left: String, expectedGeneration: Long) {
        if (!enabled || !isChinese(text) || text.length > 4096) return
        if (!loaded) {
            io.execute { recordChoice(version, code, text, left, expectedGeneration) }
            return
        }
        synchronized(lock) {
            if (generation != expectedGeneration || !enabled) return
            val context = contextKey(left)
            val keys = mutableListOf(Key(0, "", context, text))
            if (!code.isNullOrEmpty() && code.length <= 4096 && version in setOf(3, 5)) {
                val normalized = code.lowercase(Locale.ROOT)
                keys += Key(version, normalized, "", text)
                if (context.isNotEmpty()) keys += Key(version, normalized, context, text)
            }
            val time = now()
            keys.forEach { key ->
                val old = entries[key]
                entries[key] = Stat(((old?.count ?: 0) + 1).coerceAtMost(1_000_000), (old?.let { decayed(it, time) } ?: 0.0) + 1, time)
            }
            while (entries.size > capacity) entries.remove(entries.minBy { it.value.time }.key)
            revision++
        }
        persist()
    }

    /** Only changes Chinese slots: literal and English positions remain exactly as the model returned. */
    fun rank(version: Int, code: String, left: String, original: List<String>, limit: Int = 100): List<String> {
        if (!enabled) return original
        return synchronized(lock) {
            if (entries.isEmpty()) return@synchronized original
            val context = contextKey(left)
            val time = now()
            fun score(text: String): Pair<Double, Double> {
                fun evidence(key: Key): Double = entries[key]?.takeIf { it.count >= 2 }?.let { decayed(it, time) } ?: 0.0
                return if (code.isEmpty()) evidence(Key(0, "", context, text)) to 0.0
                else (if (context.isNotEmpty()) evidence(Key(version, code.lowercase(Locale.ROOT), context, text)) else 0.0) to
                    evidence(Key(version, code.lowercase(Locale.ROOT), "", text))
            }
            val additions = if (code.isEmpty()) entries.keys.asSequence()
                .filter { it.version == 0 && it.context == context && it.text !in original }
                .map { it.text }.distinct().toList() else emptyList()
            val chinese = (original.filter(::isChinese) + additions).distinct()
            val scored = chinese.map { it to score(it) }
            val promoted = scored.filter { it.second.first >= 0.25 || it.second.second >= 0.25 }
                .sortedWith(compareByDescending<Pair<String, Pair<Double, Double>>> { it.second.first }
                    .thenByDescending { it.second.second }).take(3).map { it.first }
            if (promoted.isEmpty()) return@synchronized original
            val ordered = promoted + original.filter(::isChinese).filterNot { it in promoted }
            val iterator = ordered.iterator()
            val result = original.map { if (isChinese(it)) iterator.next() else it }.toMutableList()
            while (iterator.hasNext()) result += iterator.next()
            if (code.isEmpty()) result.take(limit) else result
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear(); generation++; revision++; _changes.update { it + 1 } }
        persist()
    }

    private fun contextKey(left: String): String {
        val points = left.codePoints().toArray()
        if (points.isEmpty()) return ""
        val suffix = String(points, (points.size - 8).coerceAtLeast(0), points.size.coerceAtMost(8))
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            val bytes = doFinal(suffix.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            buildString(64) { bytes.forEach { byte ->
                val value = byte.toInt() and 255
                append(hex[value ushr 4]); append(hex[value and 15])
            } }
        }
    }

    private fun decayed(stat: Stat, time: Long): Double = stat.weight * 0.5.pow((time - stat.time).coerceAtLeast(0).toDouble() / HALF_LIFE)

    private fun persist() {
        val destination = file ?: return
        io.execute {
            runCatching {
                val snapshot = synchronized(lock) { Snapshot(secret.copyOf(), LinkedHashMap(entries), generation, revision) }
                destination.parentFile?.mkdirs()
                val temporary = File(destination.path + ".tmp")
                DataOutputStream(temporary.outputStream().buffered()).use { output ->
                    output.writeInt(1); output.write(snapshot.secret); output.writeInt(snapshot.rows.size)
                    snapshot.rows.forEach { (k, s) ->
                        output.writeInt(k.version); output.writeUTF(k.code); output.writeUTF(k.context); output.writeUTF(k.text)
                        output.writeInt(s.count); output.writeDouble(s.weight); output.writeLong(s.time)
                    }
                }
                synchronized(lock) {
                    // A clear or later choice invalidates an already queued snapshot.
                    if (generation == snapshot.generation && revision == snapshot.revision) {
                        check(temporary.renameTo(destination))
                    } else temporary.delete()
                }
            } // Storage failures leave the in-memory learning and ordinary candidates usable.
        }
    }

    private data class Snapshot(val secret: ByteArray, val rows: LinkedHashMap<Key, Stat>, val generation: Long, val revision: Long)
    companion object {
        private const val HALF_LIFE = 30.0 * 24 * 60 * 60 * 1000
        fun isChinese(text: String): Boolean = text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
    }
}
