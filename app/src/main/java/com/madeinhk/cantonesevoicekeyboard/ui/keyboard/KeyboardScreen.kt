package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.madeinhk.cantonesevoicekeyboard.KeyboardUiState
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme
import kotlinx.coroutines.flow.StateFlow

private val AlphaBottomRowSpacing = 4.dp
private val KeyboardHorizontalPadding = KeyboardUiDefaults.KeyboardHorizontalPadding
private val DefaultKeyboardBottomPadding = 40.dp
private val TextKeyboardBottomPadding = 20.dp
private const val TextPanelAdditionalRows = 2

@Composable
fun KeyboardScreen(
    uiState: StateFlow<KeyboardUiState>,
    volumeLevel: StateFlow<Float>,
    keyboardAction: StateFlow<KeyboardAction>,
    keyboardInputMode: StateFlow<KeyboardInputMode>,
    autofillSuggestionView: StateFlow<View?>,
    clipboardSuggestion: StateFlow<ClipboardSuggestion?>,
    handwritingCandidates: StateFlow<List<HandwritingCandidate>>,
    handwritingRecognizing: StateFlow<Boolean>,
    handwritingError: StateFlow<String?>,
    handwritingSession: StateFlow<Long>,
    onToggleRecording: () -> Unit,
    onDeleteSurroundingText: () -> Unit,
    onCommitText: (String) -> Unit,
    onSendSpace: () -> Unit,
    onSendEnter: () -> Unit,
    onOpenSettings: () -> Unit,
    onPasteClipboard: () -> Unit,
    onHandwritingChanged: (ByteArray, Int, Int) -> Unit,
    onHandwritingStrokeStarted: (startsNewCharacter: Boolean) -> Unit = {},
    onHandwritingCandidateSelected: (String) -> Unit,
    onAutofillTapped: () -> Unit = {},
    onEmojiPicked: (String) -> Unit,
    onKeyboardInputModeSelected: (KeyboardInputMode) -> Unit = {},
    initialEmojiPanel: Boolean = false,
    debugTouchBounds: Boolean = false,
    keyboardHapticConfig: KeyboardHapticConfig = KeyboardHapticConfig(),
) {
    val state by uiState.collectAsState()
    val volume by volumeLevel.collectAsState()
    val action by keyboardAction.collectAsState()
    val inputMode by keyboardInputMode.collectAsState()
    val autofillView by autofillSuggestionView.collectAsState()
    val clipboard by clipboardSuggestion.collectAsState()
    val candidates by handwritingCandidates.collectAsState()
    val handwritingBusy by handwritingRecognizing.collectAsState()
    val handwritingFailure by handwritingError.collectAsState()
    val handwritingSessionId by handwritingSession.collectAsState()
    var handwritingCandidatePage by remember { mutableIntStateOf(0) }
    LaunchedEffect(candidates) {
        handwritingCandidatePage = 0
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val textVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val keyboardColors = BrandTheme.colors
    val keyboardTypography = BrandTheme.typography
    var symbolVariant by remember(inputMode) {
        mutableStateOf(
            if (inputMode == KeyboardInputMode.Number) {
                SymbolVariant.English
            } else {
                SymbolVariant.Chinese
            }
        )
    }
    var activePanel by remember(inputMode, initialEmojiPanel) {
        mutableStateOf(if (initialEmojiPanel) KeyboardPanel.Emoji else inputMode.toPanel())
    }
    var isUppercase by remember(activePanel) { mutableStateOf(false) }
    var showMoreSymbols by remember(activePanel) { mutableStateOf(false) }
    var keyboardSurfaceOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var emojiPickerBoundsInSurface by remember { mutableStateOf(Rect.Zero) }
    var autofillBoundsInSurface by remember { mutableStateOf(Rect.Zero) }
    var handwritingBoundsInSurface by remember { mutableStateOf(Rect.Zero) }
    val keyRegistry = remember { KeyboardKeyRegistry() }
    val toolbarVisible = activePanel != KeyboardPanel.Emoji
    val toolbarState = toolbarPresentation(
        panel = activePanel.toInputMode(),
        hasAutofill = autofillView != null,
        hasClipboard = clipboard != null,
    )
    val toolbarKeys = if (toolbarVisible) {
        registerTextToolbarKeys(
            keyRegistry,
            candidates = if (activePanel == KeyboardPanel.Handwriting) candidates else emptyList(),
        )
    } else {
        null
    }
    val inputReceiver = remember { KeyboardScreenInputReceiver() }
    val inputDispatcher = remember(inputReceiver) {
        CoroutineKeyboardInputDispatcher(inputReceiver)
    }
    val touchController = remember(keyRegistry, inputDispatcher, inputReceiver) {
        KeyboardTouchController(
            keyRegistry = keyRegistry,
            inputDispatcher = inputDispatcher,
            inputReceiver = inputReceiver
        )
    }
    val hostView = LocalView.current
    SideEffect {
        inputReceiver.onCommitText = onCommitText
        inputReceiver.onDelete = onDeleteSurroundingText
        inputReceiver.onSendSpace = onSendSpace
        inputReceiver.onSendEnter = onSendEnter
        inputReceiver.onToggleRecording = onToggleRecording
        inputReceiver.onOpenSettings = onOpenSettings
        inputReceiver.onPasteClipboard = onPasteClipboard
        inputReceiver.onCandidateTapped = onHandwritingCandidateSelected
        inputReceiver.onCandidatePrevious = {
            handwritingCandidatePage = (handwritingCandidatePage - 1).coerceAtLeast(0)
        }
        inputReceiver.onCandidateNext = {
            handwritingCandidatePage++
        }
        inputReceiver.onOpenEmoji = { activePanel = KeyboardPanel.Emoji }
        inputReceiver.onToggleUppercase = { isUppercase = !isUppercase }
        inputReceiver.onToggleMoreSymbols = { showMoreSymbols = !showMoreSymbols }
        inputReceiver.onModeSwitch = { target ->
            when (target) {
                "number.chinese" -> {
                    symbolVariant = SymbolVariant.Chinese
                    activePanel = KeyboardPanel.Number
                }
                "number.english" -> {
                    symbolVariant = SymbolVariant.English
                    activePanel = KeyboardPanel.Number
                }
                "alpha" -> activePanel = KeyboardPanel.AlphaNum
                "handwriting" -> activePanel = KeyboardPanel.Handwriting
            }
            onKeyboardInputModeSelected(activePanel.toInputMode())
        }
        touchController.setHapticHandler { performKeyboardHaptic(hostView, keyboardHapticConfig) }
    }
    SideEffect {
        if (autofillView == null) {
            autofillBoundsInSurface = Rect.Zero
        }
    }
    DisposableEffect(touchController) {
        onDispose { touchController.dispose() }
    }
    val isAlphaPanel = activePanel == KeyboardPanel.AlphaNum
    val isTextPanel = activePanel == KeyboardPanel.AlphaNum || activePanel == KeyboardPanel.Number
    // Handwriting and text panels share one stable surface height so the toolbar
    // and bottom utility row never jump when the mode changes.
    val rowCount = KeyboardUiDefaults.MinimumRowCount + TextPanelAdditionalRows
    val effectiveRowCount = rowCount.coerceAtLeast(KeyboardUiDefaults.MinimumRowCount)
    val keyboardHeight = KeyboardUiDefaults.RowHeight * effectiveRowCount
    val isWritingPanel = isTextPanel || activePanel == KeyboardPanel.Handwriting
    val keyboardTopPadding = if (isWritingPanel) 4.dp else 12.dp
    val keyboardBottomPadding =
        if (isWritingPanel) TextKeyboardBottomPadding else DefaultKeyboardBottomPadding
    val keyboardVerticalArrangement =
        if (isWritingPanel) Arrangement.spacedBy(AlphaBottomRowSpacing) else Arrangement.SpaceBetween
    // Main Keyboard container with premium glassmorphic/charcoal aesthetics
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyboardHeight)
            .background(brush = keyboardColors.keyboardBackground)
            .navigationBarsPadding()
            .onGloballyPositioned { coordinates ->
                keyboardSurfaceOriginInRoot = coordinates.boundsInRoot().topLeft
                keyRegistry.updateSurfaceCoordinates(coordinates)
            }
            .pointerInteropFilter { event ->
                val passToAutofill = shouldPassToAutofillView(
                    autofillViewPresent = autofillView != null,
                    autofillBounds = autofillBoundsInSurface,
                    x = event.x,
                    y = event.y
                )
                if (shouldDismissAutofillOnTouch(
                        autofillViewPresent = autofillView != null,
                        autofillBounds = autofillBoundsInSurface,
                        action = event.actionMasked,
                        x = event.x,
                        y = event.y
                    )
                ) {
                    onAutofillTapped()
                }
                if (shouldPassToEmojiPicker(
                        emojiPanelActive = activePanel == KeyboardPanel.Emoji,
                        emojiPickerBounds = emojiPickerBoundsInSurface,
                        x = event.x,
                        y = event.y
                    )
                    || passToAutofill
                    || shouldPassToHandwritingCanvas(
                        handwritingPanelActive = activePanel == KeyboardPanel.Handwriting,
                        handwritingBounds = handwritingBoundsInSurface,
                        x = event.x,
                        y = event.y,
                    )
                ) {
                    false
                } else {
                    touchController.onMotionEvent(event)
                }
            }
            .drawWithContent {
                drawContent()
                if (debugTouchBounds) {
                    drawKeyboardDiagnostics(keyRegistry, touchController)
                }
            }
    ) {
        val availableWidth = maxWidth - KeyboardHorizontalPadding * 2
        val standardKeyTouchWidth =
            availableWidth / KeyboardUiDefaults.StandardRowUnits
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Extra bottom inset so the key row clears the system nav bar / IME-switch globe.
                // navigationBarsPadding() is often ~0 in the IME window, so this fixed gap carries it.
                .padding(
                    start = KeyboardHorizontalPadding,
                    end = KeyboardHorizontalPadding,
                    top = keyboardTopPadding,
                    bottom = keyboardBottomPadding
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = keyboardVerticalArrangement
            ) {
            if (toolbarVisible) {
                UnifiedKeyboardToolbar(
                    keyRegistry = keyRegistry,
                    keys = checkNotNull(toolbarKeys),
                    secondaryAction = toolbarState.secondaryAction,
                    candidates = candidates,
                    showHandwritingCandidates = activePanel == KeyboardPanel.Handwriting,
                    voiceState = state,
                    volume = volume,
                    handwritingBusy = handwritingBusy,
                    handwritingError = handwritingFailure,
                    autofillView = autofillView,
                    clipboard = clipboard,
                    surfaceOriginInRoot = keyboardSurfaceOriginInRoot,
                    onAutofillBoundsChanged = { autofillBoundsInSurface = it },
                    handwritingCandidatePage = handwritingCandidatePage,
                    onHandwritingCandidatePageChanged = { page ->
                        handwritingCandidatePage = page
                    },
                )
            }

            when (activePanel) {
                KeyboardPanel.Emoji -> {
                    EmojiPickerPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInRoot()
                                emojiPickerBoundsInSurface = Rect(
                                    left = bounds.left - keyboardSurfaceOriginInRoot.x,
                                    top = bounds.top - keyboardSurfaceOriginInRoot.y,
                                    right = bounds.right - keyboardSurfaceOriginInRoot.x,
                                    bottom = bounds.bottom - keyboardSurfaceOriginInRoot.y
                                )
                            },
                        onEmojiPicked = onEmojiPicked
                    )
                }
                KeyboardPanel.Number -> {
                    NumberPanel(
                        modifier = Modifier
                            .fillMaxWidth(),
                        variant = symbolVariant,
                        keyRegistry = keyRegistry,
                        showMoreSymbols = showMoreSymbols,
                        standardKeyTouchWidth = standardKeyTouchWidth,
                    )
                }
                KeyboardPanel.AlphaNum -> {
                    AlphaNumPanel(
                        modifier = Modifier
                            .fillMaxWidth(),
                        keyRegistry = keyRegistry,
                        isUppercase = isUppercase,
                        standardKeyTouchWidth = standardKeyTouchWidth,
                    )
                }
                KeyboardPanel.Handwriting -> {
                    HandwritingPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        sessionKey = handwritingSessionId,
                        onStrokeStarted = onHandwritingStrokeStarted,
                        onBoundsChanged = { bounds ->
                            handwritingBoundsInSurface = bounds.translate(
                                -keyboardSurfaceOriginInRoot.x,
                                -keyboardSurfaceOriginInRoot.y,
                            )
                        },
                        onDrawingChanged = onHandwritingChanged,
                    )
                }
            }

            if (isAlphaPanel) {
                AlphaNumBottomRow(
                    action = action,
                    keyRegistry = keyRegistry,
                    modeSwitchLabel = "?123",
                    modeSwitchTarget = "number.english",
                    commaValue = ",",
                    periodValue = "."
                )
            } else if (activePanel == KeyboardPanel.Number) {
                AlphaNumBottomRow(
                    action = action,
                    keyRegistry = keyRegistry,
                    modeSwitchLabel = "ABC",
                    modeSwitchTarget = "alpha",
                    commaValue = ",".toSymbolCommitValue(symbolVariant),
                    periodValue = ".".toSymbolCommitValue(symbolVariant)
                )
            } else {
                // Bottom Functional Keys Row (Space, Delete, Enter, Switch)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KeyboardUiDefaults.RowHeight),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KeyVisual(
                        key = keyRegistry.key(
                            id = "bottom.mode.number",
                            data = KeyData(
                                keyId = "bottom.mode.number",
                                kind = KeyboardKeyKind.ModeSwitch,
                                text = "number.chinese"
                            ),
                            rowId = "bottom"
                        ),
                        keyRegistry = keyRegistry,
                        modifier = Modifier.weight(0.75f),
                        shape = RoundedCornerShape(8.dp),
                        pressedScale = 0.92f,
                        restingElevation = 2.dp,
                        verticalPadding = 8.dp,
                        utilityStyle = true,
                    ) {
                        Text(
                            text = "?123",
                            color = textVariantColor,
                            style = keyboardTypography.keyboardSpaceKey
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    KeyVisual(
                        key = keyRegistry.key(
                            id = "bottom.secondary",
                            data = if (activePanel == KeyboardPanel.Handwriting) {
                                KeyData("bottom.secondary", KeyboardKeyKind.ModeSwitch, text = "alpha")
                            } else {
                                KeyData("bottom.secondary", KeyboardKeyKind.Settings)
                            },
                            rowId = "bottom"
                        ),
                        keyRegistry = keyRegistry,
                        modifier = Modifier.weight(0.75f),
                        shape = RoundedCornerShape(8.dp),
                        pressedScale = 0.92f,
                        restingElevation = 2.dp,
                        verticalPadding = 8.dp,
                        utilityStyle = true,
                    ) {
                        if (activePanel == KeyboardPanel.Handwriting) {
                            KeyboardIcon(modifier = Modifier.size(20.dp), color = textVariantColor)
                        } else {
                            SettingsIcon(modifier = Modifier.size(20.dp), color = textVariantColor)
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Space Key
                    KeyVisual(
                        key = keyRegistry.key(
                            id = "bottom.space",
                            data = KeyData("bottom.space", KeyboardKeyKind.Space),
                            rowId = "bottom"
                        ),
                        keyRegistry = keyRegistry,
                        modifier = Modifier.weight(2.1f),
                        shape = RoundedCornerShape(8.dp),
                        pressedScale = 0.92f,
                        restingElevation = 2.dp,
                        verticalPadding = 8.dp,
                        utilityStyle = true,
                    ) {
                        Text(
                            text = stringResource(R.string.keyboard_key_space),
                            color = textVariantColor,
                            style = keyboardTypography.keyboardSpaceKey
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Delete Key
                    KeyVisual(
                        key = keyRegistry.key(
                            id = "bottom.delete",
                            data = KeyData("bottom.delete", KeyboardKeyKind.Delete),
                            rowId = "bottom"
                        ),
                        keyRegistry = keyRegistry,
                        modifier = Modifier
                            .weight(KeyboardUiDefaults.StandardKeyWidthFactor),
                        shape = RoundedCornerShape(8.dp),
                        pressedScale = 0.92f,
                        restingElevation = 2.dp,
                        verticalPadding = 8.dp,
                        utilityStyle = true
                    ) {
                        BackspaceIcon(modifier = Modifier.size(20.dp), color = textVariantColor)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Enter Key - filled accent so the primary commit action stands out.
                    KeyVisual(
                        key = keyRegistry.key(
                            id = "bottom.enter",
                            data = KeyData("bottom.enter", KeyboardKeyKind.Enter),
                            rowId = "bottom"
                        ),
                        keyRegistry = keyRegistry,
                        modifier = Modifier.weight(1.1f),
                        backgroundColor = primaryColor,
                        borderColor = primaryColor,
                        shape = RoundedCornerShape(8.dp),
                        pressedScale = 0.92f,
                        restingElevation = 2.dp,
                        verticalPadding = 8.dp,
                        utilityStyle = true
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
}

private enum class KeyboardPanel {
    Handwriting,
    Emoji,
    Number,
    AlphaNum
}

private fun KeyboardInputMode.toPanel(): KeyboardPanel =
    when (this) {
        KeyboardInputMode.Handwriting -> KeyboardPanel.Handwriting
        KeyboardInputMode.Number -> KeyboardPanel.Number
        KeyboardInputMode.AlphaNum -> KeyboardPanel.AlphaNum
    }

private fun KeyboardPanel.toInputMode(): KeyboardInputMode =
    when (this) {
        KeyboardPanel.Handwriting, KeyboardPanel.Emoji -> KeyboardInputMode.Handwriting
        KeyboardPanel.Number -> KeyboardInputMode.Number
        KeyboardPanel.AlphaNum -> KeyboardInputMode.AlphaNum
    }
