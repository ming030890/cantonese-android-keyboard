package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import org.junit.Assert.*
import org.junit.Test

class SmartPunctuationTest {

    @Test
    fun testIsChineseChar() {
        // CJK Ideographs
        assertTrue(SmartPunctuation.isChineseChar('你'))
        assertTrue(SmartPunctuation.isChineseChar('好'))
        assertTrue(SmartPunctuation.isChineseChar('譚'))
        assertTrue(SmartPunctuation.isChineseChar('仔'))
        assertTrue(SmartPunctuation.isChineseChar('線'))
        assertTrue(SmartPunctuation.isChineseChar('食'))
        assertTrue(SmartPunctuation.isChineseChar('咗'))

        // Chinese Full-width Punctuation
        assertTrue(SmartPunctuation.isChineseChar('，'))
        assertTrue(SmartPunctuation.isChineseChar('。'))
        assertTrue(SmartPunctuation.isChineseChar('！'))
        assertTrue(SmartPunctuation.isChineseChar('？'))
        assertTrue(SmartPunctuation.isChineseChar('：'))
        assertTrue(SmartPunctuation.isChineseChar('；'))
        assertTrue(SmartPunctuation.isChineseChar('（'))
        assertTrue(SmartPunctuation.isChineseChar('）'))
        assertTrue(SmartPunctuation.isChineseChar('【'))
        assertTrue(SmartPunctuation.isChineseChar('】'))
        assertTrue(SmartPunctuation.isChineseChar('《'))
        assertTrue(SmartPunctuation.isChineseChar('》'))
        assertTrue(SmartPunctuation.isChineseChar('「'))
        assertTrue(SmartPunctuation.isChineseChar('」'))
        assertTrue(SmartPunctuation.isChineseChar('～'))
        assertTrue(SmartPunctuation.isChineseChar('、'))

        // Latin letters
        assertFalse(SmartPunctuation.isChineseChar('a'))
        assertFalse(SmartPunctuation.isChineseChar('Z'))
        assertFalse(SmartPunctuation.isChineseChar('m'))

        // Digits
        assertFalse(SmartPunctuation.isChineseChar('0'))
        assertFalse(SmartPunctuation.isChineseChar('9'))

        // ASCII Punctuation
        assertFalse(SmartPunctuation.isChineseChar(','))
        assertFalse(SmartPunctuation.isChineseChar('.'))
        assertFalse(SmartPunctuation.isChineseChar('?'))
        assertFalse(SmartPunctuation.isChineseChar('!'))
        assertFalse(SmartPunctuation.isChineseChar(':'))
        assertFalse(SmartPunctuation.isChineseChar(';'))
        assertFalse(SmartPunctuation.isChineseChar('('))
        assertFalse(SmartPunctuation.isChineseChar(')'))

        // Whitespace
        assertFalse(SmartPunctuation.isChineseChar(' '))
        assertFalse(SmartPunctuation.isChineseChar('\n'))
        assertFalse(SmartPunctuation.isChineseChar('\t'))
    }

