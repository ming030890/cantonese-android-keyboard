package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DefaultLongPressDelayMillis = 450L
private const val DefaultRepeatIntervalMillis = 55L

/** The semantic action represented by a key at the moment it is pressed. */
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
    Clipboard,
    Record,
    Utility
}

data class KeyData(
    val keyId: String,
    val kind: KeyboardKeyKind,
    val text: String? = null,
    val popupLabel: String? = null,
    val rolloverEligible: Boolean = kind == KeyboardKeyKind.Text,
    val repeatable: Boolean = kind == KeyboardKeyKind.Delete,
    val longPressEnabled: Boolean = repeatable
)

data class KeyGeometry(
    val visualBounds: Rect = Rect.Zero,
    val touchBounds: Rect = Rect.Zero
)

/** Stable identity and current rendering/input state for one passive key visual. */
class KeyModel(
    val id: String
) {
    var currentData: KeyData? = null
    var enabled: Boolean = true
    var rowId: String = ""
    var geometry: KeyGeometry = KeyGeometry()
    var isPressed by mutableStateOf(false)

    internal var pressedPointerCount: Int = 0
    internal var globalVisualBounds: Rect = Rect.Zero
}

/**
 * Keeps key identities and the geometry reported by the existing Compose layout.
 * Geometry is collected after measurement; no second layout is performed.
 */
class KeyboardKeyRegistry {
    private val allKeys = ArrayList<KeyModel>(64)
    private val visibleKeys = ArrayList<KeyModel>(64)
    private val rows = ArrayList<RowGeometry>(16)
    private var surfaceOriginInRoot = Offset.Zero
    private var surfaceSize = IntSize.Zero

    var geometryVersion by mutableIntStateOf(0)
        private set

    fun beginLayout() {
        visibleKeys.clear()
    }

    fun key(
        id: String,
        data: KeyData,
        rowId: String,
        enabled: Boolean = true
    ): KeyModel {
        var model = findById(id)
        if (model == null) {
            model = KeyModel(id)
            allKeys.add(model)
        }
        model.currentData = data
        model.rowId = rowId
        model.enabled = enabled
        if (!visibleKeys.containsIdentity(model)) {
            visibleKeys.add(model)
        }
        return model
    }

    fun updateSurfaceCoordinates(coordinates: LayoutCoordinates) {
        val rootBounds = coordinates.boundsInRoot()
        surfaceOriginInRoot = rootBounds.topLeft
        surfaceSize = IntSize(coordinates.size.width, coordinates.size.height)
        for (key in visibleKeys) {
            key.geometry = key.geometry.copy(
                visualBounds = key.globalVisualBounds.translate(-surfaceOriginInRoot.x, -surfaceOriginInRoot.y)
            )
        }
        recomputeTouchBounds()
    }

    fun updateVisualBounds(key: KeyModel, coordinates: LayoutCoordinates) {
        val rootBounds = coordinates.boundsInRoot()
        key.globalVisualBounds = rootBounds
        key.geometry = key.geometry.copy(
            visualBounds = rootBounds.translate(-surfaceOriginInRoot.x, -surfaceOriginInRoot.y)
        )
        recomputeTouchBounds()
    }

    /** Testable/local-layout overload. Bounds are already in keyboard-surface coordinates. */
    fun updateVisualBounds(key: KeyModel, bounds: Rect) {
        key.globalVisualBounds = bounds.translate(surfaceOriginInRoot.x, surfaceOriginInRoot.y)
        key.geometry = key.geometry.copy(visualBounds = bounds)
        recomputeTouchBounds()
    }

    /** Testable/local-layout overload. */
    fun updateSurfaceSize(width: Int, height: Int) {
        surfaceSize = IntSize(width, height)
        recomputeTouchBounds()
    }

    fun findKeyAt(x: Float, y: Float): KeyModel? {
        var index = 0
        while (index < visibleKeys.size) {
            val key = visibleKeys[index]
            if (key.enabled && key.geometry.touchBounds.contains(Offset(x, y))) {
                return key
            }
            index++
        }
        return null
    }

