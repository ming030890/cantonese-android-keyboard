package com.madeinhk.cantonesevoicekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSettingsTest {

    @Test
    fun defaultLayoutIsHandwritingOnly() {
        assertEquals(setOf(KeyboardSettings.LAYOUT_HANDWRITING), KeyboardSettings.DEFAULT_LAYOUTS)
    }

    @Test
    fun multipleKeyboardsCanBeChosen() {
        var layouts = setOf(KeyboardSettings.LAYOUT_ENGLISH)
        layouts = KeyboardSettings.computeToggle(layouts, KeyboardSettings.LAYOUT_HANDWRITING)

        assertTrue(layouts.contains(KeyboardSettings.LAYOUT_ENGLISH))
        assertTrue(layouts.contains(KeyboardSettings.LAYOUT_HANDWRITING))
        assertEquals(2, layouts.size)
    }

    @Test
    fun deselectingOneLayoutWhenBothAreSelectedLeavesOtherActive() {
        val both = setOf(KeyboardSettings.LAYOUT_ENGLISH, KeyboardSettings.LAYOUT_HANDWRITING)

        val onlyEnglish = KeyboardSettings.computeToggle(both, KeyboardSettings.LAYOUT_HANDWRITING)
        assertEquals(setOf(KeyboardSettings.LAYOUT_ENGLISH), onlyEnglish)

        val onlyHandwriting = KeyboardSettings.computeToggle(both, KeyboardSettings.LAYOUT_ENGLISH)
        assertEquals(setOf(KeyboardSettings.LAYOUT_HANDWRITING), onlyHandwriting)
    }

    @Test
    fun cannotDeselectLastRemainingLayout() {
        val onlyEnglish = setOf(KeyboardSettings.LAYOUT_ENGLISH)
        val resultEnglish = KeyboardSettings.computeToggle(onlyEnglish, KeyboardSettings.LAYOUT_ENGLISH)
        assertEquals(setOf(KeyboardSettings.LAYOUT_ENGLISH), resultEnglish)

        val onlyHandwriting = setOf(KeyboardSettings.LAYOUT_HANDWRITING)
        val resultHandwriting = KeyboardSettings.computeToggle(onlyHandwriting, KeyboardSettings.LAYOUT_HANDWRITING)
        assertEquals(setOf(KeyboardSettings.LAYOUT_HANDWRITING), resultHandwriting)
    }

}