    @Test
    fun testIsChineseContext() {
        // Empty context defaults by mode
        assertTrue(SmartPunctuation.isChineseContext("", KeyboardInputMode.Quick))
        assertTrue(SmartPunctuation.isChineseContext("", KeyboardInputMode.Handwriting))
        assertFalse(SmartPunctuation.isChineseContext("", KeyboardInputMode.AlphaNum))
        assertFalse(SmartPunctuation.isChineseContext("", KeyboardInputMode.Number))
        assertFalse(SmartPunctuation.isChineseContext(null, KeyboardInputMode.AlphaNum))

        // Whitespace only
        assertTrue(SmartPunctuation.isChineseContext("   ", KeyboardInputMode.Quick))
        assertFalse(SmartPunctuation.isChineseContext("   ", KeyboardInputMode.AlphaNum))
        assertTrue(SmartPunctuation.isChineseContext("hello\n", KeyboardInputMode.Quick))
        assertFalse(SmartPunctuation.isChineseContext("hello\n", KeyboardInputMode.AlphaNum))

        // Chinese text
        assertTrue(SmartPunctuation.isChineseContext("你好", KeyboardInputMode.Quick))
        assertTrue(SmartPunctuation.isChineseContext("你好", KeyboardInputMode.AlphaNum))
        assertTrue(SmartPunctuation.isChineseContext("你好 ", KeyboardInputMode.AlphaNum))
        assertTrue(SmartPunctuation.isChineseContext("I like 譚仔", KeyboardInputMode.AlphaNum))
        assertTrue(SmartPunctuation.isChineseContext("I like 譚仔 ", KeyboardInputMode.AlphaNum))
        assertTrue(SmartPunctuation.isChineseContext("你好，", KeyboardInputMode.AlphaNum))
        assertTrue(SmartPunctuation.isChineseContext("你好。", KeyboardInputMode.AlphaNum))

        // English / Alphanumeric text
        assertFalse(SmartPunctuation.isChineseContext("hello", KeyboardInputMode.Quick))
        assertFalse(SmartPunctuation.isChineseContext("hello ", KeyboardInputMode.Quick))
        assertFalse(SmartPunctuation.isChineseContext("今日食咗 lunch", KeyboardInputMode.Quick))
        assertFalse(SmartPunctuation.isChineseContext("今日食咗 lunch ", KeyboardInputMode.Quick))
        assertFalse(SmartPunctuation.isChineseContext("123", KeyboardInputMode.Quick))
        assertFalse(SmartPunctuation.isChineseContext("3.14", KeyboardInputMode.Quick))
        assertFalse(SmartPunctuation.isChineseContext("https://google.com/", KeyboardInputMode.Quick))
        assertFalse(SmartPunctuation.isChineseContext("test@example.com", KeyboardInputMode.Quick))

        // Overrides by composition states
        assertTrue(SmartPunctuation.isChineseContext("of", KeyboardInputMode.Quick, isComposingQuick = true))
        assertFalse(SmartPunctuation.isChineseContext("你好", KeyboardInputMode.Quick, isComposingEnglish = true))
    }

    @Test
    fun testConversionFunctions() {
        assertEquals("，", SmartPunctuation.toFullWidth(","))
        assertEquals("。", SmartPunctuation.toFullWidth("."))
        assertEquals("？", SmartPunctuation.toFullWidth("?"))
        assertEquals("！", SmartPunctuation.toFullWidth("!"))
        assertEquals("：", SmartPunctuation.toFullWidth(":"))
        assertEquals("；", SmartPunctuation.toFullWidth(";"))
        assertEquals("（", SmartPunctuation.toFullWidth("("))
        assertEquals("）", SmartPunctuation.toFullWidth(")"))
        assertEquals("【", SmartPunctuation.toFullWidth("["))
        assertEquals("】", SmartPunctuation.toFullWidth("]"))
        assertEquals("《", SmartPunctuation.toFullWidth("<"))
        assertEquals("》", SmartPunctuation.toFullWidth(">"))
        assertEquals("～", SmartPunctuation.toFullWidth("~"))

        // Quote pairing
        assertEquals("“", SmartPunctuation.toFullWidth("\"", textBeforeCursor = ""))
        assertEquals("”", SmartPunctuation.toFullWidth("\"", textBeforeCursor = "“你好"))
        assertEquals("“", SmartPunctuation.toFullWidth("\"", textBeforeCursor = "“你好”佢話"))

        assertEquals(",", SmartPunctuation.toAscii("，"))
        assertEquals(".", SmartPunctuation.toAscii("。"))
        assertEquals("?", SmartPunctuation.toAscii("？"))
        assertEquals("!", SmartPunctuation.toAscii("！"))
        assertEquals(":", SmartPunctuation.toAscii("："))
        assertEquals(";", SmartPunctuation.toAscii("；"))
        assertEquals("(", SmartPunctuation.toAscii("（"))
        assertEquals(")", SmartPunctuation.toAscii("）"))
        assertEquals("[", SmartPunctuation.toAscii("【"))
        assertEquals("]", SmartPunctuation.toAscii("】"))
        assertEquals("<", SmartPunctuation.toAscii("《"))
        assertEquals(">", SmartPunctuation.toAscii("》"))
        assertEquals("~", SmartPunctuation.toAscii("～"))
        assertEquals(",", SmartPunctuation.toAscii("、"))
    }
}
