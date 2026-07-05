package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.madeinhk.cantonesevoicekeyboard.KeyboardUiState
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme
import kotlinx.coroutines.flow.MutableStateFlow

@PreviewTest
@Preview(
    name = "Keyboard idle",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = 320
)
@Composable
fun KeyboardScreenIdleScreenshot() {
    KeyboardScreenScreenshotContent(state = KeyboardUiState.Idle)
}

@PreviewTest
@Preview(
    name = "Keyboard loading",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = 320
)
@Composable
fun KeyboardScreenLoadingScreenshot() {
    KeyboardScreenScreenshotContent(state = KeyboardUiState.LoadingModel)
}

@PreviewTest
@Preview(
    name = "Keyboard recording",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = 320
)
@Composable
fun KeyboardScreenRecordingScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Recording,
        volume = 0.72f
    )
}

@PreviewTest
@Preview(
    name = "Keyboard error",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = 320
)
@Composable
fun KeyboardScreenErrorScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Error("Model assets are not available.")
    )
}

@PreviewTest
@Preview(
    name = "Keyboard search action",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = 320
)
@Composable
fun KeyboardScreenSearchActionScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        action = KeyboardAction.Search
    )
}

@PreviewTest
@Preview(
    name = "Keyboard emoji panel",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = 320
)
@Composable
fun KeyboardScreenEmojiPanelScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        initialEmojiPanel = true
    )
}

@PreviewTest
@Preview(
    name = "Keyboard number panel",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = 320
)
@Composable
fun KeyboardScreenNumberPanelScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.Number
    )
}

@PreviewTest
@Preview(
    name = "Keyboard alphanum panel",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = 320
)
@Composable
fun KeyboardScreenAlphaNumPanelScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.AlphaNum
    )
}

@Composable
private fun KeyboardScreenScreenshotContent(
    state: KeyboardUiState,
    volume: Float = 0f,
    action: KeyboardAction = KeyboardAction.Enter,
    inputMode: KeyboardInputMode = KeyboardInputMode.Voice,
    initialEmojiPanel: Boolean = false
) {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        KeyboardScreen(
            uiState = MutableStateFlow(state),
            volumeLevel = MutableStateFlow(volume),
            keyboardAction = MutableStateFlow(action),
            keyboardInputMode = MutableStateFlow(inputMode),
            onToggleRecording = {},
            onDeleteSurroundingText = {},
            onCommitText = {},
            onSendSpace = {},
            onSendEnter = {},
            onOpenSettings = {},
            onEmojiPicked = {},
            initialEmojiPanel = initialEmojiPanel
        )
    }
}
