package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OptionalVoiceScenarioTest {
    @Test fun disabledMicCannotCommitCompositionOrStartRecording() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            KeyboardScenario(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher), lookupDispatcher = dispatcher, clipboardText = null).use { s ->
                s.voice.enabled = false
                s.start(); s.type("of"); advanceUntilIdle()
                val before = s.snapshot()
                s.press(KeyboardKeyKind.Record)
                advanceUntilIdle()
                assertEquals("", s.editor.text)
                assertEquals("of", s.editor.composing)
                assertEquals(before.quickCandidates, s.snapshot().quickCandidates)
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                assertFalse(s.snapshot().voiceEnabled)
                assertEquals(KeyboardUiState.Idle, s.snapshot().voice)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun openingIsQuietAndOnlyMicTapObservesDownloadUntilReady() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val downloads = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.WaitingForWifi)
        var requests = 0
        try {
            KeyboardScenario(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher), lookupDispatcher = dispatcher, clipboardText = null,
                voiceFactory = { editor -> VoiceInputCoordinator(editor = editor, currentSession = { 1L },
                    isInputViewVisible = { true }, modelAvailable = { false }, modelDownloadStatus = {
                        requests++; downloads
                    }) }).use { s ->
                s.start(); s.type("of"); advanceUntilIdle()
                assertEquals(0, requests)
                assertEquals("of", s.editor.composing)
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                assertEquals(KeyboardUiState.Idle, s.snapshot().voice)
                s.press(KeyboardKeyKind.Record); advanceUntilIdle()
                assertEquals(1, requests)
                assertEquals("Use Wi-Fi with no data limit.", (s.snapshot().voice as KeyboardUiState.ModelNotReady).message)
                assertEquals(KeyboardToolbarContent.VoiceStatus, s.snapshot().toolbar())
                downloads.value = ModelDownloadStatus.Downloading(42, 100); advanceUntilIdle()
                assertTrue((s.snapshot().voice as KeyboardUiState.ModelNotReady).message.contains("42%"))
                downloads.value = ModelDownloadStatus.Ready; advanceUntilIdle()
                assertTrue((s.snapshot().voice as KeyboardUiState.ModelNotReady).message.contains("Tap the mic"))
                s.hide(); s.start(); advanceUntilIdle()
                assertEquals(KeyboardUiState.Idle, s.snapshot().voice)
                assertEquals(1, requests)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun typingDismissesDownloadFeedbackAndLateProgressCannotReplaceCandidates() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val downloads = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.WaitingForWifi)
        try {
            KeyboardScenario(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher),
                lookupDispatcher = dispatcher, clipboardText = null,
                voiceFactory = { editor -> VoiceInputCoordinator(editor = editor, currentSession = { 1L },
                    isInputViewVisible = { true }, modelAvailable = { false }, modelDownloadStatus = { downloads }) }).use { s ->
                s.start(); s.press(KeyboardKeyKind.Record); advanceUntilIdle()
                assertTrue(s.snapshot().voice is KeyboardUiState.ModelNotReady)
                s.type("of"); advanceUntilIdle()
                downloads.value = ModelDownloadStatus.Downloading(80, 100); advanceUntilIdle()
                assertEquals("", s.editor.text)
                assertEquals("of", s.editor.composing)
                assertEquals(listOf("你", "係"), s.snapshot().quickCandidates.map { it.text })
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                assertEquals(KeyboardUiState.Idle, s.snapshot().voice)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun downloadsRequireVoicePermissionAndUnmeteredNetwork() {
        assertTrue(KeyboardSettings.DEFAULT_VOICE_INPUT)
        assertTrue(ModelAssetPack.downloadAllowed(true, true, true))
        assertFalse(ModelAssetPack.downloadAllowed(false, true, true))
        assertFalse(ModelAssetPack.downloadAllowed(true, false, true))
        assertFalse(ModelAssetPack.downloadAllowed(true, true, false))
    }
}
