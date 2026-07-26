package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.IntSize

/**
 * Owns the active coordinate-to-virtual-button mapping.
 *
 * Creating a [KeyModel] does not make it clickable. A Compose visual must mount the model and
 * report valid bounds. Unmounting removes both the semantic action and its stale geometry.
 */
class KeyboardKeyRegistry {
    private val allKeys = ArrayList<KeyModel>(64)
    private val mountedKeys = ArrayList<KeyModel>(64)
    private val rows = ArrayList<RowGeometry>(16)
    private var surfaceOriginInRoot = Offset.Zero
    private var surfaceSize = IntSize.Zero
    private var nextActivationToken = 1L

    var geometryVersion by mutableIntStateOf(0)
        private set

    fun key(
        id: String,
        data: KeyData,
        rowId: String,
    ): KeyModel {
        var model = findById(id)
        if (model == null) {
            model = KeyModel(id)
            allKeys.add(model)
        }
        model.currentData = data
        model.rowId = rowId
        return model
    }

    fun mount(key: KeyModel) {
        if (mountedKeys.containsIdentity(key)) return
        key.activationToken = nextActivationToken++
        key.globalVisualBounds = Rect.Zero
        key.geometry = KeyGeometry()
        mountedKeys.add(key)
        recomputeTouchBounds()
    }

    fun unmount(key: KeyModel) {
        if (!mountedKeys.removeIdentity(key)) return
        key.activationToken = nextActivationToken++
        key.globalVisualBounds = Rect.Zero
        key.geometry = KeyGeometry()
        key.pressedPointerCount = 0
        key.isPressed = false
        recomputeTouchBounds()
    }

    fun isMounted(key: KeyModel, activationToken: Long = key.activationToken): Boolean =
        key.activationToken == activationToken && mountedKeys.containsIdentity(key)

    fun updateSurfaceCoordinates(coordinates: LayoutCoordinates) {
        val rootBounds = coordinates.boundsInRoot()
        surfaceOriginInRoot = rootBounds.topLeft
        surfaceSize = IntSize(coordinates.size.width, coordinates.size.height)
        for (key in mountedKeys) {
            key.geometry = key.geometry.copy(
                visualBounds = key.globalVisualBounds.translate(
                    -surfaceOriginInRoot.x,
                    -surfaceOriginInRoot.y,
                ),
            )
        }
        recomputeTouchBounds()
    }

    fun updateVisualBounds(key: KeyModel, coordinates: LayoutCoordinates) {
        if (!isMounted(key)) return
        val rootBounds = coordinates.boundsInRoot()
        key.globalVisualBounds = rootBounds
        key.geometry = key.geometry.copy(
            visualBounds = rootBounds.translate(-surfaceOriginInRoot.x, -surfaceOriginInRoot.y),
        )
        recomputeTouchBounds()
    }

    /** Testable/local-layout overload. Bounds are already in keyboard-surface coordinates. */
    fun updateVisualBounds(key: KeyModel, bounds: Rect) {
        if (!isMounted(key)) return
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
        for (key in mountedKeys) {
            if (key.geometry.touchBounds.contains(Offset(x, y))) return key
        }
        return null
    }

    fun mountedKeysForDiagnostics(): List<KeyModel> = mountedKeys

    private fun findById(id: String): KeyModel? {
        for (key in allKeys) {
            if (key.id == id) return key
        }
        return null
    }

    private fun recomputeTouchBounds() {
        rows.clear()
        for (key in mountedKeys) {
            val visual = key.geometry.visualBounds
            if (visual.width <= 0f || visual.height <= 0f || key.rowId.isEmpty()) continue
            var row = rows.firstOrNull { it.id == key.rowId }
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
                    touchBounds = Rect(left, top, right, bottom),
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
    bottom + dy,
)

private fun <T> ArrayList<T>.containsIdentity(value: T): Boolean =
    any { it === value }

private fun <T> ArrayList<T>.removeIdentity(value: T): Boolean {
    for (index in indices) {
        if (this[index] === value) {
            removeAt(index)
            return true
        }
    }
    return false
}
