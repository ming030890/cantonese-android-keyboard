package com.example.cantonesevoiceinput.benchmark

import android.content.Context
import android.os.SystemClock
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cantonesevoiceinput.ModelAssetInstaller
import com.example.cantonesevoiceinput.inference.NativeQwenAsrEngine
import com.example.cantonesevoiceinput.inference.NativeTranscriptionTrace
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsrInferenceBenchmarkTest {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var appContext: Context
    private lateinit var testContext: Context
    private lateinit var modelDir: java.io.File
    private lateinit var samples: FloatArray
    private lateinit var engine: NativeQwenAsrEngine

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        testContext = InstrumentationRegistry.getInstrumentation().context
        assumeTrue(
            "Missing benchmark asset; run ffmpeg export for androidTest asset first.",
            Pcm16AssetLoader.isPresent(testContext, AUDIO_ASSET_NAME)
        )

        modelDir = ModelAssetInstaller.ensureInstalled(appContext)
        samples = Pcm16AssetLoader.loadMono16kPcmFloatArray(testContext, AUDIO_ASSET_NAME)
        engine = newEngine(modelDir)

        val warmup = engine.transcribeStreamPcm16kMonoProfiled(samples, forceLanguage = true)
        assertExpectedTranscript(warmup.text)
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) {
            engine.close()
        }
    }

    @Test
    fun benchmarkWarmStreamTranscribe() = benchmarkRule.measureRepeated {
        val trace = engine.transcribeStreamPcm16kMonoProfiled(samples, forceLanguage = true)
        assertExpectedTranscript(trace.text)
    }

    @Test
    fun reportWarmStreamBreakdown() {
        val runs = 1
        val wallNs = LongArray(runs)
        val traces = ArrayList<NativeTranscriptionTrace>(runs)

        repeat(runs) { index ->
            val started = SystemClock.elapsedRealtimeNanos()
            val trace = engine.transcribeStreamPcm16kMonoProfiled(samples, forceLanguage = true)
            wallNs[index] = SystemClock.elapsedRealtimeNanos() - started
            assertExpectedTranscript(trace.text)
            traces += trace
        }

        printBreakdown("warm_stream", traces, wallNs)
    }

    @Test
    fun reportWarmSetupBreakdown() {
        val runs = 3
        val installNs = LongArray(runs)
        val createNs = LongArray(runs)

        repeat(runs) { index ->
            installNs[index] = measureNs {
                ModelAssetInstaller.ensureInstalled(appContext)
            }
            createNs[index] = measureNs {
                newEngine(modelDir).use { probe ->
                    val trace = probe.transcribeStreamPcm16kMonoProfiled(samples, forceLanguage = true)
                    assertExpectedTranscript(trace.text)
                }
            }
        }

        println(
            "ASR_SETUP " +
                "runs=$runs " +
                "warmInstallMs=${formatMs(installNs.average().toLong())} " +
                "engineCreateAndWarmupMs=${formatMs(createNs.average().toLong())}"
        )
    }

    private fun printBreakdown(label: String, traces: List<NativeTranscriptionTrace>, wallNs: LongArray) {
        val avgWallNs = wallNs.average().toLong()
        val avgJniTotalNs = traces.map { it.totalNs }.average().toLong()
        val avgCopyNs = traces.map { it.inputCopyNs }.average().toLong()
        val avgNativeNs = traces.map { it.nativeInferenceNs }.average().toLong()
        val avgJniOverheadNs = traces.map { it.jniOverheadNs }.average().toLong()
        val avgKotlinNs = (avgWallNs - avgJniTotalNs).coerceAtLeast(0L)
        val audioSec = samples.size / SAMPLE_RATE_HZ.toDouble()
        val realtimeFactor = if (avgWallNs > 0) {
            audioSec / (avgWallNs / 1_000_000_000.0)
        } else {
            0.0
        }

        assertTrue("Expected non-empty traces", traces.isNotEmpty())
        println(
            "ASR_BREAKDOWN " +
                "label=$label " +
                "runs=${traces.size} " +
                "audioSec=${"%.3f".format(audioSec)} " +
                "wallMs=${formatMs(avgWallNs)} " +
                "kotlinOutsideJniMs=${formatMs(avgKotlinNs)} " +
                "jniCopyMs=${formatMs(avgCopyNs)} " +
                "nativeInferenceMs=${formatMs(avgNativeNs)} " +
                "jniOverheadMs=${formatMs(avgJniOverheadNs)} " +
                "realtimeX=${"%.3f".format(realtimeFactor)}"
        )
    }

    private fun newEngine(modelDir: java.io.File): NativeQwenAsrEngine {
        return NativeQwenAsrEngine(
            modelDir = modelDir,
            language = "Cantonese",
            threads = 0,
            segmentSec = 30f,
            searchSec = 3f,
            pastTextConditioning = true,
            streamChunkSec = 2f,
            streamRollbackTokens = 5,
            streamUnfixedChunks = 2,
            streamMaxNewTokens = 32
        )
    }

    private fun measureNs(block: () -> Unit): Long {
        val started = SystemClock.elapsedRealtimeNanos()
        block()
        return SystemClock.elapsedRealtimeNanos() - started
    }

    private fun formatMs(durationNs: Long): String {
        return "%.3f".format(durationNs / 1_000_000.0)
    }

    private fun assertExpectedTranscript(text: String) {
        val normalized = text.trim()
        assertTrue("Transcript should not be blank", normalized.isNotEmpty())
        assertTrue(
            "Unexpected transcript: $normalized",
            normalized.endsWith(EXPECTED_SUFFIX)
        )
    }

    companion object {
        private const val AUDIO_ASSET_NAME = "benchmark/PTT-20241208-WA0011-s16le.pcm"
        private const val EXPECTED_SUFFIX = "，你好吗？已经坐低咗啦。"
        private const val SAMPLE_RATE_HZ = 16_000
    }
}
