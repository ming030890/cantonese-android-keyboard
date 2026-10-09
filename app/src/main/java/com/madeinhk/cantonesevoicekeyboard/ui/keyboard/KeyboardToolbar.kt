package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.madeinhk.cantonesevoicekeyboard.KeyboardUiState
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.theme.BrandColors
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme

@Composable
internal fun UnifiedKeyboardToolbar(
    keyRegistry: KeyboardKeyRegistry,
    keys: TextToolbarKeys,
    secondaryAction: ToolbarSecondaryAction?,
    candidates: List<HandwritingCandidate>,
    showHandwritingCandidates: Boolean,
    voiceState: KeyboardUiState,
    volume: Float,
    handwritingBusy: Boolean,
    handwritingError: String?,
    autofillView: View?,
    clipboard: ClipboardSuggestion?,
    surfaceOriginInRoot: Offset,
    onAutofillBoundsChanged: (Rect) -> Unit,
    handwritingCandidatePage: Int,
    onHandwritingCandidatePageChanged: (Int) -> Unit,
) {
    val keyboardColors = BrandTheme.colors
    val candidatesVisible = showHandwritingCandidates && candidates.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KeyboardUiDefaults.ToolbarHeight)
            .padding(horizontal = KeyboardUiDefaults.KeyboardHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(KeyboardUiDefaults.ToolbarIconSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!candidatesVisible) {
            ToolbarSettingsVisual(key = keys.settings, keyRegistry = keyRegistry)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                voiceState == KeyboardUiState.Recording -> VoiceToolbarStatus(
                    volume = volume,
                    label = stringResource(R.string.keyboard_status_recording),
                )
                voiceState == KeyboardUiState.Transcribing -> VoiceToolbarStatus(
                    volume = 0f,
                    label = stringResource(R.string.keyboard_status_transcribing),
                )
                voiceState == KeyboardUiState.LoadingModel -> ToolbarStatusText(
                    stringResource(R.string.keyboard_status_loading_model),
                    keyboardColors.keyboardKeyOnSurface,
                )
                voiceState is KeyboardUiState.Error -> {
                    ToolbarStatusText(voiceState.message, MaterialTheme.colorScheme.error)
                }
                showHandwritingCandidates && handwritingError != null -> {
                    ToolbarStatusText(handwritingError, MaterialTheme.colorScheme.error)
                }
                secondaryAction == ToolbarSecondaryAction.Autofill -> {
                    val providerView = checkNotNull(autofillView)
                    if (LocalInspectionMode.current) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.keyboard_toolbar_autofill),
                                color = keyboardColors.keyboardKeyOnSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .onGloballyPositioned { coordinates ->
                                    val bounds = coordinates.boundsInRoot()
                                    onAutofillBoundsChanged(
                                        Rect(
                                            left = bounds.left - surfaceOriginInRoot.x,
                                            top = bounds.top - surfaceOriginInRoot.y,
                                            right = bounds.right - surfaceOriginInRoot.x,
                                            bottom = bounds.bottom - surfaceOriginInRoot.y,
                                        ),
                                    )
                                },
                            factory = { providerView },
                            update = {},
                        )
                    }
                }
                candidatesVisible -> HandwritingCandidateStrip(
                    candidates = candidates,
                    keys = keys,
                    keyRegistry = keyRegistry,
                    page = handwritingCandidatePage,
                    onPageChanged = onHandwritingCandidatePageChanged,
                )
                secondaryAction == ToolbarSecondaryAction.Clipboard -> ClipboardToolbarChip(
                    key = keys.clipboard,
                    keyRegistry = keyRegistry,
                    clipboard = checkNotNull(clipboard),
                    keyboardColors = keyboardColors,
                )
                showHandwritingCandidates && handwritingBusy -> ToolbarStatusText(
                    stringResource(R.string.keyboard_status_recognizing),
                    keyboardColors.keyboardKeyOnSurface,
                )
            }
        }
        VoiceToolbarVisual(
            state = voiceState,
            key = keys.voice,
            keyRegistry = keyRegistry,
            accessibilityDescription = stringResource(R.string.keyboard_toolbar_voice),
        )
    }
}

