package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.graphics.Bitmap
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.HandwritingCanvasGeometry
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingInkClearDelayMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint

private typealias HandwritingStroke = List<StrokePoint>

@Composable
internal fun HandwritingPanel(
    modifier: Modifier,
    sessionKey: Long,
    onStrokeStarted: (startsNewCharacter: Boolean) -> Unit,
    onBoundsChanged: (Rect) -> Unit,
    onStrokesChanged: (List<List<StrokePoint>>, Float, Float, ByteArray, Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val inspectionMode = LocalInspectionMode.current
    var strokes by remember { mutableStateOf<List<HandwritingStroke>>(emptyList()) }
    var activeStroke by remember { mutableStateOf<HandwritingStroke>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var clearJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val strokeColor = MaterialTheme.colorScheme.primary
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(sessionKey) {
        clearJob?.cancel()
        clearJob = null
        strokes = emptyList()
        activeStroke = emptyList()
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                canvasSize = coordinates.size
                if (!inspectionMode) {
                    HandwritingCanvasGeometry.record(context, coordinates.size)
                }
                val bounds = coordinates.boundsInRoot()
                onBoundsChanged(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
            }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        clearJob?.cancel()
                        onStrokeStarted(strokes.isEmpty())
                        activeStroke = listOf(StrokePoint(event.x, event.y, event.eventTime))
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (activeStroke.isNotEmpty()) {
                            activeStroke = activeStroke + event.handwritingStrokeSamples()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (activeStroke.isNotEmpty()) {
                            val completedStroke = activeStroke + event.handwritingStrokeSamples()
                            val completed = strokes + listOf(completedStroke)
                            strokes = completed
                            activeStroke = emptyList()
                            val width = canvasSize.width.coerceAtLeast(1)
                            val height = canvasSize.height.coerceAtLeast(1)
                            val bitmap = handwritingBitmap(completed, size = canvasSize)
                            onStrokesChanged(
                                completed,
                                width.toFloat(),
                                height.toFloat(),
                                bitmap.pixels,
                                bitmap.width,
                                bitmap.height,
                            )
                            clearJob = scope.launch {
                                delay(HandwritingInkClearDelayMillis)
                                strokes = emptyList()
                                activeStroke = emptyList()
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> activeStroke = emptyList()
                }
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = handwritingStrokeWidthPx(size.width, size.height)
            val allStrokes = strokes + listOfNotNull(activeStroke.takeIf { it.isNotEmpty() })
            allStrokes.forEach { points ->
                if (points.isEmpty()) {
                    return@forEach
                } else if (points.size == 1) {
                    drawCircle(
                        color = strokeColor,
                        radius = strokeWidth / 2f,
                        center = Offset(points.first().x, points.first().y),
                    )
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
        if (strokes.isEmpty() && activeStroke.isEmpty()) {
            Text(
                text = stringResource(R.string.keyboard_handwriting_hint),
                color = hintColor,
                style = com.madeinhk.cantonesevoicekeyboard.theme.KeyboardStatusTextStyle,
            )
        }
    }
}

internal data class HandwritingBitmap(
    val pixels: ByteArray,
    val width: Int,
    val height: Int,
)

private fun MotionEvent.handwritingStrokeSamples(): List<StrokePoint> = buildList(historySize + 1) {
    for (index in 0 until historySize) {
        add(StrokePoint(getHistoricalX(index), getHistoricalY(index), getHistoricalEventTime(index)))
    }
    add(StrokePoint(x, y, eventTime))
}

internal fun handwritingStrokeWidthPx(width: Float, height: Float): Float =
    (minOf(width, height) * KeyboardUiDefaults.HandwritingStrokeWidthFraction)
        .coerceAtLeast(KeyboardUiDefaults.MinimumHandwritingStrokeWidthPx)

internal fun handwritingBitmap(
    strokes: List<HandwritingStroke>,
    size: IntSize?,
): HandwritingBitmap {
    val width = (size?.width ?: 400).coerceAtLeast(1)
    val height = (size?.height ?: 180).coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = handwritingStrokeWidthPx(width.toFloat(), height.toFloat())
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { points ->
        if (points.isEmpty()) {
            return@forEach
        } else if (points.size == 1) {
            canvas.drawCircle(points.first().x, points.first().y, paint.strokeWidth / 2f, paint)
        } else {
            points.zipWithNext().forEach { (start, end) ->
                canvas.drawLine(start.x, start.y, end.x, end.y, paint)
            }
        }
    }
    val colors = IntArray(width * height)
    bitmap.getPixels(colors, 0, width, 0, 0, width, height)
    return HandwritingBitmap(
        pixels = ByteArray(colors.size) { index ->
            android.graphics.Color.red(colors[index]).toByte()
        },
        width = width,
        height = height,
    )
}
