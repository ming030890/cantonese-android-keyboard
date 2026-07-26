package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi

class KeyboardInputTest {
    @Test
    fun toolbarKeysKeepStableIdentityAcrossRecomposition() {
        val registry = KeyboardKeyRegistry()
        val initial = registerTextToolbarKeys(registry)
        registry.mount(initial.settings)
        registry.mount(initial.voice)
        registry.updateSurfaceSize(width = 300, height = 100)
        registry.updateVisualBounds(initial.settings, Rect(190f, 20f, 238f, 68f))
        registry.updateVisualBounds(initial.voice, Rect(246f, 20f, 294f, 68f))

        val recomposed = registerTextToolbarKeys(registry)
        registry.updateSurfaceSize(width = 300, height = 100)

        assertSame(initial.settings, recomposed.settings)
        assertSame(initial.voice, recomposed.voice)
        assertSame(initial.clipboard, recomposed.clipboard)
        assertSame(recomposed.settings, registry.findKeyAt(220f, 40f))
        assertSame(recomposed.voice, registry.findKeyAt(270f, 40f))
    }

    @Test
    fun mountedClipboardTargetParticipatesInToolbarHitTesting() {
        val registry = KeyboardKeyRegistry()
        val voice = registerTextToolbarKeys(registry)
        registry.mount(voice.settings)
        registry.mount(voice.voice)
        registry.updateSurfaceSize(width = 300, height = 100)
        registry.updateVisualBounds(voice.settings, Rect(190f, 20f, 238f, 68f))
        registry.updateVisualBounds(voice.voice, Rect(246f, 20f, 294f, 68f))

        val clipboard = registerTextToolbarKeys(registry)
        registry.mount(clipboard.clipboard)
        registry.updateSurfaceSize(width = 300, height = 100)
        registry.updateVisualBounds(clipboard.clipboard, Rect(100f, 20f, 180f, 68f))

        assertTrue(registry.isMounted(clipboard.voice))
        assertTrue(registry.isMounted(clipboard.clipboard))
        assertSame(clipboard.clipboard, registry.findKeyAt(140f, 40f))
    }

    @Test
    fun emojiPickerPassThroughOnlyAppliesInsideActivePickerBounds() {
        val bounds = Rect(10f, 20f, 190f, 120f)

        assertTrue(shouldPassToEmojiPicker(true, bounds, 50f, 60f))
        assertFalse(shouldPassToEmojiPicker(false, bounds, 50f, 60f))
        assertFalse(shouldPassToEmojiPicker(true, bounds, 50f, 140f))
    }

    @Test
    fun providerRenderedAutofillViewBypassesKeyboardTouchControllerOnlyInsideBounds() {
        val bounds = Rect(100f, 10f, 290f, 58f)

        assertTrue(shouldPassToAutofillView(true, bounds, 150f, 30f))
        assertFalse(shouldPassToAutofillView(true, bounds, 80f, 30f))
        assertFalse(shouldPassToAutofillView(false, bounds, 150f, 30f))
    }

    @Test
    fun providerRenderedAutofillViewIsDismissedOnTouchDownInsideBounds() {
        val bounds = Rect(100f, 10f, 290f, 58f)

        assertTrue(shouldDismissAutofillOnTouch(true, bounds, MotionEvent.ACTION_DOWN, 150f, 30f))
        assertFalse(shouldDismissAutofillOnTouch(true, bounds, MotionEvent.ACTION_UP, 150f, 30f))
        assertFalse(shouldDismissAutofillOnTouch(true, bounds, MotionEvent.ACTION_DOWN, 80f, 30f))
    }

    @Test
    fun handwritingCanvasPassThroughOnlyAppliesInsideActiveCanvasBounds() {
        val bounds = Rect(10f, 20f, 190f, 120f)

        assertTrue(shouldPassToHandwritingCanvas(true, bounds, 50f, 60f))
        assertFalse(shouldPassToHandwritingCanvas(false, bounds, 50f, 60f))
        assertFalse(shouldPassToHandwritingCanvas(true, bounds, 50f, 140f))
    }

    @Test
    fun candidateToolbarCreatesSemanticTargetsAlongsideFixedVoiceKey() {
        val registry = KeyboardKeyRegistry()
        val keys = registerTextToolbarKeys(
            registry,
            candidates = listOf(
                com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate("你", 0),
                com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate("尼", 1),
            ),
        )

        assertEquals(KeyboardKeyKind.CandidatePrevious, keys.candidatePrevious.currentData?.kind)
        assertEquals(KeyboardKeyKind.CandidateNext, keys.candidateNext.currentData?.kind)
        assertEquals(2, keys.candidates.size)
    }

