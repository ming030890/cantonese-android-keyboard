package com.madeinhk.cantonesevoicekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Represents the 3-state Shift/Capitalization state of the keyboard:
 * - [Off]: Lowercase characters
 * - [Shifted]: Next typed character is uppercase, then reverts to [Off] (or auto-capitalized)
 * - [CapsLock]: All typed characters are uppercase until Shift is pressed again
 */
enum class ShiftState {
    Off,
    Shifted,
    CapsLock;

    val isUppercase: Boolean get() = this != Off
    val isCapsLock: Boolean get() = this == CapsLock
}

internal object AutoCapitalization {
    const val CAP_MODE_CHARACTERS = 0x00001000
    const val CAP_MODE_WORDS = 0x00002000
    const val CAP_MODE_SENTENCES = 0x00004000

    private val EXCLUDED_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_FILTER,
    )

    /**
     * Determines whether auto-capitalization should be active at the current cursor position.
     * Uses [editorInfo] and [textBefore] (or queries [editor] for up to 64 chars of preceding context)
     * to evaluate input type constraints and empirical sentence/word boundaries.
     */
    fun shouldCapitalize(
        editor: KeyboardEditor?,
        editorInfo: EditorInfo?,
        autoCapEnabled: Boolean = true,
        textBefore: CharSequence? = null,
    ): Boolean {
        if (!autoCapEnabled) return false
        val inputType = editorInfo?.inputType ?: (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false

        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation in EXCLUDED_VARIATIONS) return false

        if (inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return true

        val text = textBefore ?: editor?.getTextBeforeCursor(64)
        return resolveCapsMode(inputType, text, autoCapEnabled) != 0
    }

    /**
     * Evaluates capitalization mode from [inputType] flags and [textBeforeCursor].
     * Returns a bitmask of matching modes ([CAP_MODE_CHARACTERS], [CAP_MODE_WORDS], [CAP_MODE_SENTENCES]),
     * or 0 if capitalization should not occur.
     */
    fun resolveCapsMode(
        inputType: Int?,
        textBeforeCursor: CharSequence?,
        autoCapEnabled: Boolean = true,
    ): Int {
        if (!autoCapEnabled || inputType == null) return 0
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return 0

        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation in EXCLUDED_VARIATIONS) return 0

        var reqModes = 0
        if (inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) {
            reqModes = reqModes or CAP_MODE_CHARACTERS
        }
        if (inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0) {
            reqModes = reqModes or CAP_MODE_WORDS
        }
        if (inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0) {
            reqModes = reqModes or CAP_MODE_SENTENCES
        }
        // The keyboard setting supplies sentence caps for ordinary text fields even
        // when the host omits capitalization flags. Explicit words/all-caps win.
        if (reqModes == 0) reqModes = CAP_MODE_SENTENCES

        val cs = textBeforeCursor ?: ""
        return getCapsMode(cs, cs.length, reqModes)
    }

    /**
     * Determines active caps modes in [cs] at character offset [off] for requested modes [reqModes].
     * Matches Android platform getCapsMode semantics and empirical Gboard emulator validation.
     */
    fun getCapsMode(cs: CharSequence?, off: Int, reqModes: Int): Int {
        if (off < 0) return 0
        var mode = 0

        if (reqModes and CAP_MODE_CHARACTERS != 0) {
            mode = mode or CAP_MODE_CHARACTERS
        }
        if (reqModes and (CAP_MODE_WORDS or CAP_MODE_SENTENCES) == 0) {
            return mode
        }

        if (cs == null || off == 0) {
            if (reqModes and CAP_MODE_WORDS != 0) mode = mode or CAP_MODE_WORDS
            if (reqModes and CAP_MODE_SENTENCES != 0) mode = mode or CAP_MODE_SENTENCES
            return mode
        }

        // 1. Back over allowed opening punctuation / quotes
        var i = off
        while (i > 0) {
            val c = cs[i - 1]
            if (c != '"' && c != '\'' && c != '“' && c != '‘' &&
                Character.getType(c) != Character.START_PUNCTUATION.toInt()
            ) {
                break
            }
            i--
        }

        // 2. Start of paragraph, with optional whitespace
        var j = i
        while (j > 0 && (cs[j - 1] == ' ' || cs[j - 1] == '\t')) {
            j--
        }
        if (j == 0 || cs[j - 1] == '\n' || cs[j - 1] == '\r') {
            if (reqModes and CAP_MODE_WORDS != 0) mode = mode or CAP_MODE_WORDS
            if (i == off && reqModes and CAP_MODE_SENTENCES != 0) mode = mode or CAP_MODE_SENTENCES
            return mode
        }

        // 3. If only CAP_MODE_WORDS requested
        if (reqModes and CAP_MODE_SENTENCES == 0) {
            if (i != j) mode = mode or CAP_MODE_WORDS
            return mode
        }

        // 4. For CAP_MODE_SENTENCES, there must be whitespace if not start of paragraph
        if (i == j) {
            return mode
        }

        // 5. Back over allowed closing/opening punctuation / quotes before whitespace
        while (j > 0) {
            val c = cs[j - 1]
            if (c != '"' && c != '\'' && c != '”' && c != '’' && c != '“' && c != '‘' &&
                Character.getType(c) != Character.END_PUNCTUATION.toInt() &&
                Character.getType(c) != Character.START_PUNCTUATION.toInt()
            ) {
                break
            }
            j--
        }
        while (j > 0 && (cs[j - 1] == ' ' || cs[j - 1] == '\t')) {
            j--
        }

        if (j > 0) {
            val c = cs[j - 1]
            if (c == '.' || c == '?' || c == '!') {
                if (c == '.') {
                    // Ellipsis (...) is a valid sentence ending
                    val isEllipsis = j >= 3 && cs[j - 2] == '.' && cs[j - 3] == '.'
                    if (!isEllipsis) {
                        for (k in j - 2 downTo 0) {
                            val prev = cs[k]
                            if (prev == '.') {
                                // Abbreviations with multiple periods (e.g., U.S., e.g., i.e.)
                                return mode
                            }
                            if (prev.isDigit()) {
                                // Decimal numbers (e.g., 3.14, 2.0, $12.50)
                                return mode
                            }
                            if (!prev.isLetter()) {
                                break
                            }
                        }
                    }
                }
                mode = mode or CAP_MODE_SENTENCES
            }
        }

        return mode
    }
}
