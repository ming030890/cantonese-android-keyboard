package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.madeinhk.cantonesevoicekeyboard.R

@Composable
fun MicIcon(modifier: Modifier = Modifier, color: Color) {
    Icon(
        painter = painterResource(R.drawable.ic_mic_rounded),
        contentDescription = null,
        modifier = modifier,
        tint = color,
    )
}

@Composable
internal fun CandidateChevronIcon(
    pointsForward: Boolean,
    modifier: Modifier = Modifier,
    color: Color,
) {
    Canvas(modifier = modifier) {
        val startX = if (pointsForward) size.width * 0.35f else size.width * 0.65f
        val endX = if (pointsForward) size.width * 0.68f else size.width * 0.32f
        drawLine(
            color = color,
            start = Offset(startX, size.height * 0.2f),
            end = Offset(endX, size.height * 0.5f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(endX, size.height * 0.5f),
            end = Offset(startX, size.height * 0.8f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun BackspaceIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.5f)
            lineTo(w * 0.42f, h * 0.24f)
            lineTo(w * 0.84f, h * 0.24f)
            lineTo(w * 0.84f, h * 0.76f)
            lineTo(w * 0.42f, h * 0.76f)
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(
            color = color,
            start = Offset(w * 0.52f, h * 0.4f),
            end = Offset(w * 0.74f, h * 0.6f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.74f, h * 0.4f),
            end = Offset(w * 0.52f, h * 0.6f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun ShiftIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    filled: Boolean = false,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.18f, h * 0.46f)
            lineTo(w * 0.34f, h * 0.46f)
            lineTo(w * 0.34f, h * 0.82f)
            lineTo(w * 0.66f, h * 0.82f)
            lineTo(w * 0.66f, h * 0.46f)
            lineTo(w * 0.82f, h * 0.46f)
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = if (filled) Fill else Stroke(
                width = 2.2.dp.toPx(),
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
fun SmileIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val strokePx = 2.dp.toPx()
        drawCircle(color = color, radius = w * 0.38f, center = center, style = Stroke(strokePx))
        drawCircle(color = color, radius = w * 0.045f, center = Offset(w * 0.38f, h * 0.42f))
        drawCircle(color = color, radius = w * 0.045f, center = Offset(w * 0.62f, h * 0.42f))
        drawArc(
            color = color,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(w * 0.33f, h * 0.38f),
            size = Size(w * 0.34f, h * 0.34f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun KeyboardIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 2.dp.toPx()
        val keyRadius = CornerRadius(w * 0.05f, w * 0.05f)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.12f, h * 0.22f),
            size = Size(w * 0.76f, h * 0.56f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
            style = Stroke(width = strokePx),
        )
        val keySize = Size(w * 0.11f, h * 0.10f)
        repeat(3) { row ->
            repeat(4) { column ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(
                        w * 0.23f + column * w * 0.14f,
                        h * 0.36f + row * h * 0.14f,
                    ),
                    size = keySize,
                    cornerRadius = keyRadius,
                )
            }
        }
    }
}

@Composable
fun ClipboardIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 2.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.22f, h * 0.18f),
            size = Size(w * 0.60f, h * 0.70f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
            style = Stroke(width = strokePx),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.36f, h * 0.10f),
            size = Size(w * 0.28f, h * 0.18f),
            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
            style = Stroke(width = strokePx),
        )
        drawLine(
            color,
            Offset(w * 0.38f, h * 0.46f),
            Offset(w * 0.66f, h * 0.46f),
            strokePx,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(w * 0.38f, h * 0.62f),
            Offset(w * 0.60f, h * 0.62f),
            strokePx,
            StrokeCap.Round,
        )
    }
}

@Composable
fun KeyboardActionIcon(
    action: KeyboardAction,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 2.4.dp.toPx()
        when (action) {
            KeyboardAction.Search -> {
                drawCircle(
                    color,
                    w * 0.25f,
                    Offset(w * 0.42f, h * 0.42f),
                    style = Stroke(strokePx),
                )
                drawLine(
                    color,
                    Offset(w * 0.60f, h * 0.60f),
                    Offset(w * 0.82f, h * 0.82f),
                    strokePx,
                    StrokeCap.Round,
                )
            }
            KeyboardAction.Send -> {
                val path = Path().apply {
                    moveTo(w * 0.14f, h * 0.18f)
                    lineTo(w * 0.88f, h * 0.50f)
                    lineTo(w * 0.14f, h * 0.82f)
                    lineTo(w * 0.26f, h * 0.55f)
                    lineTo(w * 0.52f, h * 0.50f)
                    lineTo(w * 0.26f, h * 0.45f)
                    close()
                }
                drawPath(path, color)
            }
            KeyboardAction.Done -> {
                drawLine(
                    color,
                    Offset(w * 0.18f, h * 0.54f),
                    Offset(w * 0.40f, h * 0.74f),
                    strokePx,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(w * 0.40f, h * 0.74f),
                    Offset(w * 0.84f, h * 0.26f),
                    strokePx,
                    StrokeCap.Round,
                )
            }
            KeyboardAction.Go,
            KeyboardAction.Next,
            -> drawHorizontalArrow(
                color = color,
                start = Offset(w * 0.18f, h * 0.50f),
                end = Offset(w * 0.78f, h * 0.50f),
                arrowX = w * 0.58f,
                strokePx = strokePx,
            )
            KeyboardAction.Previous -> drawHorizontalArrow(
                color = color,
                start = Offset(w * 0.82f, h * 0.50f),
                end = Offset(w * 0.22f, h * 0.50f),
                arrowX = w * 0.42f,
                strokePx = strokePx,
            )
            KeyboardAction.Enter -> {
                drawLine(
                    color,
                    Offset(w * 0.78f, h * 0.22f),
                    Offset(w * 0.78f, h * 0.58f),
                    strokePx,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(w * 0.78f, h * 0.58f),
                    Offset(w * 0.24f, h * 0.58f),
                    strokePx,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(w * 0.42f, h * 0.38f),
                    Offset(w * 0.24f, h * 0.58f),
                    strokePx,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(w * 0.42f, h * 0.78f),
                    Offset(w * 0.24f, h * 0.58f),
                    strokePx,
                    StrokeCap.Round,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHorizontalArrow(
    color: Color,
    start: Offset,
    end: Offset,
    arrowX: Float,
    strokePx: Float,
) {
    drawLine(color, start, end, strokePx, StrokeCap.Round)
    drawLine(
        color,
        Offset(arrowX, end.y * 0.6f),
        end,
        strokePx,
        StrokeCap.Round,
    )
    drawLine(
        color,
        Offset(arrowX, end.y * 1.4f),
        end,
        strokePx,
        StrokeCap.Round,
    )
}

@Composable
fun SettingsIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.width * 0.40f
        val ringRadius = size.width * 0.30f
        val strokePx = 2.dp.toPx()
        repeat(8) { index ->
            val angle = (Math.PI * 2 / 8) * index
            val dx = kotlin.math.cos(angle).toFloat()
            val dy = kotlin.math.sin(angle).toFloat()
            drawLine(
                color,
                Offset(center.x + dx * ringRadius, center.y + dy * ringRadius),
                Offset(center.x + dx * outerRadius, center.y + dy * outerRadius),
                strokePx * 1.6f,
                StrokeCap.Round,
            )
        }
        drawCircle(color, ringRadius, center, style = Stroke(strokePx))
        drawCircle(color, size.width * 0.13f, center, style = Stroke(strokePx))
    }
}
