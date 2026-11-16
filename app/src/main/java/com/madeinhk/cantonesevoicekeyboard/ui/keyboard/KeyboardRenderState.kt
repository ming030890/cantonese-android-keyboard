package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import com.madeinhk.cantonesevoicekeyboard.KeyboardUiState
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate

/** A value snapshot usable by Compose, scenario assertions, and screenshot previews. */
data class KeyboardRenderState(
    val voiceEnabled: Boolean = true,
    val voice: KeyboardUiState = KeyboardUiState.Idle,
    val volume: Float = 0f,
    val action: KeyboardAction = KeyboardAction.Enter,
    val inputMode: KeyboardInputMode = KeyboardInputMode.Handwriting,
    val numPadVariant: NumPadVariant = NumPadVariant.Number,
    val clipboard: ClipboardSuggestion? = null,
    val inspectingWord: Boolean = false,
    val handwritingCandidates: List<HandwritingCandidate> = emptyList(),
    val handwritingComposing: String? = null,
    val quickCandidates: List<HandwritingCandidate> = emptyList(),
    val englishCandidates: List<HandwritingCandidate> = emptyList(),
    val fieldCandidates: List<HandwritingCandidate> = emptyList(),
    val emailBottomRow: Boolean = false,
    val handwritingBusy: Boolean = false,
    val handwritingError: String? = null,
    val handwritingSession: Long = 0L,
    val keyboardSession: Long = 0L,
    val chinesePunctuation: Boolean? = null,
    val shiftState: com.madeinhk.cantonesevoicekeyboard.ShiftState = com.madeinhk.cantonesevoicekeyboard.ShiftState.Off,
    val swipeEnabled: Boolean = true,
) {
    internal fun toolbar(mode: KeyboardInputMode = inputMode, hasAutofill: Boolean = false): KeyboardToolbarContent =
        keyboardToolbarContent(
            voice = if (voiceEnabled) voice else KeyboardUiState.Idle,
            secondaryAction = toolbarPresentation(
                mode,
                hasAutofill,
                clipboard != null && !inspectingWord && fieldCandidates.isEmpty(),
            ).secondaryAction,
            hasCandidates = fieldCandidates.isNotEmpty() || when (mode) {
                KeyboardInputMode.Quick -> quickCandidates.isNotEmpty()
                KeyboardInputMode.Handwriting -> handwritingCandidates.isNotEmpty()
                KeyboardInputMode.AlphaNum -> englishCandidates.isNotEmpty()
                else -> false
            },
            handwritingError = handwritingError.takeIf { mode == KeyboardInputMode.Handwriting },
            handwritingBusy = handwritingBusy && mode == KeyboardInputMode.Handwriting,
        )
}

internal enum class KeyboardToolbarContent { VoiceStatus, HandwritingError, Autofill, Clipboard, Candidates, Recognizing, Empty }

/** The same precedence is used for rendering and scenario assertions. */
internal fun keyboardToolbarContent(
    voice: KeyboardUiState,
    secondaryAction: ToolbarSecondaryAction?,
    hasCandidates: Boolean,
    handwritingError: String?,
    handwritingBusy: Boolean,
): KeyboardToolbarContent = when {
    voice != KeyboardUiState.Idle -> KeyboardToolbarContent.VoiceStatus
    handwritingError != null -> KeyboardToolbarContent.HandwritingError
    secondaryAction == ToolbarSecondaryAction.Autofill -> KeyboardToolbarContent.Autofill
    secondaryAction == ToolbarSecondaryAction.Clipboard -> KeyboardToolbarContent.Clipboard
    hasCandidates -> KeyboardToolbarContent.Candidates
    handwritingBusy -> KeyboardToolbarContent.Recognizing
    else -> KeyboardToolbarContent.Empty
}
