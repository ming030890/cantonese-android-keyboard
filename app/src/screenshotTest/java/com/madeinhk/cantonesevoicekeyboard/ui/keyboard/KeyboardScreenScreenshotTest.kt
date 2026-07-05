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
    heightDp = 270
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
    heightDp = 270
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
    heightDp = 270
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
    heightDp = 270
)
@Composable
fun KeyboardScreenErrorScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Error("Model assets are not available.")
    )
}

@Composable
private fun KeyboardScreenScreenshotContent(
    state: KeyboardUiState,
    volume: Float = 0f
) {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        KeyboardScreen(
            uiState = MutableStateFlow(state),
            volumeLevel = MutableStateFlow(volume),
            onToggleRecording = {},
            onDeleteSurroundingText = {},
            onSendSpace = {},
            onSendEnter = {},
            onOpenSettings = {}
        )
    }
}
