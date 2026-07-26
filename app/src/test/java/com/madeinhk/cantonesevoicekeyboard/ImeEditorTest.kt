package com.madeinhk.cantonesevoicekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeEditorTest {
    @Test
    fun customActionTakesPrecedence() {
        assertEquals(
            EditorEnterCommand.PerformAction(42),
            resolveEnterCommand(42, EditorInfo.IME_ACTION_SEARCH),
        )
    }

    @Test
    fun standardActionIsPerformed() {
        assertEquals(
            EditorEnterCommand.PerformAction(EditorInfo.IME_ACTION_DONE),
            resolveEnterCommand(0, EditorInfo.IME_ACTION_DONE),
        )
    }

    @Test
    fun missingActionCommitsNewline() {
        assertEquals(
            EditorEnterCommand.CommitNewline,
            resolveEnterCommand(0, EditorInfo.IME_ACTION_NONE),
        )
    }

    @Test
    fun deletionDistinguishesSelectionFromCursor() {
        assertTrue(shouldDeleteSelection("selected"))
        assertFalse(shouldDeleteSelection(""))
        assertFalse(shouldDeleteSelection(null))
    }

    @Test
    fun editorInputTypesSelectExpectedKeyboard() {
        assertEquals(
            KeyboardInputMode.Number,
            keyboardInputModeForInputType(InputType.TYPE_CLASS_NUMBER),
        )
        assertEquals(
            KeyboardInputMode.AlphaNum,
            keyboardInputModeForInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ),
        )
        assertEquals(
            KeyboardInputMode.Handwriting,
            keyboardInputModeForInputType(InputType.TYPE_CLASS_TEXT),
        )
    }
}