    @Test
    fun firstCandidateReplacesHiddenSettingsInHitTesting() {
        val registry = KeyboardKeyRegistry()
        val initial = registerTextToolbarKeys(registry)
        registry.mount(initial.settings)
        registry.updateSurfaceSize(width = 300, height = 100)
        registry.updateVisualBounds(initial.settings, Rect(0f, 20f, 48f, 68f))

        registry.unmount(initial.settings)
        val candidates = registerTextToolbarKeys(
            registry,
            candidates = listOf(
                com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate("你", 0),
            ),
        )
        registry.mount(candidates.candidates[0])
        registry.updateSurfaceSize(width = 300, height = 100)
        registry.updateVisualBounds(candidates.candidates[0], Rect(0f, 20f, 62f, 68f))

        assertFalse(registry.isMounted(candidates.settings))
        assertSame(candidates.candidates[0], registry.findKeyAt(24f, 40f))
    }

    @Test
    fun keyUnmountedDuringPressDoesNotDispatchItsOriginalAction() {
        val settings = KeyData("toolbar.settings", KeyboardKeyKind.Settings, text = "Settings")

        assertTrue(
            shouldDispatchOriginalKeyUp(
                dataOnDown = settings,
                currentData = settings,
                targetStillMounted = true,
            ),
        )
        assertFalse(
            shouldDispatchOriginalKeyUp(
                dataOnDown = settings,
                currentData = settings,
                targetStillMounted = false,
            ),
        )
    }

    @Test
    fun candidatePageSizeAdaptsWithoutRequiringHorizontalScrolling() {
        assertEquals(1, handwritingCandidatePageSize(150.dp))
        assertEquals(2, handwritingCandidatePageSize(256.dp))
        assertEquals(3, handwritingCandidatePageSize(296.dp))
    }

    @Test
    fun candidatePageCountIncludesAPartialLastPage() {
        assertEquals(0, handwritingCandidatePageCount(candidateCount = 0, pageSize = 3))
        assertEquals(1, handwritingCandidatePageCount(candidateCount = 3, pageSize = 3))
        assertEquals(4, handwritingCandidatePageCount(candidateCount = 10, pageSize = 3))
    }

    @Test
    fun candidateKeysRemovedWhenRecognitionReturnsFewerResults() {
        val registry = KeyboardKeyRegistry()
        val initial = registerTextToolbarKeys(
            registry,
            candidates = listOf(
                com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate("你", 0),
                com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate("尼", 1),
            ),
        )
        registry.mount(initial.candidates[1])
        registry.updateSurfaceSize(width = 300, height = 100)
        registry.updateVisualBounds(initial.candidates[1], Rect(100f, 20f, 150f, 68f))

        registry.unmount(initial.candidates[1])
        val updated = registerTextToolbarKeys(
            registry,
            candidates = listOf(
                com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate("你", 0),
            ),
        )
        registry.updateSurfaceSize(width = 300, height = 100)

        assertEquals(1, updated.candidates.size)
        assertTrue(registry.findKeyAt(125f, 40f) == null)
    }

    @Test
    fun unmountedCandidateGeometryDoesNotShrinkActiveCandidateHitArea() {
        val registry = KeyboardKeyRegistry()
        val staleCandidate = registry.key(
            "candidate.stale",
            KeyData("candidate.stale", KeyboardKeyKind.Candidate, text = "舊"),
            "toolbar",
        )
        val activeCandidate = registry.key(
            "candidate.active",
            KeyData("candidate.active", KeyboardKeyKind.Candidate, text = "新"),
            "toolbar",
        )
        val next = registry.key(
            "candidate.next",
            KeyData("candidate.next", KeyboardKeyKind.CandidateNext),
            "toolbar",
        )
        registry.mount(activeCandidate)
        registry.mount(next)
        registry.updateSurfaceSize(width = 200, height = 100)
        registry.updateVisualBounds(staleCandidate, Rect(0f, 20f, 100f, 68f))
        registry.updateVisualBounds(activeCandidate, Rect(0f, 20f, 100f, 68f))
        registry.updateVisualBounds(next, Rect(100f, 20f, 148f, 68f))

        assertSame(activeCandidate, registry.findKeyAt(10f, 40f))
        assertSame(activeCandidate, registry.findKeyAt(90f, 40f))
        assertSame(next, registry.findKeyAt(120f, 40f))
    }