@Composable
private fun ToolbarStatusText(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun VoiceToolbarStatus(volume: Float, label: String) {
    val voiceActiveColor = BrandTheme.colors.voiceActive
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (volume > 0f) {
            Canvas(modifier = Modifier.width(36.dp).height(18.dp)) {
                val center = size.height / 2f
                val barWidth = size.width / 7f
                repeat(5) { index ->
                    val x = barWidth * (index + 1)
                    val amplitude = size.height * (0.18f + volume * (0.18f + index * 0.03f))
                    drawLine(
                        color = voiceActiveColor,
                        start = Offset(x, center - amplitude),
                        end = Offset(x, center + amplitude),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        ToolbarStatusText(label, BrandTheme.colors.keyboardKeyOnSurface)
    }
}

@Composable
private fun VoiceToolbarVisual(
    state: KeyboardUiState,
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    accessibilityDescription: String,
) {
    val keyboardColors = BrandTheme.colors
    ToolbarIconVisual(
        key = key,
        keyRegistry = keyRegistry,
        accessibilityDescription = accessibilityDescription,
    ) {
        when (state) {
            KeyboardUiState.LoadingModel,
            KeyboardUiState.Transcribing,
            -> Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(keyboardColors.voiceActiveContainer),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = keyboardColors.voiceActive,
                    strokeWidth = 2.5.dp,
                )
            }
            KeyboardUiState.Recording -> RecordingToolbarIcon(
                backgroundColor = keyboardColors.voiceActive,
                pulseColor = keyboardColors.voiceActivePulse,
                iconColor = keyboardColors.voiceOnActive,
            )
            is KeyboardUiState.Error -> Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                MicIcon(
                    modifier = Modifier.size(KeyboardUiDefaults.ToolbarIconSize),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            KeyboardUiState.Idle -> MicIcon(
                modifier = Modifier.size(KeyboardUiDefaults.ToolbarIconSize),
                color = keyboardColors.keyboardKeyOnSurface,
            )
        }
    }
}

@Composable
private fun RecordingToolbarIcon(
    backgroundColor: Color,
    pulseColor: Color,
    iconColor: Color,
) {
    val transition = rememberInfiniteTransition(label = "toolbarRecordingPulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "toolbarRecordingPulseProgress",
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .drawBehind {
                drawCircle(
                    color = pulseColor.copy(alpha = pulseColor.alpha * (1f - pulse)),
                    radius = (16.dp + 5.dp * pulse).toPx(),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            MicIcon(modifier = Modifier.size(20.dp), color = iconColor)
        }
    }
}

@Composable
private fun ToolbarSettingsVisual(
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    modifier: Modifier = Modifier,
) {
    ToolbarIconVisual(
        modifier = modifier,
        key = key,
        keyRegistry = keyRegistry,
        accessibilityDescription = stringResource(R.string.keyboard_toolbar_settings),
    ) {
        SettingsIcon(
            modifier = Modifier.size(KeyboardUiDefaults.ToolbarSettingsIconSize),
            color = BrandTheme.colors.keyboardKeyOnSurface,
        )
    }
}

@Composable
private fun ClipboardToolbarChip(
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    clipboard: ClipboardSuggestion,
    keyboardColors: BrandColors,
    modifier: Modifier = Modifier,
) {
    val description = if (clipboard.isSensitive) {
        stringResource(R.string.keyboard_toolbar_paste)
    } else {
        stringResource(
            R.string.keyboard_toolbar_clipboard_preview,
            clipboard.preview.orEmpty(),
        )
    }
    ToolbarChipVisual(
        key = key,
        keyRegistry = keyRegistry,
        accessibilityDescription = description,
        modifier = modifier.width(
            if (clipboard.isSensitive) 132.dp else 220.dp,
        ),
    ) {
        ClipboardIcon(
            modifier = Modifier.size(20.dp),
            color = keyboardColors.keyboardKeyOnSurface,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (clipboard.isSensitive) {
                stringResource(R.string.keyboard_toolbar_paste)
            } else {
                clipboard.preview.orEmpty()
            },
            color = keyboardColors.keyboardKeyOnSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
    }
}

@Composable
private fun ToolbarChipVisual(
    modifier: Modifier,
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    accessibilityDescription: String,
    content: @Composable RowScope.() -> Unit,
) {
    KeyVisual(
        modifier = modifier
            .height(44.dp)
            .semantics { contentDescription = accessibilityDescription },
        key = key,
        keyRegistry = keyRegistry,
        accessibilityDescription = accessibilityDescription,
        backgroundColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(percent = 50),
        restingElevation = 0.dp,
        pressedScale = 0.98f,
        horizontalInset = 0.dp,
        verticalInset = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