    fun visibleKeysForDiagnostics(): List<KeyModel> = visibleKeys

    private fun findById(id: String): KeyModel? {
        var index = 0
        while (index < allKeys.size) {
            val key = allKeys[index]
            if (key.id == id) return key
            index++
        }
        return null
    }

    private fun recomputeTouchBounds() {
        rows.clear()
        for (key in visibleKeys) {
            val visual = key.geometry.visualBounds
            if (visual.width <= 0f || visual.height <= 0f || key.rowId.isEmpty()) continue
            var row: RowGeometry? = null
            for (candidate in rows) {
                if (candidate.id == key.rowId) {
                    row = candidate
                    break
                }
            }
            if (row == null) {
                row = RowGeometry(key.rowId)
                rows.add(row)
            }
            row.keys.add(key)
            row.top = minOf(row.top, visual.top)
            row.bottom = maxOf(row.bottom, visual.bottom)
        }

        rows.sortBy { it.top }
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            row.keys.sortBy { it.geometry.visualBounds.left }
            val top = if (rowIndex == 0) {
                // Suggestions and other non-key content can sit above the keyboard grid.
                // Only horizontal row edges are extended unconditionally.
                row.top
            } else {
                val previous = rows[rowIndex - 1]
                (previous.bottom + row.top) / 2f
            }
            val bottom = if (rowIndex == rows.lastIndex) {
                surfaceSize.height.toFloat()
            } else {
                val next = rows[rowIndex + 1]
                (row.bottom + next.top) / 2f
            }

            for (keyIndex in row.keys.indices) {
                val key = row.keys[keyIndex]
                val visual = key.geometry.visualBounds
                val left = if (keyIndex == 0) {
                    0f
                } else {
                    val previous = row.keys[keyIndex - 1].geometry.visualBounds
                    (previous.right + visual.left) / 2f
                }
                val right = if (keyIndex == row.keys.lastIndex) {
                    surfaceSize.width.toFloat()
                } else {
                    val next = row.keys[keyIndex + 1].geometry.visualBounds
                    (visual.right + next.left) / 2f
                }
                key.geometry = key.geometry.copy(
                    touchBounds = Rect(left, top, right, bottom)
                )
            }
        }
        geometryVersion++
    }

    private class RowGeometry(val id: String) {
        val keys = ArrayList<KeyModel>(12)
        var top: Float = Float.POSITIVE_INFINITY
        var bottom: Float = Float.NEGATIVE_INFINITY
    }
}

private fun Rect.translate(dx: Float, dy: Float): Rect = Rect(
    left + dx,
    top + dy,
    right + dx,
    bottom + dy
)

private fun <T> ArrayList<T>.containsIdentity(value: T): Boolean {
    for (item in this) {
        if (item === value) return true
    }
    return false
}

data class KeyboardPointerState(
    val pointerId: Int,
    var pointerIndex: Int = -1,
    var initialKey: KeyModel? = null,
    var activeKey: KeyModel? = null,
    var dataOnDown: KeyData? = null,
    var pressedKeyInfo: PressedKeyInfo? = null,
    var longPressTriggered: Boolean = false,
    var gestureTriggered: Boolean = false,
    var lastPosition: Offset = Offset.Zero
)

class PressedKeyInfo(
    val downTimeMillis: Long,
    internal var job: Job? = null,
    var blockUp: Boolean = false
)

interface KeyboardInputReceiver {
    fun onKeyDown(data: KeyData)
    fun onKeyUp(data: KeyData)
    fun onKeyCancel(data: KeyData)
    fun onKeyRepeat(data: KeyData)
}

interface KeyboardInputDispatcher {
    fun sendDown(
        data: KeyData,
        onLongPress: () -> Boolean = { false },
        onRepeat: () -> Boolean = { false }
    ): PressedKeyInfo?

