package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardKeyKind
import org.junit.Assert.*
import org.junit.Test
import android.text.InputType
import android.view.inputmethod.EditorInfo

class AutoCapitalizationScenarioTest {

    @Test
    fun ordinaryAndWebTextFieldsUseSettingWithoutHostCapFlags() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)) {
            KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, autoCapitalizationEnabled = true).use { s ->
                s.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or variation })
                assertEquals(ShiftState.Shifted, s.snapshot().shiftState)
                s.type("hello. ")
                assertEquals("Hello. ", s.editor.text)
                assertEquals("", s.editor.composing)
                assertEquals(ShiftState.Shifted, s.snapshot().shiftState)
                s.type("world")
                assertEquals("Hello. World", s.editor.text)
                assertEquals("", s.editor.composing)
                assertTrue(s.snapshot().englishCandidates.isEmpty())
                assertEquals(com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardToolbarContent.Empty, s.snapshot().toolbar())
                assertEquals(ShiftState.Off, s.snapshot().shiftState)
            }
        }
    }

    @Test
    fun startOfTextAutoCapitalizesFirstLetterThenDropsToLowercase() {
        KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, autoCapitalizationEnabled = true).use { s ->
            s.start()
            // Starts in Shifted state
            assertEquals(ShiftState.Shifted, s.snapshot().shiftState)

            // Typing first letter produces uppercase 'H', shift drops to Off
            s.type("h")
            assertEquals("H", s.editor.text)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)

            // Subsequent letters remain lowercase
            s.type("ello")
            assertEquals("Hello", s.editor.text)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)
        }
    }

    @Test
    fun sentenceTerminatorTriggersAutoCapitalization() {
        KeyboardScenario(initialText = "Hello", initialMode = KeyboardInputMode.AlphaNum, autoCapitalizationEnabled = true).use { s ->
            s.start()
            assertEquals(ShiftState.Off, s.snapshot().shiftState)

            s.type(". ")
            assertEquals("Hello. ", s.editor.text)
            assertEquals(ShiftState.Shifted, s.snapshot().shiftState)

            s.type("w")
            assertEquals("Hello. W", s.editor.text)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)
        }
    }

    @Test
    fun backspaceAcrossSentenceBoundaryRestoresShiftState() {
        KeyboardScenario(initialText = "Hello. ", initialMode = KeyboardInputMode.AlphaNum, autoCapitalizationEnabled = true).use { s ->
            s.start()
            assertEquals(ShiftState.Shifted, s.snapshot().shiftState)

            s.type("t")
            assertEquals("Hello. T", s.editor.text)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)

            // Delete 'T' back to "Hello. "
            s.press(KeyboardKeyKind.Delete)
            assertEquals("Hello. ", s.editor.text)
            assertEquals(ShiftState.Shifted, s.snapshot().shiftState)
        }
    }

    @Test
    fun shiftKeySingleTapTogglesShiftState() {
        KeyboardScenario(initialText = "Hello ", initialMode = KeyboardInputMode.AlphaNum, autoCapitalizationEnabled = true).use { s ->
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
        KeyboardScenario(initialText = "Hello ", initialMode = KeyboardInputMode.AlphaNum, autoCapitalizationEnabled = true).use { s ->
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
    fun quickCapitalizesLiteralEnglishAndKeepsChineseSelectable() {
        KeyboardScenario(initialMode = KeyboardInputMode.Quick, autoCapitalizationEnabled = true).use { s ->
            s.start()
            assertEquals(ShiftState.Shifted, s.snapshot().shiftState)
            s.type("hello. world ")
            assertEquals("Hello. World ", s.editor.text)
            assertEquals("", s.editor.composing)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)
            s.type("\n")
            s.engine.lookup = { _, code, _, _ ->
                if (code == "Of") listOf(com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate("你", 0))
                else emptyList()
            }
            // The native engine accepts either case; preserve the observed keys.
            s.type("of")
            assertEquals("Of", s.editor.composing)
            assertEquals(listOf("你"), s.snapshot().quickCandidates.map { it.text })
            s.choose("你")
            assertEquals("Hello. World \n你", s.editor.text)
            assertEquals("", s.editor.composing)
            assertTrue(s.snapshot().quickCandidates.isNotEmpty())
        }
    }

    @Test
    fun quickDeleteRestoresFirstLetterCapsAndSettingCanDisableIt() {
        KeyboardScenario(initialMode = KeyboardInputMode.Quick, autoCapitalizationEnabled = true).use { s ->
            s.start()
            s.type("h")
            assertEquals("H", s.editor.composing)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)
            s.press(KeyboardKeyKind.Delete)
            assertEquals("", s.editor.composing)
            assertEquals(ShiftState.Shifted, s.snapshot().shiftState)
            s.autoCapitalizationEnabled = false
            s.start()
            s.type("hello ")
            assertEquals("hello ", s.editor.text)
        }
    }

    @Test
    fun quickCapsPreservesModelCorrectionAndChineseChoice() {
        val backend = ScenarioEnglishEngine()
        val engine = object : com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidateEngine by backend {
            override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String):
                com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction =
                backend.predict(word.lowercase(java.util.Locale.ROOT), centers, points, left, right)
        }
        KeyboardScenario(initialMode = KeyboardInputMode.Quick, autoCapitalizationEnabled = true,
            englishEngine = engine).use { s ->
            s.engine.lookup = { _, _, _, _ ->
                listOf(com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate("你", 0))
            }
            s.start()
            s.type("helllo")
            assertEquals("Helllo", s.editor.composing)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)
            assertTrue(s.snapshot().quickCandidates.any { it.text == "你" })
            s.press(KeyboardKeyKind.Space)
            assertEquals("Hello ", s.editor.text)
            assertEquals("", s.editor.composing)
        }
    }

    @Test
    fun disablingAutoCapitalizationInSettingsKeepsLowercase() {
        KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum).use { s ->
            s.autoCapitalizationEnabled = false
            s.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })

            // Does not start in Shifted
            assertEquals(ShiftState.Off, s.snapshot().shiftState)

            s.type("hello. ")
            assertEquals(ShiftState.Off, s.snapshot().shiftState)

            s.type("w")
            assertEquals("hello. w", s.editor.text)
        }
    }
}
