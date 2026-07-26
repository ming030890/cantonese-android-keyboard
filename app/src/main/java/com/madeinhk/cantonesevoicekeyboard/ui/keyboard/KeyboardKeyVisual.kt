package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme

/**
 * Passive visual for a semantic virtual button.
 *
 * Its Compose lifecycle is the hit-target lifecycle: mounting makes [key] eligible for input,
 * measurement supplies its coordinates, and disposal immediately removes it from hit testing.
 */
@Composable
internal fun KeyVisual(
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Unspecified,
    shape: Shape = RoundedCornerShape(KeyboardUiDefaults.StandardKeyCornerRadius),
    pressedScale: Float = 0.94f,
    restingElevation: Dp = 0.dp,
    pressedOverlayColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    verticalPadding: Dp = 0.dp,
    horizontalInset: Dp = KeyboardUiDefaults.HorizontalKeyInset,
    verticalInset: Dp = KeyboardUiDefaults.VerticalKeyInset,
    utilityStyle: Boolean = false,
    accessibilityDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    DisposableEffect(keyRegistry, key) {
        keyRegistry.mount(key)
        onDispose { keyRegistry.unmount(key) }
    }
    val keyboardColors = BrandTheme.colors
    val isPressed = key.isPressed
    val finalBackgroundColor = when {
        backgroundColor != Color.Unspecified -> backgroundColor
        utilityStyle -> MaterialTheme.colorScheme.surface
        else -> keyboardColors.keyboardKeySurface
    }
    val finalPressedOverlayColor = when {
        pressedOverlayColor != Color.Unspecified -> pressedOverlayColor
        utilityStyle -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        else -> keyboardColors.keyboardKeyOnSurface.copy(alpha = 0.08f)
    }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = tween(80, easing = FastOutSlowInEasing),
        label = "keyboardKeyPressScale",
    )

    BoxWithConstraints(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        val visibleWidth = maxWidth - horizontalInset * 2
        val visibleHeight = maxHeight - verticalInset * 2

        Box(
            modifier = Modifier
                .width(visibleWidth)
                .height(visibleHeight)
                .semantics {
                    contentDescription = accessibilityDescription ?: key.currentData?.text ?: key.id
                }
                .onGloballyPositioned { coordinates ->
                    keyRegistry.updateVisualBounds(key, coordinates)
                }
                .scale(scale)
                .shadow(elevation = if (isPressed) 0.dp else restingElevation, shape = shape)
                .clip(shape)
                .background(finalBackgroundColor)
                .then(if (isPressed) Modifier.background(finalPressedOverlayColor) else Modifier)
                .then(
                    if (borderColor != Color.Unspecified) {
                        Modifier.border(1.dp, borderColor, shape)
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = verticalPadding),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

internal fun DrawScope.drawKeyboardDiagnostics(
    keyRegistry: KeyboardKeyRegistry,
    touchController: KeyboardTouchController,
) {
    keyRegistry.geometryVersion
    touchController.debugVersion
    for (key in keyRegistry.mountedKeysForDiagnostics()) {
        drawRect(
            color = Color(0xFF00BCD4),
            topLeft = key.geometry.visualBounds.topLeft,
            size = key.geometry.visualBounds.size,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawRect(
            color = Color(0xFFFF9800),
            topLeft = key.geometry.touchBounds.topLeft,
            size = key.geometry.touchBounds.size,
            style = Stroke(width = 1.dp.toPx()),
        )
        if (key.isPressed) {
            drawRect(
                color = Color(0xFF4CAF50).copy(alpha = 0.28f),
                topLeft = key.geometry.visualBounds.topLeft,
                size = key.geometry.visualBounds.size,
            )
        }
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.MAGENTA
        textSize = 10.dp.toPx()
    }
    for (pointer in touchController.activePointersForDiagnostics()) {
        drawCircle(Color.Magenta, radius = 6.dp.toPx(), center = pointer.lastPosition)
        val initial = pointer.initialKey?.id ?: "-"
        val active = pointer.activeKey?.id ?: "-"
        drawContext.canvas.nativeCanvas.drawText(
            "p${pointer.pointerId} i=$initial a=$active gesture=${pointer.gestureTriggered}",
            pointer.lastPosition.x + 8.dp.toPx(),
            pointer.lastPosition.y - 8.dp.toPx(),
            paint,
        )
    }
}
