package com.madeinhk.cantonesevoicekeyboard.english

import com.madeinhk.cantonesevoicekeyboard.quick.QuickLanguageEvidence
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

internal interface SuggestionRequest : AutoCloseable {
    fun cancel()
    fun ensureActive()
    override fun close() = cancel()
}

internal data class JoinedSuggestions(
    val chinese: List<String> = emptyList(),
    val display: List<String> = emptyList(),
    val english: EnglishPrediction = EnglishPrediction(),
    val evidence: QuickLanguageEvidence? = null,
    val codeLength: Int = 0,
)

internal interface UnifiedSuggestionEngine {
    val supportsPreview: Boolean get() = false
    fun preview(request: SuggestionRequest, version: Int, code: String, left: String, right: String,
                englishEnabled: Boolean): JoinedSuggestions = error("Preview unavailable")
    val supportsIndependentBoundary: Boolean get() = false
    fun newRequest(): SuggestionRequest
    fun boundary(request: SuggestionRequest, code: String, left: String): EnglishPrediction =
        error("Independent boundary inference unavailable")
    fun compose(request: SuggestionRequest, version: Int, code: String, left: String, right: String,
                englishEnabled: Boolean): JoinedSuggestions
}

/** Cancellation uses a separate native registry; it never takes an inference lock. */
internal class NativeSuggestionRequest : SuggestionRequest {
    val id = nativeCreate()
    private val cancelled = AtomicBoolean(false)
    override fun cancel() { if (cancelled.compareAndSet(false, true)) nativeCancel(id) }
    override fun ensureActive() { if (cancelled.get()) throw CancellationException("Superseded suggestions") }
    companion object {
        init { System.loadLibrary("cantonese_engine") }
        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeCancel(id: Long)
    }
}

internal class NativeSharedSuggestions(private val contextHandle: () -> Long) : UnifiedSuggestionEngine {
    override val supportsPreview = true
    override fun preview(request: SuggestionRequest, version: Int, code: String, left: String, right: String,
                         englishEnabled: Boolean): JoinedSuggestions {
        request.ensureActive()
        val json = try {
            nativePreview(contextHandle(), (request as NativeSuggestionRequest).id, version, code, left, right, englishEnabled)
        } catch (error: Exception) { request.ensureActive(); throw error }
        request.ensureActive()
        return parseJoinedSuggestions(JSONObject(checkNotNull(json)), code)
    }
    override val supportsIndependentBoundary = true
    override fun newRequest() = NativeSuggestionRequest()
    override fun boundary(request: SuggestionRequest, code: String, left: String): EnglishPrediction {
        request.ensureActive()
        val json = try {
            nativeBoundary(contextHandle(), (request as NativeSuggestionRequest).id, code, left)
        } catch (error: Exception) { request.ensureActive(); throw error }
        request.ensureActive()
        return parseSharedPrediction(JSONObject(checkNotNull(json)), code)
    }
    override fun compose(request: SuggestionRequest, version: Int, code: String, left: String, right: String,
                         englishEnabled: Boolean): JoinedSuggestions {
        request.ensureActive()
        val json = try {
            nativeCompose(contextHandle(), (request as NativeSuggestionRequest).id, version, code, left, right, englishEnabled)
        } catch (error: Exception) { request.ensureActive(); throw error }
        request.ensureActive()
        return parseJoinedSuggestions(JSONObject(checkNotNull(json)), code)
    }
    companion object {
        @JvmStatic private external fun nativePreview(handle: Long, request: Long, version: Int, code: String,
                                                      left: String, right: String, englishEnabled: Boolean): String?
        @JvmStatic private external fun nativeBoundary(handle: Long, request: Long, code: String, left: String): String?
        @JvmStatic private external fun nativeCompose(handle: Long, request: Long, version: Int, code: String,
                                                      left: String, right: String, englishEnabled: Boolean): String?
    }
}

private fun parseJoinedSuggestions(result: JSONObject, code: String): JoinedSuggestions {
        fun strings(name: String) = result.getJSONArray(name).let { rows -> (0 until rows.length()).map(rows::getString) }
        val prediction = parseSharedPrediction(result.getJSONObject("english"), code)
        val evidence = if (result.isNull("evidence")) null else result.getJSONObject("evidence").let {
            QuickLanguageEvidence(it.getDouble("englishProbability").toFloat(), it.getDouble("chineseProbability").toFloat(),
                it.getDouble("englishSupport").toFloat(), it.getDouble("chineseSupport").toFloat())
        }
        return JoinedSuggestions(strings("chinese"), strings("display"), prediction, evidence, result.getInt("codeLength"))
}

internal fun parseSharedPrediction(result: JSONObject, typed: String): EnglishPrediction {
    val rows = result.getJSONArray("candidates")
    val candidates = (0 until rows.length()).map { i ->
        val row = rows.getJSONObject(i)
        val edits = row.getInt("edits")
        EnglishCandidate(row.getString("word"), row.getDouble("score"), 2.0 * edits, edits, row.getInt("completedChars"))
    }.filter { !it.word.equals(typed, ignoreCase = true) }.distinctBy { it.word.lowercase(java.util.Locale.ROOT) }
    return EnglishPrediction(candidates = candidates,
        correction = if (result.isNull("correction")) null else result.getString("correction").takeIf { fix -> candidates.any { it.word == fix } },
        known = result.getBoolean("known"), replaceBefore = typed.length, reason = "shared_lm_spelling",
        plausiblePrefix = candidates.any { it.completedChars > 0 })
}
