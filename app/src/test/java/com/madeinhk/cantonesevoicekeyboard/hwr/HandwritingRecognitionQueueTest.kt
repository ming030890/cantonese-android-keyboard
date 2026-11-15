package com.madeinhk.cantonesevoicekeyboard.hwr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandwritingRecognitionQueueTest {
    @Test
    fun newerBitmapReplacesPendingWorkForTheSameCharacter() = runTest {
        val processed = mutableListOf<String>()
        val queue = HandwritingRecognitionQueue<String>(this, 200L) { _, request ->
            processed += request
        }

        queue.submit(characterId = 1L, request = "old")
        advanceTimeBy(100L)
        queue.submit(characterId = 1L, request = "latest")
        advanceTimeBy(199L)
        runCurrent()
        assertEquals(emptyList<String>(), processed)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("latest"), processed)
    }

    @Test
    fun newerBitmapCancelsActiveWorkForTheSameCharacter() = runTest {
        val oldStarted = CompletableDeferred<Unit>()
        val holdOld = CompletableDeferred<Unit>()
        val processed = mutableListOf<String>()
        val queue = HandwritingRecognitionQueue<String>(this, 200L) { _, request ->
            if (request == "old") {
                oldStarted.complete(Unit)
                holdOld.await()
            }
            processed += request
        }

        queue.submit(characterId = 1L, request = "old")
        advanceTimeBy(200L)
        runCurrent()
        oldStarted.await()

        queue.submit(characterId = 1L, request = "latest")
        advanceTimeBy(200L)
        runCurrent()

        assertEquals(listOf("latest"), processed)
    }

    @Test
    fun nextCharacterWaitsForPreviousCharacterToFinish() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val processed = mutableListOf<Long>()
        val queue = HandwritingRecognitionQueue<String>(this, 200L) { characterId, _ ->
            if (characterId == 1L) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            processed += characterId
        }

        queue.submit(characterId = 1L, request = "first")
        advanceTimeBy(200L)
        runCurrent()
        firstStarted.await()

        queue.submit(characterId = 2L, request = "second")
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(emptyList<Long>(), processed)

        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(listOf(1L, 2L), processed)
    }

    @Test
    fun immediateDispatcherWithZeroDelayExecutesAndCleansUpWithoutUninitializedJob() = runTest {
        val immediateScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val processed = mutableListOf<String>()
            val holdSecond = CompletableDeferred<Unit>()
            val queue = HandwritingRecognitionQueue<String>(immediateScope, 0L) { _, request ->
                if (request == "suspending") {
                    holdSecond.await()
                }
                processed += request
            }

            // Synchronous completion inline inside submit() on an immediate dispatcher.
            val syncJob = queue.submit(characterId = 1L, request = "sync")
            assertTrue(syncJob.isCompleted)
            assertEquals(listOf("sync"), processed)
            assertTrue(queue.jobsSnapshot().isEmpty())

            // Suspending work that enters processingMutex.withLock synchronously before suspending.
            val suspendingJob = queue.submit(characterId = 1L, request = "suspending")
            assertEquals(listOf(suspendingJob), queue.jobsSnapshot())
            holdSecond.complete(Unit)
            assertTrue(suspendingJob.isCompleted)
            assertEquals(listOf("sync", "suspending"), processed)
            assertTrue(queue.jobsSnapshot().isEmpty())
        } finally {
            immediateScope.cancel()
        }
    }
}
