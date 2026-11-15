package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.hypot

/**
 * Single-finger swipe/glide gesture detector for QWERTY panels ([AlphaNumPanel] and [QuickPanel]).
 *
 * Preserves tap typing, long-press popups, and fast two-thumb rollover typing:
 * - Only arms when a single pointer touches down on an `a..z` letter key.
 * - Immediately aborts if a second pointer touches down before swipe mode triggers (two-thumb rollover).
 * - Immediately aborts if a long-press triggers before swipe mode triggers.
 * - Collects full [MotionEvent] historical samples `[x, y, t_ms]` in surface pixel coordinates and
 *   exposes a bounded tail of points for smooth trail rendering while dragging.
 */
class KeyboardSwipeDetector(
    private val keyRegistry: KeyboardKeyRegistry,
    private val isEnabled: () -> Boolean,
    private val onSwipeCompleted: (centersPx: FloatArray, pointsXyt: FloatArray) -> Unit,
) {
    private var armed = false
    private var triggered = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var geometrySnapshot: EnglishSwipeGeometry? = null
    private var startX = 0f
    private var startY = 0f
    private var startTimeMs = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var maxDisplacementPx = 0f
    private var totalPathPx = 0f

    private var pointsBuf = FloatArray(3 * 256)
    private var pointCount = 0

    private val recentTrail = ArrayDeque<TrailSample>(MAX_TRAIL_POINTS)

    var trailVersion by mutableIntStateOf(0)
        private set

    val isSwipeActive: Boolean
        get() = triggered

    fun currentTrailOffsets(): List<Offset> {
        if (!triggered || recentTrail.size < 2) return emptyList()
        return recentTrail.map { Offset(it.x, it.y) }
    }

    fun onPointerDown(pointer: KeyboardPointerState, event: MotionEvent) {
        val idx = pointer.pointerIndex.takeIf { it in 0 until event.pointerCount } ?: 0
        val x = if (event.pointerCount > 0) event.getX(idx) else 0f
        val y = if (event.pointerCount > 0) event.getY(idx) else 0f
        onSampleDown(
            pointerId = pointer.pointerId,
            pointerCount = event.pointerCount,
            isSecondaryPointerDown = event.actionMasked == MotionEvent.ACTION_POINTER_DOWN,
            dataOnDown = pointer.dataOnDown,
            x = x,
            y = y,
            timeMs = event.eventTime,
        )
    }

    internal fun onSampleDown(
        pointerId: Int,
        pointerCount: Int,
        isSecondaryPointerDown: Boolean,
        dataOnDown: KeyData?,
        x: Float,
        y: Float,
        timeMs: Long,
    ) {
        if (isSecondaryPointerDown || pointerCount > 1) {
            // Fast two-thumb rollover: never hijack overlapping taps as a swipe.
            if (!triggered) reset()
            return
        }
        reset()
        if (!isEnabled()) return
        val data = dataOnDown ?: return
        val isAsciiLetter = data.kind == KeyboardKeyKind.Text &&
            data.text?.length == 1 &&
            data.text[0].lowercaseChar() in 'a'..'z'
        if (!isAsciiLetter) return
        val geom = keyRegistry.englishSwipeGeometry() ?: return

        armed = true
        triggered = false
        activePointerId = pointerId
        geometrySnapshot = geom
        startX = x
        startY = y
        startTimeMs = timeMs
        lastX = x
        lastY = y
        maxDisplacementPx = 0f
        totalPathPx = 0f
        pointCount = 0
        appendPoint(x, y, timeMs, force = true)
    }

    /**
     * Returns `true` once the pointer movement qualifies as a swipe gesture (cancelling the
     * underlying key press in [KeyboardTouchController]).
     */
    fun onPointerMove(pointer: KeyboardPointerState, event: MotionEvent): Boolean {
        if (!armed || pointer.pointerId != activePointerId) return false
        if (!triggered && (pointer.longPressTriggered || event.pointerCount > 1 || !isEnabled())) {
            reset()
            return false
        }
        val idx = event.findPointerIndex(activePointerId)
        if (idx !in 0 until event.pointerCount) return triggered

        for (h in 0 until event.historySize) {
            appendPoint(
                event.getHistoricalX(idx, h),
                event.getHistoricalY(idx, h),
                event.getHistoricalEventTime(h),
                force = false,
            )
        }
        return onSampleMove(
            pointerId = pointer.pointerId,
            pointerCount = event.pointerCount,
            longPressTriggered = pointer.longPressTriggered,
            x = event.getX(idx),
            y = event.getY(idx),
            timeMs = event.eventTime,
        )
    }

    internal fun onSampleMove(
        pointerId: Int,
        pointerCount: Int = 1,
        longPressTriggered: Boolean = false,
        x: Float,
        y: Float,
        timeMs: Long,
    ): Boolean {
        if (!armed || pointerId != activePointerId) return false
        if (!triggered && (longPressTriggered || pointerCount > 1 || !isEnabled())) {
            reset()
            return false
        }
        appendPoint(x, y, timeMs, force = false)

        if (!triggered) {
            val keyW = (geometrySnapshot?.medianKeyWidthPx ?: 0f).coerceAtLeast(16f)
            if (maxDisplacementPx >= keyW * TRIGGER_DISPLACEMENT_FACTOR &&
                totalPathPx >= keyW * TRIGGER_PATH_FACTOR
            ) {
                triggered = true
            }
        }
        if (triggered) {
            pruneTrail(timeMs)
            trailVersion++
        }
        return triggered
    }

    fun onPointerUp(pointer: KeyboardPointerState, event: MotionEvent) {
        val idx = event.findPointerIndex(activePointerId)
        val x = if (idx in 0 until event.pointerCount) event.getX(idx) else pointer.lastPosition.x
        val y = if (idx in 0 until event.pointerCount) event.getY(idx) else pointer.lastPosition.y
        onSampleUp(pointer.pointerId, x, y, event.eventTime)
    }

    internal fun onSampleUp(pointerId: Int, x: Float, y: Float, timeMs: Long) {
        if (!armed || pointerId != activePointerId) return
        val wasTriggered = triggered
        val geom = geometrySnapshot
        if (wasTriggered && geom != null) {
            appendPoint(x, y, timeMs, force = true)
            if (pointCount >= 2) {
                val pointsSnapshot = pointsBuf.copyOf(pointCount * 3)
                val centersSnapshot = geom.centersPx.copyOf()
                reset()
                onSwipeCompleted(centersSnapshot, pointsSnapshot)
                return
            }
        }
        reset()
    }

    fun onCancel() {
        reset()
    }

    private fun appendPoint(x: Float, y: Float, timeMs: Long, force: Boolean) {
        if (!x.isFinite() || !y.isFinite()) return
        if (pointCount > 0) {
            val seg = hypot(x - lastX, y - lastY)
            if (!force && seg < MIN_SAMPLE_DIST_PX) return
            totalPathPx += seg
        }
        lastX = x
        lastY = y
        val disp = hypot(x - startX, y - startY)
        if (disp > maxDisplacementPx) maxDisplacementPx = disp

        if (pointCount < MAX_CAPTURED_POINTS) {
            if ((pointCount + 1) * 3 > pointsBuf.size) {
                pointsBuf = pointsBuf.copyOf(pointsBuf.size * 2)
            }
            val base = pointCount * 3
            pointsBuf[base] = x
            pointsBuf[base + 1] = y
            pointsBuf[base + 2] = (timeMs - startTimeMs).coerceAtLeast(0L).toFloat()
            pointCount++
        }

        if (recentTrail.size >= MAX_TRAIL_POINTS) recentTrail.removeFirst()
        recentTrail.addLast(TrailSample(x, y, timeMs))
    }

    private fun pruneTrail(nowMs: Long) {
        while (recentTrail.size > 2 && nowMs - recentTrail.first().timeMs > TRAIL_DECAY_MS) {
            recentTrail.removeFirst()
        }
    }

    private fun reset() {
        val hadTrail = triggered && recentTrail.isNotEmpty()
        armed = false
        triggered = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        geometrySnapshot = null
        pointCount = 0
        recentTrail.clear()
        if (hadTrail) trailVersion++
    }

    private data class TrailSample(val x: Float, val y: Float, val timeMs: Long)

    companion object {
        private const val TRIGGER_DISPLACEMENT_FACTOR = 0.85f
        private const val TRIGGER_PATH_FACTOR = 0.90f
        private const val MIN_SAMPLE_DIST_PX = 1.5f
        private const val MAX_CAPTURED_POINTS = 2048
        private const val MAX_TRAIL_POINTS = 32
        private const val TRAIL_DECAY_MS = 220L
    }
}

