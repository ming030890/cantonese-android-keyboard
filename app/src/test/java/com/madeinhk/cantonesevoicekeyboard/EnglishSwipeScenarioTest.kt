package com.madeinhk.cantonesevoicekeyboard

import androidx.compose.ui.geometry.Rect
import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnglishSwipeScenarioTest {

    private fun buildAlphaNumRegistry(
        availableWidthDp: Float,
        rowHeightDp: Float,
        dpiScale: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        uppercase: Boolean = false,
    ): KeyboardKeyRegistry {
        val registry = KeyboardKeyRegistry()
        registry.updateSurfaceSize(
            width = (availableWidthDp * dpiScale + offsetX * 2).toInt(),
            height = (rowHeightDp * 4f * dpiScale + offsetY * 2).toInt(),
        )
        val stdW = availableWidthDp / 10f
        val hGap = 2.5f
        val vGap = 3.0f

        "qwertyuiop".forEachIndexed { i, ch ->
            val label = (if (uppercase) ch.uppercaseChar() else ch).toString()
            val key = registry.key("alpha.0.$ch", KeyData("alpha.0.$ch", KeyboardKeyKind.Text, label), "row0")
            registry.mount(key)
            val left = (i * stdW + hGap) * dpiScale + offsetX
            val right = ((i + 1) * stdW - hGap) * dpiScale + offsetX
            val top = vGap * dpiScale + offsetY
            val bottom = (rowHeightDp - vGap) * dpiScale + offsetY
            registry.updateVisualBounds(key, Rect(left, top, right, bottom))
        }

        "asdfghjkl".forEachIndexed { i, ch ->
            val label = (if (uppercase) ch.uppercaseChar() else ch).toString()
            val key = registry.key("alpha.1.$ch", KeyData("alpha.1.$ch", KeyboardKeyKind.Text, label), "row1")
            registry.mount(key)
            val left = ((i + 0.5f) * stdW + hGap) * dpiScale + offsetX
            val right = ((i + 1.5f) * stdW - hGap) * dpiScale + offsetX
            val top = (rowHeightDp + vGap) * dpiScale + offsetY
            val bottom = (2f * rowHeightDp - vGap) * dpiScale + offsetY
            registry.updateVisualBounds(key, Rect(left, top, right, bottom))
        }

        val totalGaps = 8f * 5f
        val usable = (availableWidthDp - totalGaps).coerceAtLeast(10f)
        val unitW = usable / (2f * 1.56f + 7f)
        var xCursor = 1.56f * unitW + 5f
        "zxcvbnm".forEach { ch ->
            val label = (if (uppercase) ch.uppercaseChar() else ch).toString()
            val key = registry.key("alpha.2.$ch", KeyData("alpha.2.$ch", KeyboardKeyKind.Text, label), "row2")
            registry.mount(key)
            val left = xCursor * dpiScale + offsetX
            val right = (xCursor + unitW) * dpiScale + offsetX
            val top = (2f * rowHeightDp + vGap) * dpiScale + offsetY
            val bottom = (3f * rowHeightDp - vGap) * dpiScale + offsetY
            registry.updateVisualBounds(key, Rect(left, top, right, bottom))
            xCursor += unitW + 5f
        }
        return registry
    }

    private fun buildWordTrajectory(centersPx: FloatArray, word: String, stepsPerSegment: Int = 10): FloatArray {
        val letters = word.filter { it.isLetter() }.map { it.lowercaseChar() - 'a' }
        require(letters.isNotEmpty())
        val out = ArrayList<Float>((letters.size * stepsPerSegment + 2) * 3)
        var tMs = 0f
        if (letters.size == 1) {
            val k = letters[0]
            val cx = centersPx[k * 2]
            val cy = centersPx[k * 2 + 1]
            out.addAll(listOf(cx - 4f, cy, 0f, cx + 4f, cy, 40f))
            return out.toFloatArray()
        }
        for (seg in 0 until letters.size - 1) {
            val k0 = letters[seg]
            val k1 = letters[seg + 1]
            val x0 = centersPx[k0 * 2]
            val y0 = centersPx[k0 * 2 + 1]
            val x1 = centersPx[k1 * 2]
            val y1 = centersPx[k1 * 2 + 1]
            for (s in 0 until stepsPerSegment) {
                val u = s.toFloat() / stepsPerSegment.toFloat()
                val a = 0.5f * (1f - kotlin.math.cos(Math.PI.toFloat() * u))
                out.add(x0 * (1f - a) + x1 * a)
                out.add(y0 * (1f - a) + y1 * a)
                out.add(tMs)
                tMs += 12f
            }
        }
        val last = letters.last()
        out.add(centersPx[last * 2])
        out.add(centersPx[last * 2 + 1])
        out.add(tMs)
        return out.toFloatArray()
    }

    @Test
    fun detectorCapturesMultiScreenTrajectoriesAndAbortsOnRolloverOrLongPress() {
        val configs = listOf(
            Triple(320f, 50f, 2.0f),   // Compact portrait
            Triple(404f, 57f, 2.75f),  // Standard high-DPI portrait
            Triple(880f, 52f, 2.5f),   // Wide landscape tablet
        )
        for ((wDp, hDp, dpi) in configs) {
            val registry = buildAlphaNumRegistry(wDp, hDp, dpi, offsetX = 24f, offsetY = 96f)
            val geom = checkNotNull(registry.englishSwipeGeometry())
            assertEquals(52, geom.centersPx.size)
            assertTrue(geom.centersPx.all { it.isFinite() })

            val completed = mutableListOf<Pair<FloatArray, FloatArray>>()
            val detector = KeyboardSwipeDetector(registry, isEnabled = { true }) { c, p ->
                completed += c to p
            }

            val hIdx = ('h' - 'a') * 2
            val eIdx = ('e' - 'a') * 2
            val hx = geom.centersPx[hIdx]
            val hy = geom.centersPx[hIdx + 1]
            val ex = geom.centersPx[eIdx]
            val ey = geom.centersPx[eIdx + 1]
            val keyDataH = KeyData("alpha.1.h", KeyboardKeyKind.Text, "h")

            // 1. Small finger roll within a single key never triggers swipe
            detector.onSampleDown(1, 1, false, keyDataH, hx, hy, 100L)
            assertFalse(detector.onSampleMove(1, 1, false, hx + geom.medianKeyWidthPx * 0.25f, hy, 120L))
            detector.onSampleUp(1, hx + geom.medianKeyWidthPx * 0.25f, hy, 140L)
            assertTrue(completed.isEmpty())

            // 2. Fast two-thumb rollover immediately aborts swipe tracking before trigger
            detector.onSampleDown(1, 1, false, keyDataH, hx, hy, 200L)
            detector.onSampleDown(2, 2, true, KeyData("alpha.0.e", KeyboardKeyKind.Text, "e"), ex, ey, 215L)
            assertFalse(detector.onSampleMove(1, 2, false, ex, ey, 240L))
            detector.onSampleUp(1, ex, ey, 260L)
            assertTrue(completed.isEmpty())

            // 3. Long-press immediately aborts swipe tracking
            detector.onSampleDown(1, 1, false, keyDataH, hx, hy, 300L)
            assertFalse(detector.onSampleMove(1, 1, longPressTriggered = true, x = ex, y = ey, timeMs = 650L))
            detector.onSampleUp(1, ex, ey, 700L)
            assertTrue(completed.isEmpty())

            // 4. Genuine single-finger swipe triggers trail, cancels key tap, and emits trajectory on lift
            detector.onSampleDown(1, 1, false, keyDataH, hx, hy, 800L)
            val midX = (hx + ex) * 0.5f
            val midY = (hy + ey) * 0.5f
            detector.onSampleMove(1, 1, false, midX, midY, 830L)
            assertTrue(detector.onSampleMove(1, 1, false, ex, ey, 860L))
            assertTrue(detector.isSwipeActive)
            assertTrue(detector.currentTrailOffsets().size >= 2)
            detector.onSampleUp(1, ex, ey, 880L)
            assertFalse(detector.isSwipeActive)
            assertTrue(detector.currentTrailOffsets().isEmpty())
            assertEquals(1, completed.size)
            assertArrayEquals(geom.centersPx, completed[0].first, 1e-4f)
            assertTrue(completed[0].second.size >= 9)
        }
    }

    @Test
    fun consecutiveSwipesAutoSpaceCandidateReplaceBackspaceUndoAndPunctuation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recordedCalls = mutableListOf<Pair<String, Int>>()
        var nextCandidates = listOf("Hello", "Help", "Held")

        val fakeSwipeEngine = object : EnglishCandidateEngine {
            override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String) =
                EnglishPrediction()

            override fun nextWords(left: String, right: String): List<String> =
                if (left.endsWith("would")) listOf("be", "have") else listOf("the", "and")

            override fun swipe(left: String, centers: FloatArray, points: FloatArray, shiftMode: Int): List<EnglishCandidate> {
                recordedCalls += left to shiftMode
                return nextCandidates.mapIndexed { idx, w ->
                    val cased = when (shiftMode) {
                        2 -> w.uppercase()
                        1 -> w.replaceFirstChar { it.uppercaseChar() }
                        3 -> w.replaceFirstChar { it.lowercaseChar() }
                        else -> w
                    }
                    EnglishCandidate(cased, -idx.toDouble(), 0.0, 0, 0)
                }
            }
        }

        val registry = buildAlphaNumRegistry(404f, 57f, 2.75f)
        val centers = checkNotNull(registry.englishSwipeGeometry()).centersPx
        val dummyPts = buildWordTrajectory(centers, "hello")

        KeyboardScenario(
            initialMode = KeyboardInputMode.AlphaNum,
            clipboardText = "clip",
            englishEngine = fakeSwipeEngine,
            sharedSuggestions = true,
            autoCapitalizationEnabled = true,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            englishDispatcher = dispatcher,
            lookupDispatcher = dispatcher,
        ).use { s ->
            s.start()
            // First swipe dismisses clipboard chip and commits without leading space
            s.swipe(centers, dummyPts); runCurrent()
            assertEquals("Hello", s.editor.text)
            assertNull(s.snapshot().clipboard)
            assertEquals(listOf("Hello", "Help", "Held"), s.snapshot().englishCandidates.map { it.text })
            assertEquals("" to 0, recordedCalls.last())

            // Second swipe auto-prepends separating space and passes "Hello " as LM left context
            nextCandidates = listOf("world", "would", "work")
            s.swipe(centers, dummyPts); runCurrent()
            assertEquals("Hello world", s.editor.text)
            assertEquals("Hello " to 0, recordedCalls.last())
            assertEquals(listOf("world", "would", "work"), s.snapshot().englishCandidates.map { it.text })

            // Tapping an alternate swipe candidate replaces the just-swiped word (preserving space)
            s.choose("would"); runCurrent()
            assertEquals("Hello would", s.editor.text)
            assertEquals(listOf("be", "have"), s.snapshot().englishCandidates.map { it.text })

            // Third swipe + single Backspace undoes the entire swiped word + auto-space
            nextCandidates = listOf("today", "tonight")
            s.swipe(centers, dummyPts); runCurrent()
            assertEquals("Hello would today", s.editor.text)
            s.press(KeyboardKeyKind.Delete); runCurrent()
            assertEquals("Hello would", s.editor.text)

            // Subsequent Backspace deletes one character normally
            s.press(KeyboardKeyKind.Delete); runCurrent()
            assertEquals("Hello woul", s.editor.text)
        }

        // Punctuation attachment, explicit Space non-duplication, tap-typing handoff, and Shift modes
        KeyboardScenario(
            initialMode = KeyboardInputMode.AlphaNum,
            clipboardText = null,
            englishEngine = fakeSwipeEngine,
            autoCapitalizationEnabled = true,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            englishDispatcher = dispatcher,
            lookupDispatcher = dispatcher,
        ).use { s ->
            s.start()
            nextCandidates = listOf("Hello")
            s.swipe(centers, dummyPts); runCurrent()
            // Period attaches directly with no space before '.'
            s.press(KeyboardKeyKind.Text, "."); runCurrent()
            assertEquals("Hello.", s.editor.text)

            // Next swipe after '.' auto-inserts space -> "Hello. How"
            nextCandidates = listOf("How")
            s.swipe(centers, dummyPts); runCurrent()
            assertEquals("Hello. How", s.editor.text)

            // Explicit Space followed by swipe does NOT insert a double space
            s.press(KeyboardKeyKind.Space); runCurrent()
            nextCandidates = listOf("are")
            s.swipe(centers, dummyPts); runCurrent()
            assertEquals("Hello. How are", s.editor.text)

            // Tap-typing a letter immediately after a swipe auto-inserts a space instead of merging
            s.type("you"); runCurrent()
            s.press(KeyboardKeyKind.Text, "?"); runCurrent()
            assertEquals("Hello. How are you?", s.editor.text)

            // One-shot Shift sends shiftMode=1 and resets ShiftState to Off
            s.press(KeyboardKeyKind.Shift); runCurrent()
            assertEquals(ShiftState.Shifted, s.snapshot().shiftState)
            nextCandidates = listOf("great")
            s.swipe(centers, dummyPts); runCurrent()
            assertEquals("Hello. How are you? Great", s.editor.text)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)
            assertEquals(1, recordedCalls.last().second)

            // Auto-capitalization disabled sends shiftMode=3 when unshifted
            s.autoCapitalizationEnabled = false
            nextCandidates = listOf("Thanks")
            s.swipe(centers, dummyPts); runCurrent()
            assertEquals("Hello. How are you? Great thanks", s.editor.text)
            assertEquals(3, recordedCalls.last().second)
        }
    }

    @Test
    fun quickKeyboardSupportsEnglishSwipeBilingualMixingCandidateReplaceAndUndo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var swipeWords = listOf("apple", "apply", "apples")
        val fakeSwipeEngine = object : EnglishCandidateEngine {
            override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String) =
                EnglishPrediction()

            override fun swipe(left: String, centers: FloatArray, points: FloatArray, shiftMode: Int): List<EnglishCandidate> =
                swipeWords.mapIndexed { idx, w -> EnglishCandidate(w, -idx.toDouble(), 0.0, 0, 0) }
        }
        val fakeUnified = object : UnifiedSuggestionEngine {
            override fun newRequest() = object : SuggestionRequest {
                private var cancelled = false
                override fun cancel() { cancelled = true }
                override fun ensureActive() { if (cancelled) throw kotlinx.coroutines.CancellationException() }
            }

            override fun compose(
                request: SuggestionRequest,
                version: Int,
                code: String,
                left: String,
                right: String,
                englishEnabled: Boolean,
            ): JoinedSuggestions {
                val chinese = if (code == "nf") listOf("你", "好") else if (code.isEmpty()) listOf("係", "嘅") else listOf("字")
                return JoinedSuggestions(
                    chinese = chinese,
                    display = if (code.isEmpty()) chinese else chinese + code,
                    codeLength = code.length,
                )
            }
        }

        val registry = buildAlphaNumRegistry(404f, 57f, 2.75f)
        val centers = checkNotNull(registry.englishSwipeGeometry()).centersPx
        val dummyPts = buildWordTrajectory(centers, "apple")

        for (useUnified in listOf(false, true)) {
            KeyboardScenario(
                initialText = "我鍾意",
                initialMode = KeyboardInputMode.Quick,
                clipboardText = "clip",
                englishEngine = fakeSwipeEngine,
                unifiedEngine = if (useUnified) fakeUnified else null,
                sharedSuggestions = true,
                autoCapitalizationEnabled = true,
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                englishDispatcher = dispatcher,
                lookupDispatcher = dispatcher,
            ).use { s ->
                s.engine.lookup = { _, code, _, _ ->
                    if (code == "nf") listOf(com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate("你", 0))
                    else listOf(com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate("係", 0))
                }
                s.start(); runCurrent()

                // 1. Swiping after Chinese text commits directly without unwanted leading space
                swipeWords = listOf("apple", "apply", "apples")
                s.swipe(centers, dummyPts); runCurrent()
                assertEquals("我鍾意apple", s.editor.text)
                assertNull(s.snapshot().clipboard)
                // Simulate Android InputMethodService.onUpdateSelection after commitText:
                // swipe candidates and undo state must be preserved in Quick toolbar!
                val posAfterFirst = s.editor.text.length
                s.controller.onSelectionChanged(posAfterFirst, posAfterFirst, -1)
                runCurrent()
                assertEquals(listOf("apple", "apply", "apples"), s.snapshot().quickCandidates.map { it.text })
                assertEquals(false, s.snapshot().chinesePunctuation)

                // 2. Tapping an alternate swipe candidate in the Quick toolbar replaces the swiped word
                s.choose("apples"); runCurrent()
                assertEquals("我鍾意apples", s.editor.text)

                // 3. Consecutive swipe in Quick mode auto-inserts English space ("我鍾意apples pie")
                swipeWords = listOf("pie", "piece")
                s.swipe(centers, dummyPts); runCurrent()
                assertEquals("我鍾意apples pie", s.editor.text)
                assertEquals(listOf("pie", "piece"), s.snapshot().quickCandidates.map { it.text })

                // 4. Single Backspace undoes the swiped English word + separating space in Quick mode
                s.press(KeyboardKeyKind.Delete); runCurrent()
                assertEquals("我鍾意apples", s.editor.text)

                // 5. Swiping again then tap-typing a 2-letter Quick Chinese code ("nf" -> "你") works seamlessly
                swipeWords = listOf("pie", "piece")
                s.swipe(centers, dummyPts); runCurrent()
                assertEquals("我鍾意apples pie", s.editor.text)
                s.type("nf"); runCurrent()
                assertEquals("nf", s.editor.composing)
                assertTrue(s.snapshot().quickCandidates.any { it.text == "你" })
                s.choose("你"); runCurrent()
                assertEquals("我鍾意apples pie你", s.editor.text)
            }
        }
    }

    @Test
    fun realJniSwipeDecodesAcrossDeviceSizesAndContextualContractions() = runTest {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))

        val deviceLayouts = listOf(
            "CompactPortrait" to buildAlphaNumRegistry(320f, 50f, 2.0f, offsetX = 12f, offsetY = 80f),
            "StandardPortrait" to buildAlphaNumRegistry(404f, 57f, 2.75f, offsetX = 22f, offsetY = 140f),
            "FoldableInner" to buildAlphaNumRegistry(680f, 62f, 3.0f, offsetX = 48f, offsetY = 180f),
            "TabletLandscape" to buildAlphaNumRegistry(880f, 52f, 2.5f, offsetX = 100f, offsetY = 60f),
        )

        for ((deviceName, registry) in deviceLayouts) {
            val centers = checkNotNull(registry.englishSwipeGeometry()).centersPx
            val dispatcher = StandardTestDispatcher(testScheduler)
            val quick = NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() })

            for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
                KeyboardScenario(
                    initialMode = mode,
                    clipboardText = null,
                    quickEngine = quick,
                    englishEngine = SharedLmEnglishEngine(quick::englishContextHandle),
                    unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle),
                    autoCapitalizationEnabled = true,
                    scope = CoroutineScope(SupervisorJob() + dispatcher),
                    englishDispatcher = dispatcher,
                    lookupDispatcher = dispatcher,
                ).use { s ->
                    s.start()
                    s.swipe(centers, buildWordTrajectory(centers, "hello")); runCurrent()
                    val rowAfterHello = if (mode == KeyboardInputMode.Quick) {
                        s.snapshot().quickCandidates
                    } else {
                        s.snapshot().englishCandidates
                    }
                    assertTrue("$deviceName/$mode toolbar shows Hello", rowAfterHello.any { it.text == "Hello" })

                    s.swipe(centers, buildWordTrajectory(centers, "world")); runCurrent()
                    s.press(KeyboardKeyKind.Text, "."); runCurrent()
                    assertEquals("$deviceName/$mode hello world", "Hello world.", s.editor.text)

                    // Contraction with internal apostrophe from 169k lexicon
                    s.swipe(centers, buildWordTrajectory(centers, "dont")); runCurrent()
                    s.swipe(centers, buildWordTrajectory(centers, "worry")); runCurrent()
                    assertEquals("$deviceName/$mode contraction", "Hello world. Don't worry", s.editor.text)
                }
                runCurrent()
            }

            // Contextual disambiguation on identical i-t-s swipe trajectory ("it's" vs "its")
            KeyboardScenario(
                initialText = "I think ",
                initialMode = KeyboardInputMode.AlphaNum,
                clipboardText = null,
                quickEngine = quick,
                englishEngine = SharedLmEnglishEngine(quick::englishContextHandle),
                unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle),
                autoCapitalizationEnabled = true,
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                englishDispatcher = dispatcher,
                lookupDispatcher = dispatcher,
            ).use { s ->
                s.start()
                s.swipe(centers, buildWordTrajectory(centers, "its")); runCurrent()
                assertEquals("$deviceName contraction context", "I think it's", s.editor.text)
            }
            runCurrent()

            KeyboardScenario(
                initialText = "The company increased ",
                initialMode = KeyboardInputMode.AlphaNum,
                clipboardText = null,
                quickEngine = quick,
                englishEngine = SharedLmEnglishEngine(quick::englishContextHandle),
                unifiedEngine = NativeSharedSuggestions(quick::englishContextHandle),
                autoCapitalizationEnabled = true,
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                englishDispatcher = dispatcher,
                lookupDispatcher = dispatcher,
            ).use { s ->
                s.start()
                s.swipe(centers, buildWordTrajectory(centers, "its")); runCurrent()
                assertEquals("$deviceName possessive context", "The company increased its", s.editor.text)
            }
            runCurrent()
        }
    }
}
