package com.madeinhk.cantonesevoicekeyboard.quick

import android.content.Context
import android.util.Log
import org.json.JSONObject

internal data class QuickLanguageEvidence(val englishProbability: Float, val chineseProbability: Float,
    val englishSupport: Float, val chineseSupport: Float) {
    val shouldCommitChinese: Boolean get() = chineseProbability >= 0.95f && chineseSupport >= 0.02f
}

internal interface QuickCandidateEngine : AutoCloseable {
    fun candidates(version: Int, code: String, left: String, right: String): List<QuickCandidate>
    fun predictions(left: String, right: String, limit: Int): List<QuickCandidate> = emptyList()
    fun languageEvidence(code: String, left: String, right: String, chinese: String): QuickLanguageEvidence? = null
    override fun close() {}
}

/** Dictionary lookup, tokenization and ranking belong to the standalone Rust LM crate. */
internal class NativeQuickEngine private constructor(private var handle: Long) : QuickCandidateEngine {
    @Synchronized fun englishContextHandle(): Long = handle
    companion object {
        init { System.loadLibrary("cantonese_engine") }

        fun load(context: Context): NativeQuickEngine {
            val assets = context.applicationContext.assets
            fun read(path: String) = assets.open(path).use { it.readBytes() }
            val neural = runCatching { read("language_model/model.cng") }.getOrElse {
                Log.w("NativeQuickEngine", "Neural LM unavailable; using count fallback", it)
                ByteArray(0)
            }
            val handle = nativeCreate(read("quick/quick3.bin"), read("quick/quick5.bin"), neural,
                runCatching { read("handwriting/lm_csr.bin") }.getOrElse { ByteArray(0) },
                read("handwriting/labels.txt"))
            check(handle != 0L) { "Could not create Quick engine" }
            return NativeQuickEngine(handle)
        }

        @JvmStatic private external fun nativeCreate(three: ByteArray, five: ByteArray,
            neural: ByteArray, counts: ByteArray, labels: ByteArray): Long
        @JvmStatic private external fun nativeCandidates(handle: Long, version: Int,
            code: String, left: String, right: String): Array<String>?
        @JvmStatic private external fun nativePredictions(handle: Long, left: String, right: String, limit: Int): Array<String>?
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeLanguageEvidence(handle: Long, code: String, left: String,
            right: String, chinese: String): String?
    }

    @Synchronized override fun candidates(version: Int, code: String, left: String, right: String): List<QuickCandidate> {
        check(handle != 0L) { "Quick engine closed" }
        return nativeCandidates(handle, version, code, left, right).orEmpty().mapIndexed { i, text -> QuickCandidate(text, i) }
    }

    @Synchronized override fun predictions(left: String, right: String, limit: Int): List<QuickCandidate> {
        check(handle != 0L) { "Quick engine closed" }
        return nativePredictions(handle, left, right, limit).orEmpty().mapIndexed { i, text -> QuickCandidate(text, i) }
    }

    @Synchronized override fun languageEvidence(code: String, left: String, right: String, chinese: String): QuickLanguageEvidence? {
        check(handle != 0L) { "Quick engine closed" }
        val result = checkNotNull(nativeLanguageEvidence(handle, code, left, right, chinese))
        if (result == "null") return null
        val json = JSONObject(result)
        return QuickLanguageEvidence(json.getDouble("englishProbability").toFloat(),
            json.getDouble("chineseProbability").toFloat(), json.getDouble("englishSupport").toFloat(),
            json.getDouble("chineseSupport").toFloat())
    }

    @Synchronized override fun close() {
        val old = handle
        handle = 0
        if (old != 0L) nativeDestroy(old)
    }
}
