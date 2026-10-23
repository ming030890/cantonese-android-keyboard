package com.madeinhk.cantonesevoicekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardAction
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode

internal class ImeEditor(
    private val connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
) : KeyboardEditor {
    override fun setComposingText(text: String) {
        connection()?.setComposingText(text, 1)
    }

    override fun finishComposingText() {
        connection()?.finishComposingText()
    }

    override fun commitText(text: String) {
        connection()?.commitText(text, 1)
    }

    override fun getTextBeforeCursor(length: Int): String? {
        return connection()?.getTextBeforeCursor(length, 0)?.toString()
    }

    override fun getTextAfterCursor(length: Int): String? =
        connection()?.getTextAfterCursor(length, 0)?.toString()

    override fun deleteSelectionOrPreviousCharacter() {
        connection()?.run {
            if (shouldDeleteSelection(getSelectedText(0))) {
                commitText("", 1)
            } else {
                val before = getTextBeforeCursor(1024, 0)
                deleteSurroundingText(before?.let(::previousCharacterLength) ?: 1, 0)
            }
        }
    }

    override fun sendEnter() {
        val inputConnection = connection() ?: return
        val info = editorInfo()
        when (val command = resolveEnterCommand(info?.actionId ?: 0, info?.imeOptions)) {
            is EditorEnterCommand.PerformAction ->
                inputConnection.performEditorAction(command.actionId)
            EditorEnterCommand.CommitNewline -> inputConnection.commitText("\n", 1)
        }
    }

    override fun enterDelimiter(): String? {
        val info = editorInfo()
        return if (resolveEnterCommand(info?.actionId ?: 0, info?.imeOptions) == EditorEnterCommand.CommitNewline) "\n" else null
    }

    override fun cursorSelection(): Pair<Int, Int>? = connection()
        ?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        ?.let { (it.startOffset + it.selectionStart) to (it.startOffset + it.selectionEnd) }

    override fun replaceTextBeforeCursor(expected: String, replacement: String): Boolean {
        val input = connection() ?: return false
        if (!input.getSelectedText(0).isNullOrEmpty() ||
            input.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return false
        input.beginBatchEdit()
        return try {
            input.deleteSurroundingText(expected.length, 0) && input.commitText(replacement, 1)
        } finally { input.endBatchEdit() }
    }
}

internal sealed interface EditorEnterCommand {
    data class PerformAction(val actionId: Int) : EditorEnterCommand
    data object CommitNewline : EditorEnterCommand
}

internal fun resolveEnterCommand(actionId: Int, imeOptions: Int?): EditorEnterCommand {
    if (actionId != 0) return EditorEnterCommand.PerformAction(actionId)
    val standardAction =
        imeOptions?.let(::standardImeAction) ?: EditorInfo.IME_ACTION_NONE
    return if (standardAction != EditorInfo.IME_ACTION_NONE) {
        EditorEnterCommand.PerformAction(standardAction)
    } else {
        EditorEnterCommand.CommitNewline
    }
}

internal fun shouldDeleteSelection(selectedText: CharSequence?): Boolean =
    !selectedText.isNullOrEmpty()

private val graphemePattern = java.util.regex.Pattern.compile("\\X")

/** InputConnection lengths count UTF-16 units; one visible character may span many. */
internal fun previousCharacterLength(beforeCursor: CharSequence): Int {
    val matcher = graphemePattern.matcher(beforeCursor)
    var start = beforeCursor.length
    while (matcher.find()) start = matcher.start()
    return beforeCursor.length - start
}

internal fun keyboardActionFor(editorInfo: EditorInfo?): KeyboardAction =
    when (editorInfo?.let { standardImeAction(it.imeOptions) } ?: EditorInfo.IME_ACTION_NONE) {
        EditorInfo.IME_ACTION_GO -> KeyboardAction.Go
        EditorInfo.IME_ACTION_SEARCH -> KeyboardAction.Search
        EditorInfo.IME_ACTION_SEND -> KeyboardAction.Send
        EditorInfo.IME_ACTION_NEXT -> KeyboardAction.Next
        EditorInfo.IME_ACTION_PREVIOUS -> KeyboardAction.Previous
        EditorInfo.IME_ACTION_DONE -> KeyboardAction.Done
        else -> KeyboardAction.Enter
    }

internal fun keyboardInputModeFor(editorInfo: EditorInfo?): KeyboardInputMode {
    return keyboardInputModeForInputType(editorInfo?.inputType)
}

internal fun keyboardInputModeForInputType(inputType: Int?): KeyboardInputMode {
    inputType ?: return KeyboardInputMode.Handwriting
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    val inputVariation = inputType and InputType.TYPE_MASK_VARIATION
    return when {
        inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE ||
            inputClass == InputType.TYPE_CLASS_DATETIME -> KeyboardInputMode.Number
        inputClass == InputType.TYPE_CLASS_TEXT &&
            inputVariation in PASSWORD_VARIATIONS -> KeyboardInputMode.AlphaNum
        else -> KeyboardInputMode.Handwriting
    }
}

private val PASSWORD_VARIATIONS = setOf(
    InputType.TYPE_TEXT_VARIATION_PASSWORD,
    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
)
