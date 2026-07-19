package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.graphics.Color
import android.view.Gravity
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
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
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
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
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
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
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
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
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
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
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
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
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
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
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenAlphaNumPanelScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.AlphaNum
    )
}

@PreviewTest
@Preview(
    name = "Keyboard clipboard preview",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenClipboardPreviewScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        clipboard = ClipboardSuggestion(
            token = 1L,
            preview = "voice",
            isSensitive = false,
        ),
    )
}

@PreviewTest
@Preview(
    name = "Keyboard sensitive paste",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenSensitivePasteScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        clipboard = ClipboardSuggestion(token = 2L, preview = null, isSensitive = true),
    )
}

@PreviewTest
@Preview(
    name = "Keyboard autofill placeholder",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenAutofillPlaceholderScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        showAutofillPlaceholder = true,
    )
}

@Composable
private fun KeyboardScreenScreenshotContent(
    state: KeyboardUiState,
    volume: Float = 0f,
    action: KeyboardAction = KeyboardAction.Enter,
    inputMode: KeyboardInputMode = KeyboardInputMode.Voice,
    initialEmojiPanel: Boolean = false,
    clipboard: ClipboardSuggestion? = null,
    showAutofillPlaceholder: Boolean = false,
) {
    val context = LocalContext.current
    val autofillView = if (showAutofillPlaceholder) {
        remember(context) {
            TextView(context).apply {
                text = "Autofill suggestion"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(42, 84, 126))
            }
        }
    } else {
        null
    }
    CantoneseVoiceKeyboardTheme(darkTheme = false, dynamicColor = false) {
        KeyboardScreen(
            uiState = MutableStateFlow(state),
            volumeLevel = MutableStateFlow(volume),
            keyboardAction = MutableStateFlow(action),
            keyboardInputMode = MutableStateFlow(inputMode),
            autofillSuggestionView = MutableStateFlow(autofillView),
            clipboardSuggestion = MutableStateFlow(clipboard),
            onToggleRecording = {},
            onDeleteSurroundingText = {},
            onCommitText = {},
            onSendSpace = {},
            onSendEnter = {},
            onOpenSettings = {},
            onPasteClipboard = {},
            onEmojiPicked = {},
            initialEmojiPanel = initialEmojiPanel
        )
    }
}
