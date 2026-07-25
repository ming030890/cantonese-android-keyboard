package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandwritingBitmapTest {
    @Test
    fun rasterizesAStrokeAsWhiteBackgroundWithDarkInk() {
        val bitmap = handwritingBitmap(
            strokes = listOf(
                listOf(Offset(4f, 4f), Offset(28f, 28f)),
            ),
            size = IntSize(32, 32),
        )

        assertEquals(32, bitmap.width)
        assertEquals(32, bitmap.height)
        assertEquals(32 * 32, bitmap.pixels.size)
        assertTrue(bitmap.pixels.any { (it.toInt() and 0xff) < 255 })
        assertTrue(bitmap.pixels.any { (it.toInt() and 0xff) == 255 })
    }

    @Test
    fun rasterizesASinglePointInsteadOfDroppingIt() {
        val bitmap = handwritingBitmap(
            strokes = listOf(listOf(Offset(16f, 16f))),
            size = IntSize(32, 32),
        )

        assertTrue(bitmap.pixels.any { (it.toInt() and 0xff) < 255 })
    }

    @Test
    fun displayAndInferenceUseTheSameStrokeWidthRule() {
        assertEquals(7f, handwritingStrokeWidthPx(400f, 200f))
        assertEquals(5f, handwritingStrokeWidthPx(100f, 100f))
    }
}
