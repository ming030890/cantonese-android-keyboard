package com.example.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cantonesevoicekeyboard.KeyboardUiState
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sin

@Composable
fun KeyboardScreen(
    uiState: StateFlow<KeyboardUiState>,
    volumeLevel: StateFlow<Float>,
    onToggleRecording: () -> Unit,
    onDeleteSurroundingText: () -> Unit,
    onSendSpace: () -> Unit,
    onSendEnter: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by uiState.collectAsState()
    val volume by volumeLevel.collectAsState()

    // Main Keyboard container with premium glassmorphic/charcoal aesthetics
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(270.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1E1E24), // Sleek deep charcoal
                        Color(0xFF121215)  // Dark carbon black
                    )
                )
            )
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header / Status Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when (val current = state) {
                    KeyboardUiState.Idle -> {
                        Text(
                            text = "粵語語音輸入 (廣東話)",
                            color = Color(0xFF8E8E9F),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    KeyboardUiState.LoadingModel -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFF00E5FF),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "正在載入語音模組...",
                                color = Color(0xFF00E5FF),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                    KeyboardUiState.Recording -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "正在聆聽... 廣東話 dictation",
                                color = Color(0xFFFF2D55),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            VoiceWaveform(volume = volume)
                        }
                    }
                    KeyboardUiState.Transcribing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFFFFD600),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "正在進行語音識別...",
                                color = Color(0xFFFFD600),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                    is KeyboardUiState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = current.message,
                                color = Color(0xFFFF3B30),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "點擊開啟設定 app 進行配置",
                                color = Color(0xFF0A84FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { onOpenSettings() }
                            )
                        }
                    }
                }
            }

            // Central Recording Control (Tap to Speak)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.5f),
                contentAlignment = Alignment.Center
            ) {
                RecordTriggerButton(
                    state = state,
                    onClick = onToggleRecording
                )
            }

            // Bottom Functional Keys Row (Space, Delete, Enter, Switch)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Switch Keyboard Key
                KeyboardUtilityKey(
                    modifier = Modifier.weight(1f),
                    onClick = onSwitchKeyboard
                ) {
                    GlobeIcon(modifier = Modifier.size(20.dp), color = Color(0xFFD1D1D6))
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Space Key
                KeyboardUtilityKey(
                    modifier = Modifier.weight(2.5f),
                    onClick = onSendSpace,
                    backgroundColor = Color(0xFF2C2C35) // Lighter key cap
                ) {
                    Text(
                        text = "空格",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Delete Key
                KeyboardUtilityKey(
                    modifier = Modifier.weight(1.2f),
                    onClick = onDeleteSurroundingText
                ) {
                    BackspaceIcon(modifier = Modifier.size(20.dp), color = Color(0xFFD1D1D6))
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Enter Key
                KeyboardUtilityKey(
                    modifier = Modifier.weight(1.3f),
                    onClick = onSendEnter,
                    backgroundColor = Color(0xFF00E5FF).copy(alpha = 0.15f),
                    borderColor = Color(0xFF00E5FF).copy(alpha = 0.4f)
                ) {
                    Text(
                        text = "換行",
                        color = Color(0xFF00E5FF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun RecordTriggerButton(
    state: KeyboardUiState,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    
    // Animate glowing size when recording
    val pulseScale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (state is KeyboardUiState.Recording) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = if (state is KeyboardUiState.Recording) 0.8f else 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Button colors dynamically based on status
    val buttonColor = when (state) {
        KeyboardUiState.Recording -> Color(0xFFFF2D55) // Pulsing active red
        KeyboardUiState.Transcribing -> Color(0xFFFFD600) // Transcribing yellow
        KeyboardUiState.LoadingModel -> Color(0xFF2C2C35) // Loading state gray
        is KeyboardUiState.Error -> Color(0xFF3A2024) // Error state deep red
        KeyboardUiState.Idle -> Color(0xFF00E5FF) // Idle glowing cyan
    }

    val iconColor = when (state) {
        KeyboardUiState.Recording -> Color.White
        is KeyboardUiState.Error -> Color(0xFFFF3B30)
        else -> Color.Black
    }

    Box(contentAlignment = Alignment.Center) {
        // Glowing Background Ring
        if (state is KeyboardUiState.Recording) {
            Box(
                modifier = Modifier
                    .size(68.dp * pulseScale)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = 0.25f * pulseAlpha))
            )
        }

        // Inner Action Circle Button
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(
                    elevation = if (state is KeyboardUiState.Recording) 8.dp else 4.dp,
                    shape = CircleShape,
                    ambientColor = buttonColor,
                    spotColor = buttonColor
                )
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = 0.9f),
                            buttonColor
                        )
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (state is KeyboardUiState.Transcribing || state is KeyboardUiState.LoadingModel) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = iconColor,
                    strokeWidth = 2.5.dp
                )
            } else {
                MicIcon(
                    modifier = Modifier.size(24.dp),
                    color = iconColor
                )
            }
        }
    }
}

