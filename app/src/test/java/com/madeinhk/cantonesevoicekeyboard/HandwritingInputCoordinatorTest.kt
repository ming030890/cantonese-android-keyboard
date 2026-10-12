package com.madeinhk.cantonesevoicekeyboard

import android.view.inputmethod.InputConnection
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class HandwritingInputCoordinatorTest {

    @Test
    fun predictionsShowWhenIdleAndRefreshAfterSelection() = runTest {
        var before = "我想"
        val committed = mutableListOf<String>()
        var predictionRequests = 0

        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getTextBeforeCursor" -> before
                "getTextAfterCursor" -> ""
                "commitText" -> {
                    val text = args!![0].toString()
                    committed += text
                    before += text
                    true
                }
                "finishComposingText" -> true
                else -> true
            }
        } as InputConnection

        val editor = ImeEditor({ connection }, { null })
        val coordinator = HandwritingInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { 1L },
            isInputViewVisible = { true },
            stopVoiceInput = {},
            predictionProvider = { left, right, limit ->
                predictionRequests++
                assertEquals(before, left)
                assertEquals(10, limit)
                listOf(HandwritingCandidate("食", 0), HandwritingCandidate("去", 1))
            },
            coroutineScope = backgroundScope,
            synchronousPredictionsForTest = true,
        )

        coordinator.refreshPredictions()
        assertEquals(2, coordinator.candidates.value.size)
        assertEquals("食", coordinator.candidates.value[0].text)
        assertFalse(coordinator.hasActiveInput())

        coordinator.selectCandidate("食")
        assertEquals(listOf("食"), committed)
        assertEquals("我想食", before)
        assertEquals(2, predictionRequests)
        assertEquals(2, coordinator.candidates.value.size)

        coordinator.discard()
        assertTrue(coordinator.candidates.value.isEmpty())
        assertFalse(coordinator.hasActiveInput())
        coordinator.destroy()
    }

    @Test
    fun wordBoundarySpacingForEnglishPredictions() = runTest {
        var before = "hello"
        var after = "friend"
        val committed = mutableListOf<String>()

        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getTextBeforeCursor" -> before
                "getTextAfterCursor" -> after
                "commitText" -> {
                    val text = args!![0].toString()
                    committed += text
                    before += text
                    true
                }
                else -> true
            }
        } as InputConnection

        val editor = ImeEditor({ connection }, { null })
        val coordinator = HandwritingInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { 1L },
            isInputViewVisible = { true },
            stopVoiceInput = {},
            predictionProvider = { _, _, _ ->
                listOf(HandwritingCandidate("my", 0))
            },
            coroutineScope = backgroundScope,
            synchronousPredictionsForTest = true,
        )

        coordinator.refreshPredictions()
        coordinator.selectCandidate("my")
        assertEquals(" my ", committed.single())
        coordinator.destroy()
    }

    @Test
    fun onStrokeStartedClearsPredictionsImmediately() = runTest {
        var voiceStopped = false
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getTextBeforeCursor" -> "你"
                "getTextAfterCursor" -> ""
                else -> true
            }
        } as InputConnection

        val editor = ImeEditor({ connection }, { null })
        val coordinator = HandwritingInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { 1L },
            isInputViewVisible = { true },
            stopVoiceInput = { voiceStopped = true },
            predictionProvider = { _, _, _ ->
                listOf(HandwritingCandidate("好", 0))
            },
            coroutineScope = backgroundScope,
            synchronousPredictionsForTest = true,
        )

        coordinator.refreshPredictions()
        assertEquals(1, coordinator.candidates.value.size)
        assertFalse(coordinator.hasActiveInput())

        coordinator.onStrokeStarted(startsNewCharacter = true)
        assertTrue(voiceStopped)
        assertTrue(coordinator.candidates.value.isEmpty())
        assertFalse(coordinator.hasActiveInput())
        coordinator.destroy()
    }

    @Test
    fun selectRecognitionCandidateCommitsAndRefreshesPredictions() = runTest {
        var before = ""
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getTextBeforeCursor" -> before
                "getTextAfterCursor" -> ""
                "setComposingText" -> true
                "finishComposingText" -> {
                    before = "好"
                    true
                }
                else -> true
            }
        } as InputConnection

        val editor = ImeEditor({ connection }, { null })
        val coordinator = HandwritingInputCoordinator(
            context = null,
            editor = editor,
            currentSession = { 1L },
            isInputViewVisible = { true },
            stopVoiceInput = {},
            predictionProvider = { left, _, _ ->
                if (left == "好") listOf(HandwritingCandidate("呀", 0)) else emptyList()
            },
            coroutineScope = backgroundScope,
            synchronousPredictionsForTest = true,
        )

        coordinator.selectCandidate("好")
        assertEquals(1, coordinator.candidates.value.size)
        assertEquals("呀", coordinator.candidates.value[0].text)
        coordinator.destroy()
    }
}
