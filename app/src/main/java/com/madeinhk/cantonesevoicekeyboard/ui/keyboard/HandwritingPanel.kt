package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.graphics.Bitmap
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.HandwritingCanvasGeometry

import com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme

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
    // A recognition result advances this key in the same main-thread update as editor text.
    var strokes by remember(sessionKey) { mutableStateOf<List<HandwritingStroke>>(emptyList()) }
    var activeStroke by remember(sessionKey) { mutableStateOf<HandwritingStroke>(emptyList()) }
    var activePointerId by remember(sessionKey) { mutableStateOf(MotionEvent.INVALID_POINTER_ID) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    fun finishActiveStroke(finalSamples: List<StrokePoint>) {
        if (activeStroke.isEmpty()) {
            activePointerId = MotionEvent.INVALID_POINTER_ID
            return
        }
        val completedStroke = activeStroke + finalSamples
        val completed = strokes + listOf(completedStroke)
        strokes = completed
        activeStroke = emptyList()
        activePointerId = MotionEvent.INVALID_POINTER_ID
        val width = canvasSize.width.coerceAtLeast(1)
        val height = canvasSize.height.coerceAtLeast(1)
        onStrokesChanged(
            completed, width.toFloat(), height.toFloat(),
            ByteArray(0), width, height,
        )
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
                        activePointerId = event.getPointerId(0)
                        onStrokeStarted(strokes.isEmpty())
                        activeStroke = listOf(StrokePoint(event.x, event.y, event.eventTime))
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0 && activeStroke.isNotEmpty()) {
                            activeStroke = activeStroke + event.handwritingStrokeSamples(pointerIndex)
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        val liftedIndex = event.actionIndex
                        if (liftedIndex in 0 until event.pointerCount &&
                            event.getPointerId(liftedIndex) == activePointerId
                        ) {
                            finishActiveStroke(
                                listOf(
                                    StrokePoint(
                                        event.getX(liftedIndex),
                                        event.getY(liftedIndex),
                                        event.eventTime,
                                    )
                                )
                            )
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0) {
                            finishActiveStroke(event.handwritingStrokeSamples(pointerIndex))
                        } else {
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            activeStroke = emptyList()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        activeStroke = emptyList()
                    }
                }
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        val padShape = RoundedCornerShape(KeyboardUiDefaults.StandardKeyCornerRadius)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = KeyboardUiDefaults.HorizontalKeyInset,
                    vertical = KeyboardUiDefaults.VerticalKeyInset,
                )
                .background(
                    color = BrandTheme.colors.keyboardKeySurface,
                    shape = padShape,
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = padShape,
                ),
        )
        HandwritingInk(
            strokes = strokes + listOfNotNull(activeStroke.takeIf { it.isNotEmpty() }),
            modifier = Modifier.fillMaxSize(),
        )
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

private fun MotionEvent.handwritingStrokeSamples(pointerIndex: Int = 0): List<StrokePoint> = buildList(historySize + 1) {
    for (index in 0 until historySize) {
        add(
            StrokePoint(
                getHistoricalX(pointerIndex, index),
                getHistoricalY(pointerIndex, index),
                getHistoricalEventTime(index),
            )
        )
    }
    add(StrokePoint(getX(pointerIndex), getY(pointerIndex), eventTime))
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
    val pixels = ByteArray(colors.size) { index ->
        android.graphics.Color.red(colors[index]).toByte()
    }
    bitmap.recycle()
    return HandwritingBitmap(pixels, width, height)
}
