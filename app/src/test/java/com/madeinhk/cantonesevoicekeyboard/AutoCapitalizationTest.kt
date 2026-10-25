package com.madeinhk.cantonesevoicekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class AutoCapitalizationTest {

    private data class TestCase(
        val id: String,
        val context: String,
        val inputType: Int,
        val expectedCap: Boolean,
        val desc: String,
    )

    private val empiricalCases = listOf(
        // 1. Start of text / paragraph
        TestCase("empty_start", "", 180225, true, "Empty editor start"),
        TestCase("spaces_only", "   ", 180225, true, "Spaces only at start"),
        TestCase("newline_start", "\n", 180225, true, "Single newline"),
        TestCase("newline_after_sentence", "Hello world.\n", 180225, true, "Newline after sentence"),
        TestCase("newline_after_words_no_period", "Hello world\n", 180225, true, "Newline without punctuation"),
        TestCase("newline_with_space", "Hello world\n  ", 180225, true, "Newline followed by indent spaces"),

        // 2. Sentence-ending punctuation
        TestCase("period_space", "Hello world. ", 180225, true, "Period + space"),
        TestCase("period_double_space", "Hello world.  ", 180225, true, "Period + double space"),
        TestCase("question_space", "How are you? ", 180225, true, "Question mark + space"),
        TestCase("question_double_space", "Really?  ", 180225, true, "Question mark + double space"),
        TestCase("exclamation_space", "Great job! ", 180225, true, "Exclamation mark + space"),
        TestCase("exclamation_double_space", "Awesome!  ", 180225, true, "Exclamation mark + double space"),

        // 3. Non-sentence-ending boundaries (should NOT capitalize)
        TestCase("mid_sentence_word_space", "Hello ", 180225, false, "Word + space"),
        TestCase("mid_sentence_multiple_words", "The quick brown fox ", 180225, false, "Words in sentence"),
        TestCase("comma_space", "First, ", 180225, false, "Comma + space"),
        TestCase("semicolon_space", "Item one; ", 180225, false, "Semicolon + space"),
        TestCase("colon_space", "Remember this: ", 180225, false, "Colon + space"),
        TestCase("hyphen_space", "One - ", 180225, false, "Hyphen + space"),

        // 4. Quotes and brackets at sentence start
        TestCase("quote_start", "\"", 180225, false, "Quote at field start"),
        TestCase("quote_after_period_space", "He said. \" ", 180225, true, "Quote after period + space"),
        TestCase("quote_closed_then_space", "He said, \"hello.\" ", 180225, true, "Closing quote after period + space"),
        TestCase("paren_after_period_space", "Sentence one. (", 180225, true, "Paren after period space"),

        // 5. Abbreviations and numbers
        TestCase("decimal_pi", "Pi is 3.14 ", 180225, false, "Decimal number followed by space"),
        TestCase("version_num", "Version 2.0 ", 180225, false, "Version number followed by space"),
        TestCase("currency_val", "Total is $12.50 ", 180225, false, "Currency decimal followed by space"),
        TestCase("abbrev_eg", "for example e.g. ", 180225, false, "Abbrev e.g. + space"),
        TestCase("abbrev_ie", "that is i.e. ", 180225, false, "Abbrev i.e. + space"),
        TestCase("abbrev_us", "living in the U.S. ", 180225, false, "Abbrev U.S. + space"),
        TestCase("ellipsis", "Wait... ", 180225, true, "Ellipsis + space"),
        TestCase("web_domain", "go to google.com ", 180225, false, "Domain name + space"),
        TestCase("email_addr", "mail user@test.com ", 180225, false, "Email address + space"),

        // 6. InputType flag variations
        TestCase("cap_characters_start", "", 4097, true, "CAP_CHARACTERS start"),
        TestCase("cap_characters_mid", "abc ", 4097, true, "CAP_CHARACTERS mid"),
        TestCase("cap_words_start", "", 8193, true, "CAP_WORDS start"),
        TestCase("cap_words_after_space", "hello ", 8193, true, "CAP_WORDS after word space"),
        TestCase("cap_words_mid_word", "hel", 8193, false, "CAP_WORDS mid word"),
        TestCase("plain_text_start", "", 1, false, "Plain text (no cap flags)"),
        TestCase("plain_text_period", "Hello. ", 1, false, "Plain text after period space"),

        // 7. Excluded variations
        TestCase("password_field", "", 129, false, "Password field"),
        TestCase("email_field", "", 33, false, "Email field"),
        TestCase("uri_field", "", 17, false, "URI field"),
    )

    @Test
    fun empiricalCasesMatchGboardParity() {
        for (c in empiricalCases) {
            val result = AutoCapitalization.resolveCapsMode(c.inputType, c.context)
            val capitalized = result != 0
            assertEquals("Failed case: ${c.id} (${c.desc})", c.expectedCap, capitalized)
        }
    }

    @Test
    fun shiftStateProperties() {
        assertFalse(ShiftState.Off.isUppercase)
        assertFalse(ShiftState.Off.isCapsLock)

        assertTrue(ShiftState.Shifted.isUppercase)
        assertFalse(ShiftState.Shifted.isCapsLock)

        assertTrue(ShiftState.CapsLock.isUppercase)
        assertTrue(ShiftState.CapsLock.isCapsLock)
    }

    @Test
    fun shouldCapitalizeHandlesEditorInfoFlags() {
        val editor = MemoryKeyboardEditor("Hello world. ")
        val infoSentences = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        assertTrue(AutoCapitalization.shouldCapitalize(editor, infoSentences, autoCapEnabled = true))
        assertFalse(AutoCapitalization.shouldCapitalize(editor, infoSentences, autoCapEnabled = false))

        val infoPassword = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertFalse(AutoCapitalization.shouldCapitalize(editor, infoPassword, autoCapEnabled = true))

        val infoNullEditorInfo = null
        assertTrue(AutoCapitalization.shouldCapitalize(editor, infoNullEditorInfo, autoCapEnabled = true))
    }

    @Test
    fun benchmarkAutoCapitalizationLatency() {
        val editor = MemoryKeyboardEditor("Hello world. ")
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        // Warmup
        repeat(5_000) {
            AutoCapitalization.shouldCapitalize(editor, info, autoCapEnabled = true, textBefore = "Hello world. ")
        }
        val iterations = 50_000
        val start = System.nanoTime()
        repeat(iterations) {
            AutoCapitalization.shouldCapitalize(editor, info, autoCapEnabled = true, textBefore = "Hello world. ")
        }
        val totalNanos = System.nanoTime() - start
        val nanosPerOp = totalNanos.toDouble() / iterations
        println("AutoCapitalization latency per call: ${String.format("%.2f", nanosPerOp)} ns (${String.format("%.4f", nanosPerOp / 1_000_000.0)} ms)")
        assertTrue("Latency per op should be under 10 microseconds (10,000 ns)", nanosPerOp < 10_000)
    }
}