    fun sendUp(data: KeyData, pressedKeyInfo: PressedKeyInfo? = null)
    fun sendCancel(data: KeyData, pressedKeyInfo: PressedKeyInfo? = null)
    fun sendDownUp(data: KeyData)
    fun dispose()
}

class CoroutineKeyboardInputDispatcher(
    private val receiver: KeyboardInputReceiver,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    ),
    private val longPressDelayMillis: Long = DefaultLongPressDelayMillis,
    private val repeatIntervalMillis: Long = DefaultRepeatIntervalMillis,
    private val nowMillis: () -> Long = SystemClock::uptimeMillis
) : KeyboardInputDispatcher {
    override fun sendDown(
        data: KeyData,
        onLongPress: () -> Boolean,
        onRepeat: () -> Boolean
    ): PressedKeyInfo {
        receiver.onKeyDown(data)
        val info = PressedKeyInfo(nowMillis())
        if (data.longPressEnabled) {
            info.job = scope.launch {
                delay(longPressDelayMillis)
                if (!isActive) return@launch
                val consumed = onLongPress()
                info.blockUp = consumed
                if (!consumed && data.repeatable) {
                    while (isActive) {
                        delay(repeatIntervalMillis)
                        if (!onRepeat()) break
                    }
                }
            }
        }
        return info
    }

    override fun sendUp(data: KeyData, pressedKeyInfo: PressedKeyInfo?) {
        pressedKeyInfo?.job?.cancel()
        if (pressedKeyInfo?.blockUp != true) {
            receiver.onKeyUp(data)
        }
    }

    override fun sendCancel(data: KeyData, pressedKeyInfo: PressedKeyInfo?) {
        pressedKeyInfo?.job?.cancel()
        receiver.onKeyCancel(data)
    }

    override fun sendDownUp(data: KeyData) {
        receiver.onKeyDown(data)
        receiver.onKeyUp(data)
    }

    override fun dispose() {
        scope.coroutineContext[Job]?.cancel()
    }
}

fun isWithinActiveKeyTolerance(
    position: Offset,
    bounds: Rect,
    horizontalTolerance: Float = HorizontalMoveTolerance,
    verticalTolerance: Float = VerticalMoveTolerance
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
    key: KeyModel
): Boolean {
    if (pointer.gestureTriggered || pointer.longPressTriggered) return false
    val data = pointer.dataOnDown ?: key.currentData ?: return false
    return data.rolloverEligible && data.kind == KeyboardKeyKind.Text
}

const val HorizontalMoveTolerance = 0.10f
const val VerticalMoveTolerance = 0.35f

