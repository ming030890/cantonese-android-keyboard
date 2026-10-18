package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.english.NativeEnglishEngine
import com.madeinhk.cantonesevoicekeyboard.english.EnglishLiteralLexicon
import com.madeinhk.cantonesevoicekeyboard.english.InternalTypingProfile
import com.madeinhk.cantonesevoicekeyboard.english.TypingLearning
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Stage timings against the installed benchmark app's actual assets and native library. */
@RunWith(AndroidJUnit4::class)
class QuickLatencyBenchmarkTest {
    @Test fun stages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rows = JSONArray()
        fun <T> stage(name: String, block: () -> T): T {
            val start = SystemClock.elapsedRealtimeNanos()
            val result = block()
            val row = JSONObject().put("stage", name).put("ms", (SystemClock.elapsedRealtimeNanos()-start)/1e6)
            rows.put(row); Log.i("QuickLatencyBenchmark", row.toString())
            return result
        }
        val assets = JSONObject()
        for (path in listOf("language_model/model.cng") + if (BuildConfig.INTERNAL_TYPING_TRIAL) listOf("typing/model.cng") else emptyList()) {
            val bytes = stage("read:$path") { context.assets.open(path).use { it.readBytes() } }
            assets.put(path, JSONObject().put("bytes",bytes.size).put("sha256",
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
        }
        val quick = stage("quick_load") { NativeQuickEngine.load(context) }
        try {
            repeat(4) { run ->
                stage("predictions_empty:$run") { quick.predictions("", "", 100) }
                for (code in listOf("o", "of", "a", "ab")) {
                    val candidates = stage("quick_$code:$run") { quick.candidates(3,code,"","") }
                    Log.i("QuickLatencyBenchmark", "$code count=${candidates.size} top=${candidates.take(3)}")
                }
            }
            val english = stage("english_load") {
                NativeEnglishEngine.fromAssets({ path -> stage("english_read:$path") { context.assets.open(path).use { it.readBytes() } } }, EnglishLiteralLexicon.assetPath(context),quick::englishContextHandle)
            }
            try {
                repeat(4) { run ->
                    for(code in listOf("o","of")) {
                        val prediction = stage("english_$code:$run") { english.predict(code,floatArrayOf(),floatArrayOf(),"","") }
                        val chinese = quick.candidates(3,code,"","").take(8).map { it.text }
                        val pool = (chinese + code + prediction.candidates.map { it.word }).distinct()
                        stage("mixed_rank_$code:$run") { english.rankCandidates("","",pool) }
                    }
                }
            } finally { (english as? AutoCloseable)?.close() }
            if (BuildConfig.INTERNAL_TYPING_TRIAL) {
                val internal = stage("internal_english_load") {
                    InternalTypingProfile.load(context,quick::englishContextHandle,TypingLearning.None)
                }
                try {
                    stage("internal_english_of") { internal.predict("of",floatArrayOf(),floatArrayOf(),"","") }
                    stage("internal_mixed_rank_of") { internal.rankCandidates("","",listOf("係","你","無","of")) }
                } finally { (internal as? AutoCloseable)?.close() }
            }
        } finally { quick.close() }
        val validity = stage("validity_read") { context.assets.open(EnglishLiteralLexicon.assetPath(context)).bufferedReader().use { it.readText() } }
        val parsed = stage("validity_parse_actual") { EnglishLiteralLexicon.fromWordsReader(validity.reader()) }
        val reference = stage("validity_parse_legacy") {
            val words = validity.reader().buffered().readLines()
            require(words.isNotEmpty() && words.all { it.matches(Regex("[A-Za-z]+(?:'[A-Za-z]+)*")) })
            val (acronyms, ordinary) = words.partition { it.length > 1 && it == it.uppercase(java.util.Locale.ROOT) }
            ordinary.map { it.lowercase(java.util.Locale.ROOT) }.toSet() to acronyms.toSet()
        }
        for (word in validity.lineSequence().filter { it.isNotEmpty() }) {
            for (variant in listOf(word,word.lowercase(java.util.Locale.ROOT),word.uppercase(java.util.Locale.ROOT))) {
                assertEquals(variant,variant.lowercase(java.util.Locale.ROOT) in reference.first || variant in reference.second,parsed.contains(variant))
            }
        }
        assertTrue("ASR asset packs must remain ready",ModelAssetPack.isModelAvailable(context))
        File(context.filesDir,"quick-benchmark.json").writeText(JSONObject().put("assets",assets).put("timings",rows).toString(2))
    }
}