    @Test
    fun touchBounds_fillVisualSpacingAndKeyboardEdges() {
        val registry = KeyboardKeyRegistry()
        val left = registry.key("left", KeyData("left", KeyboardKeyKind.Text, text = "a"), "row")
        val right = registry.key("right", KeyData("right", KeyboardKeyKind.Text, text = "b"), "row")
        registry.mount(left)
        registry.mount(right)
        registry.updateSurfaceSize(width = 300, height = 100)
        registry.updateVisualBounds(left, Rect(12f, 20f, 140f, 80f))
        registry.updateVisualBounds(right, Rect(160f, 20f, 288f, 80f))

        assertEquals(0f, left.geometry.touchBounds.left)
        assertEquals(150f, left.geometry.touchBounds.right)
        assertEquals(150f, right.geometry.touchBounds.left)
        assertEquals(300f, right.geometry.touchBounds.right)
        assertEquals(100f, left.geometry.touchBounds.bottom)
        assertSame(left, registry.findKeyAt(149.5f, 50f))
        assertSame(right, registry.findKeyAt(150.5f, 50f))
    }

    @Test
    fun rowsMeetVerticallyAndHitTestingUsesTouchBounds() {
        val registry = KeyboardKeyRegistry()
        val top = registry.key("top", KeyData("top", KeyboardKeyKind.Text, text = "a"), "top")
        val bottom = registry.key("bottom", KeyData("bottom", KeyboardKeyKind.Text, text = "b"), "bottom")
        registry.mount(top)
        registry.mount(bottom)
        registry.updateSurfaceSize(width = 200, height = 120)
        registry.updateVisualBounds(top, Rect(20f, 10f, 180f, 40f))
        registry.updateVisualBounds(bottom, Rect(20f, 70f, 180f, 100f))

        assertEquals(55f, top.geometry.touchBounds.bottom)
        assertEquals(55f, bottom.geometry.touchBounds.top)
        // This point is in the visual gap, but it must still be touchable.
        assertSame(top, registry.findKeyAt(10f, 50f))
        assertSame(bottom, registry.findKeyAt(10f, 60f))
    }

    @Test
    fun movementToleranceUsesVisibleBounds() {
        val bounds = Rect(100f, 100f, 200f, 200f)

        assertTrue(isWithinActiveKeyTolerance(Offset(95f, 90f), bounds))
        assertTrue(isWithinActiveKeyTolerance(Offset(205f, 230f), bounds))
        assertFalse(isWithinActiveKeyTolerance(Offset(89f, 100f), bounds))
        assertFalse(isWithinActiveKeyTolerance(Offset(100f, 236f), bounds))
    }

    @Test
    fun rolloverFinalizesTextButNotModifierOrLongPress() {
        val character = KeyModel("a").apply {
            currentData = KeyData("a", KeyboardKeyKind.Text, text = "a")
        }
        val shift = KeyModel("shift").apply {
            currentData = KeyData("shift", KeyboardKeyKind.Shift)
        }
        val characterPointer = KeyboardPointerState(pointerId = 1, activeKey = character, dataOnDown = character.currentData)
        val shiftPointer = KeyboardPointerState(pointerId = 2, activeKey = shift, dataOnDown = shift.currentData)

        assertTrue(shouldFinalizeForRollover(characterPointer, character))
        assertFalse(shouldFinalizeForRollover(shiftPointer, shift))
        characterPointer.longPressTriggered = true
        assertFalse(shouldFinalizeForRollover(characterPointer, character))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun longPressCanBlockUpAndRepeatStopsOnUp() = runTest {
        val events = mutableListOf<String>()
        val receiver = object : KeyboardInputReceiver {
            override fun onKeyDown(data: KeyData) { events += "down" }
            override fun onKeyUp(data: KeyData) { events += "up" }
            override fun onKeyCancel(data: KeyData) { events += "cancel" }
            override fun onKeyRepeat(data: KeyData) { events += "repeat" }
        }
        val dispatcher = CoroutineKeyboardInputDispatcher(
            receiver = receiver,
            scope = this,
            longPressDelayMillis = 100L,
            repeatIntervalMillis = 10L,
            nowMillis = { 0L }
        )
        val delete = KeyData("delete", KeyboardKeyKind.Delete)
        val repeatInfo = dispatcher.sendDown(delete, onRepeat = {
            receiver.onKeyRepeat(delete)
            true
        })

        advanceTimeBy(121L)
        runCurrent()
        assertTrue(events.count { it == "repeat" } >= 2)
        dispatcher.sendUp(delete, repeatInfo)
        val repeatsAfterUp = events.count { it == "repeat" }
        advanceTimeBy(100L)
        assertEquals(repeatsAfterUp, events.count { it == "repeat" })

        val consuming = KeyData(
            keyId = "long",
            kind = KeyboardKeyKind.Text,
            text = "x",
            longPressEnabled = true
        )
        val consumingInfo = dispatcher.sendDown(consuming, onLongPress = { true })
        advanceTimeBy(100L)
        runCurrent()
        dispatcher.sendUp(consuming, consumingInfo)
        assertFalse(events.takeLast(1).contains("up"))
    }
}
