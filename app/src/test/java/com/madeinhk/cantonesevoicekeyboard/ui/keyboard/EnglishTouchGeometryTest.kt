package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class EnglishTouchGeometryTest {
    private fun registry(scale: Float, uppercase: Boolean = false): KeyboardKeyRegistry {
        val registry = KeyboardKeyRegistry()
        registry.updateSurfaceSize((1000 * scale).toInt(), (400 * scale).toInt())
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
            letters.forEachIndexed { column, letter ->
                val key = registry.key("$letter", KeyData("$letter", KeyboardKeyKind.Text,
                    text = (if (uppercase) letter.uppercaseChar() else letter).toString()), "row$row")
                registry.mount(key)
                registry.updateVisualBounds(key, Rect(column*100f*scale, row*100f*scale,
                    (column*100f+80f)*scale, (row*100f+80f)*scale))
            }
        }
        return registry
    }

    @Test fun normalizedEvidenceIsIndependentOfSizeAndUppercase() {
        val normal = registry(1f).englishTouchEvidence(Offset(50f, 60f))!!
        val resized = registry(2f, uppercase = true).englishTouchEvidence(Offset(100f, 120f))!!
        assertEquals(normal.x, resized.x); assertEquals(normal.y, resized.y)
        assertArrayEquals(normal.centers, resized.centers, 0f)
        val q = ('q' - 'a') * 2
        assertEquals(0.5f, normal.centers[q]); assertEquals(0.5f, normal.centers[q + 1])
    }

    @Test fun unmountedLettersCannotSupplyStaleGeometry() {
        val registry = registry(1f)
        registry.unmount(registry.mountedKeysForDiagnostics().first())
        assertNull(registry.englishTouchEvidence(Offset.Zero))
    }
}