class KeyboardTouchController(
    private val keyRegistry: KeyboardKeyRegistry,
    private val inputDispatcher: KeyboardInputDispatcher,
    private val inputReceiver: KeyboardInputReceiver,
    private var performHaptic: (KeyData) -> Unit = {},
    private val onLongPress: (KeyboardPointerState, KeyModel) -> Boolean = { _, _ -> false },
    private val onGestureMove: ((KeyboardPointerState, MotionEvent) -> Boolean)? = null
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
                // Own the complete stream once it enters the keyboard surface. Returning false
                // for a down outside a measured key prevents Android from delivering its up,
                // which made toolbar controls intermittent while their geometry was settling.
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
            MotionEvent.ACTION_UP -> {
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
        var index = activePointers.size - 1
        while (index >= 0) {
            cancelPointer(activePointers[index])
            index--
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
        if (pointerIndex < 0 || pointerIndex >= event.pointerCount) return
        val pointerId = event.getPointerId(pointerIndex)
        removePointerById(pointerId)
        val pointer = KeyboardPointerState(pointerId = pointerId, pointerIndex = pointerIndex)
        pointer.lastPosition = Offset(event.getX(pointerIndex), event.getY(pointerIndex))
        activePointers.add(pointer)
        pressKeyAtCurrentPosition(event, pointer)
        if (pointer.activeKey == null) {
            activePointers.removeAt(activePointers.lastIndex)
        }
        debugVersion++
    }

    private fun handleMove(event: MotionEvent) {
        var index = 0
        while (index < activePointers.size) {
            val pointer = activePointers[index]
            val pointerIndex = pointer.pointerIndex
            if (pointerIndex < 0 || pointerIndex >= event.pointerCount) {
                index++
                continue
            }
            val position = Offset(event.getX(pointerIndex), event.getY(pointerIndex))
            pointer.lastPosition = position
            if (onGestureMove?.invoke(pointer, event) == true) {
                pointer.gestureTriggered = true
                cancelActiveKey(pointer)
                index++
                continue
            }
            val activeKey = pointer.activeKey
            if (activeKey != null && !isWithinActiveKeyTolerance(position, activeKey.geometry.visualBounds)) {
                cancelActiveKey(pointer)
                pressKeyAtCurrentPosition(event, pointer)
            }
            index++
        }
        debugVersion++
    }

    private fun handlePointerUp(event: MotionEvent, pointerIndex: Int) {
        if (pointerIndex < 0 || pointerIndex >= event.pointerCount) return
        val pointer = findPointerById(event.getPointerId(pointerIndex)) ?: return
        pointer.pointerIndex = pointerIndex
        pointer.lastPosition = Offset(event.getX(pointerIndex), event.getY(pointerIndex))
        handlePointerUp(pointer)
        debugVersion++
    }

    private fun handlePointerUp(pointer: KeyboardPointerState) {
        val key = pointer.activeKey
        val dataOnDown = pointer.dataOnDown
        if (key != null) {
            releaseVisualPress(key)
        }
        if (!pointer.gestureTriggered && dataOnDown != null && pointer.pressedKeyInfo != null) {
            val currentData = key?.currentData
            if (currentData == dataOnDown) {
                inputDispatcher.sendUp(dataOnDown, pointer.pressedKeyInfo)
            } else {
                inputDispatcher.sendCancel(dataOnDown, pointer.pressedKeyInfo)
                if (currentData != null && key.enabled) {
                    inputDispatcher.sendDownUp(currentData)
                }
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
        if (pointerIndex < 0 || pointerIndex >= event.pointerCount) return
        val key = keyRegistry.findKeyAt(event.getX(pointerIndex), event.getY(pointerIndex)) ?: return
        if (!key.enabled) return
        val data = key.currentData ?: return

        pointer.activeKey = key
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
            }
        )
    }

    private fun cancelActiveKey(pointer: KeyboardPointerState) {
        val data = pointer.dataOnDown
        val info = pointer.pressedKeyInfo
        pointer.activeKey?.let(::releaseVisualPress)
        if (data != null && info != null) {
            inputDispatcher.sendCancel(data, info)
        }
        pointer.activeKey = null
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

    private fun findPointerById(pointerId: Int): KeyboardPointerState? {
        for (pointer in activePointers) {
            if (pointer.pointerId == pointerId) return pointer
        }
        return null
    }

    private fun removePointerById(pointerId: Int) {
        val existing = findPointerById(pointerId) ?: return
        cancelPointer(existing)
    }

    private fun removePointer(pointer: KeyboardPointerState) {
        activePointers.remove(pointer)
        pointer.activeKey = null
        pointer.dataOnDown = null
        pointer.pressedKeyInfo = null
    }
}

fun performKeyboardHaptic(
    view: View,
    config: KeyboardHapticConfig = KeyboardHapticConfig(),
) {
    val hapticConfig = config.clamped()
    if (!hapticConfig.shouldVibrate()) return
    if (hapticConfig.respectSystemSettings && !view.isHapticFeedbackEnabled) return

    if (hapticConfig.useSystemHapticInterface &&
        hapticConfig.respectSystemSettings &&
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    ) {
        return
    }

    val vibratorManager = view.context.getSystemService(VibratorManager::class.java)
        ?: return
    val vibrator = vibratorManager.defaultVibrator
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(
        VibrationEffect.createOneShot(
            hapticConfig.durationMs.toLong(),
            keyboardVibrationAmplitude(hapticConfig.strengthPercent)
        )
    )
}
