package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.madeinhk.cantonesevoicekeyboard.KeyboardScenarios
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

@PreviewTest
@Preview(name = "English spelling candidates", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioEnglishAutocorrectCandidates() = ScenarioScreen(remember { KeyboardScenarios.englishAutocorrectCandidates() })

@PreviewTest
@Preview(name = "Quick Chinese and English candidates", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioQuickEnglishCandidates() = ScenarioScreen(remember { KeyboardScenarios.quickEnglishCandidates() })

@PreviewTest
@Preview(name = "Clipboard before typing", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioClipboardInitially() = ScenarioScreen(remember { KeyboardScenarios.clipboardInitially() })

@PreviewTest
@Preview(name = "Clipboard after typing", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioClipboardAfterTyping() = ScenarioScreen(remember { KeyboardScenarios.clipboardAfterTyping() })

@PreviewTest
@Preview(name = "Quick code candidates", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioQuickCandidates() = ScenarioScreen(remember { KeyboardScenarios.quickCandidates() })

@PreviewTest
@Preview(name = "Next word after selection", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioNextWordPredictions() = ScenarioScreen(remember { KeyboardScenarios.nextWordPredictions() })

@PreviewTest
@Preview(name = "Clipboard restored by new copy", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioClipboardAfterNewCopy() = ScenarioScreen(remember { KeyboardScenarios.clipboardAfterNewCopy() })

@PreviewTest
@Preview(name = "Handwriting recognizing", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioHandwritingRecognizing() = ScenarioScreen(remember { KeyboardScenarios.handwritingRecognizing() })

@PreviewTest
@Preview(name = "Handwriting error", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioHandwritingError() = ScenarioScreen(remember { KeyboardScenarios.handwritingError() })

@PreviewTest
@Preview(name = "Rich clipboard plain text", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioRichClipboard() = ScenarioScreen(remember { KeyboardScenarios.richClipboard() })

@Composable
private fun ScenarioScreen(state: KeyboardRenderState) {
    CantoneseVoiceKeyboardTheme(dynamicColor = false, darkTheme = false) {
        KeyboardScreen(
            renderState = state,
            onToggleRecording = {}, onDeleteSurroundingText = {}, onCommitText = {},
            onSendSpace = {}, onSendEnter = {}, onOpenSettings = {}, onPasteClipboard = {},
            onHandwritingChanged = { _, _, _, _, _, _ -> },
            onHandwritingCandidateSelected = {}, onEmojiPicked = {},
        )
    }
}
