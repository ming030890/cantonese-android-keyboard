package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

internal object TurtleMascotPalette {
    val LimeHead = Color(0xFFD8EC90)
    val Outline = Color(0xFF482408)
    val ShellGreen = Color(0xFF48B078)
    val MouthPink = Color(0xFFF89890)
    val Blush = Color(0xFFF5A396)
    val CapAqua = Color(0xFF74D8D8)
    val CapIce = Color(0xFFC8F4F8)
}

internal enum class TurtleVoiceMood {
    Sleeping,
    Listening,
    Thinking,
}

/**
 * Monochrome toolbar emblem matching the stroke weight of [SmileIcon] and [SettingsIcon],
 * depicting the app's baby turtle wearing its signature curved nightcap and pom-pom.
 */
@Composable
fun TurtleBrandIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = (w * 0.082f).coerceAtLeast(0.9.dp.toPx())
        val thinStrokePx = strokePx * 0.82f

        // Nightcap arch curving from upper-left of head over to the right pom-pom
        val capPath = Path().apply {
            moveTo(w * 0.21f, h * 0.45f)
            cubicTo(
                w * 0.24f, h * 0.10f,
                w * 0.68f, h * 0.08f,
                w * 0.80f, h * 0.41f,
            )
            cubicTo(
                w * 0.68f, h * 0.34f,
                w * 0.44f, h * 0.31f,
                w * 0.21f, h * 0.45f,
            )
            close()
        }
        drawPath(
            path = capPath,
            color = color,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Subtle stripe accent inside nightcap
        val stripePath = Path().apply {
            moveTo(w * 0.31f, h * 0.28f)
            quadraticTo(w * 0.54f, h * 0.22f, w * 0.72f, h * 0.34f)
        }
        drawPath(
            path = stripePath,
            color = color.copy(alpha = 0.72f),
            style = Stroke(width = thinStrokePx * 0.85f, cap = StrokeCap.Round),
        )

        // Pom-pom on the right tip of the nightcap
        drawCircle(
            color = color,
            radius = w * 0.095f,
            center = Offset(w * 0.85f, h * 0.47f),
            style = Stroke(width = strokePx),
        )

        // Chubby horizontal oval turtle head
        val headLeft = w * 0.11f
        val headTop = h * 0.36f
        val headWidth = w * 0.66f
        val headHeight = h * 0.50f
        drawOval(
            color = color,
            topLeft = Offset(headLeft, headTop),
            size = Size(headWidth, headHeight),
            style = Stroke(width = strokePx),
        )

        // Widely-set round dot eyes
        val eyeY = headTop + headHeight * 0.46f
        val eyeRadius = w * 0.048f
        drawCircle(
            color = color,
            radius = eyeRadius,
            center = Offset(headLeft + headWidth * 0.29f, eyeY),
        )
        drawCircle(
            color = color,
            radius = eyeRadius,
            center = Offset(headLeft + headWidth * 0.71f, eyeY),
        )

        // Happy D-shaped smile
        val mouthWidth = headWidth * 0.24f
        val mouthHeight = headHeight * 0.26f
        val mouthLeft = headLeft + (headWidth - mouthWidth) / 2f
        val mouthTop = eyeY - mouthHeight * 0.12f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(mouthLeft, mouthTop),
            size = Size(mouthWidth, mouthHeight),
            style = Stroke(width = thinStrokePx, join = StrokeJoin.Round),
        )
    }
}

/**
 * Compact full-color expressive turtle face paired with voice status text in the toolbar.
 */
@Composable
internal fun TurtleVoiceStatusFace(
    mood: TurtleVoiceMood,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawColoredTurtleFace(
            center = Offset(size.width * 0.46f, size.height * 0.56f),
            headRadiusX = size.width * 0.35f,
            tiltDegrees = when (mood) {
                TurtleVoiceMood.Sleeping -> -6f
                TurtleVoiceMood.Listening -> 0f
                TurtleVoiceMood.Thinking -> 6f
            },
            mood = mood,
            glowColor = null,
        )
    }
}

/**
 * Renders the kawaii baby turtle face at the tip of an active swipe gesture trail,
 * steering gently into the direction of motion without ever flipping upside-down.
 */
