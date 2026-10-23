package com.madeinhk.cantonesevoicekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeEditorTest {
    @Test fun backspaceDeletesWholeEmojiAndPreservesSurroundingText() {
        for (character in listOf("😀", "👍🏽", "👨‍👩‍👧‍👦", "🇭🇰", "❤️", "1️⃣", "e\u0301", "你", "a")) {
            var left = "before$character"
            val right = "after"
            val deletions = mutableListOf<Pair<Int, Int>>()
            val input = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)) { _, method, args ->
                when (method.name) {
                    "getSelectedText" -> null
                    "getTextBeforeCursor" -> left.takeLast(args!![0] as Int)
                    "getTextAfterCursor" -> right
                    "deleteSurroundingText" -> {
                        val before = args!![0] as Int
                        deletions += before to (args[1] as Int)
                        left = left.dropLast(before)
                        true
                    }
                    else -> error("Unexpected InputConnection call: ${method.name}")
                }
            } as InputConnection
            val editor = ImeEditor({ input }, { null })
            editor.deleteSelectionOrPreviousCharacter()
            assertEquals(character, "before", left)
            assertEquals(listOf(character.length to 0), deletions)
            assertEquals("after", editor.getTextAfterCursor(10))
        }
    }

    @Test fun backspaceReplacesSelectionWithoutDeletingAdjacentText() {
        val commits = mutableListOf<String>()
        val input = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, args ->
            when (method.name) {
                "getSelectedText" -> "😀hello"
                "commitText" -> { commits += args!![0].toString(); true }
                else -> error("Unexpected InputConnection call: ${method.name}")
            }
        } as InputConnection
        ImeEditor({ input }, { null }).deleteSelectionOrPreviousCharacter()
        assertEquals(listOf(""), commits)
    }

    @Test fun previousCharacterLengthHandlesEmptyTextAndAdjacentEmoji() {
        assertEquals(0, previousCharacterLength(""))
        assertEquals("👍🏽".length, previousCharacterLength("😀👍🏽"))
        assertEquals("🇭🇰".length, previousCharacterLength("🇬🇧🇭🇰"))
    }

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
            keyboardInputModeForInputType(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
        )
        // Variation values are scoped to their input class: 0x10 is also text URI.
        assertEquals(
            KeyboardInputMode.Handwriting,
            keyboardInputModeForInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
        )
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
