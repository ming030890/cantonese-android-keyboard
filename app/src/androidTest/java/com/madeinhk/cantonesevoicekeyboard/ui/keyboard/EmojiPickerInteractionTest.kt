package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

class EmojiPickerInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val preferences get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("androidx.emoji2.emojipicker.preferences", 0)

    @Before fun clearHistory() {
        preferences.edit().remove("pref_key_recent_emoji").commit()
    }

    @Test fun searchWorksAfterLayoutChangeAndResetsOnReopening() {
        var renderState by mutableStateOf(KeyboardRenderState(inputMode = KeyboardInputMode.Quick))
        val committed = mutableListOf<String>()
        compose.setContent {
            CantoneseVoiceKeyboardTheme(darkTheme = false) {
                KeyboardScreen(renderState = renderState,
                    onEmojiPicked = { committed += it }, onCommitText = { committed += it },
                    onDeleteSurroundingText = {}, onToggleRecording = {}, onSendSpace = {}, onSendEnter = {},
                    onOpenSettings = {}, onPasteClipboard = {},
                    onHandwritingChanged = { _, _, _, _, _, _ -> }, onHandwritingCandidateSelected = {})
            }
        }
        // Reproduce a retained receiver after the selected system layout changes.
        compose.runOnIdle { renderState = renderState.copy(inputMode = KeyboardInputMode.AlphaNum) }
        compose.onNodeWithContentDescription("text.bottom.emoji").performTouchInput { click() }
        compose.onNodeWithText("Search emoji").performTouchInput { click() }
        "heart".forEach { compose.onNodeWithText(it.toString()).performTouchInput { click() } }
        compose.onNodeWithText("heart").assertExists()
        compose.runOnIdle { assertTrue(committed.isEmpty()); renderState = renderState.copy(keyboardSession = 1L) }
        compose.onNodeWithText("Search emoji").assertDoesNotExist()
        compose.onNodeWithText("h").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("h"), committed) }
        compose.onNodeWithContentDescription("text.bottom.emoji").performTouchInput { click() }
        compose.onNodeWithContentDescription("Recent emoji").assertIsSelected()
        compose.onNodeWithText("Search emoji").performTouchInput { click() }
        compose.onNodeWithText("h").performTouchInput { click() }
        compose.onNodeWithContentDescription("Clear search").assertExists()
        compose.runOnIdle { assertEquals(listOf("h"), committed) }
    }

    @Test fun browsingScrollsAcrossCategoriesAndTabsJumpWithinTheSheet() {
        compose.setContent {
            CantoneseVoiceKeyboardTheme(darkTheme = false) {
                EmojiPickerPanel(Modifier.fillMaxSize(), onEmojiPicked = {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("😀").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Recent emoji").assertIsSelected()
        compose.onNodeWithContentDescription("Symbols").performClick()
        compose.onNodeWithContentDescription("Symbols").assertIsSelected()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Flags"))
        compose.onNodeWithText("Flags").assertExists()
        // A vertical swipe enters the next category without selecting its tab.
        compose.onNode(hasScrollToNodeAction()).performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("Flags").assertIsSelected()
        compose.onNodeWithContentDescription("Recent emoji").performClick()
        compose.onNodeWithText("Your recent emoji will appear here").assertExists()
        compose.onNodeWithText("Smileys and emotions").assertExists()
    }

    @Test fun recentSelectionsPersistAndOpenFirstWhenPickerReopens() {
        val picked = mutableListOf<String>()
        var showPicker by mutableStateOf(true)
        compose.setContent {
            CantoneseVoiceKeyboardTheme(darkTheme = false) {
                if (showPicker) EmojiPickerPanel(Modifier.fillMaxSize(), onEmojiPicked = { picked += it })
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("😀").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("😀")[0].performClick()
        compose.onAllNodesWithText("😃")[0].performClick()
        compose.onAllNodesWithText("😀")[0].performClick()
        compose.onNodeWithContentDescription("Recent emoji").performClick()
        compose.onAllNodesWithText("😀")[0].assertExists()
        compose.onAllNodesWithText("😃")[0].assertExists()
        compose.onNodeWithContentDescription("Recent emoji").assertIsSelected()
        compose.runOnIdle {
            assertEquals(listOf("😀", "😃", "😀"), picked)
            assertEquals("😀,😃", preferences.getString("pref_key_recent_emoji", ""))
            showPicker = false
        }
        compose.waitForIdle()
        compose.runOnIdle { showPicker = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("😀").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Recent emoji").assertIsSelected()
        compose.onNodeWithContentDescription("Recent emoji").assertIsSelected()
        compose.onAllNodesWithText("😃")[0].performClick()
        compose.runOnIdle { assertEquals("😃,😀", preferences.getString("pref_key_recent_emoji", "")) }
    }

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
        compose.onNodeWithText("?123").performTouchInput { click() }
        compose.onNodeWithText("1").performTouchInput { click() }
        compose.onNodeWithText("heart1").assertExists()
        compose.onNodeWithText("ABC").performTouchInput { click() }
        // Standard keyboard delete (not a separate search control).
        compose.onNodeWithContentDescription("alpha.delete").performTouchInput { click() }
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
                KeyboardScreen(
                    renderState = KeyboardRenderState(inputMode = KeyboardInputMode.Quick),
                    initialEmojiPanel = true,
                    onEmojiPicked = { picked += it }, onCommitText = {},
                    onDeleteSurroundingText = {}, onToggleRecording = {},
                    onSendSpace = {}, onSendEnter = {}, onOpenSettings = {}, onPasteClipboard = {},
                    onHandwritingChanged = { _, _, _, _, _, _ -> }, onHandwritingCandidateSelected = {},
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("grinning face").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Search emoji").performClick()
        "thumb".forEach { letter -> compose.onNodeWithText(letter.toString()).performTouchInput { click() } }
        compose.onNodeWithContentDescription("thumbs up").performTouchInput { longClick() }
        compose.onNodeWithText("Choose a variant").assertExists()
        compose.onNodeWithText("👍🏽").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("👍🏽"), picked) }
        compose.onNodeWithText("Choose a variant").assertDoesNotExist()
    }
    @Test fun searchKeepsNormalEnglishKeyGeometryShiftAndDeleteRepeat() {
        val editorInput = mutableListOf<String>()
        var deletes = 0
        compose.setContent {
            CantoneseVoiceKeyboardTheme(darkTheme = false) {
                KeyboardScreen(
                    renderState = KeyboardRenderState(inputMode = KeyboardInputMode.AlphaNum),
                    onEmojiPicked = {}, onCommitText = { editorInput += it },
                    onDeleteSurroundingText = { deletes++ }, onToggleRecording = {},
                    onSendSpace = { editorInput += " " }, onSendEnter = { editorInput += "\n" },
                    onOpenSettings = {}, onPasteClipboard = {},
                    onHandwritingChanged = { _, _, _, _, _, _ -> }, onHandwritingCandidateSelected = {},
                )
            }
        }
        val normalBounds = compose.onNodeWithContentDescription("q").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("text.bottom.emoji").performTouchInput { click() }
        compose.onNodeWithText("Search emoji").performTouchInput { click() }
        val searchBounds = compose.onNodeWithContentDescription("q").fetchSemanticsNode().boundsInRoot
        assertEquals(normalBounds.height, searchBounds.height, 0.5f)
        assertEquals(normalBounds.width, searchBounds.width, 0.5f)
        compose.onNodeWithContentDescription("alpha.shift").performTouchInput { click() }
        compose.onNodeWithText("H").performTouchInput { click() }
        compose.onNodeWithContentDescription("alpha.delete").performTouchInput { longClick() }
        compose.onNodeWithText("Search emoji").assertExists()
        compose.onNodeWithContentDescription("text.bottom.enter").performTouchInput { click() }
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        compose.runOnIdle { assertTrue(editorInput.isEmpty()); assertEquals(0, deletes) }
    }

}
