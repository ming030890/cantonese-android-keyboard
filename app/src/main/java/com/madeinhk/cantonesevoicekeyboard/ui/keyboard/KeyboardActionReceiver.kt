package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

/**
 * Translates semantic virtual-button events into host callbacks.
 *
 * Coordinate lookup and pointer state deliberately stop before this boundary.
 */
internal class KeyboardScreenInputReceiver : KeyboardInputReceiver {
    var onCommitText: (String) -> Unit = {}
    var onDelete: () -> Unit = {}
    var onSendSpace: () -> Unit = {}
    var onSendEnter: () -> Unit = {}
    var onToggleRecording: () -> Unit = {}
    var onOpenSettings: () -> Unit = {}
    var onPasteClipboard: () -> Unit = {}
    var onCandidateTapped: (String) -> Unit = {}
    var onCandidatePrevious: () -> Unit = {}
    var onCandidateNext: () -> Unit = {}
    var onOpenEmoji: () -> Unit = {}
    var onToggleUppercase: () -> Unit = {}
    var onToggleMoreSymbols: () -> Unit = {}
    var onModeSwitch: (String) -> Unit = {}
    var onInterruptVoice: () -> Unit = {}

    override fun onKeyDown(data: KeyData) {
        if (data.kind != KeyboardKeyKind.Record && data.kind != KeyboardKeyKind.Utility) {
            onInterruptVoice()
        }
        if (data.kind == KeyboardKeyKind.Delete) onDelete()
    }

    override fun onKeyUp(data: KeyData) {
        when (data.kind) {
            KeyboardKeyKind.Text -> data.text?.let(onCommitText)
            KeyboardKeyKind.Space -> onSendSpace()
            KeyboardKeyKind.Enter -> onSendEnter()
            KeyboardKeyKind.Shift -> onToggleUppercase()
            KeyboardKeyKind.ModeSwitch -> onModeSwitch(data.text.orEmpty())
            KeyboardKeyKind.SymbolMore -> onToggleMoreSymbols()
            KeyboardKeyKind.Emoji -> onOpenEmoji()
            KeyboardKeyKind.Settings -> onOpenSettings()
            KeyboardKeyKind.Candidate -> data.text?.let(onCandidateTapped)
            KeyboardKeyKind.CandidatePrevious -> onCandidatePrevious()
            KeyboardKeyKind.CandidateNext -> onCandidateNext()
            KeyboardKeyKind.Clipboard -> onPasteClipboard()
            KeyboardKeyKind.Record -> onToggleRecording()
            KeyboardKeyKind.Delete,
            KeyboardKeyKind.Utility,
            -> Unit
        }
    }

    override fun onKeyCancel(data: KeyData) = Unit

    override fun onKeyRepeat(data: KeyData) {
        if (data.kind == KeyboardKeyKind.Delete) onDelete()
    }
}
