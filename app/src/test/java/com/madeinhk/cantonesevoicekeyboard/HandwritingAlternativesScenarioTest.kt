package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Test

class HandwritingAlternativesScenarioTest {
    @Test fun nativeRecognitionAlternativesKeepTheirOrderAndSelectionReplacesCompositionOnce() {
        KeyboardScenario(initialMode = KeyboardInputMode.Handwriting, clipboardText = null).use { s ->
            s.start(); s.controller.onHandwritingStrokeStarted(true)
            s.editor.setComposingText("不")
            val words = KeyboardScenarios.capturedHandwritingAlternatives
            s.handwriting.candidates.value = words.mapIndexed { i, text -> HandwritingCandidate(text, i) }
            assertEquals(words, s.snapshot().handwritingCandidates.map { it.text })
            assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
            s.choose("下")
            assertEquals("下", s.editor.text); assertEquals("", s.editor.composing)
            assertEquals(listOf("下"), s.editor.commits)
        }
    }
}
