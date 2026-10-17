package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EmojiPickerInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchTapsStayInPickerAndResultCommitsOnce() {
        val picked = mutableListOf<String>()
        val editorInput = mutableListOf<String>()
        var deletes = 0
        compose.setContent {
            CantoneseVoiceKeyboardTheme(darkTheme = false) {
                KeyboardScreen(
                    renderState = KeyboardRenderState(inputMode = KeyboardInputMode.AlphaNum),
                    initialEmojiPanel = true,
                    onEmojiPicked = { picked += it },
                    onCommitText = { editorInput += it },
                    onDeleteSurroundingText = { deletes++ },
                    onToggleRecording = {}, onSendSpace = {}, onSendEnter = {},
                    onOpenSettings = {}, onPasteClipboard = {},
                    onHandwritingChanged = { _, _, _, _, _, _ -> },
                    onHandwritingCandidateSelected = {},
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("grinning face").fetchSemanticsNodes().isNotEmpty() }
        // Physical touches exercise the parent surface's pass-through routing too.
        compose.onNodeWithText("Search emoji").performTouchInput { click() }
        "heart".forEach { letter -> compose.onNodeWithText(letter.toString()).performTouchInput { click() } }
        compose.onNodeWithText("heart").assertExists()
        compose.runOnIdle { assertTrue(editorInput.isEmpty()); assertTrue(picked.isEmpty()); assertEquals(0, deletes) }
        compose.onNodeWithContentDescription("red heart").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("❤️"), picked); assertTrue(editorInput.isEmpty()) }
        compose.onNodeWithContentDescription("Clear search").performTouchInput { click() }
        compose.onNodeWithText("Search emoji").assertExists()
        compose.onNodeWithContentDescription("Close search").performTouchInput { click() }
        compose.onNodeWithContentDescription("Delete", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, deletes) }
        compose.onNodeWithText("ABC").performTouchInput { click() }
        compose.onNodeWithText("Search emoji").assertDoesNotExist()
    }

    @Test fun longPressOffersVariantsAndKeepsCompleteSequence() {
        val picked = mutableListOf<String>()
        compose.setContent {
            CantoneseVoiceKeyboardTheme(darkTheme = false) {
                EmojiPickerPanel(onEmojiPicked = { picked += it })
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("grinning face").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Search emoji").performClick()
        "thumb".forEach { letter -> compose.onNodeWithText(letter.toString()).performClick() }
        compose.onNodeWithContentDescription("thumbs up").performTouchInput { longClick() }
        compose.onNodeWithText("Choose a variant").assertExists()
        compose.onNodeWithText("👍🏽").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("👍🏽"), picked) }
        compose.onNodeWithText("Choose a variant").assertDoesNotExist()
    }
}
