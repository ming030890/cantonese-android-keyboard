package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/** The semantic action represented by a virtual key at the moment it is pressed. */
enum class KeyboardKeyKind {
    Text,
    Space,
    Delete,
    Enter,
    Shift,
    ModeSwitch,
    SymbolMore,
    Emoji,
    Settings,
    Candidate,
    CandidatePrevious,
    CandidateNext,
    Clipboard,
    Record,
    Utility,
}

data class KeyData(
    val keyId: String,
    val kind: KeyboardKeyKind,
    val text: String? = null,
    val popupLabel: String? = null,
    val rolloverEligible: Boolean = kind == KeyboardKeyKind.Text,
    val repeatable: Boolean = kind == KeyboardKeyKind.Delete,
    val longPressEnabled: Boolean = repeatable,
)

data class KeyGeometry(
    val visualBounds: Rect = Rect.Zero,
    val touchBounds: Rect = Rect.Zero,
)

/**
 * Stable visual state for a virtual key.
 *
 * Whether the key can receive input belongs to [KeyboardKeyRegistry], not this model. A model may
 * exist while its Compose visual is absent, but only a mounted model participates in hit testing.
 */
class KeyModel(
    val id: String,
) {
    var currentData: KeyData? = null
    var rowId: String = ""
    var geometry: KeyGeometry = KeyGeometry()
    var isPressed by mutableStateOf(false)

    internal var pressedPointerCount: Int = 0
    internal var globalVisualBounds: Rect = Rect.Zero
    internal var activationToken: Long = 0L
}

enum class KeyboardAction {
    Enter,
    Go,
    Search,
    Send,
    Next,
    Previous,
    Done,
}

enum class KeyboardInputMode {
    Handwriting,
    Number,
    AlphaNum,
}
