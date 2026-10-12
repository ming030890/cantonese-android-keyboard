package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSuggestionTest {
    @Test
    fun autofillWinsClipboardAndSettingsIsRetained() {
        val presentation = toolbarPresentation(
            panel = KeyboardInputMode.AlphaNum,
            hasAutofill = true,
            hasClipboard = true,
        )

        assertTrue(presentation.showSettings)
        assertEquals(ToolbarSecondaryAction.Autofill, presentation.secondaryAction)
    }

    @Test
    fun clipboardReplacesNormalSecondaryControlOnTextPanels() {
        val presentation = toolbarPresentation(
            panel = KeyboardInputMode.Number,
            hasAutofill = false,
            hasClipboard = true,
        )

        assertTrue(presentation.showSettings)
        assertEquals(ToolbarSecondaryAction.Clipboard, presentation.secondaryAction)
    }

    @Test
    fun voiceHasOnlySettingsWhenThereIsNoSuggestion() {
        assertTrue(toolbarPresentation(KeyboardInputMode.Handwriting, false, false).showSettings)
        assertNull(toolbarPresentation(KeyboardInputMode.Handwriting, false, false).secondaryAction)
    }

    @Test
    fun ordinaryTextShowsFirstNonEmptyPlainTextItem() {
        val suggestion = clipboardSuggestionFor(
            ClipboardSnapshot(
                token = 1L,
                isSensitive = false,
                items = listOf(
                    ClipboardItemSnapshot(setOf(ClipMimeTypes.TEXT_PLAIN), ""),
                    ClipboardItemSnapshot(setOf(ClipMimeTypes.TEXT_PLAIN), "A long preview"),
                ),
            ),
            dismissedToken = null,
        )

        assertEquals("A long preview", suggestion?.preview)
        assertEquals(false, suggestion?.isSensitive)
    }

    @Test
    fun sensitiveTextUsesGenericPasteWithoutExposingText() {
        val suggestion = clipboardSuggestionFor(
            ClipboardSnapshot(
                token = 2L,
                isSensitive = true,
                items = listOf(
                    ClipboardItemSnapshot(setOf(ClipMimeTypes.TEXT_PLAIN), null),
                ),
            ),
            dismissedToken = null,
        )

        assertNull(suggestion?.preview)
        assertEquals(true, suggestion?.isSensitive)
    }

    @Test
    fun unsupportedAndEmptyClipsAreIgnored() {
        assertNull(
            clipboardSuggestionFor(
                ClipboardSnapshot(
                    token = 3L,
                    isSensitive = false,
                    items = listOf(ClipboardItemSnapshot(setOf("image/png"), "not shown")),
                ),
                dismissedToken = null,
            )
        )
        assertNull(
            clipboardSuggestionFor(
                ClipboardSnapshot(
                    token = 6L,
                    isSensitive = false,
                    items = listOf(
                        ClipboardItemSnapshot(
                            setOf(ClipMimeTypes.TEXT_PLAIN, "text/html"),
                            "rich text",
                        )
                    ),
                ),
                dismissedToken = null,
            )
        )
        assertNull(
            clipboardSuggestionFor(
                ClipboardSnapshot(
                    token = 4L,
                    isSensitive = false,
                    items = listOf(ClipboardItemSnapshot(setOf(ClipMimeTypes.TEXT_PLAIN), "")),
                ),
                dismissedToken = null,
            )
        )
    }

    @Test
    fun dismissedGenerationStaysHiddenUntilClipboardChanges() {
        val snapshot = ClipboardSnapshot(
            token = 5L,
            isSensitive = false,
            items = listOf(ClipboardItemSnapshot(setOf(ClipMimeTypes.TEXT_PLAIN), "hello")),
        )

        assertNull(clipboardSuggestionFor(snapshot, dismissedToken = 5L))
        assertEquals(
            "hello",
            clipboardSuggestionFor(snapshot.copy(token = 6L), dismissedToken = 5L)?.preview,
        )
    }

    @Test
    fun typingStateSuppressesClipboardFromToolbarPresentation() {
        val presentation = toolbarPresentation(
            panel = KeyboardInputMode.AlphaNum,
            hasAutofill = false,
            hasClipboard = false,
        )

        assertNull(presentation.secondaryAction)
    }
}
