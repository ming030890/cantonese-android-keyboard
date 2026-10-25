package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

/** A clipboard item that is safe to expose in the keyboard toolbar. */
data class ClipboardSuggestion(
    val token: Long,
    val preview: String?,
    val isSensitive: Boolean,
    val imageUri: String? = null,
    val mimeType: String? = null,
) {
    val isImage: Boolean get() = imageUri != null || (isSensitive && mimeType != null && ClipMimeTypes.isImage(mimeType))
}

internal data class ClipboardItemSnapshot(
    val mimeTypes: Set<String>,
    val text: String?,
    val uri: String? = null,
)

internal data class ClipboardSnapshot(
    val token: Long,
    val items: List<ClipboardItemSnapshot>,
    val isSensitive: Boolean,
)

/**
 * Chooses the first supported clipboard item without exposing sensitive text.
 * For sensitive clips, callers deliberately pass no item text/URI and this function
 * only checks the advertised MIME type.
 */
internal fun clipboardSuggestionFor(
    snapshot: ClipboardSnapshot?,
    dismissedToken: Long?,
): ClipboardSuggestion? {
    if (snapshot == null || snapshot.token == dismissedToken) return null

    val textItem = snapshot.items.firstOrNull { item ->
        ClipMimeTypes.TEXT_PLAIN in item.mimeTypes &&
            (snapshot.isSensitive || !item.text.isNullOrEmpty())
    }
    if (textItem != null) {
        return ClipboardSuggestion(
            token = snapshot.token,
            preview = if (snapshot.isSensitive) null else textItem.text,
            isSensitive = snapshot.isSensitive,
        )
    }

    val imageItem = snapshot.items.firstOrNull { item ->
        item.mimeTypes.any(ClipMimeTypes::isImage) && (snapshot.isSensitive || item.uri != null)
    }
    if (imageItem != null) {
        val imageMime = imageItem.mimeTypes.firstOrNull(ClipMimeTypes::isImage) ?: "image/png"
        return ClipboardSuggestion(
            token = snapshot.token,
            preview = null,
            isSensitive = snapshot.isSensitive,
            imageUri = if (snapshot.isSensitive) null else imageItem.uri,
            mimeType = imageMime,
        )
    }

    return null
}

internal object ClipMimeTypes {
    const val TEXT_PLAIN = "text/plain"
    fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/")
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
