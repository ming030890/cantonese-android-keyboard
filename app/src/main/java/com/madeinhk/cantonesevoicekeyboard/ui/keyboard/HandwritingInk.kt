package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint

/** Display-only ink styling. Recognition receives the original, unfiltered points. */
@Composable
internal fun HandwritingInk(strokes: List<List<StrokePoint>>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val paths = remember(strokes) {
        strokes.filter { it.size > 1 }.map { points ->
            Path().apply {
                moveTo(points.first().x, points.first().y)
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
    val dots = remember(strokes) { strokes.filter { it.size == 1 }.map { Offset(it[0].x, it[0].y) } }
    Canvas(modifier) {
        val width = handwritingStrokeWidthPx(size.width, size.height)
        val brush = Brush.linearGradient(listOf(primary, secondary), Offset.Zero, Offset(size.width, size.height))
        // A quiet translucent edge gives the ink depth without obscuring small details.
        paths.forEach { path ->
            drawPath(path, primary.copy(alpha = 0.10f), style = Stroke(width * 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, brush, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        dots.forEach { point ->
            drawCircle(primary.copy(alpha = 0.10f), width * 0.9f, point)
            drawCircle(brush, width / 2f, point)
        }
    }
}
