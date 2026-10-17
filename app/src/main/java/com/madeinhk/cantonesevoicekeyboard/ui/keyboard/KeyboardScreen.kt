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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import com.madeinhk.cantonesevoicekeyboard.KeyboardUiState
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingCandidate
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val AlphaBottomRowSpacing = KeyboardUiDefaults.WritingPanelSpacing
private val KeyboardHorizontalPadding = KeyboardUiDefaults.KeyboardHorizontalPadding

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
    quickCandidates: StateFlow<List<HandwritingCandidate>> = MutableStateFlow<List<HandwritingCandidate>>(emptyList()),
    englishCandidates: StateFlow<List<HandwritingCandidate>> = MutableStateFlow(emptyList()),
    onQuickKey: (String) -> Unit = {},
    onTouchEvidence: (com.madeinhk.cantonesevoicekeyboard.english.EnglishTouchEvidence?) -> Unit = {},
    onVisiblePanelChanged: (KeyboardInputMode?) -> Unit = {},
    onToggleRecording: () -> Unit,
    onDeleteSurroundingText: () -> Unit,
    onCommitText: (String) -> Unit,
    onSendSpace: () -> Unit,
    onSendEnter: () -> Unit,
    onOpenSettings: () -> Unit,
    onPasteClipboard: () -> Unit,
    onHandwritingChanged: (strokes: List<List<com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint>>, canvasWidth: Float, canvasHeight: Float, pixels: ByteArray, width: Int, height: Int) -> Unit,
    onHandwritingStrokeStarted: (startsNewCharacter: Boolean) -> Unit = {},
    onHandwritingCandidateSelected: (String) -> Unit,
    onAutofillTapped: () -> Unit = {},
    onEmojiPicked: (String) -> Unit,
    onKeyboardInputModeSelected: (KeyboardInputMode) -> Unit = {},
    onInterruptVoice: () -> Unit = {},
    initialEmojiPanel: Boolean = false,
    initialUppercase: Boolean = false,
    initialShowMoreSymbols: Boolean = false,
    initialHandwritingCandidatePage: Int = 0,
    debugTouchBounds: Boolean = false,
    keyboardHapticConfig: KeyboardHapticConfig = KeyboardHapticConfig(),
) {
    val voice by uiState.collectAsState()
    val volume by volumeLevel.collectAsState()
    val action by keyboardAction.collectAsState()
    val inputMode by keyboardInputMode.collectAsState()
    val autofillView by autofillSuggestionView.collectAsState()
    val clipboard by clipboardSuggestion.collectAsState()
    val handwritingCandidates by handwritingCandidates.collectAsState()
    val handwritingBusy by handwritingRecognizing.collectAsState()
    val handwritingError by handwritingError.collectAsState()
    val handwritingSession by handwritingSession.collectAsState()
    val quickCandidates by quickCandidates.collectAsState()
    val englishCandidates by englishCandidates.collectAsState()
    KeyboardScreen(
        renderState = KeyboardRenderState(
            voice = voice,
            volume = volume,
            action = action,
            inputMode = inputMode,
            clipboard = clipboard,
            handwritingCandidates = handwritingCandidates,
            handwritingBusy = handwritingBusy,
            handwritingError = handwritingError,
            handwritingSession = handwritingSession,
            quickCandidates = quickCandidates,
            englishCandidates = englishCandidates,
        ),
        autofillView = autofillView,
        onQuickKey = onQuickKey,
        onTouchEvidence = onTouchEvidence,
        onVisiblePanelChanged = onVisiblePanelChanged,
        onToggleRecording = onToggleRecording,
        onDeleteSurroundingText = onDeleteSurroundingText,
        onCommitText = onCommitText,
        onSendSpace = onSendSpace,
        onSendEnter = onSendEnter,
        onOpenSettings = onOpenSettings,
        onPasteClipboard = onPasteClipboard,
        onHandwritingChanged = onHandwritingChanged,
        onHandwritingStrokeStarted = onHandwritingStrokeStarted,
        onHandwritingCandidateSelected = onHandwritingCandidateSelected,
        onAutofillTapped = onAutofillTapped,
        onEmojiPicked = onEmojiPicked,
        onKeyboardInputModeSelected = onKeyboardInputModeSelected,
        onInterruptVoice = onInterruptVoice,
        initialEmojiPanel = initialEmojiPanel,
        initialUppercase = initialUppercase,
        initialShowMoreSymbols = initialShowMoreSymbols,
        initialHandwritingCandidatePage = initialHandwritingCandidatePage,
        debugTouchBounds = debugTouchBounds,
        keyboardHapticConfig = keyboardHapticConfig,
    )
}

