package com.madeinhk.cantonesevoicekeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLayoutInferenceTest {
    private fun ime(vararg subtypes: KeyboardLayoutInference.SubtypeInfo) =
        KeyboardLayoutInference.ImeInfo(subtypes = subtypes.toList())

    @Test fun quickUserStartsWithQuickOnlyEvenWithEnglishAndHandwritingEnabledElsewhere() {
        val existing = ime(
            KeyboardLayoutInference.SubtypeInfo(extraValue = "KeyboardLayoutType=quick,AsciiCapable"),
            KeyboardLayoutInference.SubtypeInfo(locale = "en_US", mode = "keyboard"),
            KeyboardLayoutInference.SubtypeInfo(mode = "handwriting"),
        )
        assertEquals(setOf(KeyboardSettings.LAYOUT_QUICK), KeyboardLayoutInference.inferLayouts(listOf(existing)))
    }

    @Test fun noExistingKeyboardsDefaultsToHandwriting() {
        assertEquals(setOf(KeyboardSettings.LAYOUT_HANDWRITING), KeyboardLayoutInference.inferLayouts(emptyList()))
    }

    @Test fun englishKeyboardDoesNotEnableEnglishByDefault() {
        val existing = ime(KeyboardLayoutInference.SubtypeInfo(locale = "en_US", languageTag = "en-US", mode = "keyboard"))
        assertEquals(setOf(KeyboardSettings.LAYOUT_HANDWRITING), KeyboardLayoutInference.inferLayouts(listOf(existing)))
    }

    @Test fun cantoneseLocaleAndFullCangjieDoNotImplyQuick() {
        val existing = ime(
            KeyboardLayoutInference.SubtypeInfo(locale = "zh_HK", languageTag = "yue-Hant-HK", mode = "handwriting"),
            KeyboardLayoutInference.SubtypeInfo(extraValue = "KeyboardLayoutType=cangjie", label = "倉頡"),
        )
        assertEquals(setOf(KeyboardSettings.LAYOUT_HANDWRITING), KeyboardLayoutInference.inferLayouts(listOf(existing)))
    }

    @Test fun quickCanBeIdentifiedByLocalizedSubtypeLabelOrLayoutMetadata() {
        for (subtype in listOf(
            KeyboardLayoutInference.SubtypeInfo(label = "繁體中文（速成）"),
            KeyboardLayoutInference.SubtypeInfo(label = "Chinese (Quick)"),
            KeyboardLayoutInference.SubtypeInfo(extraValue = "KeyboardLayoutType=SuChEnG"),
        )) {
            assertEquals(setOf(KeyboardSettings.LAYOUT_QUICK), KeyboardLayoutInference.inferLayouts(listOf(ime(subtype))))
        }
    }

    @Test fun dedicatedQuickImeCanBeIdentifiedWithoutSubtypeMetadata() {
        for (existing in listOf(
            KeyboardLayoutInference.ImeInfo(label = "速成鍵盤"),
            KeyboardLayoutInference.ImeInfo(packageName = "example.quick.keyboard"),
        )) {
            assertEquals(setOf(KeyboardSettings.LAYOUT_QUICK), KeyboardLayoutInference.inferLayouts(listOf(existing)))
        }
    }
}
