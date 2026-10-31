package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardKeyKind
import org.junit.Assert.*
import org.junit.Test
import android.text.InputType
import android.view.inputmethod.EditorInfo

class AutoCapitalizationScenarioTest {

    @Test
    fun startOfTextAndSentenceTerminatorsLeaveShiftStateOffForUnifiedAutocorrect() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)) {
            KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum).use { s ->
                s.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or variation })
                assertEquals(ShiftState.Off, s.snapshot().shiftState)
                s.type("hello. ")
                assertEquals(ShiftState.Off, s.snapshot().shiftState)
                s.type("world")
                assertEquals("hello. world", s.editor.text)
            }
        }
    }

    @Test
    fun capCharactersFieldActivatesShiftState() {
        KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum).use { s ->
            s.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS })
            assertEquals(ShiftState.Shifted, s.snapshot().shiftState)
        }
    }

    @Test
    fun shiftKeySingleTapTogglesShiftState() {
        KeyboardScenario(initialText = "Hello ", initialMode = KeyboardInputMode.AlphaNum).use { s ->
            s.start()
            assertEquals(ShiftState.Off, s.snapshot().shiftState)

            // Tap Shift -> Shifted
            val t0 = 1000L
            s.controller.onToggleShift(now = t0)
            assertEquals(ShiftState.Shifted, s.snapshot().shiftState)

            // Tap Shift again (>400ms) -> Off
            s.controller.onToggleShift(now = t0 + 1000L)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)
        }
    }

    @Test
    fun doubleTapShiftActivatesCapsLock() {
        KeyboardScenario(initialText = "Hello ", initialMode = KeyboardInputMode.AlphaNum).use { s ->
            s.start()
            assertEquals(ShiftState.Off, s.snapshot().shiftState)

            // Double tap within 400ms -> CapsLock
            s.controller.onToggleShift(now = 100L)
            assertEquals(ShiftState.Shifted, s.snapshot().shiftState)

            s.controller.onToggleShift(now = 250L)
            assertEquals(ShiftState.CapsLock, s.snapshot().shiftState)

            // In CapsLock, typing multiple letters stays uppercase and stays in CapsLock
            s.type("abc")
            assertEquals("Hello ABC", s.editor.text)
            assertEquals(ShiftState.CapsLock, s.snapshot().shiftState)

            // Tapping Shift clears CapsLock back to Off
            s.controller.onToggleShift(now = 1500L)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)
        }
    }

    @Test
    fun quickModeKeepsShiftStateOffAndPreservesChineseChoice() {
        val backend = ScenarioEnglishEngine()
        val engine = object : com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidateEngine by backend {
            override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String):
                com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction =
                backend.predict(word, centers, points, left, right)
        }
        KeyboardScenario(initialMode = KeyboardInputMode.Quick, englishEngine = engine).use { s ->
            s.engine.lookup = { _, _, _, _ ->
                listOf(com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate("你", 0))
            }
            s.start()
            s.type("helllo")
            assertEquals("helllo", s.editor.composing)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)
            assertTrue(s.snapshot().quickCandidates.any { it.text == "你" })
            s.choose("你")
            assertEquals("你", s.editor.text)
            assertEquals("", s.editor.composing)
        }
    }
}
