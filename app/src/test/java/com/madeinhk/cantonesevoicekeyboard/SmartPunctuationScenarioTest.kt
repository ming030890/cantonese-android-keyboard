package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Test

class SmartPunctuationScenarioTest {
    @Test fun literalQuickCodesUseAsciiPunctuationWithOrWithoutAutocorrect() {
        for (backend in listOf(false, true)) for (enabled in listOf(false, true)) {
            for (code in listOf("df", "of", "DF")) for (punctuation in listOf(".", "。", ",", "，")) {
                KeyboardScenario(initialText = "你好", englishEngine = if (backend) ScenarioEnglishEngine() else null).use { s ->
                    if (!enabled) s.autocorrectLayouts = emptySet()
                    s.start(); s.type(code)
                    assertEquals(false, s.snapshot().chinesePunctuation)
                    assertEquals(code, s.editor.composing)
                    s.press(KeyboardKeyKind.Text, punctuation)
                    assertEquals("你好$code${SmartPunctuation.toAscii(punctuation)}", s.editor.text)
                    assertEquals("", s.editor.composing)
                    assertEquals(false, s.snapshot().chinesePunctuation)
                }
            }
        }
    }

    @Test fun chineseSelectionAndCursorChangesUpdatePunctuationWidth() {
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum)) {
            KeyboardScenario(initialText = "你好", initialMode = mode).use { s ->
                s.start()
                assertEquals(true, s.snapshot().chinesePunctuation)
                s.press(KeyboardKeyKind.Text, ".")
                assertEquals("你好。", s.editor.text)
                s.editor.text = "hello"; s.controller.onSelectionChanged()
                assertEquals(false, s.snapshot().chinesePunctuation)
                s.press(KeyboardKeyKind.Text, "。")
                assertEquals("hello.", s.editor.text)
            }
        }
        KeyboardScenario().use { s ->
            s.start(); s.type("of")
            assertEquals(false, s.snapshot().chinesePunctuation)
            s.choose("你")
            assertEquals(true, s.snapshot().chinesePunctuation)
            s.press(KeyboardKeyKind.Text, ".")
            assertEquals("你。", s.editor.text)
        }
    }

    @Test fun deletingLatinCodeRestoresChineseWidthAndSymbolsKeepEnglishWidth() {
        KeyboardScenario(initialText = "你").use { s ->
            s.start(); s.type("df")
            s.press(KeyboardKeyKind.Delete); s.press(KeyboardKeyKind.Delete)
            assertEquals(true, s.snapshot().chinesePunctuation)
            s.type("df"); s.controller.onVisiblePanelChanged(KeyboardInputMode.Number)
            assertEquals(false, s.snapshot().chinesePunctuation)
            s.press(KeyboardKeyKind.Text, "。")
            assertEquals("你df.", s.editor.text)
        }
    }
}
