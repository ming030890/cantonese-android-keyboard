package com.madeinhk.cantonesevoicekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Test

class SystemKeyboardSubtypeScenarioTest {
    @Test fun globeSelectionsSwitchAllLayoutsWithoutTypingAndSurviveReopen() {
        KeyboardScenario().use { s ->
            s.start()
            for ((id, mode) in listOf(KeyboardSubtypes.ENGLISH to KeyboardInputMode.AlphaNum,
                KeyboardSubtypes.HANDWRITING to KeyboardInputMode.Handwriting,
                KeyboardSubtypes.QUICK to KeyboardInputMode.Quick)) {
                s.controller.onSystemSubtypeSelected(id)
                assertEquals(mode, s.snapshot().inputMode)
                assertEquals("", s.editor.text)
                assertEquals("", s.editor.composing)
                assertTrue(s.snapshot().quickCandidates.all { it.text in listOf("我", "你") })
                assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
                s.hide(); s.start()
                assertEquals(mode, s.snapshot().inputMode)
            }
        }
    }

    @Test fun switchingOutOfUnfinishedQuickCodeCommitsLiteralOnce() {
        KeyboardScenario().use { s ->
            s.start(); s.type("of")
            assertEquals("of", s.editor.composing)
            s.controller.onSystemSubtypeSelected(KeyboardSubtypes.ENGLISH)
            s.controller.onSystemSubtypeSelected(KeyboardSubtypes.ENGLISH)
            assertEquals("of", s.editor.text)
            assertEquals("", s.editor.composing)
            assertTrue(s.snapshot().quickCandidates.isEmpty())
            assertEquals(KeyboardToolbarContent.Empty, s.snapshot().toolbar())
        }
    }

    @Test fun startupHonorsSystemSubtypeAndPreservesSpecialEditorLayouts() {
        KeyboardScenario().use { s ->
            s.controller.onStartInput(null, false, KeyboardSubtypes.HANDWRITING)
            s.controller.onStartInputView(null)
            assertEquals(KeyboardInputMode.Handwriting, s.snapshot().inputMode)
            s.hide()
            val numeric = EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER }
            s.controller.onStartInput(numeric, false, KeyboardSubtypes.QUICK)
            s.controller.onStartInputView(numeric)
            assertEquals(KeyboardInputMode.Number, s.snapshot().inputMode)
            s.hide()
            val password = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            s.controller.onStartInput(password, false, KeyboardSubtypes.HANDWRITING)
            s.controller.onStartInputView(password)
            assertEquals(KeyboardInputMode.AlphaNum, s.snapshot().inputMode)
            s.hide(); s.start()
            assertEquals(KeyboardInputMode.Handwriting, s.snapshot().inputMode)
        }
    }

    @Test fun disabledAndUnknownSubtypesCannotSelectAnUnavailableLayout() {
        KeyboardScenario().use { s ->
            s.enabledLayouts = setOf(KeyboardSettings.LAYOUT_ENGLISH)
            s.start()
            s.controller.onSystemSubtypeSelected(KeyboardSubtypes.QUICK)
            assertEquals(KeyboardInputMode.AlphaNum, s.snapshot().inputMode)
            s.controller.onSystemSubtypeSelected(9999)
            assertEquals(KeyboardInputMode.AlphaNum, s.snapshot().inputMode)
            assertArrayEquals(intArrayOf(KeyboardSubtypes.ENGLISH), KeyboardSubtypes.enabledIds(s.enabledLayouts))
            assertEquals("", s.editor.text)
        }
        assertArrayEquals(intArrayOf(1001, 1002, 1003), KeyboardSubtypes.enabledIds(KeyboardSettings.DEFAULT_LAYOUTS))
    }
}
