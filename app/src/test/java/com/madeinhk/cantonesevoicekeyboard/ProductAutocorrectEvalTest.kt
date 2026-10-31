package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardKeyKind
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeNotNull
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** Opt-in measurement, not an assertion that every labelled intent is satisfied. */
class ProductAutocorrectEvalTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun replaySessionsThroughProductionController() = runTest(timeout = 5.minutes) {
        val library = System.getProperty("english.native.library")
        val input = System.getProperty("product.eval.sessions")
        val destination = System.getProperty("product.eval.report")
        assumeNotNull("Product evaluation requires JNI, session and report paths", library, input, destination)
        val nativeLibrary = requireNotNull(library)
        val reportPath = requireNotNull(destination)
        System.load(nativeLibrary)
        val source = File(requireNotNull(input))
        val sessions = source.readLines().filter(String::isNotBlank).map(::JSONObject)
        require(sessions.map { it.getString("id") }.toSet().size == sessions.size) { "Duplicate session IDs" }
        val results = JSONArray()
        // Keep immutable model weights alive while each scenario receives a fresh
        // native inference handle, controller, editor and coroutine scope.
        val assetBytes = mutableMapOf<String, ByteArray>()
        fun readAsset(path: String) = assetBytes.getOrPut(path) { File("src/main/assets/$path").readBytes() }
        val weightAnchor = NativeQuickEngine.fromAssets(read = ::readAsset)
        try {
        for ((sessionIndex, session) in sessions.withIndex()) {
            require(session.getString("schema") == "keyboard-product-session-v1")
            val layout = session.getString("layout")
            require(layout in setOf("english", "quick3", "quick5"))
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val quick = NativeQuickEngine.fromAssets(read = ::readAsset)
            val english = SharedLmEnglishEngine(quick::englishContextHandle)
            val steps = JSONArray()
            try {
                KeyboardScenario(initialText = session.getString("initial_text"), clipboardText = null,
                    initialMode = if (layout == "english") KeyboardInputMode.AlphaNum else KeyboardInputMode.Quick,
                    quickEngine = quick, englishEngine = english, scope = scope,
                    lookupDispatcher = dispatcher, englishDispatcher = dispatcher,
                    autoCapitalizationEnabled = session.optBoolean("auto_capitalization", false)).use { keyboard ->
                    keyboard.quickVersion = if (layout == "quick5") 5 else 3
                    keyboard.start()
                    val events = session.getJSONArray("events")
                    for (index in 0 until events.length()) {
                        val event = events.getJSONObject(index)
                        val before = keyboard.editor.text
                        val composing = keyboard.editor.composing
                        when (event.getString("kind")) {
                            "type" -> keyboard.type(event.getString("text"))
                            "space" -> keyboard.press(KeyboardKeyKind.Space)
                            "enter" -> keyboard.press(KeyboardKeyKind.Enter)
                            "submit" -> {
                                keyboard.editor.actionEnter = true
                                keyboard.press(KeyboardKeyKind.Enter)
                            }
                            "delete" -> keyboard.press(KeyboardKeyKind.Delete)
                            "choose" -> keyboard.choose(event.getString("text"))
                            "cursor" -> keyboard.moveCursor(event.getInt("position"), event.optInt("end", event.getInt("position")))
                            else -> error("Unsupported recorded event: ${event.getString("kind")}")
                        }
                        if (event.optBoolean("settle", true)) testScheduler.runCurrent()
                        val snapshot = keyboard.snapshot()
                        val candidates = if (layout == "english") snapshot.englishCandidates else snapshot.quickCandidates
                        steps.put(JSONObject().put("kind", event.getString("kind")).put("before", before)
                            .put("composing_before", composing).put("after", keyboard.editor.text)
                            .put("composing_after", keyboard.editor.composing)
                            .put("candidates", JSONArray(candidates.map { it.text })))
                    }
                    testScheduler.runCurrent()
                    val actual = keyboard.editor.text + keyboard.editor.right
                    val labelled = session.getString("intent_status") != "unreviewed"
                    val expected = if (labelled) session.getString("expected_text") else null
                    val composition = keyboard.editor.composing
                    results.put(JSONObject().put("id", session.getString("id")).put("group", session.getString("group"))
                        .put("origin", session.getString("origin")).put("layout", layout)
                        .put("language", session.getString("language")).put("intent", session.getString("intent"))
                        .put("tags", session.getJSONArray("tags")).put("expected", expected ?: JSONObject.NULL).put("actual", actual)
                        .put("composing", composition).put("labelled", labelled)
                        .put("submit_actions", keyboard.editor.actions)
                        .put("submitted_texts", JSONArray(keyboard.editor.submittedTexts))
                        .put("exact_success", if (labelled) actual == expected && composition == session.getString("expected_composing") else JSONObject.NULL)
                        .put("casefold_success", if (labelled) actual.equals(expected, ignoreCase = true) && composition == session.getString("expected_composing") else JSONObject.NULL)
                        .put("steps", steps))
                }
            } finally {
                scope.cancel()
                testScheduler.runCurrent()
                quick.close()
            }
            if ((sessionIndex + 1) % 16 == 0 || sessionIndex == sessions.lastIndex) {
                File(reportPath + ".progress.json").writeText(JSONObject()
                    .put("completed", sessionIndex + 1).put("total", sessions.size)
                    .put("last_id", session.getString("id")).toString())
            }
        }
        } finally { weightAnchor.close() }
        fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val report = JSONObject().put("schema", "keyboard-product-controller-report-v1")
            .put("sessions_sha256", sha(source)).put("native_library_sha256", sha(File(nativeLibrary)))
            .put("model_sha256", sha(File("src/main/assets/language_model/model.cng")))
            .put("results", results).put("scope", "Production controller and native JNI with fake editor/OS and virtual scheduler; not physical-phone latency or user traffic")
        File(reportPath).apply { parentFile?.mkdirs(); writeText(report.toString(2) + "\n") }
        val labelled = (0 until results.length()).count { results.getJSONObject(it).optBoolean("labelled", false) }
        val passed = (0 until results.length()).count { results.getJSONObject(it).optBoolean("exact_success", false) }
        println("PRODUCT_EVAL_EXACT_SESSIONS=$passed/$labelled; report=$destination; JUnit pass means replay completed, not quality qualification")
    }
}
