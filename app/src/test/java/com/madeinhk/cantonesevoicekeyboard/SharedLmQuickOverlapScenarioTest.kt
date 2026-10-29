package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate
import com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction
import com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine
import com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedLmQuickOverlapScenarioTest {
    @Test fun approvedSharedModelCorrectionSurvivesQuickCodeOverlapAndSupportsUndo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for ((mode, version) in listOf(KeyboardInputMode.AlphaNum to 3,
            KeyboardInputMode.Quick to 3, KeyboardInputMode.Quick to 5)) {
            for (approved in listOf(false, true)) {
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                // Real adapter, with the native result isolated from platform timing.
                // The baseline native probe approves ir -> it after "try ".
                val english = SharedLmEnglishEngine({ 1L }, complete = { _, _, _ -> emptyList() },
                    rank = { _, _, _, words, _ -> words }, suggest = { _, left, typed ->
                    assertEquals("try ", left)
                    EnglishPrediction(candidates = listOf(EnglishCandidate("it", -3.87, 2.0, 1, 0)),
                        correction = "it".takeIf { approved && typed == "ir" },
                        known = true, replaceBefore = typed.length, reason = "shared_lm_spelling")
                })
                try {
                    KeyboardScenario(initialText = "try ", initialMode = mode, scope = scope,
                        lookupDispatcher = dispatcher, englishDispatcher = dispatcher,
                        englishEngine = english, clipboardText = null).use { s ->
                        s.quickVersion = version
                        s.engine.lookup = { _, code, _, _ ->
                            if (code == "ir") listOf(QuickCandidate("台", 0)) else emptyList()
                        }
                        s.start(); s.type("ir"); runCurrent()
                        val candidates = if (mode == KeyboardInputMode.Quick) s.snapshot().quickCandidates
                            else s.snapshot().englishCandidates
                        assertTrue(candidates.any { it.text == "it" })
                        assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                        if (mode == KeyboardInputMode.Quick) assertTrue(candidates.any { it.text == "台" })
                        s.press(KeyboardKeyKind.Space); runCurrent()
                        assertEquals("$mode/$version approved=$approved", "try ${if (approved) "it" else "ir"} ", s.editor.text)
                        assertEquals("", s.editor.composing)
                        if (approved) {
                            s.press(KeyboardKeyKind.Delete); runCurrent()
                            assertEquals("try ", s.editor.text)
                            assertEquals("ir", s.editor.composing)
                            s.press(KeyboardKeyKind.Space); runCurrent()
                            assertEquals("try ir ", s.editor.text)
                        }
                    }
                } finally { scope.cancel() }
            }
        }
    }

    @Test fun explicitChineseSelectionStillWinsOverApprovedEnglishCorrection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (version in listOf(3, 5)) {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val english = SharedLmEnglishEngine({ 1L }, complete = { _, _, _ -> emptyList() },
                rank = { _, _, _, words, _ -> words }, suggest = { _, _, typed ->
                EnglishPrediction(candidates = listOf(EnglishCandidate("it", -3.87, 2.0, 1, 0)),
                    correction = "it".takeIf { typed == "ir" }, replaceBefore = typed.length)
            })
            try {
                KeyboardScenario(initialText = "try ", scope = scope, lookupDispatcher = dispatcher,
                    englishDispatcher = dispatcher, englishEngine = english, clipboardText = null).use { s ->
                    s.quickVersion = version
                    s.engine.lookup = { _, _, _, _ -> listOf(QuickCandidate("台", 0)) }
                    s.start(); s.type("ir"); runCurrent(); s.choose("台"); runCurrent()
                    assertEquals("try 台", s.editor.text)
                    assertEquals("", s.editor.composing)
                }
            } finally { scope.cancel() }
        }
    }
}