internal fun DrawScope.drawSwipeTrail(points: List<Offset>, color: Color) {
    if (points.size < 2) return
    val bands = 4
    val minWidth = 3.0f * density
    val maxWidth = 9.5f * density
    for (band in 0 until bands) {
        val bandFraction = band.toFloat() / bands.toFloat()
        val startIndex = ((points.size - 1) * bandFraction * 0.78f).toInt().coerceIn(0, points.size - 2)
        val path = buildSmoothTrailPath(points, startIndex)
        val width = minWidth + (maxWidth - minWidth) * ((band + 1).toFloat() / bands.toFloat())
        if (band == bands - 1) {
            drawPath(
                path = path,
                color = TurtleMascotPalette.CapAqua.copy(alpha = 0.22f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = width * 1.85f,
                    cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
        drawPath(
            path = path,
            color = color.copy(alpha = 0.22f + 0.10f * band),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = width,
                cap = StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
    drawTurtleSwipeHead(points = points, trailColor = color)
}

private fun buildSmoothTrailPath(points: List<Offset>, startIndex: Int): androidx.compose.ui.graphics.Path {
    return androidx.compose.ui.graphics.Path().apply {
        val first = points[startIndex]
        moveTo(first.x, first.y)
        if (points.size - startIndex == 2) {
            val last = points.last()
            lineTo(last.x, last.y)
            return@apply
        }
        for (i in startIndex + 1 until points.size - 1) {
            val curr = points[i]
            val next = points[i + 1]
            quadraticTo(curr.x, curr.y, (curr.x + next.x) * 0.5f, (curr.y + next.y) * 0.5f)
        }
        val end = points.last()
        lineTo(end.x, end.y)
    }
}
