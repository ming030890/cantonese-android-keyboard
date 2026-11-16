package com.madeinhk.cantonesevoicekeyboard

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.ClipboardSuggestion
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineAutofillTest {

    @Test
    fun chipClipBoundsReturnsNullWhenChipFitsEntirelyInViewport() {
        assertNull(
            computeInlineChipClipBounds(
                scrollX = 0,
                viewportWidth = 280,
                chipLeft = 20,
                chipWidth = 180,
                chipHeight = 44,
            ),
        )
    }

    @Test
    fun chipClipBoundsCropsRightOverflowAndLeftScrolledPortionsAndHidesOffscreenChips() {
        // Chip 1 at [188, 368] in a 280px viewport at scrollX = 0: only left 92px [0..92] visible.
        assertEquals(
            InlineChipClip(left = 0, top = 0, right = 92, bottom = 44),
            computeInlineChipClipBounds(
                scrollX = 0,
                viewportWidth = 280,
                chipLeft = 188,
                chipWidth = 180,
                chipHeight = 44,
            ),
        )

        // Chip 0 at [0, 180] scrolled right by 88px: only right 92px [88..180] visible.
        assertEquals(
            InlineChipClip(left = 88, top = 0, right = 180, bottom = 44),
            computeInlineChipClipBounds(
                scrollX = 88,
                viewportWidth = 280,
                chipLeft = 0,
                chipWidth = 180,
                chipHeight = 44,
            ),
        )

        // Chip 0 at [0, 180] scrolled completely past left edge (scrollX = 200): 0-width clip hides surface.
        val offscreenLeft = computeInlineChipClipBounds(
            scrollX = 200,
            viewportWidth = 280,
            chipLeft = 0,
            chipWidth = 180,
            chipHeight = 44,
        )!!
        assertEquals(0, offscreenLeft.right - offscreenLeft.left)
    }

    @Test
    fun focusCallbacksAndRestartingInputPreserveInFlightOrActiveAutofill() {
        val tracker = TrackingAutofillSession()
        val editor = MemoryKeyboardEditor("")
        val controller = KeyboardSessionController(
            editor = editor,
            settings = KeyboardSessionSettings(
                enabledLayouts = { setOf(KeyboardSettings.LAYOUT_QUICK, KeyboardSettings.LAYOUT_ENGLISH) },
                quickVersion = { 3 },
            ),
        ) { ed, session, visible ->
            KeyboardSessionDependencies(
                voice = ScenarioVoice(ed),
                quick = com.madeinhk.cantonesevoicekeyboard.quick.QuickInputCoordinator({ ScenarioQuickEngine() }, ed),
                handwriting = ScenarioHandwriting(ed),
                suggestions = tracker,
            )
        }
        try {
            // Case 1: Autofill request/response arrives slightly before onStartInput / onStartInputView.
            tracker.deliverAutofill()
            assertTrue(tracker.autofillPresent)

            controller.onStartInput(null, restarting = false)
            assertTrue("onStartInput must not wipe autofill for newly focused field", tracker.autofillPresent)

            controller.onStartInputView(null)
            assertTrue("onStartInputView must not wipe autofill", tracker.autofillPresent)

            // Case 2: WebView / Compose TextField restarts input and reports initial cursor selection.
            controller.onStartInput(null, restarting = true)
            assertTrue("restarting input on same field must preserve autofill", tracker.autofillPresent)

            controller.onSelectionChanged(0, 0, -1)
            assertTrue("initial selection update must not wipe autofill", tracker.autofillPresent)

            // Case 3: Typing dismisses autofill; session finish also dismisses autofill.
            controller.selectKeyboardInputMode(KeyboardInputMode.AlphaNum)
            assertTrue("switching keyboard mode without typing keeps autofill", tracker.autofillPresent)
            controller.commitText("a")
            assertFalse("typing dismisses stale autofill", tracker.autofillPresent)

            tracker.deliverAutofill()
            assertTrue(tracker.autofillPresent)
            controller.onFinishInput()
            assertFalse("finishing input clears autofill", tracker.autofillPresent)
        } finally {
            controller.destroy()
        }
    }

    private class TrackingAutofillSession : SuggestionSession {
        var autofillPresent = false
            private set
        override val autofillSuggestionView = MutableStateFlow<View?>(null)
        override val clipboardSuggestion = MutableStateFlow<ClipboardSuggestion?>(null)

        fun deliverAutofill() {
            autofillPresent = true
        }

        override fun onInputStarted() {
            // Mirrors KeyboardSuggestionCoordinator.onInputStarted(): preserves autofillPresent.
        }

        override fun onInputFinished() {
            autofillPresent = false
        }

        override fun onUserTyped() {
            autofillPresent = false
        }

        override fun onSelectionChanged() {
            // Mirrors KeyboardSuggestionCoordinator.onSelectionChanged(): preserves autofillPresent.
        }

        override fun pasteClipboard() {
            autofillPresent = false
        }

        override fun onAutofillTapped() {
            autofillPresent = false
        }

        override fun createInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest =
            error("Unused in JVM test")

        override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean = true
    }
}
