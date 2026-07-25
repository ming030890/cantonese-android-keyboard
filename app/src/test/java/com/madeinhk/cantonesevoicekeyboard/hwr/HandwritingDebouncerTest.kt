package com.madeinhk.cantonesevoicekeyboard.hwr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandwritingDebouncerTest {
    @Test
    fun onlyTheLatestFrameRunsAfterTheIdleDelay() = runTest {
        val recognized = mutableListOf<Int>()
        val debouncer = HandwritingDebouncer<Int>(this, 100L) { recognized += it }

        debouncer.submit(1)
        advanceTimeBy(60L)
        debouncer.submit(2)
        advanceTimeBy(99L)
        runCurrent()
        assertEquals(emptyList<Int>(), recognized)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(2), recognized)
    }

    @Test
    fun cancelPreventsAPendingFrameFromRunning() = runTest {
        val recognized = mutableListOf<Int>()
        val debouncer = HandwritingDebouncer<Int>(this, 100L) { recognized += it }

        debouncer.submit(1)
        debouncer.cancel()
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(emptyList<Int>(), recognized)
    }
}
