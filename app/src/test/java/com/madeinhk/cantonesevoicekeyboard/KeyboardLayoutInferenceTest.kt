package com.madeinhk.cantonesevoicekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class KeyboardLayoutInferenceTest {

    @Test
    fun inferQuickAndEnglishForGboardWithQuick() {
        val gboard = KeyboardLayoutInference.ImeInfo(
            packageName = "com.google.android.inputmethod.latin",
            id = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
            subtypes = listOf(
                KeyboardLayoutInference.SubtypeInfo(
                    locale = "zh_HK",
                    languageTag = "yue-Hant-HK",
                    mode = "keyboard",
                    extraValue = "KeyboardLayoutType=quick,AsciiCapable"
                ),
                KeyboardLayoutInference.SubtypeInfo(
                    locale = "en_US",
                    languageTag = "en-US",
                    mode = "keyboard",
                    extraValue = "AsciiCapable"
                )
            )
        )

        val inferred = KeyboardLayoutInference.inferLayouts(
            listOf(gboard),
            listOf(Locale.US)
        )

        assertTrue(inferred.contains(KeyboardSettings.LAYOUT_QUICK))
        assertTrue(inferred.contains(KeyboardSettings.LAYOUT_ENGLISH))
    }

    @Test
    fun inferHandwritingWhenExistingImeHasHandwriting() {
        val ime = KeyboardLayoutInference.ImeInfo(
            packageName = "com.google.android.inputmethod.latin",
            id = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
            subtypes = listOf(
                KeyboardLayoutInference.SubtypeInfo(
                    locale = "zh_HK",
                    languageTag = "yue-Hant-HK",
                    mode = "handwriting",
                    extraValue = "KeyboardLayoutType=handwriting"
                )
            )
        )

        val inferred = KeyboardLayoutInference.inferLayouts(
            listOf(ime),
            listOf(Locale.US)
        )

        assertTrue(inferred.contains(KeyboardSettings.LAYOUT_HANDWRITING))
        assertTrue(inferred.contains(KeyboardSettings.LAYOUT_QUICK))
    }

    @Test
    fun inferQuickAndEnglishForHkSystemLocale() {
        val inferred = KeyboardLayoutInference.inferLayouts(
            emptyList(),
            listOf(Locale("zh", "HK"))
        )

        assertTrue(inferred.contains(KeyboardSettings.LAYOUT_QUICK))
        assertTrue(inferred.contains(KeyboardSettings.LAYOUT_ENGLISH))
    }

    @Test
    fun inferEnglishAndQuickFallbackForEnglishUser() {
        val englishIme = KeyboardLayoutInference.ImeInfo(
            packageName = "com.android.inputmethod.latin",
            id = "com.android.inputmethod.latin/.LatinIME",
            subtypes = listOf(
                KeyboardLayoutInference.SubtypeInfo(
                    locale = "en_US",
                    languageTag = "en-US",
                    mode = "keyboard",
                    extraValue = "AsciiCapable"
                )
            )
        )

        val inferred = KeyboardLayoutInference.inferLayouts(
            listOf(englishIme),
            listOf(Locale.US)
        )

        assertTrue(inferred.contains(KeyboardSettings.LAYOUT_ENGLISH))
    }
}