@Composable
fun KeyboardScreen(
    renderState: KeyboardRenderState,
    autofillView: View? = null,
    onQuickKey: (String) -> Unit = {},
    onTouchEvidence: (com.madeinhk.cantonesevoicekeyboard.english.EnglishTouchEvidence?) -> Unit = {},
    onVisiblePanelChanged: (KeyboardInputMode?) -> Unit = {},
    onToggleRecording: () -> Unit,
    onDeleteSurroundingText: () -> Unit,
    onCommitText: (String) -> Unit,
    onSendSpace: () -> Unit,
    onSendEnter: () -> Unit,
    onOpenSettings: () -> Unit,
    onPasteClipboard: () -> Unit,
    onHandwritingChanged: (strokes: List<List<com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint>>, canvasWidth: Float, canvasHeight: Float, pixels: ByteArray, width: Int, height: Int) -> Unit,
    onHandwritingStrokeStarted: (startsNewCharacter: Boolean) -> Unit = {},
    onHandwritingCandidateSelected: (String) -> Unit,
    onAutofillTapped: () -> Unit = {},
    onEmojiPicked: (String) -> Unit,
    onKeyboardInputModeSelected: (KeyboardInputMode) -> Unit = {},
    onInterruptVoice: () -> Unit = {},
    initialEmojiPanel: Boolean = false,
    initialUppercase: Boolean = false,
    initialShowMoreSymbols: Boolean = false,
    initialHandwritingCandidatePage: Int = 0,
    debugTouchBounds: Boolean = false,
    keyboardHapticConfig: KeyboardHapticConfig = KeyboardHapticConfig(),
) {
    val state = renderState.voice
    val volume = renderState.volume
    val action = renderState.action
    val inputMode = renderState.inputMode
    val clipboard = renderState.clipboard
    val candidates = renderState.handwritingCandidates
    val qCandidates = renderState.quickCandidates
    val handwritingBusy = renderState.handwritingBusy
    val handwritingFailure = renderState.handwritingError
    val handwritingSessionId = renderState.handwritingSession

    var activePanel by remember(inputMode, initialEmojiPanel) {
        mutableStateOf(if (initialEmojiPanel) KeyboardPanel.Emoji else inputMode.toPanel())
    }
    var previousPanelBeforeNumber by remember(inputMode) {
        mutableStateOf(inputMode.toPanel())
    }
    val isQuickPanel = activePanel == KeyboardPanel.Quick
    val isHandwritingPanel = activePanel == KeyboardPanel.Handwriting
    val activeCandidateList = when {
        isQuickPanel -> qCandidates
        isHandwritingPanel -> candidates
        activePanel == KeyboardPanel.AlphaNum -> renderState.englishCandidates
        else -> emptyList()
    }
    var candidatePage by remember(activeCandidateList, initialHandwritingCandidatePage) {
        mutableIntStateOf(initialHandwritingCandidatePage)
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
    var isUppercase by remember(activePanel, initialUppercase) {
        mutableStateOf(initialUppercase)
    }
    var showMoreSymbols by remember(activePanel, initialShowMoreSymbols) {
        mutableStateOf(initialShowMoreSymbols)
    }
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
            candidates = activeCandidateList,
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
        inputReceiver.onTouchEvidence = onTouchEvidence
        inputReceiver.onCommitText = { text ->
            dispatchKeyboardText(activePanel.toInputMode(), text, onQuickKey, onCommitText)
        }
        inputReceiver.onDelete = onDeleteSurroundingText
        inputReceiver.onSendSpace = onSendSpace
        inputReceiver.onSendEnter = onSendEnter
        inputReceiver.onToggleRecording = onToggleRecording
        inputReceiver.onOpenSettings = onOpenSettings
        inputReceiver.onPasteClipboard = onPasteClipboard
        inputReceiver.onCandidateTapped = onHandwritingCandidateSelected
        inputReceiver.onInterruptVoice = onInterruptVoice
        inputReceiver.onCandidatePrevious = {
            candidatePage = (candidatePage - 1).coerceAtLeast(0)
        }
        inputReceiver.onCandidateNext = {
            candidatePage++
        }
        inputReceiver.onOpenEmoji = { onVisiblePanelChanged(null); activePanel = KeyboardPanel.Emoji }
        inputReceiver.onToggleUppercase = { isUppercase = !isUppercase }
        inputReceiver.onToggleMoreSymbols = { showMoreSymbols = !showMoreSymbols }
        inputReceiver.onModeSwitch = { target ->
            when (target) {
                "number.chinese" -> {
                    previousPanelBeforeNumber = activePanel
                    symbolVariant = SymbolVariant.Chinese
                    activePanel = KeyboardPanel.Number
                }
                "number.english" -> {
                    previousPanelBeforeNumber = activePanel
                    symbolVariant = SymbolVariant.English
                    activePanel = KeyboardPanel.Number
                }
                "alpha" -> activePanel = KeyboardPanel.AlphaNum
                "quick" -> activePanel = KeyboardPanel.Quick
                "handwriting" -> activePanel = KeyboardPanel.Handwriting
            }
            onVisiblePanelChanged(activePanel.toInputMode())
            if (activePanel != KeyboardPanel.Number) onKeyboardInputModeSelected(activePanel.toInputMode())
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
    val isTextPanel = activePanel == KeyboardPanel.AlphaNum ||
        activePanel == KeyboardPanel.Number ||
        activePanel == KeyboardPanel.Quick
    // Android exposes the IME dismiss/switch controls as a caption bar.
    // Union takes the maximum on each edge rather than double-counting bars.
    val systemPadding = WindowInsets.navigationBars
        .union(WindowInsets.captionBar)
        .union(WindowInsets.displayCutout)
        .asPaddingValues()
    val keyboardHeight = KeyboardUiDefaults.KeyboardSurfaceHeightDp.dp
    val isWritingPanel = isTextPanel || activePanel == KeyboardPanel.Handwriting
    val keyboardTopPadding =
        if (isWritingPanel) KeyboardUiDefaults.WritingTopPadding else 12.dp
    val keyboardBottomPadding = systemPadding.calculateBottomPadding()
    val keyboardVerticalArrangement = when {
        isTextPanel -> Arrangement.Top
        isWritingPanel -> Arrangement.spacedBy(AlphaBottomRowSpacing)
        else -> Arrangement.SpaceBetween
    }
    // Main Keyboard container with premium glassmorphic/charcoal aesthetics
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyboardHeight)
            .background(brush = keyboardColors.keyboardBackground)
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
            - systemPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
            - systemPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
        // Share the safe area below the toolbar equally across all four rows.
        val textRowHeight = (maxHeight - keyboardTopPadding -
            keyboardBottomPadding - KeyboardUiDefaults.ToolbarHeight) / 4
        val standardKeyTouchWidth =
            availableWidth / KeyboardUiDefaults.StandardRowUnits
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Reserve the system-owned IME navigation area inside the surface.
                .padding(
                    start = KeyboardHorizontalPadding + systemPadding.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
                    end = KeyboardHorizontalPadding + systemPadding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
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
                    candidates = activeCandidateList,
                    showHandwritingCandidates = isHandwritingPanel || activeCandidateList.isNotEmpty(),
                    voiceState = state,
                    volume = volume,
                    handwritingBusy = if (isHandwritingPanel) handwritingBusy else false,
                    handwritingError = if (isHandwritingPanel) handwritingFailure else null,
                    autofillView = autofillView,
                    clipboard = clipboard,
                    surfaceOriginInRoot = keyboardSurfaceOriginInRoot,
                    onAutofillBoundsChanged = { autofillBoundsInSurface = it },
                    handwritingCandidatePage = candidatePage,
                    onHandwritingCandidatePageChanged = { page ->
                        candidatePage = page
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
                        onEmojiPicked = onEmojiPicked,
                        onBackToKeyboard = {
                            activePanel = inputMode.toPanel()
                            onVisiblePanelChanged(inputMode)
                        },
                        onDelete = onDeleteSurroundingText,
                    )
                }
                KeyboardPanel.Number -> {
                    NumberPanel(
                        rowHeight = textRowHeight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(textRowHeight * 3),
                        variant = symbolVariant,
                        keyRegistry = keyRegistry,
                        showMoreSymbols = showMoreSymbols,
                        standardKeyTouchWidth = standardKeyTouchWidth,
                    )
                }
                KeyboardPanel.AlphaNum -> {
                    AlphaNumPanel(
                        rowHeight = textRowHeight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(textRowHeight * 3),
                        keyRegistry = keyRegistry,
                        isUppercase = isUppercase,
                        standardKeyTouchWidth = standardKeyTouchWidth,
                    )
                }
                KeyboardPanel.Quick -> {
                    QuickPanel(
                        rowHeight = textRowHeight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(textRowHeight * 3),
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
                        onStrokesChanged = onHandwritingChanged,
                    )
                }
            }

            if (isQuickPanel) {
                QuickBottomRow(
                    rowHeight = textRowHeight,
                    action = action,
                    keyRegistry = keyRegistry,
                    currentMode = KeyboardInputMode.Quick,
                    modeSwitchLabel = "?123",
                    modeSwitchTarget = "number.chinese",
                    commaValue = ",",
                    periodValue = ".",
                )
            } else if (isAlphaPanel) {
                AlphaNumBottomRow(
                    rowHeight = textRowHeight,
                    action = action,
                    keyRegistry = keyRegistry,
                    modeSwitchLabel = "?123",
                    modeSwitchTarget = "number.english",
                    commaValue = ",",
                    periodValue = "."
                )
            } else if (activePanel == KeyboardPanel.Number) {
                val backTarget = when (previousPanelBeforeNumber) {
                    KeyboardPanel.Quick -> "quick"
                    KeyboardPanel.Handwriting -> "handwriting"
                    else -> "alpha"
                }
                val backLabel = when (previousPanelBeforeNumber) {
                    KeyboardPanel.Quick -> "速"
                    KeyboardPanel.Handwriting -> "手寫"
                    else -> "ABC"
                }
                AlphaNumBottomRow(
                    rowHeight = textRowHeight,
                    action = action,
                    keyRegistry = keyRegistry,
                    modeSwitchLabel = backLabel,
                    modeSwitchTarget = backTarget,
                    commaValue = ",".toSymbolCommitValue(symbolVariant),
                    periodValue = ".".toSymbolCommitValue(symbolVariant)
                )
            } else if (activePanel != KeyboardPanel.Emoji) {
                // Bottom Functional Keys Row (Space, Delete, Enter, Switch)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KeyboardUiDefaults.RowHeight),
                    horizontalArrangement = Arrangement.spacedBy(
                        KeyboardUiDefaults.BottomRowKeySpacing
                    ),
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
                            style = keyboardTypography.keyboardModeSwitchKey,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    val context = LocalContext.current
                    val enabledLayouts = remember(context) {
                        KeyboardSettings.getEnabledLayouts(context)
                    }
                    val nextLayoutTarget = remember(enabledLayouts) {
                        computeNextLayoutTarget(KeyboardInputMode.Handwriting, enabledLayouts)
                    }
                    val showLayoutSwitch = activePanel == KeyboardPanel.Handwriting && nextLayoutTarget != null
                    KeyVisual(
                        key = keyRegistry.key(
                            id = "bottom.secondary",
                            data = if (showLayoutSwitch) {
                                KeyData("bottom.secondary", KeyboardKeyKind.ModeSwitch, text = nextLayoutTarget)
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
                        if (showLayoutSwitch) {
                            KeyboardIcon(modifier = Modifier.size(20.dp), color = textVariantColor)
                        } else {
                            SettingsIcon(modifier = Modifier.size(20.dp), color = textVariantColor)
                        }
                    }

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
    AlphaNum,
    Quick,
}

private fun KeyboardInputMode.toPanel(): KeyboardPanel =
    when (this) {
        KeyboardInputMode.Handwriting -> KeyboardPanel.Handwriting
        KeyboardInputMode.Number -> KeyboardPanel.Number
        KeyboardInputMode.AlphaNum -> KeyboardPanel.AlphaNum
        KeyboardInputMode.Quick -> KeyboardPanel.Quick
    }

private fun KeyboardPanel.toInputMode(): KeyboardInputMode =
    when (this) {
        KeyboardPanel.Handwriting, KeyboardPanel.Emoji -> KeyboardInputMode.Handwriting
        KeyboardPanel.Number -> KeyboardInputMode.Number
        KeyboardPanel.AlphaNum -> KeyboardInputMode.AlphaNum
        KeyboardPanel.Quick -> KeyboardInputMode.Quick
    }
