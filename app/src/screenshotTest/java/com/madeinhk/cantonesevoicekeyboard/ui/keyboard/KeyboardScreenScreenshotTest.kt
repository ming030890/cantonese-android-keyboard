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
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
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
        state = KeyboardUiState.Error("Model download failed", canRetryModelDownload = true)
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
    heightDp = KeyboardUiDefaults.EmojiSurfaceHeightDp
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
    name = "Keyboard quick panel",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenQuickPanelScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.Quick
    )
}

@PreviewTest
@Preview(
    name = "Keyboard quick candidates",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenQuickCandidatesScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.Quick,
        candidates = listOf(
            HandwritingCandidate("係", 0),
            HandwritingCandidate("你", 1),
            HandwritingCandidate("個", 2),
            HandwritingCandidate("好", 3),
            HandwritingCandidate("我", 4),
        ),
    )
}

@PreviewTest
@Preview(
    name = "Keyboard quick uppercase",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenQuickUppercaseScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.Quick,
        initialUppercase = true,
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
        candidates = listOf(HandwritingCandidate("係", 0), HandwritingCandidate("你", 1)),
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
    name = "Keyboard handwriting candidates",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenHandwritingCandidatesScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        candidates = (0 until 10).map { index ->
            HandwritingCandidate("字$index", index)
        },
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

@PreviewTest
@Preview(
    name = "Keyboard transcribing",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenTranscribingScreenshot() {
    KeyboardScreenScreenshotContent(state = KeyboardUiState.Transcribing)
}

@PreviewTest
@Preview(
    name = "Keyboard dark theme",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenDarkThemeScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.AlphaNum,
        darkTheme = true,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard handwriting recognizing",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenHandwritingRecognizingScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        handwritingRecognizing = true,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard handwriting error",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenHandwritingErrorScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        handwritingError = "Handwriting recognition unavailable.",
    )
}

@PreviewTest
@Preview(
    name = "Keyboard later candidate page",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenLaterCandidatePageScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        candidates = (0 until 18).map { index ->
            HandwritingCandidate("字$index", index)
        },
        initialHandwritingCandidatePage = 3,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard send action",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenSendActionScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        action = KeyboardAction.Send,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard done action",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenDoneActionScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        action = KeyboardAction.Done,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard previous action",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenPreviousActionScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        action = KeyboardAction.Previous,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard uppercase",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenUppercaseScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.AlphaNum,
        initialUppercase = true,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard alternate symbols",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 412,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenAlternateSymbolsScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.Number,
        initialShowMoreSymbols = true,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard compact width",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 360,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenCompactWidthScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.AlphaNum,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard compact quick",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 360,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenCompactQuickScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.Quick,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard compact handwriting",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 360,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenCompactHandwritingScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.Handwriting,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard compact number",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 360,
    heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp
)
@Composable
fun KeyboardScreenCompactNumberScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        inputMode = KeyboardInputMode.Number,
    )
}

@PreviewTest
@Preview(
    name = "Keyboard compact emoji",
    showBackground = true,
    backgroundColor = 0xFF0D1117,
    widthDp = 360,
    heightDp = KeyboardUiDefaults.EmojiSurfaceHeightDp
)
@Composable
fun KeyboardScreenCompactEmojiScreenshot() {
    KeyboardScreenScreenshotContent(
        state = KeyboardUiState.Idle,
        initialEmojiPanel = true,
    )
}

@Composable
private fun KeyboardScreenScreenshotContent(
    state: KeyboardUiState,
    volume: Float = 0f,
    action: KeyboardAction = KeyboardAction.Enter,
    inputMode: KeyboardInputMode = KeyboardInputMode.Handwriting,
    initialEmojiPanel: Boolean = false,
    clipboard: ClipboardSuggestion? = null,
    showAutofillPlaceholder: Boolean = false,
    candidates: List<HandwritingCandidate> = emptyList(),
    darkTheme: Boolean = false,
    handwritingRecognizing: Boolean = false,
    handwritingError: String? = null,
    initialUppercase: Boolean = false,
    initialShowMoreSymbols: Boolean = false,
    initialHandwritingCandidatePage: Int = 0,
) {
    val context = LocalContext.current
    val autofillView = if (showAutofillPlaceholder) {
        remember(context) {
            TextView(context).apply {
                text = context.getString(R.string.keyboard_toolbar_autofill)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(42, 84, 126))
            }
        }
    } else {
        null
    }
    CantoneseVoiceKeyboardTheme(darkTheme = darkTheme, dynamicColor = false) {
        KeyboardScreen(
            uiState = MutableStateFlow(state),
            volumeLevel = MutableStateFlow(volume),
            keyboardAction = MutableStateFlow(action),
            keyboardInputMode = MutableStateFlow(inputMode),
            handwritingCandidates = MutableStateFlow(candidates),
            quickCandidates = MutableStateFlow(candidates),
            handwritingRecognizing = MutableStateFlow(handwritingRecognizing),
            handwritingError = MutableStateFlow(handwritingError),
            handwritingSession = MutableStateFlow(0L),
            autofillSuggestionView = MutableStateFlow(autofillView),
            clipboardSuggestion = MutableStateFlow(clipboard),
            onToggleRecording = {},
            onDeleteSurroundingText = {},
            onCommitText = {},
            onSendSpace = {},
            onSendEnter = {},
            onOpenSettings = {},
            onPasteClipboard = {},
            onHandwritingChanged = { _, _, _, _, _, _ -> },
            onHandwritingCandidateSelected = {},
            onEmojiPicked = {},
            initialEmojiPanel = initialEmojiPanel,
            initialUppercase = initialUppercase,
            initialShowMoreSymbols = initialShowMoreSymbols,
            initialHandwritingCandidatePage = initialHandwritingCandidatePage,
        )
    }
}
