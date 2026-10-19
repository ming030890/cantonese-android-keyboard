package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.madeinhk.cantonesevoicekeyboard.KeyboardScenarios
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

@PreviewTest
@Preview(name = "Shared LM spelling suggestion", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioSharedLmSpellingSuggestion() = ScenarioScreen(remember { KeyboardScenarios.sharedLmSpellingSuggestions() })

@PreviewTest
@Preview(name = "Shared LM English completion", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioSharedLmEnglishCompletion() = ScenarioScreen(remember { KeyboardScenarios.sharedLmEnglishCompletion() })

@PreviewTest
@Preview(name = "Missing final letter candidates", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioMissingFinalLetterCandidates() = ScenarioScreen(remember { KeyboardScenarios.missingFinalLetterCandidates() })

@PreviewTest
@Preview(name = "English spelling candidates", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioEnglishAutocorrectCandidates() = ScenarioScreen(remember { KeyboardScenarios.englishAutocorrectCandidates() })

@PreviewTest
@Preview(name = "Uncertain company name stays literal", widthDp = 320, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioUncertainCompanyCandidates() = ScenarioScreen(remember { KeyboardScenarios.uncertainCompanyCandidates() })

@PreviewTest
@Preview(name = "Quick Chinese and English candidates", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioQuickEnglishCandidates() = ScenarioScreen(remember { KeyboardScenarios.quickEnglishCandidates() })

@PreviewTest
@Preview(name = "Long bilingual candidates", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Preview(name = "Long bilingual candidates narrow", widthDp = 320, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioLongMixedCandidates() = ScenarioScreen(remember { KeyboardScenarios.longMixedCandidates() })

@PreviewTest
@Preview(name = "Long bilingual candidates second page", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Preview(name = "Long bilingual candidates narrow second page", widthDp = 320, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioLongMixedCandidatesSecondPage() = ScenarioScreen(remember { KeyboardScenarios.longMixedCandidates() }, page = 1)

@PreviewTest
@Preview(name = "Long English candidates", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Preview(name = "Long English candidates narrow", widthDp = 320, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioLongEnglishCandidates() = ScenarioScreen(remember { KeyboardScenarios.longEnglishCandidates() })

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
private fun ScenarioScreen(state: KeyboardRenderState, page: Int = 0) {
    CantoneseVoiceKeyboardTheme(dynamicColor = false, darkTheme = false) {
        KeyboardScreen(
            renderState = state,
            initialHandwritingCandidatePage = page,
            onToggleRecording = {}, onDeleteSurroundingText = {}, onCommitText = {},
            onSendSpace = {}, onSendEnter = {}, onOpenSettings = {}, onPasteClipboard = {},
            onHandwritingChanged = { _, _, _, _, _, _ -> },
            onHandwritingCandidateSelected = {}, onEmojiPicked = {},
        )
    }
}