internal fun DrawScope.drawTurtleSwipeHead(
    points: List<Offset>,
    trailColor: Color,
) {
    if (points.isEmpty()) return
    val tip = points.last()
    val anchorIndex = (points.size - 5).coerceAtLeast(0)
    val dx = tip.x - points[anchorIndex].x
    val dy = tip.y - points[anchorIndex].y
    val dist = hypot(dx, dy)
    val tiltDegrees = if (dist > 2f * density) {
        ((dx / dist) * 18f).coerceIn(-18f, 18f)
    } else {
        0f
    }

    drawColoredTurtleFace(
        center = tip,
        headRadiusX = 12.5.dp.toPx(),
        tiltDegrees = tiltDegrees,
        mood = TurtleVoiceMood.Listening,
        glowColor = trailColor,
    )
}

private fun DrawScope.drawColoredTurtleFace(
    center: Offset,
    headRadiusX: Float,
    tiltDegrees: Float,
    mood: TurtleVoiceMood,
    glowColor: Color?,
) {
    val rX = headRadiusX
    val rY = rX * 0.78f
    val outlinePx = (rX * 0.16f).coerceAtLeast(1.4f)
    val fineOutlinePx = outlinePx * 0.82f

    withTransform({
        translate(left = center.x, top = center.y)
        rotate(degrees = tiltDegrees, pivot = Offset.Zero)
    }) {
        if (glowColor != null) {
            drawCircle(
                color = glowColor.copy(alpha = 0.24f),
                radius = rX * 1.48f,
                center = Offset.Zero,
            )
        }

        // Peeking emerald shell on lower-right behind head
        val shellRect = Rect(
            left = rX * 0.35f,
            top = -rY * 0.15f,
            right = rX * 1.18f,
            bottom = rY * 0.92f,
        )
        drawOval(
            color = TurtleMascotPalette.ShellGreen,
            topLeft = shellRect.topLeft,
            size = shellRect.size,
        )
        drawOval(
            color = TurtleMascotPalette.Outline,
            topLeft = shellRect.topLeft,
            size = shellRect.size,
            style = Stroke(width = outlinePx),
        )

        // Striped aqua-mint nightcap curving over the top to the right
        val capPath = Path().apply {
            moveTo(-rX * 0.78f, -rY * 0.36f)
            cubicTo(
                -rX * 0.68f, -rY * 1.72f,
                rX * 0.72f, -rY * 1.74f,
                rX * 1.12f, -rY * 0.44f,
            )
            cubicTo(
                rX * 0.72f, -rY * 0.76f,
                -rX * 0.12f, -rY * 0.88f,
                -rX * 0.78f, -rY * 0.36f,
            )
            close()
        }
        drawPath(path = capPath, color = TurtleMascotPalette.CapIce)
        clipPath(capPath) {
            val stripe1 = Path().apply {
                moveTo(-rX * 0.90f, -rY * 1.30f)
                quadraticTo(rX * 0.15f, -rY * 1.56f, rX * 1.20f, -rY * 0.95f)
                lineTo(rX * 1.20f, -rY * 1.24f)
                quadraticTo(rX * 0.15f, -rY * 1.84f, -rX * 0.90f, -rY * 1.58f)
                close()
            }
            val stripe2 = Path().apply {
                moveTo(-rX * 0.88f, -rY * 0.76f)
                quadraticTo(rX * 0.12f, -rY * 1.08f, rX * 1.18f, -rY * 0.52f)
                lineTo(rX * 1.18f, -rY * 0.80f)
                quadraticTo(rX * 0.12f, -rY * 1.34f, -rX * 0.88f, -rY * 1.02f)
                close()
            }
            drawPath(path = stripe1, color = TurtleMascotPalette.CapAqua)
            drawPath(path = stripe2, color = TurtleMascotPalette.CapAqua)
        }
        drawPath(
            path = capPath,
            color = TurtleMascotPalette.Outline,
            style = Stroke(width = outlinePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Nightcap pom-pom
        val pomCenter = Offset(rX * 1.20f, -rY * 0.26f)
        val pomRadius = rX * 0.27f
        drawCircle(
            color = TurtleMascotPalette.CapIce,
            radius = pomRadius,
            center = pomCenter,
        )
        drawCircle(
            color = TurtleMascotPalette.Outline,
            radius = pomRadius,
            center = pomCenter,
            style = Stroke(width = outlinePx),
        )

        // Chubby pistachio-lime oval head
        val headTopLeft = Offset(-rX, -rY * 0.76f)
        val headSize = Size(rX * 2f, rY * 1.72f)
        drawOval(
            color = TurtleMascotPalette.LimeHead,
            topLeft = headTopLeft,
            size = headSize,
        )
        drawOval(
            color = TurtleMascotPalette.Outline,
            topLeft = headTopLeft,
            size = headSize,
            style = Stroke(width = outlinePx),
        )

        // Cheek blush
        val eyeY = rY * 0.04f
        val blushY = eyeY + rY * 0.22f
        val blushSize = Size(rX * 0.28f, rY * 0.18f)
        drawOval(
            color = TurtleMascotPalette.Blush.copy(alpha = 0.55f),
            topLeft = Offset(-rX * 0.72f, blushY),
            size = blushSize,
        )
        drawOval(
            color = TurtleMascotPalette.Blush.copy(alpha = 0.55f),
            topLeft = Offset(rX * 0.44f, blushY),
            size = blushSize,
        )

        // Eyes & mouth by mood
        val leftEyeX = -rX * 0.42f
        val rightEyeX = rX * 0.42f
        when (mood) {
            TurtleVoiceMood.Sleeping -> {
                val eyeArcW = rX * 0.28f
                val eyeArcH = rY * 0.22f
                drawArc(
                    color = TurtleMascotPalette.Outline,
                    startAngle = 15f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(leftEyeX - eyeArcW / 2f, eyeY - eyeArcH * 0.4f),
                    size = Size(eyeArcW, eyeArcH),
                    style = Stroke(width = fineOutlinePx, cap = StrokeCap.Round),
                )
                drawArc(
                    color = TurtleMascotPalette.Outline,
                    startAngle = 15f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(rightEyeX - eyeArcW / 2f, eyeY - eyeArcH * 0.4f),
                    size = Size(eyeArcW, eyeArcH),
                    style = Stroke(width = fineOutlinePx, cap = StrokeCap.Round),
                )
                // Tiny sleepy smile
                drawArc(
                    color = TurtleMascotPalette.Outline,
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(-rX * 0.16f, eyeY + rY * 0.12f),
                    size = Size(rX * 0.32f, rY * 0.24f),
                    style = Stroke(width = fineOutlinePx, cap = StrokeCap.Round),
                )
            }
            TurtleVoiceMood.Thinking -> {
                val eyeRadius = rX * 0.135f
                drawCircle(
                    color = TurtleMascotPalette.Outline,
                    radius = eyeRadius,
                    center = Offset(leftEyeX, eyeY - rY * 0.06f),
                )
                drawCircle(
                    color = TurtleMascotPalette.Outline,
                    radius = eyeRadius,
                    center = Offset(rightEyeX, eyeY - rY * 0.06f),
                )
                // Cute small oval "o" mouth
                val mouthRect = Rect(
                    left = -rX * 0.13f,
                    top = eyeY + rY * 0.12f,
                    right = rX * 0.13f,
                    bottom = eyeY + rY * 0.42f,
                )
                drawOval(
                    color = TurtleMascotPalette.MouthPink,
                    topLeft = mouthRect.topLeft,
                    size = mouthRect.size,
                )
                drawOval(
                    color = TurtleMascotPalette.Outline,
                    topLeft = mouthRect.topLeft,
                    size = mouthRect.size,
                    style = Stroke(width = fineOutlinePx),
                )
            }
            TurtleVoiceMood.Listening -> {
                val eyeRadius = rX * 0.145f
                drawCircle(
                    color = TurtleMascotPalette.Outline,
                    radius = eyeRadius,
                    center = Offset(leftEyeX, eyeY),
                )
                drawCircle(
                    color = TurtleMascotPalette.Outline,
                    radius = eyeRadius,
                    center = Offset(rightEyeX, eyeY),
                )
                // Signature happy coral-pink D-smile
                val mouthW = rX * 0.48f
                val mouthH = rY * 0.56f
                val mouthTopLeft = Offset(-mouthW / 2f, eyeY - mouthH * 0.06f)
                val mouthSize = Size(mouthW, mouthH)
                drawArc(
                    color = TurtleMascotPalette.MouthPink,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = mouthTopLeft,
                    size = mouthSize,
                    style = Fill,
                )
                drawArc(
                    color = TurtleMascotPalette.Outline,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = mouthTopLeft,
                    size = mouthSize,
                    style = Stroke(width = fineOutlinePx, join = StrokeJoin.Round),
                )
            }
        }
    }
}
