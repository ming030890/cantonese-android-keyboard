package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KeyboardSessionScenarioTest {
    @Test
    fun clipboardWinsIdlePredictionsThenTypingHidesIt() = KeyboardScenario().use { s ->
        s.start()
        assertEquals(listOf("我", "你"), s.snapshot().quickCandidates.map { it.text })
        assertEquals("copied text", s.snapshot().clipboard?.preview)
        assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
        s.type("o")
        assertNull(s.snapshot().clipboard)
        assertEquals("o", s.editor.composing)
        s.type("f")
        assertEquals(listOf("你", "係"), s.snapshot().quickCandidates.map { it.text })
        assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
    }

    @Test
    fun choosingQuickCandidateCommitsAndPredictsFromUpdatedContext() = KeyboardScenario().use { s ->
        s.start()
        s.type("of")
        s.choose("你")
        assertEquals("你", s.editor.text)
        assertEquals("", s.editor.composing)
        assertFalse(s.quick.hasActiveInput())
        assertEquals(listOf("好", "哋"), s.snapshot().quickCandidates.map { it.text })
        assertEquals(listOf("prediction", "你", ""), s.engine.requests.last())
        assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
        assertNull(s.snapshot().clipboard)
        s.choose("好")
        assertEquals("你好", s.editor.text)
        assertEquals(listOf("嗎"), s.snapshot().quickCandidates.map { it.text })
    }

    @Test
    fun nonTextKeysAndLayoutChangesKeepClipboardAvailable() = KeyboardScenario().use { s ->
        s.start()
        for (kind in listOf(KeyboardKeyKind.Shift, KeyboardKeyKind.Settings,
                            KeyboardKeyKind.SymbolMore, KeyboardKeyKind.CandidateNext,
                            KeyboardKeyKind.CandidatePrevious, KeyboardKeyKind.ModeSwitch)) {
            s.press(kind)
            assertNotNull("Hidden after $kind", s.snapshot().clipboard)
        }
        for (mode in KeyboardInputMode.entries) {
            s.mode(mode)
            assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
        }
        s.cancel(KeyboardKeyKind.Text)
        assertNotNull(s.snapshot().clipboard)
        assertEquals("", s.editor.text)
    }

    @Test
    fun pasteKeyDownDoesNotDismissChipBeforePasteKeyUp() = KeyboardScenario().use { s ->
        s.start()
        s.keyDown(KeyboardKeyKind.Clipboard)
        assertNotNull(s.snapshot().clipboard)
        assertTrue(s.editor.commits.isEmpty())
        s.keyUp(KeyboardKeyKind.Clipboard)
        assertEquals(listOf("copied text"), s.editor.commits)
        assertNull(s.snapshot().clipboard)
        s.hide()
        s.start()
        assertEquals("copied text", s.snapshot().clipboard?.preview)
    }

    @Test
    fun newlyCopiedTextRestoresClipboardAfterTyping() = KeyboardScenario().use { s ->
        s.start()
        s.copy("new text")
        assertEquals("new text", s.snapshot().clipboard?.preview)
        s.type("o")
        s.copy("newer text")
        s.controller.onSelectionChanged()
        assertEquals("newer text", s.snapshot().clipboard?.preview)
        s.hide()
        assertNull(s.snapshot().clipboard)
        assertTrue(s.snapshot().quickCandidates.isEmpty())
        s.start()
        assertEquals("newer text", s.snapshot().clipboard?.preview)
    }

    @Test
    fun missingEmptyAndSensitiveClipboardHaveExpectedPresentation() = KeyboardScenario(clipboardText = null).use { s ->
        s.start()
        assertNull(s.snapshot().clipboard)
        s.copy("")
        assertNull(s.snapshot().clipboard)
        s.copy("secret", sensitive = true)
        assertTrue(s.snapshot().clipboard!!.isSensitive)
        assertNull(s.snapshot().clipboard!!.preview)
        assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
        s.copy(null)
        assertNull(s.snapshot().clipboard)
    }

    @Test
    fun handwritingStrokeHidesClipboardBeforeRecognitionCompletes() = KeyboardScenario(initialMode = KeyboardInputMode.Handwriting).use { s ->
        s.start()
        s.handwriting.candidates.value = listOf(HandwritingCandidate("我", 0))
        assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
        s.controller.onHandwritingStrokeStarted(true)
        s.handwriting.candidates.value = emptyList()
        s.handwriting.recognizing.value = true
        assertNull(s.snapshot().clipboard)
        assertEquals(KeyboardToolbarContent.Recognizing, s.snapshot().toolbar())
        s.handwriting.recognizing.value = false
        s.handwriting.candidates.value = listOf(HandwritingCandidate("你", 0))
        assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
        s.choose("你")
        assertEquals("你", s.editor.text)
    }

    @Test
    fun statusAndAutofillPrecedenceMatchRendering() = KeyboardScenario().use { s ->
        s.start()
        for (state in listOf(KeyboardUiState.LoadingModel, KeyboardUiState.DownloadingModel,
                             KeyboardUiState.Recording, KeyboardUiState.Transcribing,
                             KeyboardUiState.Error("failed"))) {
            s.voice.state.value = state
            assertEquals(KeyboardToolbarContent.VoiceStatus, s.snapshot().toolbar())
        }
        s.voice.state.value = KeyboardUiState.Idle
        assertEquals(KeyboardToolbarContent.Autofill, s.snapshot().toolbar(hasAutofill = true))
        assertEquals(KeyboardToolbarContent.Clipboard, s.snapshot().toolbar())
    }

    @Test
    fun deleteSpaceEnterAndEmojiCommitHideClipboard() {
        for (kind in listOf(KeyboardKeyKind.Delete, KeyboardKeyKind.Space, KeyboardKeyKind.Enter)) {
            KeyboardScenario().use { s -> s.start(); s.press(kind); assertNull("$kind", s.snapshot().clipboard) }
        }
        KeyboardScenario().use { s ->
            s.start()
            s.controller.commitText("😀")
            assertEquals("😀", s.editor.text)
            assertNull(s.snapshot().clipboard)
        }
    }

    @Test
    fun emojiSearchStaysLocalThenSelectionCommitsOneWholeVariant() = KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum).use { s ->
        s.start()
        s.emojiSearch = s.emojiSearch.open()
        s.type("thumbs")
        s.press(KeyboardKeyKind.Space)
        s.type("up!")
        assertEquals("thumbs up!", s.emojiSearch.query)
        s.press(KeyboardKeyKind.Delete)
        assertEquals("thumbs up", s.emojiSearch.query)
        s.keyDown(KeyboardKeyKind.Text, "x")
        s.keyCancel(KeyboardKeyKind.Text, "x")
        assertEquals("thumbs up", s.emojiSearch.query)
        assertEquals("", s.editor.text)
        assertEquals("", s.editor.composing)
        assertNotNull(s.snapshot().clipboard)
        s.controller.commitText("👍🏽")
        assertEquals("👍🏽", s.editor.text)
        assertEquals(listOf("👍🏽"), s.editor.commits)
        assertNull(s.snapshot().clipboard)
        s.press(KeyboardKeyKind.Enter)
        assertEquals(EmojiSearchState(), s.emojiSearch)
        assertEquals("👍🏽", s.editor.text)
        s.type("x")
        assertEquals("👍🏽x", s.editor.text)
    }

    @Test
    fun heldSearchDeleteNeverReachesEditorEvenAfterQueryIsEmpty() = KeyboardScenario(initialText = "keep").use { s ->
        s.start()
        s.emojiSearch = EmojiSearchState(active = true, query = "ab")
        s.keyDown(KeyboardKeyKind.Delete)
        s.keyRepeat(KeyboardKeyKind.Delete)
        s.keyRepeat(KeyboardKeyKind.Delete)
        s.keyUp(KeyboardKeyKind.Delete)
        assertEquals("", s.emojiSearch.query)
        assertEquals("keep", s.editor.text)
        assertNotNull(s.snapshot().clipboard)
        s.press(KeyboardKeyKind.Emoji)
        assertFalse(s.emojiSearch.active)
    }

    @Test
    fun predictionsUseCommittedContextAndBothSidesOfCursor() = KeyboardScenario(initialText = "我想", clipboardText = null).use { s ->
        s.editor.right = "呀"
        s.engine.predict = { left, right, _ ->
            assertEquals("呀", right)
            listOf(QuickCandidate(if (left.endsWith("你")) "食" else "你", 0))
        }
        s.start()
        s.type("of")
        assertEquals(listOf("code", "3", "of", "我想", "呀"), s.engine.requests.last())
        s.choose("你")
        assertEquals("我想你", s.editor.text)
        assertEquals(listOf("prediction", "我想你", "呀"), s.engine.requests.last())
        assertEquals("食", s.snapshot().quickCandidates.single().text)
    }

    @Test
    fun rapidTypingAndSessionEndCannotRestoreOldCandidates() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        KeyboardScenario(scope = scope, lookupDispatcher = dispatcher).use { s ->
            s.start()
            s.type("of")
            advanceUntilIdle()
            assertEquals(listOf("你", "係"), s.snapshot().quickCandidates.map { it.text })
            s.choose("你")
            advanceUntilIdle()
            assertEquals(listOf("好", "哋"), s.snapshot().quickCandidates.map { it.text })
            s.type("of")
            s.hide()
            advanceUntilIdle()
            assertTrue(s.snapshot().quickCandidates.isEmpty())
            assertNull(s.snapshot().clipboard)
        }
    }

    @Test
    fun sharedScreenshotScenariosAssertActualTransitions() {
        assertEquals(KeyboardToolbarContent.Clipboard, KeyboardScenarios.clipboardInitially().toolbar())
        assertNull(KeyboardScenarios.clipboardAfterTyping().clipboard)
        assertEquals(listOf("你", "係"), KeyboardScenarios.quickCandidates().quickCandidates.map { it.text })
        assertEquals(listOf("好", "哋"), KeyboardScenarios.nextWordPredictions().quickCandidates.map { it.text })
    }

    @Test
    fun backspaceAfterVoiceTypingCommitsAndDeletesOnlyLastCharacter() = KeyboardScenario().use { s ->
        s.start()
        s.voice.state.value = KeyboardUiState.Recording
        s.editor.setComposingText("你好呀")
        assertEquals("你好呀", s.editor.composing)
        assertEquals("", s.editor.text)

        s.press(KeyboardKeyKind.Delete)

        // Voice typing is interrupted: composing text is committed, and only the last character is deleted
        assertEquals("", s.editor.composing)
        assertEquals("你好", s.editor.text)
        assertEquals(KeyboardUiState.Idle, s.voice.state.value)
    }

    @Test
    fun backspaceWhileTranscribingCommitsAndDeletesOnlyLastCharacter() = KeyboardScenario().use { s ->
        s.start()
        s.voice.state.value = KeyboardUiState.Transcribing
        s.editor.setComposingText("早晨呀")
        assertEquals("早晨呀", s.editor.composing)
        assertEquals("", s.editor.text)

        s.press(KeyboardKeyKind.Delete)

        assertEquals("", s.editor.composing)
        assertEquals("早晨", s.editor.text)
        assertEquals(KeyboardUiState.Idle, s.voice.state.value)
    }

    @Test
    fun typingAfterVoiceTypingCommitsAndAppendsCharacter() = KeyboardScenario().use { s ->
        s.start()
        s.voice.state.value = KeyboardUiState.Recording
        s.editor.setComposingText("你好")

        s.controller.commitText("！")

        assertEquals("", s.editor.composing)
        assertEquals("你好！", s.editor.text)
        assertEquals(KeyboardUiState.Idle, s.voice.state.value)
    }

    @Test
    fun smartPunctuationUsesFullWidthForChineseAndAsciiOtherwise() = KeyboardScenario().use { s ->
        s.start()

        // 1. Chinese context -> Full-width punctuation
        s.type("of")
        s.choose("你")
        s.choose("好")
        assertEquals("你好", s.editor.text)
        s.controller.commitText(",")
        assertEquals("你好，", s.editor.text)
        s.controller.commitText(".")
        assertEquals("你好，。", s.editor.text)
        s.controller.commitText("?")
        assertEquals("你好，。？", s.editor.text)
        s.controller.commitText("!")
        assertEquals("你好，。？！", s.editor.text)

        // 2. English context -> ASCII punctuation
        s.editor.text = "hello"
        s.controller.commitText(".")
        assertEquals("hello.", s.editor.text)
        s.controller.commitText(",")
        assertEquals("hello.,", s.editor.text)
        // Full-width typed in English context converts to ASCII
        s.controller.commitText("。")
        assertEquals("hello.,.", s.editor.text)
        s.controller.commitText("，")
        assertEquals("hello.,.,", s.editor.text)

        // 3. Multilingual mixed text
        s.editor.text = "今日食咗 lunch"
        s.controller.commitText(".")
        assertEquals("今日食咗 lunch.", s.editor.text)

        s.editor.text = "I like 譚仔"
        s.controller.commitText(".")
        assertEquals("I like 譚仔。", s.editor.text)

        // 4. Numeric context
        s.editor.text = "3.14"
        s.controller.commitText(",")
        assertEquals("3.14,", s.editor.text)

        // 5. Empty field in Chinese Quick mode defaults to full-width
        s.editor.text = ""
        s.controller.commitText(",")
        assertEquals("，", s.editor.text)
        s.editor.text = ""
        s.controller.commitText(".")
        assertEquals("。", s.editor.text)

        // 6. Empty field in AlphaNum mode defaults to ASCII
        s.mode(KeyboardInputMode.AlphaNum)
        s.editor.text = ""
        s.controller.commitText(",")
        assertEquals(",", s.editor.text)
        s.editor.text = ""
        s.controller.commitText(".")
        assertEquals(".", s.editor.text)
    }
}

