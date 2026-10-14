package com.madeinhk.cantonesevoicekeyboard

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import com.madeinhk.cantonesevoicekeyboard.english.NativeEnglishEngine
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/** Real dictionary + JNI + controller + key receiver; only the Android editor is fake. */
class EnglishRealEngineScenarioTest {
    private fun engine(handle: Long = 0L): NativeEnglishEngine {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Run with -PenglishNativeLibrary=/absolute/path/to/host/library", library)
        System.load(requireNotNull(library))
        val load = NativeEnglishEngine::class.java.getDeclaredMethod("nativeLoad", ByteArray::class.java)
            .apply { isAccessible = true }
        assertEquals(true, load.invoke(null, File("src/main/assets/english/en.bin").readBytes()))
        // Avoid Android Context/System.loadLibrary; prediction still uses the production JNI wrapper.
        return NativeEnglishEngine::class.java.declaredConstructors.single { it.parameterCount == 1 }.apply { isAccessible = true }
            .newInstance({ handle }) as NativeEnglishEngine
    }

    private fun registry(): KeyboardKeyRegistry = KeyboardKeyRegistry().apply {
        updateSurfaceSize(1000, 400)
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
            val inset = listOf(0f, 50f, 150f)[row]
            letters.forEachIndexed { column, letter ->
                val key = key("$letter", KeyData("$letter", KeyboardKeyKind.Text, "$letter"), "row$row")
                mount(key)
                val x = inset + column * 100f
                updateVisualBounds(key, Rect(x, row * 100f, x + 100f, row * 100f + 100f))
            }
        }
    }

    @Test fun realPredictionsReachCommittedTextWithAndWithoutTouch() {
        val engine = engine()
        val registry = registry()
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            for (touch in listOf(false, true)) {
                for ((typed, expected) in listOf("teh" to "the", "helllo" to "hello", "wprld" to "world")) {
                    KeyboardScenario(initialMode = mode, englishEngine = engine).use { s ->
                        s.start()
                        typed.forEach { letter ->
                            val key = registry.mountedKeysForDiagnostics().single { it.currentData?.text == "$letter" }
                            val evidence = if (touch) registry.englishTouchEvidence(key.geometry.visualBounds.center) else null
                            s.pressWithTouch("$letter", evidence)
                        }
                        val label = "$mode touch=$touch $typed candidates=${s.snapshot().englishCandidates}"
                        s.press(KeyboardKeyKind.Space)
                        assertEquals(label, "$expected ", s.editor.text)
                        assertEquals("", s.editor.composing)
                        if (typed != expected) {
                            s.press(KeyboardKeyKind.Delete)
                            assertEquals(label, typed, s.editor.composing)
                        }
                    }
                }
            }
        }
    }

    @Test fun reportedTyposAppearAndClearCentreTapsCorrect() {
        val engine = engine()
        val registry = registry()
        val words = listOf("engkish" to "english", "shoukd" to "should", "chineae" to "chinese",
            "candjdates" to "candidates", "kehboard" to "keyboard", "ebgkisb" to "english")
        for ((typed, expected) in words) {
            val result = engine.predict(typed, floatArrayOf(), floatArrayOf(), "", "")
            assertTrue("$typed: $result", result.candidates.any { it.word == expected })
            for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
                KeyboardScenario(initialMode = mode, englishEngine = engine).use { s ->
                    s.start()
                    typed.forEach { letter ->
                        val key = registry.mountedKeysForDiagnostics().single { it.currentData?.text == "$letter" }
                        s.pressWithTouch("$letter", registry.englishTouchEvidence(key.geometry.visualBounds.center))
                    }
                    val candidates = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                    assertTrue("$mode $typed: $candidates", candidates.any { it.text == expected })
                    s.press(KeyboardKeyKind.Space)
                    // The severe three-substitution typo is offered for selection, not silently replaced.
                    assertEquals("$mode $typed", if (typed == "ebgkisb") "$typed " else "$expected ", s.editor.text)
                }
            }
        }
    }

    @Test fun reportedChatTyposOfferIntendedWordsWithoutReplacingAmbiguousInput() {
        val engine = engine()
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            for ((typed, intended) in listOf("tyoing" to "typing", "srjll" to "still")) {
                for (select in listOf(false, true)) {
                    KeyboardScenario(initialMode = mode, englishEngine = engine).use { s ->
                        s.start(); s.type(typed)
                        val candidates = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                        assertTrue("$mode $typed: $candidates", candidates.any { it.text == intended })
                        if (select) s.choose(intended) else s.press(KeyboardKeyKind.Space)
                        assertEquals("$mode $typed select=$select", if (select) intended else "$typed ", s.editor.text)
                    }
                }
            }
        }
    }

    @Test fun wmMissingFinalLetterCorrectsButTwoEditsRequireSelection() {
        val engine = engine()
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            for ((typed, intended, auto) in listOf(Triple("keyboar", "keyboard", true),
                Triple("gwllo", "hello", false))) {
                for (select in listOf(false, true)) {
                    KeyboardScenario(initialMode = mode, englishEngine = engine).use { s ->
                        s.start(); s.type(typed)
                        val candidates = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                        assertTrue("$mode $typed: $candidates", candidates.any { it.text == intended })
                        assertEquals(typed, s.editor.composing)
                        if (select) s.choose(intended) else s.press(KeyboardKeyKind.Space)
                        assertEquals("$mode $typed select=$select", if (select) intended else
                            (if (auto) intended else typed) + " ", s.editor.text)
                        assertEquals("", s.editor.composing)
                        if (!select && auto) {
                            s.press(KeyboardKeyKind.Delete)
                            assertEquals(typed, s.editor.composing)
                        }
                    }
                }
            }
        }
    }

    @Test fun shippedBilingualModelRanksEnglishAndChineseInOnePool() {
        quickEngine().use { quick ->
            val engine = engine(quick.englishContextHandle())
            val pool = listOf("你", "係", "中文", "english", "keyboard", "the")
            for (left in listOf("I use the ", "我想", "")) {
                val ranked = engine.rankCandidates(left, "", pool)
                println("Mixed ranking left='$left': $ranked")
                assertEquals(pool.toSet(), ranked.toSet())
                assertEquals(pool.size, ranked.size)
                if (left == "I use the ") assertEquals("the", ranked.first())
                if (left == "我想") assertEquals("你", ranked.first())
            }
        }
    }

    private fun quickEngine(): NativeQuickEngine {
        engine() // Load host JNI first.
        fun read(path: String) = File("src/main/assets/$path").readBytes()
        val create = NativeQuickEngine::class.java.getDeclaredMethod("nativeCreate",
            ByteArray::class.java, ByteArray::class.java, ByteArray::class.java, ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }
        val handle = create.invoke(null, read("quick/quick3.bin"), read("quick/quick5.bin"),
            read("language_model/model.cng"), byteArrayOf(), read("handwriting/labels.txt")) as Long
        assertTrue(handle != 0L)
        return NativeQuickEngine::class.java.declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }.newInstance(handle) as NativeQuickEngine
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun realLmLanguageEvidenceProtectsUnknownEnglishAndCommitsConfidentChinese() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for ((left, raw) in listOf("I use the " to "tonysunusualname", "我哋一陣" to "hirdgiovoert")) {
            quickEngine().use { quick ->
                val nativeEnglish = engine(quick.englishContextHandle())
                KeyboardScenario(initialText = left, englishEngine = nativeEnglish, quickEngine = quick,
                    scope = CoroutineScope(SupervisorJob() + dispatcher), lookupDispatcher = dispatcher).use { s ->
                    s.start(); s.type(raw); advanceUntilIdle()
                    assertEquals(left, s.editor.text); assertEquals(raw, s.editor.composing)
                    val top = quick.candidates(3, raw.take(12), left, "").first().text
                    val evidence = checkNotNull(quick.languageEvidence(raw, left, "", top))
                    println("Language evidence '$raw' after '$left': $evidence top='$top'")
                    assertEquals(1f, evidence.englishProbability + evidence.chineseProbability, 1e-5f)
                    assertEquals(raw == "hirdgiovoert", evidence.shouldCommitChinese)
                    s.type("q"); advanceUntilIdle()
                    assertEquals(if (evidence.shouldCommitChinese) left + top else left, s.editor.text)
                    assertEquals(if (evidence.shouldCommitChinese) "q" else raw + "q", s.editor.composing)
                    assertFalse(s.snapshot().quickCandidates.isEmpty())
                }
            }
        }
    }

    @Test fun adjacentMistapCorrectsWithRealTouchEvidence() {
        val engine = engine()
        val registry = registry()
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            KeyboardScenario(initialMode = mode, englishEngine = engine).use { s ->
                s.start()
                "wprld".forEach { letter ->
                    val key = registry.mountedKeysForDiagnostics().single { it.currentData?.text == "$letter" }
                    val center = key.geometry.visualBounds.center
                    // P was dispatched, but the finger is just inside its shared edge with O.
                    val point = if (letter == 'p') Offset(center.x - 49f, center.y) else center
                    s.pressWithTouch("$letter", registry.englishTouchEvidence(point))
                }
                s.press(KeyboardKeyKind.Space)
                assertEquals("$mode", "world ", s.editor.text)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun realEngineWithScheduledKeysAndPendingBoundary() = runTest {
        val engine = engine()
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (settle in listOf(false, true)) {
            KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, englishEngine = engine,
                scope = CoroutineScope(SupervisorJob() + dispatcher), lookupDispatcher = dispatcher).use { s ->
                s.start(); s.type("teh")
                if (settle) advanceUntilIdle()
                s.press(KeyboardKeyKind.Space); advanceUntilIdle()
                assertEquals("settle=$settle", "the ", s.editor.text)
            }
        }
    }
}