@Composable
fun VoiceWaveform(volume: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveOffset")
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .height(30.dp)
    ) {
        val width = size.width
        val height = size.height
        val midY = height / 2f
        val path = Path()

        path.moveTo(0f, midY)
        for (x in 0..width.toInt() step 4) {
            val xVal = x.toFloat()
            // Combination of two sine waves modulated by volume level to look fluid
            val sineValue = sin(xVal * 0.03f + waveOffset) * 0.7f + sin(xVal * 0.07f - waveOffset * 1.4f) * 0.3f
            // Amplify using the volume level
            val amplitude = sineValue * height * 0.45f * (volume + 0.05f) // always show slight baseline movement
            path.lineTo(xVal, midY + amplitude)
        }

        drawPath(
            path = path,
            color = Color(0xFFFF2D55),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun KeyboardUtilityKey(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFF1E1E24),
    borderColor: Color = Color(0xFF2C2C35),
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}

// Beautiful vector custom drawn icons
@Composable
fun MicIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Mic body: rounded rectangle in the center
        val micW = w * 0.36f
        val micH = h * 0.48f
        val micX = (w - micW) / 2f
        val micY = h * 0.16f

        drawRoundRect(
            color = color,
            topLeft = Offset(micX, micY),
            size = Size(micW, micH),
            cornerRadius = CornerRadius(micW / 2f, micW / 2f)
        )

        // Mic stand (U-shape)
        val standW = w * 0.54f
        val standH = h * 0.22f
        val standX = (w - standW) / 2f
        val standY = micY + micH * 0.52f

        val standPath = Path().apply {
            arcTo(
                rect = Rect(standX, standY - standH * 0.2f, standX + standW, standY + standH),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 180f,
                forceMoveTo = true
            )
        }

        drawPath(
            path = standPath,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Vertical stem
        val stemX = w / 2f
        val stemYStart = standY + standH
        val stemYEnd = h * 0.82f

        drawLine(
            color = color,
            start = Offset(stemX, stemYStart),
            end = Offset(stemX, stemYEnd),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Base horizontal line
        val baseXStart = w * 0.32f
        val baseXEnd = w * 0.68f
        drawLine(
            color = color,
            start = Offset(baseXStart, stemYEnd),
            end = Offset(baseXEnd, stemYEnd),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round
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
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 'X' inside backspace box
        val innerW = w * 0.11f
        drawLine(
            color = color,
            start = Offset(w * 0.52f, h * 0.4f),
            end = Offset(w * 0.74f, h * 0.6f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.74f, h * 0.4f),
            end = Offset(w * 0.52f, h * 0.6f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun GlobeIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val radius = w * 0.36f

        // Outer circle
        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // Equator
        drawLine(
            color = color,
            start = Offset(center.x - radius, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = 2.dp.toPx()
        )

        // Prime meridian
        drawLine(
            color = color,
            start = Offset(center.x, center.y - radius),
            end = Offset(center.x, center.y + radius),
            strokeWidth = 2.dp.toPx()
        )

        // Horizontal lines top & bottom (latitude lines)
        val latOffset = radius * 0.5f
        drawLine(
            color = color,
            start = Offset(center.x - radius * 0.86f, center.y - latOffset),
            end = Offset(center.x + radius * 0.86f, center.y - latOffset),
            strokeWidth = 1.5.dp.toPx()
        )
        drawLine(
            color = color,
            start = Offset(center.x - radius * 0.86f, center.y + latOffset),
            end = Offset(center.x + radius * 0.86f, center.y + latOffset),
            strokeWidth = 1.5.dp.toPx()
        )
    }
}
