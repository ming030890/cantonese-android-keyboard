package com.madeinhk.cantonesevoicekeyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Debug-only animation preview; never opens the microphone or loads a model. */
class RecordingPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CantoneseVoiceKeyboardTheme(darkTheme = false, dynamicColor = false) {
                KeyboardScreen(
                    uiState = MutableStateFlow(KeyboardUiState.Recording),
                    volumeLevel = MutableStateFlow(0f),
                    keyboardAction = MutableStateFlow(KeyboardAction.Enter),
                    keyboardInputMode = MutableStateFlow(KeyboardInputMode.AlphaNum),
                    handwritingCandidates = MutableStateFlow(emptyList()),
                    handwritingRecognizing = MutableStateFlow(false),
                    handwritingError = MutableStateFlow(null),
                    handwritingSession = MutableStateFlow(0L),
                    autofillSuggestionView = MutableStateFlow(null),
                    clipboardSuggestion = MutableStateFlow(null),
                    onToggleRecording = {}, onDeleteSurroundingText = {},
                    onCommitText = {}, onSendSpace = {}, onSendEnter = {},
                    onOpenSettings = {}, onPasteClipboard = {},
                    onHandwritingChanged = { _, _, _, _, _, _ -> },
                    onHandwritingCandidateSelected = {}, onEmojiPicked = {},
                )
            }
        }
    }
}
