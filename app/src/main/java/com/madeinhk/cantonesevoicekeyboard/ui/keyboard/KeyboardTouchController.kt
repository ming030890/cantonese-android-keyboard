package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

const val HorizontalMoveTolerance = 0.10f
const val VerticalMoveTolerance = 0.35f

data class KeyboardPointerState(
    val pointerId: Int,
    var pointerIndex: Int = -1,
    var initialKey: KeyModel? = null,
    var activeKey: KeyModel? = null,
    var activeKeyActivationToken: Long = 0L,
    var dataOnDown: KeyData? = null,
    var pressedKeyInfo: PressedKeyInfo? = null,
    var longPressTriggered: Boolean = false,
    var gestureTriggered: Boolean = false,
    var lastPosition: Offset = Offset.Zero,
)

fun isWithinActiveKeyTolerance(
    position: Offset,
    bounds: Rect,
    horizontalTolerance: Float = HorizontalMoveTolerance,
    verticalTolerance: Float = VerticalMoveTolerance,
): Boolean {
    val extraX = bounds.width * horizontalTolerance
    val extraY = bounds.height * verticalTolerance
    return position.x >= bounds.left - extraX &&
        position.x <= bounds.right + extraX &&
        position.y >= bounds.top - extraY &&
        position.y <= bounds.bottom + extraY
}

fun shouldFinalizeForRollover(
    pointer: KeyboardPointerState,
    key: KeyModel,
): Boolean {
    if (pointer.gestureTriggered || pointer.longPressTriggered) return false
    val data = pointer.dataOnDown ?: key.currentData ?: return false
    return data.rolloverEligible && data.kind == KeyboardKeyKind.Text
}

