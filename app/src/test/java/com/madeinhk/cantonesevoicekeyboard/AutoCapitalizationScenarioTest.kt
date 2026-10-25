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
    fun quickModeDoesNotAutoCapitalizeRadicals() {
        KeyboardScenario(initialMode = KeyboardInputMode.Quick).use { s ->
            s.start()
            // Quick mode should start in Off (no auto-capitalizing radical entry)
            assertEquals(ShiftState.Off, s.snapshot().shiftState)
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
