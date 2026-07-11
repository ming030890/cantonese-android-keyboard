package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardTouchControllerTest {
    @Test
    fun downHapticsAndUpCommitsExactlyOnce() {
        val fixture = Fixture()
        fixture.controller.onMotionEvent(event(MotionEvent.ACTION_DOWN, 0, intArrayOf(10), floatArrayOf(40f)))

        assertTrue(fixture.a.isPressed)
        assertEquals(1, fixture.haptics)
        assertEquals(listOf("down:a"), fixture.events)

        fixture.controller.onMotionEvent(event(MotionEvent.ACTION_UP, 0, intArrayOf(10), floatArrayOf(40f)))

        assertFalse(fixture.a.isPressed)
        assertEquals(listOf("down:a", "up:a"), fixture.events)
    }

    @Test
    fun cancelNeverCommitsAndDisabledKeyDoesNothing() {
        val fixture = Fixture()
        fixture.controller.onMotionEvent(event(MotionEvent.ACTION_DOWN, 0, intArrayOf(10), floatArrayOf(40f)))
        fixture.controller.onMotionEvent(event(MotionEvent.ACTION_CANCEL, 0, intArrayOf(10), floatArrayOf(40f)))

        assertEquals(listOf("down:a", "cancel:a"), fixture.events)
        fixture.b.enabled = false
        fixture.controller.onMotionEvent(event(MotionEvent.ACTION_DOWN, 0, intArrayOf(11), floatArrayOf(160f)))
        assertEquals(listOf("down:a", "cancel:a"), fixture.events)
    }

    @Test
    fun rolloverFinalizesOldCharacterAndLatePhysicalUpDoesNotDuplicate() {
        val fixture = Fixture()
        fixture.controller.onMotionEvent(event(MotionEvent.ACTION_DOWN, 0, intArrayOf(1), floatArrayOf(40f)))
        fixture.controller.onMotionEvent(
            event(
                MotionEvent.ACTION_POINTER_DOWN,
                1,
                intArrayOf(1, 2),
                floatArrayOf(40f, 160f)
            )
        )

        assertEquals(listOf("down:a", "up:a", "down:b"), fixture.events)
        fixture.controller.onMotionEvent(
            event(
                MotionEvent.ACTION_POINTER_UP,
                0,
                intArrayOf(1, 2),
                floatArrayOf(40f, 160f)
            )
        )
        fixture.controller.onMotionEvent(event(MotionEvent.ACTION_UP, 0, intArrayOf(2), floatArrayOf(160f)))

        assertEquals(listOf("down:a", "up:a", "down:b", "up:b"), fixture.events)
    }

    @Test
    fun changedKeyDataCancelsSnapshotThenDispatchesReplacementDownUp() {
        val fixture = Fixture()
        fixture.controller.onMotionEvent(event(MotionEvent.ACTION_DOWN, 0, intArrayOf(1), floatArrayOf(40f)))
        fixture.a.currentData = KeyData("a", KeyboardKeyKind.Text, text = "A")
        fixture.controller.onMotionEvent(event(MotionEvent.ACTION_UP, 0, intArrayOf(1), floatArrayOf(40f)))

        assertEquals(
            listOf("down:a", "cancel:a", "down:a", "up:a"),
            fixture.events
        )
    }

    private class Fixture {
        val events = mutableListOf<String>()
        var haptics = 0
        val registry = KeyboardKeyRegistry().apply {
            beginLayout()
        }
        val a = registry.key("a", KeyData("a", KeyboardKeyKind.Text, text = "a"), "row")
        val b = registry.key("b", KeyData("b", KeyboardKeyKind.Text, text = "b"), "row")
        private val receiver = RecordingReceiver(events)
        private val dispatcher = RecordingDispatcher(events)
        val controller: KeyboardTouchController

        init {
            registry.updateSurfaceSize(200, 80)
            registry.updateVisualBounds(a, Rect(10f, 10f, 90f, 70f))
            registry.updateVisualBounds(b, Rect(110f, 10f, 190f, 70f))
            controller = KeyboardTouchController(
                keyRegistry = registry,
                inputDispatcher = dispatcher,
                inputReceiver = receiver,
                performHaptic = { haptics++ }
            )
        }
    }

    private class RecordingReceiver(private val events: MutableList<String>) : KeyboardInputReceiver {
        override fun onKeyDown(data: KeyData) = Unit
        override fun onKeyUp(data: KeyData) = Unit
        override fun onKeyCancel(data: KeyData) = Unit
        override fun onKeyRepeat(data: KeyData) = events.add("repeat:${data.keyId}").let { Unit }
    }

    private class RecordingDispatcher(private val events: MutableList<String>) : KeyboardInputDispatcher {
        override fun sendDown(
            data: KeyData,
            onLongPress: () -> Boolean,
            onRepeat: () -> Boolean
        ): PressedKeyInfo = PressedKeyInfo(0L).also { events += "down:${data.keyId}" }

        override fun sendUp(data: KeyData, pressedKeyInfo: PressedKeyInfo?) {
            events += "up:${data.keyId}"
        }

        override fun sendCancel(data: KeyData, pressedKeyInfo: PressedKeyInfo?) {
            events += "cancel:${data.keyId}"
        }

        override fun sendDownUp(data: KeyData) {
            events += "down:${data.keyId}"
            events += "up:${data.keyId}"
        }

        override fun dispose() = Unit
    }
}

private fun event(
    actionMasked: Int,
    actionIndex: Int,
    pointerIds: IntArray,
    xPositions: FloatArray
): MotionEvent {
    val properties = Array(pointerIds.size) { index ->
        MotionEvent.PointerProperties().apply { id = pointerIds[index] }
    }
    val coordinates = Array(pointerIds.size) { index ->
        MotionEvent.PointerCoords().apply {
            x = xPositions[index]
            y = 40f
            pressure = 1f
            size = 1f
        }
    }
    val action = actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    return MotionEvent.obtain(
        1L,
        1L,
        action,
        pointerIds.size,
        properties,
        coordinates,
        0,
        0,
        1f,
        1f,
        0,
        0,
        InputDevice.SOURCE_TOUCHSCREEN,
        0
    )
}