class KeyboardTouchController(
    private val keyRegistry: KeyboardKeyRegistry,
    private val inputDispatcher: KeyboardInputDispatcher,
    private val inputReceiver: KeyboardInputReceiver,
    private var performHaptic: (KeyData) -> Unit = {},
    private val onLongPress: (KeyboardPointerState, KeyModel) -> Boolean = { _, _ -> false },
    private val onGestureMove: ((KeyboardPointerState, MotionEvent) -> Boolean)? = null,
) {
    private val activePointers = ArrayList<KeyboardPointerState>(5)
    var debugVersion by mutableIntStateOf(0)
        private set

    fun setHapticHandler(handler: (KeyData) -> Unit) {
        performHaptic = handler
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        refreshPointerIndices(event)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (activePointers.isNotEmpty()) cancelAllPointers()
                handlePointerDown(event, event.actionIndex)
                // Own the whole stream even when geometry is settling so Android still sends up.
                true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                finalizePreviousCharacterKeysBeforeNewPointer()
                handlePointerDown(event, event.actionIndex)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                handleMove(event)
                true
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            -> {
                handlePointerUp(event, event.actionIndex)
                activePointers.isNotEmpty() || event.actionMasked == MotionEvent.ACTION_UP
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelAllPointers()
                true
            }
            else -> activePointers.isNotEmpty()
        }
    }

    fun finalizePreviousCharacterKeysBeforeNewPointer() {
        var index = 0
        while (index < activePointers.size) {
            val pointer = activePointers[index]
            val key = pointer.activeKey
            if (key != null && shouldFinalizeForRollover(pointer, key)) {
                handlePointerUp(pointer)
            } else {
                index++
            }
        }
    }

    fun cancelAllPointers() {
        for (index in activePointers.indices.reversed()) {
            cancelPointer(activePointers[index])
        }
        activePointers.clear()
        debugVersion++
    }

    fun dispose() {
        cancelAllPointers()
        inputDispatcher.dispose()
    }

    fun activePointersForDiagnostics(): List<KeyboardPointerState> = activePointers

    private fun handlePointerDown(event: MotionEvent, pointerIndex: Int) {
        if (pointerIndex !in 0 until event.pointerCount) return
        val pointerId = event.getPointerId(pointerIndex)
        removePointerById(pointerId)
        val pointer = KeyboardPointerState(pointerId = pointerId, pointerIndex = pointerIndex)
        pointer.lastPosition = Offset(event.getX(pointerIndex), event.getY(pointerIndex))
        activePointers.add(pointer)
        pressKeyAtCurrentPosition(event, pointer)
        if (pointer.activeKey == null) activePointers.removeAt(activePointers.lastIndex)
        debugVersion++
    }

    private fun handleMove(event: MotionEvent) {
        for (pointer in activePointers) {
            val pointerIndex = pointer.pointerIndex
            if (pointerIndex !in 0 until event.pointerCount) continue
            val position = Offset(event.getX(pointerIndex), event.getY(pointerIndex))
            pointer.lastPosition = position
            if (onGestureMove?.invoke(pointer, event) == true) {
                pointer.gestureTriggered = true
                cancelActiveKey(pointer)
                continue
            }
            val activeKey = pointer.activeKey
            if (activeKey != null &&
                !isWithinActiveKeyTolerance(position, activeKey.geometry.visualBounds)
            ) {
                cancelActiveKey(pointer)
                pressKeyAtCurrentPosition(event, pointer)
            }
        }
        debugVersion++
    }

    private fun handlePointerUp(event: MotionEvent, pointerIndex: Int) {
        if (pointerIndex !in 0 until event.pointerCount) return
        val pointer = findPointerById(event.getPointerId(pointerIndex)) ?: return
        pointer.pointerIndex = pointerIndex
        pointer.lastPosition = Offset(event.getX(pointerIndex), event.getY(pointerIndex))
        handlePointerUp(pointer)
        debugVersion++
    }

    private fun handlePointerUp(pointer: KeyboardPointerState) {
        val key = pointer.activeKey
        val dataOnDown = pointer.dataOnDown
        if (key != null) releaseVisualPress(key)
        if (!pointer.gestureTriggered && dataOnDown != null && pointer.pressedKeyInfo != null) {
            if (shouldDispatchOriginalKeyUp(
                    dataOnDown = dataOnDown,
                    currentData = key?.currentData,
                    targetStillMounted = key != null && keyRegistry.isMounted(
                        key,
                        pointer.activeKeyActivationToken,
                    ),
                )
            ) {
                inputDispatcher.sendUp(dataOnDown, pointer.pressedKeyInfo)
            } else {
                inputDispatcher.sendCancel(dataOnDown, pointer.pressedKeyInfo)
            }
        } else if (dataOnDown != null && pointer.pressedKeyInfo != null) {
            inputDispatcher.sendCancel(dataOnDown, pointer.pressedKeyInfo)
        }
        removePointer(pointer)
    }

    private fun cancelPointer(pointer: KeyboardPointerState) {
        pointer.activeKey?.let(::releaseVisualPress)
        pointer.dataOnDown?.let { data ->
            pointer.pressedKeyInfo?.let { info -> inputDispatcher.sendCancel(data, info) }
        }
        removePointer(pointer)
    }

    private fun pressKeyAtCurrentPosition(event: MotionEvent, pointer: KeyboardPointerState) {
        val pointerIndex = pointer.pointerIndex
        if (pointerIndex !in 0 until event.pointerCount) return
        val key = keyRegistry.findKeyAt(event.getX(pointerIndex), event.getY(pointerIndex)) ?: return
        val data = key.currentData ?: return

        pointer.activeKey = key
        pointer.activeKeyActivationToken = key.activationToken
        pointer.dataOnDown = data
        if (pointer.initialKey == null) pointer.initialKey = key
        key.pressedPointerCount++
        key.isPressed = true
        performHaptic(data)
        pointer.pressedKeyInfo = inputDispatcher.sendDown(
            data = data,
            onLongPress = {
                pointer.longPressTriggered = true
                onLongPress(pointer, key)
            },
            onRepeat = {
                inputReceiver.onKeyRepeat(data)
                true
            },
        )
    }

    private fun cancelActiveKey(pointer: KeyboardPointerState) {
        val data = pointer.dataOnDown
        val info = pointer.pressedKeyInfo
        pointer.activeKey?.let(::releaseVisualPress)
        if (data != null && info != null) inputDispatcher.sendCancel(data, info)
        pointer.activeKey = null
        pointer.activeKeyActivationToken = 0L
        pointer.dataOnDown = null
        pointer.pressedKeyInfo = null
        pointer.longPressTriggered = false
    }

    private fun releaseVisualPress(key: KeyModel) {
        key.pressedPointerCount = (key.pressedPointerCount - 1).coerceAtLeast(0)
        key.isPressed = key.pressedPointerCount > 0
    }

    private fun refreshPointerIndices(event: MotionEvent) {
        for (pointer in activePointers) {
            pointer.pointerIndex = event.findPointerIndex(pointer.pointerId)
        }
    }

    private fun findPointerById(pointerId: Int): KeyboardPointerState? =
        activePointers.firstOrNull { it.pointerId == pointerId }

    private fun removePointerById(pointerId: Int) {
        findPointerById(pointerId)?.let(::cancelPointer)
    }

    private fun removePointer(pointer: KeyboardPointerState) {
        activePointers.remove(pointer)
        pointer.activeKey = null
        pointer.activeKeyActivationToken = 0L
        pointer.dataOnDown = null
        pointer.pressedKeyInfo = null
    }
}

internal fun shouldDispatchOriginalKeyUp(
    dataOnDown: KeyData,
    currentData: KeyData?,
    targetStillMounted: Boolean,
): Boolean = targetStillMounted && currentData == dataOnDown
