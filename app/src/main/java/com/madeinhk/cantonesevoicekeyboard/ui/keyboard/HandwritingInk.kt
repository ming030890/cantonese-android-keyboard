package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme

private val DisplayInkStrokeWidth = 4.5.dp

/** Display-only ink styling. Recognition receives the original, unfiltered points. */
@Composable
internal fun HandwritingInk(strokes: List<List<StrokePoint>>, modifier: Modifier = Modifier) {
    val inkColor = BrandTheme.colors.keyboardKeyOnSurface
    val paths = remember(strokes) {
        strokes.filter { it.size > 1 }.map { points ->
            Path().apply {
                moveTo(points.first().x, points.first().y)
                if (points.size == 2) {
                    lineTo(points[1].x, points[1].y)
                } else {
                    // Midpoint quadratics smooth corners without delaying the pen tip.
                    for (i in 1 until points.lastIndex) {
                        val point = points[i]
                        val next = points[i + 1]
                        quadraticTo(point.x, point.y, (point.x + next.x) / 2f, (point.y + next.y) / 2f)
                    }
                    lineTo(points.last().x, points.last().y)
                }
            }
        }
    }
    val dots = remember(strokes) { strokes.filter { it.size == 1 }.map { Offset(it[0].x, it[0].y) } }
    Canvas(modifier) {
        val width = DisplayInkStrokeWidth.toPx()
        val strokeStyle = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        paths.forEach { path ->
            drawPath(path, inkColor, style = strokeStyle)
        }
        dots.forEach { point ->
            drawCircle(inkColor, width / 2f, point)
        }
    }
}
