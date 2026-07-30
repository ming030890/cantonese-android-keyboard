package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

internal data class PromptedInkPoint(
    val x: Float,
    val y: Float,
    val elapsedMillis: Long,
    val pressure: Float,
    val size: Float,
    val orientationRadians: Float,
    val tiltRadians: Float?,
    val toolType: Int,
)

internal data class PromptedInkSnapshot(
    val strokes: List<List<PromptedInkPoint>>,
    val canvasSize: IntSize,
) {
    val pointCount: Int get() = strokes.sumOf(List<PromptedInkPoint>::size)
    val isEmpty: Boolean get() = pointCount == 0
    val isUsable: Boolean get() = strokes.any { stroke ->
        stroke.zipWithNext().any { (start, end) ->
            val dx = end.x - start.x
            val dy = end.y - start.y
            dx * dx + dy * dy >= 4f
        }
    }
}

@Stable
internal class PromptedInkState {
    var strokes by mutableStateOf<List<List<PromptedInkPoint>>>(emptyList())
        private set
    var activeStroke by mutableStateOf<List<PromptedInkPoint>>(emptyList())
        private set
    var canvasSize by mutableStateOf(IntSize.Zero)
        private set
    private var startedAtMillis: Long? = null

    val hasInk: Boolean get() = strokes.isNotEmpty() || activeStroke.isNotEmpty()

    fun clear() {
        strokes = emptyList()
        activeStroke = emptyList()
        startedAtMillis = null
    }

    fun undo() {
        if (activeStroke.isNotEmpty()) {
            activeStroke = emptyList()
        } else if (strokes.isNotEmpty()) {
            strokes = strokes.dropLast(1)
        }
        if (strokes.isEmpty() && activeStroke.isEmpty()) startedAtMillis = null
    }

    fun snapshot(): PromptedInkSnapshot =
        PromptedInkSnapshot(strokes = strokes, canvasSize = canvasSize)

    internal fun setCanvasSize(size: IntSize) {
        canvasSize = size
    }

    internal fun onMotionEvent(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val start = startedAtMillis ?: event.eventTime.also { startedAtMillis = it }
                activeStroke = listOf(event.point(pointerIndex, null, start))
            }
            MotionEvent.ACTION_MOVE -> {
                val start = startedAtMillis ?: event.downTime.also { startedAtMillis = it }
                val additions = buildList {
                    for (historyIndex in 0 until event.historySize) {
                        add(event.point(pointerIndex, historyIndex, start))
                    }
                    add(event.point(pointerIndex, null, start))
                }
                activeStroke = appendDistinct(activeStroke, additions)
            }
            MotionEvent.ACTION_UP -> {
                val start = startedAtMillis ?: event.downTime.also { startedAtMillis = it }
                val additions = buildList {
                    for (historyIndex in 0 until event.historySize) {
                        add(event.point(pointerIndex, historyIndex, start))
                    }
                    add(event.point(pointerIndex, null, start))
                }
                val completed = appendDistinct(activeStroke, additions)
                if (completed.isNotEmpty()) strokes = strokes + listOf(completed)
                activeStroke = emptyList()
            }
            MotionEvent.ACTION_CANCEL -> activeStroke = emptyList()
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> return true
        }
        return true
    }
}

@Composable
internal fun rememberPromptedInkState(): PromptedInkState = remember { PromptedInkState() }

@Composable
internal fun PromptedHandwritingCanvas(
    state: PromptedInkState,
    modifier: Modifier = Modifier,
) {
    val strokeColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .onSizeChanged(state::setCanvasSize)
            .pointerInteropFilter { event -> state.onMotionEvent(event) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = handwritingStrokeWidthPx(size.width, size.height)
            (state.strokes + listOfNotNull(state.activeStroke.takeIf(List<PromptedInkPoint>::isNotEmpty)))
                .forEach { points ->
                    if (points.size == 1) {
                        drawCircle(strokeColor, strokeWidth / 2f, Offset(points[0].x, points[0].y))
                    } else {
                        points.zipWithNext().forEach { (start, end) ->
                            drawLine(
                                color = strokeColor,
                                start = Offset(start.x, start.y),
                                end = Offset(end.x, end.y),
                                strokeWidth = strokeWidth,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
        }
    }
}

internal fun promptedHandwritingBitmap(snapshot: PromptedInkSnapshot): HandwritingBitmap =
    handwritingBitmap(
        strokes = snapshot.strokes.map { stroke ->
            stroke.map { point -> Offset(point.x, point.y) }
        },
        size = snapshot.canvasSize,
    )

private fun appendDistinct(
    existing: List<PromptedInkPoint>,
    additions: List<PromptedInkPoint>,
): List<PromptedInkPoint> {
    if (additions.isEmpty()) return existing
    val result = existing.toMutableList()
    additions.forEach { point ->
        val previous = result.lastOrNull()
        if (
            previous == null ||
            previous.elapsedMillis != point.elapsedMillis ||
            previous.x != point.x ||
            previous.y != point.y
        ) {
            result += point
        }
    }
    return result
}

private fun MotionEvent.point(
    pointerIndex: Int,
    historyIndex: Int?,
    startedAtMillis: Long,
): PromptedInkPoint {
    val time = historyIndex?.let(::getHistoricalEventTime) ?: eventTime
    fun axis(axis: Int): Float = if (historyIndex == null) {
        getAxisValue(axis, pointerIndex)
    } else {
        getHistoricalAxisValue(axis, pointerIndex, historyIndex)
    }
    return PromptedInkPoint(
        x = axis(MotionEvent.AXIS_X),
        y = axis(MotionEvent.AXIS_Y),
        elapsedMillis = (time - startedAtMillis).coerceAtLeast(0L),
        pressure = axis(MotionEvent.AXIS_PRESSURE),
        size = axis(MotionEvent.AXIS_SIZE),
        orientationRadians = axis(MotionEvent.AXIS_ORIENTATION),
        tiltRadians = axis(MotionEvent.AXIS_TILT).takeIf {
            getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS ||
                getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER
        },
        toolType = getToolType(pointerIndex),
    )
}
