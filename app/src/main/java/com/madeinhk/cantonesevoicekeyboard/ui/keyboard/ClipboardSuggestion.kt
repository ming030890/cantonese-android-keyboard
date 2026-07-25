package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

/** A clipboard item that is safe to expose in the keyboard toolbar. */
data class ClipboardSuggestion(
    val token: Long,
    val preview: String?,
    val isSensitive: Boolean,
)

internal data class ClipboardItemSnapshot(
    val mimeTypes: Set<String>,
    val text: String?,
)

internal data class ClipboardSnapshot(
    val token: Long,
    val items: List<ClipboardItemSnapshot>,
    val isSensitive: Boolean,
)

/**
 * Chooses the first supported clipboard item without exposing sensitive text.
 * For sensitive clips, callers deliberately pass no item text and this function
 * only checks the advertised plain-text MIME type.
 */
internal fun clipboardSuggestionFor(
    snapshot: ClipboardSnapshot?,
    dismissedToken: Long?,
): ClipboardSuggestion? {
    if (snapshot == null || snapshot.token == dismissedToken) return null

    val item = snapshot.items.firstOrNull { item ->
        item.mimeTypes == setOf(ClipMimeTypes.TEXT_PLAIN) &&
            (snapshot.isSensitive || !item.text.isNullOrEmpty())
    } ?: return null

    return ClipboardSuggestion(
        token = snapshot.token,
        preview = if (snapshot.isSensitive) null else item.text,
        isSensitive = snapshot.isSensitive,
    )
}

internal object ClipMimeTypes {
    const val TEXT_PLAIN = "text/plain"
}

internal enum class ToolbarSecondaryAction {
    Clipboard,
    Autofill,
}

internal data class ToolbarPresentation(
    val showSettings: Boolean = true,
    val secondaryAction: ToolbarSecondaryAction?,
)

/** Autofill and clipboard occupy only the shared toolbar center slot. */
internal fun toolbarPresentation(
    panel: KeyboardInputMode,
    hasAutofill: Boolean,
    hasClipboard: Boolean,
): ToolbarPresentation = ToolbarPresentation(
    secondaryAction = when {
        hasAutofill -> ToolbarSecondaryAction.Autofill
        hasClipboard -> ToolbarSecondaryAction.Clipboard
        else -> null
    },
)
