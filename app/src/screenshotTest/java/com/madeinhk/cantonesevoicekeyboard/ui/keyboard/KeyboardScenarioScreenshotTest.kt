package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.android.tools.screenshot.PreviewTest
import com.madeinhk.cantonesevoicekeyboard.KeyboardScenario
import com.madeinhk.cantonesevoicekeyboard.KeyboardSubtypes
import com.madeinhk.cantonesevoicekeyboard.KeyboardScenarios
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

@PreviewTest
@Preview(name = "Model restores internal capitals", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioModelCasing() = ScenarioScreen(remember {
    val engine = com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine(
        contextHandle = { 0L }, suggest = { _, _, _ ->
            com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(
                candidates = listOf(com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate("iPhone", 0.0, 0.0, 0, 0)),
                correction = "iPhone", known = true)
        })
    KeyboardScenario(initialText = "I just got an ", initialMode = KeyboardInputMode.Quick,
        englishEngine = engine, autoCapitalizationEnabled = true).use { scenario ->
        scenario.start(); scenario.type("iphone")
        scenario.snapshot()
    }
})

@PreviewTest
@Preview(name = "Quick manual word correction with autocorrect off", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioQuickManualCursorCorrection() = ScenarioScreen(remember {
    KeyboardScenario(initialText = "Fix t", initialMode = KeyboardInputMode.Quick,
        englishEngine = com.madeinhk.cantonesevoicekeyboard.ScenarioEnglishEngine()).use { scenario ->
        scenario.autocorrectLayouts = emptySet()
        scenario.editor.right = "eh word."
        scenario.start()
        scenario.snapshot()
    }
})

@PreviewTest
@Preview(name = "Quick capitalization correction", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioQuickCapitalizationCorrection() = ScenarioScreen(remember {
    val engine = object : com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidateEngine {
        override fun predict(word: String, centers: FloatArray, points: FloatArray, left: String, right: String) =
            com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction(
                candidates = listOf(com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate("How", 0.0, 0.0, 0, 0)),
                correction = "How", known = true)
    }
    KeyboardScenario(initialMode = KeyboardInputMode.Quick, englishEngine = engine,
        autoCapitalizationEnabled = true).use { scenario ->
        scenario.start()
        scenario.type("how")
        scenario.snapshot()
    }
})

@PreviewTest
@Preview(name = "Quick first letter auto caps", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioAutoCapsQuick() = ScenarioScreen(remember {
    KeyboardScenario(initialMode = KeyboardInputMode.Quick, autoCapitalizationEnabled = true).use { scenario ->
        scenario.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
        scenario.snapshot()
    }
})

@PreviewTest
@Preview(name = "Auto caps in ordinary text field", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioAutoCapsPlainText() = ScenarioScreen(remember {
    KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, autoCapitalizationEnabled = true).use { scenario ->
        scenario.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
        scenario.type("hello. ")
        scenario.snapshot()
    }
})

@PreviewTest
@Preview(name = "PIN editor number layout", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioPinEditor() = ScenarioScreen(remember {
    KeyboardScenario(clipboardText = null).use { scenario ->
        val pin = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        scenario.controller.onStartInput(pin, false, KeyboardSubtypes.QUICK)
        scenario.controller.onStartInputView(pin)
        scenario.snapshot()
    }
})

@PreviewTest
@Preview(name = "Candidates retained during rapid typing", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioUnifiedCandidatesPending() = ScenarioScreen(remember { KeyboardScenarios.unifiedCandidatesPending() })

@PreviewTest
@Preview(name = "Fast candidates during full ranking", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioUnifiedCandidatesUpdating() = ScenarioScreen(remember { KeyboardScenarios.unifiedCandidatesUpdating() })

@PreviewTest
@Preview(name = "Bilingual problem preview", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioBilingualProblemPreview() = ScenarioScreen(remember { KeyboardScenarios.bilingualProblemPreview() })

@PreviewTest
@Preview(name = "Chinese composition beyond six characters", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioUnboundedChinesePreview() = ScenarioScreen(remember { KeyboardScenarios.unboundedChinesePreview() })

@PreviewTest
@Preview(name = "Handwriting visual alternatives", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioHandwritingAlternatives() = ScenarioScreen(remember { KeyboardScenarios.handwritingAlternatives() })

@PreviewTest
@Preview(name = "Mixed Chinese phrase ranking", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioMixedChinesePhrase() = ScenarioScreen(remember { KeyboardScenarios.mixedChinesePhrase() })

@PreviewTest
@Preview(name = "Mixed Chinese phrase second page", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioMixedChinesePhraseSecondPage() = ScenarioScreen(remember { KeyboardScenarios.mixedChinesePhrase() }, page = 1)

@PreviewTest
@Preview(name = "Mixed exact English ranking", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioMixedExactEnglish() = ScenarioScreen(remember { KeyboardScenarios.mixedExactEnglish() })

@PreviewTest
@Preview(name = "Quick apostrophe correction", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioQuickApostropheCorrection() = ScenarioScreen(remember { KeyboardScenarios.quickApostropheCorrection() })

@PreviewTest
@Preview(name = "Quick English correction", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioQuickEnglishCorrection() = ScenarioScreen(remember { KeyboardScenarios.quickEnglishCorrection() })

@PreviewTest
@Preview(name = "Unambiguous neighbor correction", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioUnambiguousNeighborCorrection() = ScenarioScreen(remember { KeyboardScenarios.unambiguousNeighborCorrection() })

@PreviewTest
@Preview(name = "Known word contextual correction", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioSharedLmKnownWordCorrection() = ScenarioScreen(remember { KeyboardScenarios.sharedLmKnownWordCorrection() })

@PreviewTest
@Preview(name = "Contextual short word correction", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioGboardSupportedShortWordCorrection() = ScenarioScreen(remember { KeyboardScenarios.gboardSupportedShortWordCorrection() })

@PreviewTest
@Preview(name = "Correction after prior typo", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioNoisyContextCorrection() = ScenarioScreen(remember { KeyboardScenarios.noisyContextCorrection() })

@PreviewTest
@Preview(name = "Learned typing channel correction", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioLearnedTypingChannelCorrection() = ScenarioScreen(remember { KeyboardScenarios.learnedTypingChannelCorrection() })

@PreviewTest
@Preview(name = "Quick ir correction and undo", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioSharedLmQuickOverlapCorrection() = ScenarioScreen(remember { KeyboardScenarios.sharedLmQuickOverlapCorrection() })

@PreviewTest
@Preview(name = "Expanded short-word correction and undo", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioExpandedShortTrainingCorrection() = ScenarioScreen(remember { KeyboardScenarios.expandedShortTrainingCorrection() })

@PreviewTest
@Preview(name = "One edit short word correction", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioOneEditShortWordCorrection() = ScenarioScreen(remember { KeyboardScenarios.oneEditShortWordCorrection() })

@PreviewTest
@Preview(name = "Uncertain spelling remains selectable", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioUncertainSharedLmSpelling() = ScenarioScreen(remember { KeyboardScenarios.uncertainSharedLmSpelling() })

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
@Preview(name = "Two letter Quick code Chinese first", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioTwoLetterQuickCandidates() = ScenarioScreen(remember { KeyboardScenarios.twoLetterQuickCandidates() })

@PreviewTest
@Preview(name = "Short Quick code before full ranking", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioShortCodePreviewCandidates() = ScenarioScreen(remember { KeyboardScenarios.shortCodePreviewCandidates() })

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
@Preview(name = "Predictions after emoji backspace", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioAfterEmojiBackspace() = ScenarioScreen(remember { KeyboardScenarios.afterEmojiBackspace() })

@PreviewTest
@Preview(name = "Typing interrupts voice and shows ASCII punctuation", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioTypingInterruptsVoice() = ScenarioScreen(remember { KeyboardScenarios.typingInterruptsVoice() })

@PreviewTest
@Preview(name = "Continue uses ASCII punctuation", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioContinuePunctuation() = ScenarioScreen(remember { KeyboardScenarios.continuePunctuation() })

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

@PreviewTest
@Preview(name = "English selected by system switcher", widthDp = 360, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioSystemEnglishLayout() = ScenarioScreen(remember {
    KeyboardScenario().use { scenario ->
        scenario.start()
        scenario.controller.onSystemSubtypeSelected(KeyboardSubtypes.ENGLISH)
        scenario.snapshot()
    }
})

@PreviewTest
@Preview(name = "Corrections for word under cursor", widthDp = 412, heightDp = KeyboardUiDefaults.KeyboardSurfaceHeightDp)
@Composable
fun ScenarioCursorWordCorrections() = ScenarioScreen(remember { KeyboardScenarios.cursorWordCorrections() })
