package com.madeinhk.cantonesevoicekeyboard

import androidx.compose.ui.unit.dp
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Acceptance IDs and pending policies are defined in KEYBOARD_QA_SPEC.md. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardQaContractTest {
    @Test
    fun cb01_clipboardAppearsBeforeInputOnEveryTextLayoutWithExistingEditorText() {
        for (mode in KeyboardInputMode.entries) {
            KeyboardScenario(initialText = "already written", initialMode = mode).use { s ->
                s.start()
                assertEquals("$mode", KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
                assertEquals("already written", s.editor.text)
                assertEquals("", s.editor.composing)
                assertEquals("copied text", s.snapshot().clipboard?.preview)
            }
        }
    }

    @Test
    fun cb02_inputDismissesClipboardAcrossLayoutsAndCandidateSelection() {
        for ((mode, text) in listOf(KeyboardInputMode.Quick to "o", KeyboardInputMode.AlphaNum to "A",
                                   KeyboardInputMode.Number to "9", KeyboardInputMode.Handwriting to "。")) {
            KeyboardScenario(initialMode = mode).use { s ->
                s.start()
                s.type(text)
                assertNull("$mode", s.snapshot().clipboard)
            }
        }
        KeyboardScenario().use { s ->
            s.start()
            s.choose("我")
            assertNull(s.snapshot().clipboard)
            assertEquals("我", s.editor.text)
        }
    }

    @Test
    fun cb03_cursorMovementAndTemporaryVoiceStatusDoNotDismissClipboardEligibility() = KeyboardScenario().use { s ->
        s.start()
        s.controller.onSelectionChanged()
        assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
        s.voice.state.value = KeyboardUiState.LoadingModel
        assertEquals(KeyboardToolbarContent.VoiceStatus, s.snapshot().toolbar())
        assertNotNull(s.snapshot().clipboard)
        s.voice.state.value = KeyboardUiState.Idle
        assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
        assertEquals("", s.editor.text)
    }

    @Test
    fun cb04_cancelledLetterDoesNotCommitComposeOrHideClipboard() = KeyboardScenario().use { s ->
        s.start()
        s.keyDown(KeyboardKeyKind.Text, "o")
        assertNotNull(s.snapshot().clipboard)
        s.keyCancel(KeyboardKeyKind.Text, "o")
        assertEquals("", s.editor.text)
        assertEquals("", s.editor.composing)
        assertTrue(s.editor.commits.isEmpty())
        assertNotNull(s.snapshot().clipboard)
    }

    @Test
    fun cb05_absentClipboardAllowsIdlePredictions() = KeyboardScenario(clipboardText = null).use { s ->
        s.start()
        assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
        assertEquals(listOf("我", "你"), s.snapshot().quickCandidates.map { it.text })
        s.copy("")
        assertNull(s.snapshot().clipboard)
        assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
    }

    @Test
    fun cb06_sensitiveClipboardUsesGenericPreviewButPastesRealTextOnce() = KeyboardScenario().use { s ->
        s.start()
        s.copy("private value", sensitive = true)
        assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
        assertNull(s.snapshot().clipboard!!.preview)
        assertTrue(s.snapshot().clipboard!!.isSensitive)
        s.press(KeyboardKeyKind.Clipboard)
        assertEquals(listOf("private value"), s.editor.commits)
        assertNull(s.snapshot().clipboard)
    }

    @Test
    fun cb08_newClipboardBeforeTypingReplacesPreviewWithoutEditingText() = KeyboardScenario().use { s ->
        s.start()
        val firstToken = s.snapshot().clipboard!!.token
        s.copy("replacement")
        assertEquals("replacement", s.snapshot().clipboard!!.preview)
        assertNotEquals(firstToken, s.snapshot().clipboard!!.token)
        assertTrue(s.editor.commits.isEmpty())
        s.copy(null)
        assertNull(s.snapshot().clipboard)
    }

    @Test
    fun qk01_codeComposesWithoutCommittingAndLookupExcludesOnlyItsSuffix() = KeyboardScenario(initialText = "of你", clipboardText = null).use { s ->
        s.editor.right = "呀"
        s.start()
        s.type("o")
        assertEquals("o", s.editor.composing)
        assertEquals("of你", s.editor.text)
        s.type("f")
        assertEquals("of", s.editor.composing)
        assertTrue(s.editor.commits.isEmpty())
        assertEquals(listOf("code", "3", "of", "of你", "呀"), s.engine.requests.last())
        assertEquals(listOf("你", "係"), s.snapshot().quickCandidates.map { it.text })
    }

    @Test
    fun qk02_secondCandidateCommitsExactlyChosenTextAndRefreshesContext() = KeyboardScenario().use { s ->
        s.engine.predict = { left, _, _ -> if (left == "係") listOf(QuickCandidate("呀", 0)) else emptyList() }
        s.start()
        s.type("of")
        s.choose("係")
        assertEquals(listOf("係"), s.editor.commits)
        assertEquals("係", s.editor.text)
        assertEquals("", s.editor.composing)
        assertEquals(listOf("prediction", "係", ""), s.engine.requests.last())
        assertEquals(listOf("呀"), s.snapshot().quickCandidates.map { it.text })
    }

    @Test
    fun qk03_predictionSelectionCanChainWithoutRestoringTheQuickCode() = KeyboardScenario().use { s ->
        s.start()
        s.type("of")
        s.choose("你")
        s.choose("好")
        assertEquals(listOf("你", "好"), s.editor.commits)
        assertEquals("你好", s.editor.text)
        assertEquals("", s.quick.getComposingText())
        assertEquals(listOf("嗎"), s.snapshot().quickCandidates.map { it.text })
    }

    @Test
    fun qk04_selectionNotificationKeepsActiveCodeAndCandidates() = KeyboardScenario().use { s ->
        s.start()
        s.type("of")
        val candidates = s.snapshot().quickCandidates
        val requests = s.engine.requests.size
        s.controller.onSelectionChanged()
        assertEquals("of", s.editor.composing)
        assertEquals(candidates, s.snapshot().quickCandidates)
        assertEquals(requests, s.engine.requests.size)
        assertEquals("", s.editor.text)
    }

    @Test
    fun qk05_backspaceEditsCodeBeforePreviouslyCommittedText() = KeyboardScenario(initialText = "我").use { s ->
        s.start()
        s.type("of")
        s.press(KeyboardKeyKind.Delete)
        assertEquals("o", s.editor.composing)
        assertEquals("我", s.editor.text)
        s.press(KeyboardKeyKind.Delete)
        assertEquals("", s.editor.composing)
        assertEquals("我", s.editor.text)
        s.press(KeyboardKeyKind.Delete)
        assertEquals("", s.editor.text)
        // D-02: deleting all input alone must not restore the chip.
        assertNull(s.snapshot().clipboard)
    }

    @Test
    fun qk06_rapidInputOnlyPublishesLatestCodeCandidates() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        KeyboardScenario(scope = CoroutineScope(SupervisorJob() + dispatcher), lookupDispatcher = dispatcher).use { s ->
            s.engine.lookup = { _, code, _, _ -> listOf(QuickCandidate("result:$code", 0)) }
            s.start()
            s.type("ofx")
            advanceUntilIdle()
            assertEquals("ofx", s.editor.composing)
            assertEquals(listOf("result:ofx"), s.snapshot().quickCandidates.map { it.text })
            assertTrue(s.engine.requests.filter { it.first() == "code" }.all { it[2] == "ofx" })
        }
    }

    @Test
    fun qk07_englishPredictionAddsOnlyRequiredSeparators() {
        for ((left, right, expected) in listOf(Triple("hello", "friend", " my "),
                                             Triple("hello ", " friend", "my"),
                                             Triple("我", "呀", "my"))) {
            KeyboardScenario(initialText = left, clipboardText = null).use { s ->
                s.editor.right = right
                s.engine.predict = { _, _, _ -> listOf(QuickCandidate("my", 0)) }
                s.start()
                s.choose("my")
                assertEquals(listOf(expected), s.editor.commits)
                assertEquals(right, s.editor.right)
            }
        }
    }

    @Test
    fun ed01_englishAndNumbersCommitWithoutQuickLookup() {
        for ((mode, text) in listOf(KeyboardInputMode.AlphaNum to "Hello!", KeyboardInputMode.Number to "123.4")) {
            KeyboardScenario(initialMode = mode).use { s ->
                s.start()
                s.type(text)
                assertEquals(text, s.editor.text)
                assertEquals("", s.editor.composing)
                assertTrue(s.engine.requests.isEmpty())
                assertTrue(s.snapshot().quickCandidates.isEmpty())
            }
        }
    }

    @Test
    fun ed02_selectionUsesContextOnBothSidesWithoutModifyingRightText() = KeyboardScenario(initialText = "我想").use { s ->
        s.editor.right = "呀"
        s.start()
        s.type("of")
        s.choose("你")
        assertEquals("我想你", s.editor.text)
        assertEquals("呀", s.editor.right)
        assertEquals(listOf("prediction", "我想你", "呀"), s.engine.requests.last())
    }

    @Test
    fun hw03_realHandwritingCoordinatorCommitsSelectionAndRequestsNextWords() = runTest {
        val editor = MemoryKeyboardEditor("我想", "呀")
        val requests = mutableListOf<Pair<String, String>>()
        val coordinator = HandwritingInputCoordinator(
            editor = editor, currentSession = { 1L }, isInputViewVisible = { true }, stopVoiceInput = {},
            predictionProvider = { left, right, _ ->
                requests += left to right
                listOf(HandwritingCandidate(if (left.endsWith("你")) "好" else "你", 0))
            },
            coroutineScope = backgroundScope, synchronousPredictionsForTest = true,
        )
        try {
            coordinator.refreshPredictions()
            assertEquals("你", coordinator.candidates.value.single().text)
            coordinator.selectCandidate("你")
            assertEquals("我想你", editor.text)
            assertEquals("", editor.composing)
            assertEquals("好", coordinator.candidates.value.single().text)
            assertEquals(listOf("我想" to "呀", "我想你" to "呀"), requests)
        } finally { coordinator.destroy() }
    }

    @Test
    fun hw04_handwritingErrorIsConfinedToHandwritingLayout() {
        val state = KeyboardRenderState(handwritingError = "Cannot recognize", clipboard = ClipboardSuggestion(1, "clip", false))
        assertEquals(KeyboardToolbarContent.HandwritingError, state.toolbar(KeyboardInputMode.Handwriting))
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum, KeyboardInputMode.Number)) {
            assertEquals("$mode", KeyboardToolbarContent.Clipboard, state.toolbar(mode))
        }
    }

    @Test
    fun vo02_typingInterruptsVoiceBeforeInsertingNewText() {
        for (voice in listOf(KeyboardUiState.Recording, KeyboardUiState.Transcribing)) {
            KeyboardScenario(initialText = "earlier", initialMode = KeyboardInputMode.AlphaNum).use { s ->
                s.start()
                s.voice.state.value = voice
                s.keyDown(KeyboardKeyKind.Text, "x")
                assertEquals(KeyboardUiState.Idle, s.snapshot().voice)
                assertEquals("earlier", s.editor.text)
                s.keyUp(KeyboardKeyKind.Text, "x")
                assertEquals("earlierx", s.editor.text)
                assertEquals(listOf("x"), s.editor.commits)
            }
        }
    }

    @Test
    fun tb01_toolbarPrecedenceCoversEveryCombination() {
        val voices = listOf(KeyboardUiState.Idle, KeyboardUiState.LoadingModel, KeyboardUiState.DownloadingModel,
                            KeyboardUiState.Recording, KeyboardUiState.Transcribing, KeyboardUiState.Error("failed"),
                            KeyboardUiState.Error("retry download", true))
        for (mode in KeyboardInputMode.entries) for (voice in voices) for (bits in 0 until 32) {
            val autofill = bits and 1 != 0
            val clipboard = bits and 2 != 0
            val candidates = bits and 4 != 0
            val error = bits and 8 != 0
            val busy = bits and 16 != 0
            val items = if (candidates) listOf(HandwritingCandidate("你", 0)) else emptyList()
            val state = KeyboardRenderState(voice = voice, inputMode = mode,
                clipboard = if (clipboard) ClipboardSuggestion(1, "clip", false) else null,
                quickCandidates = items, handwritingCandidates = items,
                handwritingError = if (error) "error" else null, handwritingBusy = busy)
            // QA precedence contract; independent of which branches the renderer executes.
            val expected = when {
                voice != KeyboardUiState.Idle -> KeyboardToolbarContent.VoiceStatus
                mode == KeyboardInputMode.Handwriting && error -> KeyboardToolbarContent.HandwritingError
                autofill -> KeyboardToolbarContent.Autofill
                clipboard -> KeyboardToolbarContent.Clipboard
                candidates && mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.Handwriting) -> KeyboardToolbarContent.Candidates
                busy && mode == KeyboardInputMode.Handwriting -> KeyboardToolbarContent.Recognizing
                else -> KeyboardToolbarContent.Empty
            }
            assertEquals("mode=$mode voice=$voice flags=$bits", expected, state.toolbar(hasAutofill = autofill))
        }
    }

    @Test
    fun tb02_inactiveHandwritingStateCannotOccupyAnotherLayoutsToolbar() {
        val state = KeyboardRenderState(handwritingBusy = true, handwritingError = "error",
                                       handwritingCandidates = listOf(HandwritingCandidate("你", 0)))
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum, KeyboardInputMode.Number)) {
            assertEquals("$mode", KeyboardToolbarContent.Empty, state.toolbar(mode))
        }
    }

    @Test
    fun ux01_candidatePaginationNeverSkipsOrDuplicatesAnIndexAcrossWidths() {
        for (width in listOf(120, 200, 256, 296, 360)) for (count in 0..100) {
            val widths = List(count) { index -> listOf(62, 128, 156, 240)[index % 4].dp }
            val pages = textCandidatePages(widths, width.dp)
            val indices = pages.flatMap { page ->
                (page.firstCandidate until minOf(count, page.firstCandidate + page.capacity)).toList()
            }
            assertEquals("width=$width count=$count", (0 until count).toList(), indices)
            pages.forEachIndexed { index, page ->
                assertTrue(page.capacity > 0)
                assertEquals(index > 0, page.hasPrevious)
                assertEquals(index < pages.lastIndex, page.hasNext)
            }
        }
    }

    @Test
    fun ux03_releaseCommitsTextOnceAndDeleteUpDoesNotDeleteAgain() = KeyboardScenario(initialText = "before", initialMode = KeyboardInputMode.AlphaNum).use { s ->
        s.start()
        s.keyDown(KeyboardKeyKind.Text, "a")
        assertTrue(s.editor.commits.isEmpty())
        s.keyUp(KeyboardKeyKind.Text, "a")
        assertEquals(listOf("a"), s.editor.commits)
        assertEquals("beforea", s.editor.text)
        s.keyDown(KeyboardKeyKind.Delete)
        assertEquals("before", s.editor.text)
        s.keyUp(KeyboardKeyKind.Delete)
        assertEquals("before", s.editor.text)
        assertEquals(listOf("a"), s.editor.commits)
    }

    @Test
    fun as01_allSessionFinishPathsPreventPendingQuickResultsFromReturning() = runTest {
        for (finish in 0..3) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            KeyboardScenario(scope = CoroutineScope(SupervisorJob() + dispatcher), lookupDispatcher = dispatcher).use { s ->
                s.start()
                s.type("of")
                when (finish) {
                    0 -> s.controller.onFinishInputView()
                    1 -> s.controller.onFinishInput()
                    2 -> s.hide()
                    else -> s.controller.destroy()
                }
                advanceUntilIdle()
                assertTrue("finish=$finish", s.snapshot().quickCandidates.isEmpty())
                assertNull(s.snapshot().clipboard)
                assertEquals(KeyboardUiState.Idle, s.snapshot().voice)
            }
        }
    }

    @Test
    fun as02_disabledSelectedLayoutFallsBackToAnEnabledLayout() = KeyboardScenario().use { s ->
        s.start()
        s.hide()
        s.enabledLayouts = setOf(KeyboardSettings.LAYOUT_ENGLISH)
        s.start()
        assertEquals(KeyboardInputMode.AlphaNum, s.snapshot().inputMode)
        s.mode(KeyboardInputMode.Number)
        assertEquals(KeyboardInputMode.Number, s.snapshot().inputMode)
    }

    @Test
    fun as03_newSessionAppliesConfiguredQuickVersion() = KeyboardScenario().use { s ->
        s.start()
        s.type("of")
        assertEquals("3", s.engine.requests.last()[1])
        s.hide()
        s.quickVersion = 5
        s.start()
        s.type("of")
        assertEquals(listOf("code", "5", "of", "of", ""), s.engine.requests.last())
    }
    @Test
    fun cb09_newCopyRestoresChipButDeletingAllInputDoesNot() = KeyboardScenario().use { s ->
        s.start()
        s.type("of")
        s.press(KeyboardKeyKind.Delete)
        s.press(KeyboardKeyKind.Delete)
        assertEquals("", s.editor.composing)
        assertNull(s.snapshot().clipboard)
        s.copy("fresh text")
        assertEquals("fresh text", s.snapshot().clipboard!!.preview)
        assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
        s.type("o")
        assertNull(s.snapshot().clipboard)
        s.copy("fresh text") // Copying identical text again still has a new generation.
        assertEquals("fresh text", s.snapshot().clipboard!!.preview)
    }

    @Test
    fun cb09_sameClipboardGenerationCannotResurrectDismissedChip() {
        val clipboard = ClipboardSession()
        val item = ClipboardSnapshot(1, listOf(ClipboardItemSnapshot(setOf(ClipMimeTypes.TEXT_PLAIN), "text")), false)
        clipboard.update(item)
        clipboard.start()
        clipboard.onUserTyped()
        clipboard.update(item)
        assertNull(clipboard.suggestion.value)
        clipboard.update(item.copy(token = 2, items = listOf(ClipboardItemSnapshot(setOf("image/png"), "unsupported"))))
        assertNull(clipboard.suggestion.value)
        clipboard.update(item.copy(token = 3))
        assertEquals("text", clipboard.suggestion.value!!.preview)
        clipboard.finish()
        clipboard.update(item.copy(token = 4))
        assertNull(clipboard.suggestion.value)
    }

    @Test
    fun qk08_spaceEnterAndLayoutSwitchCommitLiteralLetters() {
        for (action in 0..2) {
            KeyboardScenario().use { s ->
                s.start()
                s.type("of")
                when (action) {
                    0 -> s.press(KeyboardKeyKind.Space)
                    1 -> s.press(KeyboardKeyKind.Enter)
                    else -> s.mode(KeyboardInputMode.AlphaNum)
                }
                assertEquals("action=$action", when (action) { 0 -> "of "; 1 -> "of\n"; else -> "of" }, s.editor.text)
                assertEquals("", s.editor.composing)
                assertEquals("", s.quick.getComposingText())
                assertFalse(s.editor.commits.contains("你"))
            }
        }
    }

    @Test
    fun vo03_backspaceCancelsVoiceAndDeletesExactlyOnePreviouslyCommittedCharacter() {
        for (voice in listOf(KeyboardUiState.Recording, KeyboardUiState.Transcribing)) for (direct in listOf(false, true)) {
            KeyboardScenario(initialText = "ab", initialMode = KeyboardInputMode.AlphaNum).use { s ->
                s.start()
                s.voice.state.value = voice
                if (direct) s.controller.deleteSurroundingText() else s.press(KeyboardKeyKind.Delete)
                assertEquals("voice=$voice direct=$direct", "a", s.editor.text)
                assertEquals(KeyboardUiState.Idle, s.snapshot().voice)
                s.press(KeyboardKeyKind.Delete)
                assertEquals("", s.editor.text)
            }
        }
    }

    @Test
    fun as04_closeAndFocusFinishCommitLiteralCodeExactlyOnce() {
        for (finish in 0..2) {
            KeyboardScenario(initialText = "我").use { s ->
                s.start()
                s.type("of")
                when (finish) {
                    0 -> s.controller.onFinishInputView()
                    1 -> s.controller.onFinishInput()
                    else -> s.hide()
                }
                assertEquals("我of", s.editor.text)
                assertEquals(listOf("of"), s.editor.commits)
                s.controller.onFinishInputView()
                s.controller.onFinishInput()
                s.hide()
                assertEquals(listOf("of"), s.editor.commits)
                assertEquals("", s.editor.composing)
                s.start()
                assertEquals(listOf("prediction", "我of", ""), s.engine.requests.last())
                assertEquals("我of", s.editor.text)
            }
        }
    }

    @Test
    fun cb10_richTextClipboardPreviewsAndPastesThePlainVersion() = KeyboardScenario().use { s ->
        s.start()
        s.type("o")
        s.copy("plain version", mimeTypes = setOf(ClipMimeTypes.TEXT_PLAIN, "text/html"))
        assertEquals("plain version", s.snapshot().clipboard!!.preview)
        s.press(KeyboardKeyKind.Clipboard)
        assertEquals(listOf("o", "plain version"), s.editor.commits)
        assertEquals("oplain version", s.editor.text)
        assertEquals("", s.editor.composing)
        s.copy("<b>HTML only</b>", mimeTypes = setOf("text/html"))
        assertNull(s.snapshot().clipboard)
    }

    @Test
    fun qk10_pasteDuringQuickCompositionPreservesLettersAndClearsCode() = KeyboardScenario().use { s ->
        s.start()
        s.type("of")
        s.copy("fresh text")
        s.press(KeyboardKeyKind.Clipboard)
        assertEquals(listOf("of", "fresh text"), s.editor.commits)
        assertEquals("offresh text", s.editor.text)
        assertEquals("", s.quick.getComposingText())
        assertEquals("", s.editor.composing)
        assertEquals(listOf("prediction", "offresh text", ""), s.engine.requests.last())
        s.type("o")
        assertEquals("o", s.editor.composing)
        assertEquals("offresh text", s.editor.text)
    }

    @Test
    fun qk10_recordAndPunctuationNeverGuessChineseCandidate() {
        for (record in listOf(false, true)) {
            KeyboardScenario().use { s ->
                s.start()
                s.type("of")
                if (record) s.press(KeyboardKeyKind.Record) else s.type("。")
                assertEquals(if (record) "of" else "of.", s.editor.text)
                assertEquals("", s.editor.composing)
                assertEquals("", s.quick.getComposingText())
                assertFalse(s.editor.commits.contains("你"))
                if (record) assertEquals(KeyboardUiState.Recording, s.snapshot().voice)
            }
        }
    }

    @Test fun ed05_backspaceDeletesOneEmojiPerPressAcrossLayouts() {
        for (mode in listOf(KeyboardInputMode.Quick, KeyboardInputMode.AlphaNum, KeyboardInputMode.Handwriting)) {
            KeyboardScenario(initialText = "你", initialMode = mode).use { s ->
                s.start()
                listOf("😀", "👍🏽", "👨‍👩‍👧‍👦").forEach { s.press(KeyboardKeyKind.Text, it) }
                assertEquals("你😀👍🏽👨‍👩‍👧‍👦", s.editor.text)
                for (expected in listOf("你😀👍🏽", "你😀", "你")) {
                    s.press(KeyboardKeyKind.Delete)
                    assertEquals(expected, s.editor.text)
                    assertEquals("", s.editor.composing)
                    assertEquals(if (mode == KeyboardInputMode.Quick) KeyboardToolbarContent.Candidates
                        else KeyboardToolbarContent.Empty, s.snapshot().toolbar())
                    if (mode == KeyboardInputMode.Quick) assertEquals(
                        if (expected == "你") listOf("好", "哋") else listOf("我", "你"),
                        s.snapshot().quickCandidates.map { it.text })
                }
            }
        }
    }

    @Test
    fun hw03_realRecognitionSelectionCommitsReplacementAndThenPredicts() = runTest {
        val editor = MemoryKeyboardEditor("我想", "呀")
        val coordinator = HandwritingInputCoordinator(
            editor = editor, currentSession = { 1L }, isInputViewVisible = { true }, stopVoiceInput = {},
            predictionProvider = { left, right, _ ->
                assertEquals("我想你", left)
                assertEquals("呀", right)
                listOf(HandwritingCandidate("好", 0))
            },
            coroutineScope = backgroundScope, synchronousPredictionsForTest = true,
        )
        try {
            // No idle prediction was presented: exercise recognition selection's replacement path.
            coordinator.selectCandidate("你")
            assertEquals("我想你", editor.text)
            assertEquals("", editor.composing)
            assertFalse(coordinator.hasActiveInput())
            assertEquals(listOf("好"), coordinator.candidates.value.map { it.text })
        } finally { coordinator.destroy() }
    }

}
