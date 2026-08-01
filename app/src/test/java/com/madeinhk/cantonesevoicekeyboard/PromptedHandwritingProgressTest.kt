package com.madeinhk.cantonesevoicekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptedHandwritingProgressTest {
    @Test
    fun shuffledOrderIsStableAndComplete() {
        val labels = listOf("人", "口", "手", "心", "日")
        val first = PromptedHandwritingProgress.shuffledClassIds(labels, "writer", "inventory")
        val second = PromptedHandwritingProgress.shuffledClassIds(labels, "writer", "inventory")
        val otherWriter = PromptedHandwritingProgress.shuffledClassIds(labels, "other", "inventory")

        assertEquals(first, second)
        assertEquals(labels.indices.toSet(), first.toSet())
        assertNotEquals(first, otherWriter)
    }

    @Test
    fun preferredCharactersComeFirstAndTheRemainderStaysComplete() {
        val labels = listOf("人", "口", "手", "心", "日")
        val order = PromptedHandwritingProgress.shuffledClassIds(
            labels,
            "writer",
            "inventory",
            preferredCharacters = listOf("心", "missing", "心", "人"),
        )

        assertEquals(listOf(3, 0), order.take(2))
        assertEquals(labels.indices.toSet(), order.toSet())
    }

    @Test
    fun nextCharacterNeverRepeatsAndDeferredCharactersFallBack() {
        val order = listOf(3, 1, 2, 0)
        assertEquals(2, PromptedHandwritingProgress.nextClassId(order, setOf(3, 1)))
        assertEquals(0, PromptedHandwritingProgress.nextClassId(order, setOf(3, 1), setOf(2)))
        assertEquals(2, PromptedHandwritingProgress.nextClassId(order, setOf(3, 1, 0), setOf(2)))
        assertNull(PromptedHandwritingProgress.nextClassId(order, order.toSet()))
    }

    @Test
    fun inventoryHashChangesWithLabels() {
        val first = PromptedHandwritingProgress.inventoryHash("人\n口\n".toByteArray())
        val second = PromptedHandwritingProgress.inventoryHash("人\n手\n".toByteArray())
        assertEquals(64, first.length)
        assertTrue(first != second)
    }
}
