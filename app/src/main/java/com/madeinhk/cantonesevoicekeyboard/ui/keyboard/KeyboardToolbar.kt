package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import androidx.compose.ui.viewinterop.AndroidView
import com.madeinhk.cantonesevoicekeyboard.KeyboardUiState
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.theme.BrandColors
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme
import com.madeinhk.cantonesevoicekeyboard.theme.KeyboardStatusTextStyle

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
    val content = keyboardToolbarContent(
        voice = voiceState,
        secondaryAction = secondaryAction,
        hasCandidates = showHandwritingCandidates && candidates.isNotEmpty(),
        handwritingError = handwritingError.takeIf { showHandwritingCandidates },
        handwritingBusy = showHandwritingCandidates && handwritingBusy,
    )
    val candidatesVisible = content == KeyboardToolbarContent.Candidates

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
                    label = stringResource(R.string.keyboard_status_recording),
                )
                voiceState == KeyboardUiState.Transcribing -> VoiceToolbarStatus(
                    label = stringResource(R.string.keyboard_status_transcribing),
                )
                voiceState == KeyboardUiState.LoadingModel -> ToolbarStatusText(
                    stringResource(R.string.keyboard_status_loading_model),
                    keyboardColors.keyboardKeyOnSurface,
                )
                voiceState == KeyboardUiState.DownloadingModel -> ToolbarStatusText(
                    stringResource(R.string.keyboard_status_downloading_model),
                    keyboardColors.keyboardKeyOnSurface,
                )
                voiceState is KeyboardUiState.Error -> {
                    ToolbarStatusText(voiceState.message, keyboardColors.keyboardKeyOnSurface)
                }
                content == KeyboardToolbarContent.HandwritingError -> {
                    ToolbarStatusText(checkNotNull(handwritingError), MaterialTheme.colorScheme.error)
                }
                content == KeyboardToolbarContent.Autofill -> {
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
                                style = KeyboardStatusTextStyle,
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
                content == KeyboardToolbarContent.Clipboard -> ClipboardToolbarChip(
                    key = keys.clipboard,
                    keyRegistry = keyRegistry,
                    clipboard = checkNotNull(clipboard),
                    keyboardColors = keyboardColors,
                )
                content == KeyboardToolbarContent.Recognizing -> ToolbarStatusText(
                    stringResource(R.string.keyboard_status_recognizing),
                    keyboardColors.keyboardKeyOnSurface,
                )
            }
        }
        VoiceToolbarVisual(
            volume = volume,
            state = voiceState,
            key = keys.voice,
            keyRegistry = keyRegistry,
            accessibilityDescription = stringResource(
                if ((voiceState as? KeyboardUiState.Error)?.canRetryModelDownload == true)
                    R.string.keyboard_model_retry else R.string.keyboard_toolbar_voice,
            ),
        )
    }
}

@Composable
private fun ToolbarStatusText(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = KeyboardStatusTextStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun VoiceToolbarStatus(label: String) {
    ToolbarStatusText(label, BrandTheme.colors.keyboardKeyOnSurface)
}

@Composable
private fun VoiceToolbarVisual(
    volume: Float,
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
            KeyboardUiState.DownloadingModel,
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
                volume = volume,
                backgroundColor = keyboardColors.voiceActive,
                pulseColor = keyboardColors.voiceActivePulse,
                iconColor = keyboardColors.voiceOnActive,
            )
            is KeyboardUiState.Error -> Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (state.canRetryModelDownload) {
                    Text(
                        text = stringResource(R.string.keyboard_model_retry),
                        color = MaterialTheme.colorScheme.primary,
                        style = KeyboardStatusTextStyle,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    MicIcon(
                        modifier = Modifier.size(KeyboardUiDefaults.ToolbarIconSize),
                        color = keyboardColors.keyboardKeyOnSurface,
                    )
                }
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
    volume: Float,
    backgroundColor: Color,
    pulseColor: Color,
    iconColor: Color,
) {
    val transition = rememberInfiniteTransition(label = "toolbarRecordingPulse")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "listeningRingRotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
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
                rotate(rotation) {
                    drawCircle(
                        brush = Brush.sweepGradient(listOf(
                            Color(0xFF4285F4), Color(0xFF34A853),
                            Color(0xFFFBBC05), Color(0xFFEA4335), Color(0xFF4285F4),
                        )),
                        radius = (19.dp + pulse.dp).toPx(),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .scale(1f + 0.10f * pulse + 0.06f * volume.coerceIn(0f, 1f))
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
            style = KeyboardStatusTextStyle,
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
