package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import com.madeinhk.cantonesevoicekeyboard.KeyboardUiState
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate

/** A value snapshot usable by Compose, scenario assertions, and screenshot previews. */
data class KeyboardRenderState(
    val voice: KeyboardUiState = KeyboardUiState.Idle,
    val volume: Float = 0f,
    val action: KeyboardAction = KeyboardAction.Enter,
    val inputMode: KeyboardInputMode = KeyboardInputMode.Handwriting,
    val clipboard: ClipboardSuggestion? = null,
    val handwritingCandidates: List<HandwritingCandidate> = emptyList(),
    val quickCandidates: List<HandwritingCandidate> = emptyList(),
    val englishCandidates: List<HandwritingCandidate> = emptyList(),
    val handwritingBusy: Boolean = false,
    val handwritingError: String? = null,
    val handwritingSession: Long = 0L,
) {
    internal fun toolbar(mode: KeyboardInputMode = inputMode, hasAutofill: Boolean = false): KeyboardToolbarContent =
        keyboardToolbarContent(
            voice = voice,
            secondaryAction = toolbarPresentation(mode, hasAutofill, clipboard != null).secondaryAction,
            hasCandidates = when (mode) {
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
