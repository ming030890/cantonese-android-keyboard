package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.view.View
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.madeinhk.cantonesevoicekeyboard.KeyboardUiState
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sin

@Composable
fun KeyboardScreen(
    uiState: StateFlow<KeyboardUiState>,
    volumeLevel: StateFlow<Float>,
    keyboardAction: StateFlow<KeyboardAction>,
    keyboardInputMode: StateFlow<KeyboardInputMode>,
    inlineSuggestionViews: StateFlow<List<View>>,
    onToggleRecording: () -> Unit,
    onDeleteSurroundingText: () -> Unit,
    onCommitText: (String) -> Unit,
    onSendSpace: () -> Unit,
    onSendEnter: () -> Unit,
    onOpenSettings: () -> Unit,
    onEmojiPicked: (String) -> Unit,
    initialEmojiPanel: Boolean = false
) {
    val state by uiState.collectAsState()
    val volume by volumeLevel.collectAsState()
    val action by keyboardAction.collectAsState()
    val inputMode by keyboardInputMode.collectAsState()
    val inlineSuggestions by inlineSuggestionViews.collectAsState()

    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val textVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val haptics = LocalHapticFeedback.current
    var isDeleting by remember { mutableStateOf(false) }
    var activePanel by remember(inputMode, initialEmojiPanel) {
        mutableStateOf(if (initialEmojiPanel) KeyboardPanel.Emoji else inputMode.toPanel())
    }
    // Visual depress for the delete key, which uses a raw pointer gesture (no clickable).
    val deleteScale by animateFloatAsState(
        targetValue = if (isDeleting) 0.92f else 1f,
        animationSpec = tween(80, easing = FastOutSlowInEasing),
        label = "deletePressScale"
    )

    LaunchedEffect(isDeleting) {
        if (isDeleting) {
            delay(450)
            while (isDeleting) {
                onDeleteSurroundingText()
                delay(55)
            }
        }
    }

    // Main Keyboard container with premium glassmorphic/charcoal aesthetics
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        surfaceColor,     // Sleek deep charcoal
                        backgroundColor   // Dark carbon black
                    )
                )
            )
            .navigationBarsPadding()
            // Extra bottom inset so the key row clears the system nav bar / IME-switch globe.
            // navigationBarsPadding() is often ~0 in the IME window, so this fixed gap carries it.
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 40.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            InlineSuggestionStrip(
                suggestions = inlineSuggestions,
                modifier = Modifier.fillMaxWidth()
            )

            when (activePanel) {
                KeyboardPanel.Emoji -> {
                    EmojiPickerPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        onEmojiPicked = onEmojiPicked
                    )
                }
                KeyboardPanel.Number -> {
                    NumberPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        onCommitText = onCommitText
                    )
                }
                KeyboardPanel.AlphaNum -> {
                    AlphaNumPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        onCommitText = onCommitText,
                        onDeleteSurroundingText = onDeleteSurroundingText
                    )
                }
                KeyboardPanel.Voice -> {
                    // Central voice control: the mic and its state-driven label read as one
                    // cohesive cluster, vertically centered so there's no floating dead space.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        RecordTriggerButton(
                            state = state,
                            onClick = onToggleRecording
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        // Fixed-height label slot keeps the mic from jumping as the status changes.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            KeyboardStatusLabel(
                                state = state,
                                volume = volume,
                                onOpenSettings = onOpenSettings
                            )
                        }
                    }
                }
            }

            if (activePanel == KeyboardPanel.AlphaNum) {
                AlphaNumBottomRow(
                    action = action,
                    onCommitText = onCommitText,
                    onSendSpace = onSendSpace,
                    onSendEnter = onSendEnter,
                    onOpenEmoji = { activePanel = KeyboardPanel.Emoji },
                    onBackToVoice = { activePanel = KeyboardPanel.Voice }
                )
            } else {
                // Bottom Functional Keys Row (Space, Delete, Enter, Switch)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KeyboardUtilityKey(
                        modifier = Modifier.weight(0.75f),
                        onClick = {
                            activePanel = if (activePanel == KeyboardPanel.Voice) {
                                KeyboardPanel.Emoji
                            } else {
                                KeyboardPanel.Voice
                            }
                        }
                    ) {
                        if (activePanel == KeyboardPanel.Voice) {
                            SmileIcon(modifier = Modifier.size(20.dp), color = textVariantColor)
                        } else {
                            Text(
                                text = "中",
                                color = textVariantColor,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    KeyboardUtilityKey(
                        modifier = Modifier.weight(0.75f),
                        onClick = {
                            if (activePanel == KeyboardPanel.Voice) {
                                activePanel = KeyboardPanel.AlphaNum
                            } else {
                                onOpenSettings()
                            }
                        }
                    ) {
                        if (activePanel == KeyboardPanel.Voice) {
                            KeyboardIcon(modifier = Modifier.size(20.dp), color = textVariantColor)
                        } else {
                            SettingsIcon(modifier = Modifier.size(20.dp), color = textVariantColor)
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Space Key
                    KeyboardUtilityKey(
                        modifier = Modifier.weight(2.1f),
                        onClick = onSendSpace
                    ) {
                        Text(
                            text = stringResource(R.string.keyboard_key_space),
                            color = textVariantColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Delete Key
                    KeyboardUtilityKey(
                        modifier = Modifier
                            .weight(1f)
                            .scale(deleteScale)
                            .pointerInput(onDeleteSurroundingText) {
                                detectTapGestures(
                                    onPress = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onDeleteSurroundingText()
                                        isDeleting = true
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            isDeleting = false
                                        }
                                    }
                                )
                            },
                        onClick = null
                    ) {
                        BackspaceIcon(modifier = Modifier.size(20.dp), color = textVariantColor)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Enter Key - filled accent so the primary commit action stands out.
                    KeyboardUtilityKey(
                        modifier = Modifier.weight(1.1f),
                        onClick = onSendEnter,
                        backgroundColor = primaryColor,
                        borderColor = primaryColor
                    ) {
                        KeyboardActionIcon(
                            action = action,
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineSuggestionStrip(
    suggestions: List<View>,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) {
        return
    }

    Row(
        modifier = modifier
            .height(44.dp)
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        suggestions.forEach { suggestionView ->
            key(suggestionView) {
                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp)),
                    factory = { suggestionView },
                    update = {}
                )
            }
        }
    }
}

private enum class KeyboardPanel {
    Voice,
    Emoji,
    Number,
    AlphaNum
}

enum class KeyboardAction {
    Enter,
    Go,
    Search,
    Send,
    Next,
    Previous,
    Done
}

enum class KeyboardInputMode {
    Voice,
    Number,
    AlphaNum
}

private fun KeyboardInputMode.toPanel(): KeyboardPanel =
    when (this) {
        KeyboardInputMode.Voice -> KeyboardPanel.Voice
        KeyboardInputMode.Number -> KeyboardPanel.Number
        KeyboardInputMode.AlphaNum -> KeyboardPanel.AlphaNum
    }

@Composable
fun NumberPanel(
    modifier: Modifier = Modifier,
    onCommitText: (String) -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "-")
    )

    KeyGridPanel(
        modifier = modifier,
        rows = rows,
        onCommitText = onCommitText
    )
}

@Composable
fun AlphaNumPanel(
    modifier: Modifier = Modifier,
    onCommitText: (String) -> Unit,
    onDeleteSurroundingText: () -> Unit
) {
    var isUppercase by remember { mutableStateOf(true) }
    val keyboardColors = BrandTheme.colors

    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        KeyRow(labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), onCommitText = onCommitText)
        KeyRow(labels = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"), uppercase = isUppercase, onCommitText = onCommitText)
        KeyRow(
            labels = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            uppercase = isUppercase,
            leadingWeight = 0.5f,
            trailingWeight = 0.5f,
            onCommitText = onCommitText
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GboardKey(
                modifier = Modifier.weight(1.35f),
                onClick = { isUppercase = !isUppercase },
                backgroundColor = keyboardColors.keyboardModifierKeySurface
            ) {
                ShiftIcon(
                    modifier = Modifier.size(21.dp),
                    color = keyboardColors.keyboardKeyOnSurface,
                    filled = isUppercase
                )
            }
            listOf("z", "x", "c", "v", "b", "n", "m").forEach { key ->
                CharacterKey(
                    modifier = Modifier.weight(1f),
                    label = if (isUppercase) key.uppercase() else key,
                    commitValue = if (isUppercase) key.uppercase() else key,
                    onCommitText = onCommitText
                )
            }
            GboardKey(
                modifier = Modifier.weight(1.35f),
                onClick = onDeleteSurroundingText,
                backgroundColor = keyboardColors.keyboardModifierKeySurface
            ) {
                BackspaceIcon(modifier = Modifier.size(22.dp), color = keyboardColors.keyboardKeyOnSurface)
            }
        }
    }
}

@Composable
private fun KeyGridPanel(
    modifier: Modifier = Modifier,
    rows: List<List<String>>,
    onCommitText: (String) -> Unit
) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { key ->
                    CharacterKey(
                        modifier = Modifier.weight(1f),
                        label = key.uppercase(),
                        commitValue = key,
                        onCommitText = onCommitText
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyRow(
    labels: List<String>,
    uppercase: Boolean = false,
    leadingWeight: Float = 0f,
    trailingWeight: Float = 0f,
    onCommitText: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingWeight > 0f) {
            Spacer(modifier = Modifier.weight(leadingWeight))
        }
        labels.forEach { key ->
            CharacterKey(
                modifier = Modifier.weight(1f),
                label = if (uppercase) key.uppercase() else key.uppercase(),
                commitValue = if (uppercase) key.uppercase() else key,
                onCommitText = onCommitText
            )
        }
        if (trailingWeight > 0f) {
            Spacer(modifier = Modifier.weight(trailingWeight))
        }
    }
}

@Composable
private fun AlphaNumBottomRow(
    action: KeyboardAction,
    onCommitText: (String) -> Unit,
    onSendSpace: () -> Unit,
    onSendEnter: () -> Unit,
    onOpenEmoji: () -> Unit,
    onBackToVoice: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val keyboardColors = BrandTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GboardKey(
            modifier = Modifier.weight(1.15f),
            onClick = onBackToVoice,
            backgroundColor = keyboardColors.keyboardModifierKeySurface,
            shape = CircleShape
        ) {
            Text(
                text = "中",
                color = keyboardColors.keyboardKeyOnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        GboardKey(
            modifier = Modifier.weight(0.9f),
            onClick = { onCommitText(",") },
            backgroundColor = keyboardColors.keyboardModifierKeySurface
        ) {
            Text(
                text = ",",
                color = keyboardColors.keyboardKeyOnSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        GboardKey(
            modifier = Modifier.weight(0.9f),
            onClick = onOpenEmoji
        ) {
            SmileIcon(modifier = Modifier.size(22.dp), color = keyboardColors.keyboardKeyOnSurface)
        }
        GboardKey(
            modifier = Modifier.weight(3.6f),
            onClick = onSendSpace
        ) {
            Text(
                text = stringResource(R.string.keyboard_key_space),
                color = keyboardColors.keyboardKeyOnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
        GboardKey(
            modifier = Modifier.weight(0.9f),
            onClick = { onCommitText(".") },
            backgroundColor = keyboardColors.keyboardModifierKeySurface
        ) {
            Text(
                text = ".",
                color = keyboardColors.keyboardKeyOnSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        GboardKey(
            modifier = Modifier.weight(1.25f),
            onClick = onSendEnter,
            backgroundColor = primaryColor,
            shape = CircleShape
        ) {
            KeyboardActionIcon(
                action = action,
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun GboardKey(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    backgroundColor: Color = Color.Unspecified,
    shape: Shape = RoundedCornerShape(9.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val resolvedBackgroundColor =
        if (backgroundColor == Color.Unspecified) {
            BrandTheme.colors.keyboardKeySurface
        } else {
            backgroundColor
        }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = tween(80, easing = FastOutSlowInEasing),
        label = "gboardKeyPressScale"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .scale(pressScale)
            .shadow(elevation = if (isPressed) 0.dp else 1.dp, shape = shape)
            .clip(shape)
            .background(resolvedBackgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun CharacterKey(
    modifier: Modifier = Modifier,
    label: String,
    commitValue: String = label.lowercase(),
    onCommitText: (String) -> Unit
) {
    val keyboardColors = BrandTheme.colors

    GboardKey(
        modifier = modifier,
        onClick = { onCommitText(commitValue) }
    ) {
        Text(
            text = label,
            color = keyboardColors.keyboardKeyOnSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun EmojiPickerPanel(
    modifier: Modifier = Modifier,
    onEmojiPicked: (String) -> Unit
) {
    if (LocalInspectionMode.current) {
        EmojiPreviewGrid(modifier = modifier)
        return
    }

    AndroidView(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        factory = { context ->
            EmojiPickerView(context).apply {
                emojiGridColumns = 8
                setOnEmojiPickedListener { item -> onEmojiPicked(item.emoji) }
            }
        },
        update = { picker ->
            picker.setOnEmojiPickedListener { item -> onEmojiPicked(item.emoji) }
        }
    )
}

@Composable
private fun EmojiPreviewGrid(modifier: Modifier = Modifier) {
    val emojis = listOf(
        "😀", "😂", "😍", "🥳", "😎", "😭", "😡", "👍",
        "🙏", "👏", "💪", "🔥", "✨", "❤️", "🎉", "✅",
        "🍜", "☕", "🚇", "🏠", "📍", "💬", "🔎", "⭐"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        emojis.chunked(8).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(34.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun KeyboardStatusLabel(
    state: KeyboardUiState,
    volume: Float,
    onOpenSettings: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val textVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error

    when (val current = state) {
        KeyboardUiState.Idle -> {
            Text(
                text = stringResource(R.string.keyboard_status_idle),
                color = textVariantColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp
            )
        }
        KeyboardUiState.LoadingModel -> {
            // The spinner already lives inside the mic button, so the label is text-only
            // to avoid showing two progress indicators at once.
            Text(
                text = stringResource(R.string.keyboard_status_loading_model),
                color = primaryColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        KeyboardUiState.Recording -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                VoiceWaveform(volume = volume)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.keyboard_status_recording),
                    color = errorColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                )
            }
        }
        KeyboardUiState.Transcribing -> {
            // The spinner already lives inside the mic button, so the label is text-only
            // to avoid showing two progress indicators at once.
            Text(
                text = stringResource(R.string.keyboard_status_transcribing),
                color = TranscribeYellow,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        is KeyboardUiState.Error -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = current.message,
                    color = errorColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.keyboard_status_error_setup),
                    color = SystemBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onOpenSettings() }
                )
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

    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val errorColor = MaterialTheme.colorScheme.error
    val haptics = LocalHapticFeedback.current

    // Press feedback: a subtle depress on touch-down for the main trigger.
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(80, easing = FastOutSlowInEasing),
        label = "recordPressScale"
    )

    // Button colors dynamically based on status
    val buttonColor = when (state) {
        KeyboardUiState.Recording -> errorColor // Pulsing active red
        KeyboardUiState.Transcribing -> TranscribeYellow // Transcribing yellow
        KeyboardUiState.LoadingModel -> outlineColor // Loading state gray
        is KeyboardUiState.Error -> errorColor.copy(alpha = 0.25f) // Error state deep red
        KeyboardUiState.Idle -> primaryColor // Idle glowing cyan
    }

    val iconColor = when (state) {
        KeyboardUiState.Recording -> Color.White
        is KeyboardUiState.Error -> errorColor
        // Pick black/white for legibility against whatever the button fill is,
        // so the mic/spinner stays readable in both light and dark themes.
        else -> if (buttonColor.luminance() > 0.5f) Color.Black else Color.White
    }

    Box(contentAlignment = Alignment.Center) {
        // Inner Action Circle Button. The recording glow is painted via drawBehind so it can
        // bleed past the 56.dp button without enlarging the layout footprint — that keeps the
        // status label below it from being pushed down into the bottom key row.
        Box(
            modifier = Modifier
                .size(56.dp)
                .drawBehind {
                    if (state is KeyboardUiState.Recording) {
                        drawCircle(
                            color = buttonColor.copy(alpha = 0.25f * pulseAlpha),
                            radius = 34.dp.toPx() * pulseScale
                        )
                    }
                }
                .scale(pressScale)
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
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
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

    val errorColor = MaterialTheme.colorScheme.error

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
            color = errorColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun KeyboardUtilityKey(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    onClick: (() -> Unit)?,
    content: @Composable BoxScope.() -> Unit
) {
    val finalBgColor = if (backgroundColor == Color.Unspecified) MaterialTheme.colorScheme.surface else backgroundColor
    // Default to a subtle outline so key-caps stay delineated from the keyboard
    // background in both themes (white-on-white in light / dark-on-dark in dark).
    val finalBorderColor = if (borderColor == Color.Unspecified) MaterialTheme.colorScheme.outline else borderColor
    val keyShape = RoundedCornerShape(8.dp)
    val haptics = LocalHapticFeedback.current

    // Track press state so the key visibly depresses (scale + brightness) on touch.
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = tween(80, easing = FastOutSlowInEasing),
        label = "keyPressScale"
    )
    // Brighten the key cap while held for an extra visual cue.
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    val keyModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .scale(pressScale)
            // Subtle elevation gives each cap a tactile, raised feel; it flattens on press.
            .shadow(
                elevation = if (isPressed) 0.dp else 2.dp,
                shape = keyShape,
                clip = false
            )
            .clip(keyShape)
            .background(finalBgColor)
            .then(if (isPressed) Modifier.background(highlightColor) else Modifier)
            .border(1.dp, finalBorderColor, keyShape)
            .then(keyModifier)
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
fun ShiftIcon(modifier: Modifier = Modifier, color: Color = Color.White, filled: Boolean = false) {
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
            style = if (filled) Fill else Stroke(width = 2.2.dp.toPx(), join = StrokeJoin.Round)
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

        drawCircle(
            color = color,
            radius = w * 0.38f,
            center = center,
            style = Stroke(width = strokePx)
        )
        drawCircle(color = color, radius = w * 0.045f, center = Offset(w * 0.38f, h * 0.42f))
        drawCircle(color = color, radius = w * 0.045f, center = Offset(w * 0.62f, h * 0.42f))
        drawArc(
            color = color,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(w * 0.33f, h * 0.38f),
            size = Size(w * 0.34f, h * 0.34f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
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
            style = Stroke(width = strokePx)
        )
        val keyW = w * 0.11f
        val keyH = h * 0.10f
        val startX = w * 0.23f
        val startY = h * 0.36f
        repeat(3) { row ->
            repeat(4) { column ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(startX + column * w * 0.14f, startY + row * h * 0.14f),
                    size = Size(keyW, keyH),
                    cornerRadius = keyRadius
                )
            }
        }
    }
}

@Composable
fun KeyboardActionIcon(
    action: KeyboardAction,
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 2.4.dp.toPx()

        when (action) {
            KeyboardAction.Search -> {
                drawCircle(
                    color = color,
                    radius = w * 0.25f,
                    center = Offset(w * 0.42f, h * 0.42f),
                    style = Stroke(width = strokePx)
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.60f, h * 0.60f),
                    end = Offset(w * 0.82f, h * 0.82f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
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
                drawPath(path = path, color = color)
            }
            KeyboardAction.Done -> {
                drawLine(
                    color = color,
                    start = Offset(w * 0.18f, h * 0.54f),
                    end = Offset(w * 0.40f, h * 0.74f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.40f, h * 0.74f),
                    end = Offset(w * 0.84f, h * 0.26f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
            }
            KeyboardAction.Go,
            KeyboardAction.Next -> {
                drawLine(
                    color = color,
                    start = Offset(w * 0.18f, h * 0.50f),
                    end = Offset(w * 0.78f, h * 0.50f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.58f, h * 0.30f),
                    end = Offset(w * 0.78f, h * 0.50f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.58f, h * 0.70f),
                    end = Offset(w * 0.78f, h * 0.50f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
            }
            KeyboardAction.Previous -> {
                drawLine(
                    color = color,
                    start = Offset(w * 0.22f, h * 0.50f),
                    end = Offset(w * 0.82f, h * 0.50f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.42f, h * 0.30f),
                    end = Offset(w * 0.22f, h * 0.50f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.42f, h * 0.70f),
                    end = Offset(w * 0.22f, h * 0.50f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
            }
            KeyboardAction.Enter -> {
                drawLine(
                    color = color,
                    start = Offset(w * 0.78f, h * 0.22f),
                    end = Offset(w * 0.78f, h * 0.58f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.78f, h * 0.58f),
                    end = Offset(w * 0.24f, h * 0.58f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.42f, h * 0.38f),
                    end = Offset(w * 0.24f, h * 0.58f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.42f, h * 0.78f),
                    end = Offset(w * 0.24f, h * 0.58f),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun SettingsIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val outerR = w * 0.40f   // tip of the gear teeth
        val ringR = w * 0.30f    // gear body ring
        val innerR = w * 0.13f   // center hub hole
        val toothLen = outerR - ringR
        val strokePx = 2.dp.toPx()

        // Eight radial teeth around the gear body.
        val teeth = 8
        for (i in 0 until teeth) {
            val angle = (Math.PI * 2 / teeth) * i
            val dx = kotlin.math.cos(angle).toFloat()
            val dy = kotlin.math.sin(angle).toFloat()
            drawLine(
                color = color,
                start = Offset(center.x + dx * ringR, center.y + dy * ringR),
                end = Offset(center.x + dx * (ringR + toothLen), center.y + dy * (ringR + toothLen)),
                strokeWidth = strokePx * 1.6f,
                cap = StrokeCap.Round
            )
        }

        // Gear body ring
        drawCircle(
            color = color,
            radius = ringR,
            center = center,
            style = Stroke(width = strokePx)
        )

        // Center hub
        drawCircle(
            color = color,
            radius = innerR,
            center = center,
            style = Stroke(width = strokePx)
        )
    }
}
