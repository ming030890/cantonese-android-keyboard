package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine
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
        for (path in listOf("language_model/model.cng")) {
            val bytes = stage("read:$path") { context.assets.open(path).use { it.readBytes() } }
            assets.put(path, JSONObject().put("bytes",bytes.size).put("sha256",
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
        }
        val quick = stage("quick_load") { NativeQuickEngine.load(context) }
        try {
            repeat(4) { run ->
                stage("predictions_empty:$run") { quick.predictions("", "", 100) }
                for (code in listOf("o", "of", "df", "a", "ab")) {
                    stage("quick_preview_$code:$run") { quick.previewCandidates(3,code,"","") }
                    val candidates = stage("quick_$code:$run") { quick.candidates(3,code,"","") }
                    Log.i("QuickLatencyBenchmark", "$code count=${candidates.size} top=${candidates.take(3)}")
                }
            }
            val english = stage("english_load") {
                SharedLmEnglishEngine(quick::englishContextHandle)
            }
            repeat(4) { run ->
                for(code in listOf("o","of")) {
                        val prediction = stage("english_$code:$run") { english.predict(code,floatArrayOf(),floatArrayOf(),"","") }
                        val chinese = quick.candidates(3,code,"","").take(8).map { it.text }
                        val pool = (chinese + code + prediction.candidates.map { it.word }).distinct()
                        stage("mixed_rank_$code:$run") { english.rankCandidates("","",pool) }
                }
            }
        } finally { quick.close() }
        assertTrue("ASR asset packs must remain ready",ModelAssetPack.isModelAvailable(context))
        File(context.filesDir,"quick-benchmark.json").writeText(JSONObject().put("assets",assets).put("timings",rows).toString(2))
    }
}
