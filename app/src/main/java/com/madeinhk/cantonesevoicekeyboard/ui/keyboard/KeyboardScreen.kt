package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.view.View
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.emoji2.emojipicker.EmojiPickerView
import com.madeinhk.cantonesevoicekeyboard.KeyboardUiState
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.theme.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sin

private val AlphaRowSpacing = 0.dp
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
    inlineSuggestionViews: StateFlow<List<View>>,
    onToggleRecording: () -> Unit,
    onDeleteSurroundingText: () -> Unit,
    onCommitText: (String) -> Unit,
    onSendSpace: () -> Unit,
    onSendEnter: () -> Unit,
    onOpenSettings: () -> Unit,
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
    val inlineSuggestions by inlineSuggestionViews.collectAsState()

    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val textVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
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
    val keyRegistry = remember { KeyboardKeyRegistry() }
    keyRegistry.beginLayout()
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
            }
            onKeyboardInputModeSelected(activePanel.toInputMode())
        }
        inputReceiver.onBackToVoice = {
            activePanel = KeyboardPanel.Voice
            onKeyboardInputModeSelected(KeyboardInputMode.Voice)
        }
        touchController.setHapticHandler { performKeyboardHaptic(hostView, keyboardHapticConfig) }
    }
    DisposableEffect(touchController) {
        onDispose { touchController.dispose() }
    }
    val isAlphaPanel = activePanel == KeyboardPanel.AlphaNum
    val isTextPanel = activePanel == KeyboardPanel.AlphaNum || activePanel == KeyboardPanel.Number
    // Voice needs the same vertical room as the text panels for its recording status
    // and bottom utility row. Keep one consistent keyboard surface height.
    val rowCount = KeyboardUiDefaults.MinimumRowCount + TextPanelAdditionalRows
    val effectiveRowCount = rowCount.coerceAtLeast(KeyboardUiDefaults.MinimumRowCount)
    val keyboardHeight = KeyboardUiDefaults.RowHeight * effectiveRowCount
    val keyboardTopPadding = if (isTextPanel) 4.dp else 12.dp
    val keyboardBottomPadding =
        if (isTextPanel) TextKeyboardBottomPadding else DefaultKeyboardBottomPadding
    val keyboardVerticalArrangement =
        if (isTextPanel) Arrangement.spacedBy(AlphaBottomRowSpacing) else Arrangement.SpaceBetween
    // Main Keyboard container with premium glassmorphic/charcoal aesthetics
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyboardHeight)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        surfaceColor,     // Sleek deep charcoal
                        backgroundColor   // Dark carbon black
                    )
                )
            )
            .navigationBarsPadding()
            .onGloballyPositioned(keyRegistry::updateSurfaceCoordinates)
            .pointerInteropFilter { event -> touchController.onMotionEvent(event) }
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
            InlineSuggestionStrip(
                suggestions = inlineSuggestions,
                modifier = Modifier.fillMaxWidth()
            )

            if (isTextPanel) {
                TextKeyboardToolbar(
                    keyRegistry = keyRegistry
                )
            }

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
                        RecordTriggerVisual(
                            state = state,
                            key = keyRegistry.key(
                                id = "voice.record",
                                data = KeyData("voice.record", KeyboardKeyKind.Record),
                                rowId = "voice.record"
                            ),
                            keyRegistry = keyRegistry
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
                            data = if (activePanel == KeyboardPanel.Voice) {
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
                        if (activePanel == KeyboardPanel.Voice) {
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

@Composable
private fun TextKeyboardToolbar(
    keyRegistry: KeyboardKeyRegistry
) {
    val keyboardColors = BrandTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KeyboardUiDefaults.ToolbarHeight)
            .padding(horizontal = KeyboardUiDefaults.KeyboardHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(
            KeyboardUiDefaults.ToolbarIconSpacing,
            alignment = Alignment.End
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarIconVisual(
            key = keyRegistry.key(
                id = "toolbar.settings",
                data = KeyData("toolbar.settings", KeyboardKeyKind.Settings),
                rowId = "toolbar"
            ),
            keyRegistry = keyRegistry
        ) {
            SettingsIcon(
                modifier = Modifier.size(KeyboardUiDefaults.ToolbarSettingsIconSize),
                color = keyboardColors.keyboardKeyOnSurface
            )
        }
        ToolbarIconVisual(
            key = keyRegistry.key(
                id = "toolbar.voice",
                data = KeyData("toolbar.voice", KeyboardKeyKind.BackToVoice),
                rowId = "toolbar"
            ),
            keyRegistry = keyRegistry
        ) {
            MicIcon(
                modifier = Modifier.size(KeyboardUiDefaults.ToolbarIconSize),
                color = keyboardColors.keyboardKeyOnSurface
            )
        }
    }
}

@Composable
private fun ToolbarIconVisual(
    modifier: Modifier = Modifier,
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    content: @Composable BoxScope.() -> Unit
) {
    KeyVisual(
        modifier = modifier.size(KeyboardUiDefaults.ToolbarIconTouchSize),
        key = key,
        keyRegistry = keyRegistry,
        backgroundColor = Color.Transparent,
        shape = CircleShape,
        restingElevation = 0.dp,
        pressedScale = 0.9f,
        borderColor = Color.Transparent,
        content = content
    )
}

private enum class KeyboardPanel {
    Voice,
    Emoji,
    Number,
    AlphaNum
}

private enum class SymbolVariant {
    Chinese,
    English
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

private fun KeyboardPanel.toInputMode(): KeyboardInputMode =
    when (this) {
        KeyboardPanel.Voice, KeyboardPanel.Emoji -> KeyboardInputMode.Voice
        KeyboardPanel.Number -> KeyboardInputMode.Number
        KeyboardPanel.AlphaNum -> KeyboardInputMode.AlphaNum
    }

private class KeyboardScreenInputReceiver : KeyboardInputReceiver {
    var onCommitText: (String) -> Unit = {}
    var onDelete: () -> Unit = {}
    var onSendSpace: () -> Unit = {}
    var onSendEnter: () -> Unit = {}
    var onToggleRecording: () -> Unit = {}
    var onOpenSettings: () -> Unit = {}
    var onOpenEmoji: () -> Unit = {}
    var onBackToVoice: () -> Unit = {}
    var onToggleUppercase: () -> Unit = {}
    var onToggleMoreSymbols: () -> Unit = {}
    var onModeSwitch: (String) -> Unit = {}

    override fun onKeyDown(data: KeyData) {
        if (data.kind == KeyboardKeyKind.Delete) onDelete()
    }

    override fun onKeyUp(data: KeyData) {
        when (data.kind) {
            KeyboardKeyKind.Text -> data.text?.let(onCommitText)
            KeyboardKeyKind.Space -> onSendSpace()
            KeyboardKeyKind.Enter -> onSendEnter()
            KeyboardKeyKind.Shift -> onToggleUppercase()
            KeyboardKeyKind.ModeSwitch -> onModeSwitch(data.text.orEmpty())
            KeyboardKeyKind.SymbolMore -> onToggleMoreSymbols()
            KeyboardKeyKind.Emoji -> onOpenEmoji()
            KeyboardKeyKind.Settings -> onOpenSettings()
            KeyboardKeyKind.BackToVoice -> onBackToVoice()
            KeyboardKeyKind.Record -> onToggleRecording()
            KeyboardKeyKind.Delete,
            KeyboardKeyKind.Utility -> Unit
        }
    }

    override fun onKeyCancel(data: KeyData) = Unit

    override fun onKeyRepeat(data: KeyData) {
        if (data.kind == KeyboardKeyKind.Delete) onDelete()
    }
}

@Composable
private fun NumberPanel(
    modifier: Modifier = Modifier,
    variant: SymbolVariant,
    keyRegistry: KeyboardKeyRegistry,
    showMoreSymbols: Boolean,
    standardKeyTouchWidth: Dp,
) {
    val rows = if (showMoreSymbols) {
        listOf(
            listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
            listOf("_", "\\", "|", "~", "<", ">", "$", "€", "£", "¥"),
            listOf(".", ",", "?", "!", "'", "\"", ":", ";")
        )
    } else {
        listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "£", "_", "&", "-", "+", "(", ")", "/"),
            listOf("*", "\"", "'", ":", ";", "!", "?")
        )
    }

    SymbolGridPanel(
        modifier = modifier,
        rows = rows,
        variant = variant,
        showMoreSymbols = showMoreSymbols,
        keyRegistry = keyRegistry,
        standardKeyTouchWidth = standardKeyTouchWidth,
    )
}

@Composable
fun AlphaNumPanel(
    modifier: Modifier = Modifier,
    keyRegistry: KeyboardKeyRegistry,
    isUppercase: Boolean,
    standardKeyTouchWidth: Dp,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AlphaRowSpacing)
    ) {
        KeyRow(
            labels = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            uppercase = isUppercase,
            keyRegistry = keyRegistry,
            rowId = "alpha.row0",
            standardKeyTouchWidth = standardKeyTouchWidth,
        )
        KeyRow(
            labels = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            uppercase = isUppercase,
            leadingWeight = 0.5f,
            trailingWeight = 0.5f,
            keyRegistry = keyRegistry,
            rowId = "alpha.row1",
            standardKeyTouchWidth = standardKeyTouchWidth,
        )
        BottomLetterRow(
            isUppercase = isUppercase,
            keyRegistry = keyRegistry
        )
    }
}

@Composable
private fun BottomLetterRow(
    isUppercase: Boolean,
    keyRegistry: KeyboardKeyRegistry
) {
    val keyboardColors = BrandTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KeyboardUiDefaults.RowHeight),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KeyVisual(
            key = keyRegistry.key(
                id = "alpha.shift",
                data = KeyData("alpha.shift", KeyboardKeyKind.Shift),
                rowId = "alpha.row2"
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(KeyboardUiDefaults.FunctionKeyWidthFactor),
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
                key = keyRegistry.key(
                    id = "alpha.row2.$key",
                    data = KeyData(
                        keyId = "alpha.row2.$key",
                        kind = KeyboardKeyKind.Text,
                        text = if (isUppercase) key.uppercase() else key,
                        popupLabel = if (isUppercase) key.uppercase() else key
                    ),
                    rowId = "alpha.row2"
                ),
                keyRegistry = keyRegistry,
                modifier = Modifier.weight(KeyboardUiDefaults.StandardKeyWidthFactor),
                label = if (isUppercase) key.uppercase() else key,
                showPressPopup = true
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "alpha.delete",
                data = KeyData("alpha.delete", KeyboardKeyKind.Delete),
                rowId = "alpha.row2"
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(KeyboardUiDefaults.FunctionKeyWidthFactor),
            backgroundColor = keyboardColors.keyboardModifierKeySurface
        ) {
            BackspaceIcon(modifier = Modifier.size(22.dp), color = keyboardColors.keyboardKeyOnSurface)
        }
    }
}

@Composable
private fun SymbolGridPanel(
    modifier: Modifier = Modifier,
    rows: List<List<String>>,
    variant: SymbolVariant,
    showMoreSymbols: Boolean,
    keyRegistry: KeyboardKeyRegistry,
    standardKeyTouchWidth: Dp,
) {
    val keyboardColors = BrandTheme.colors
    val keyboardTypography = BrandTheme.typography

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AlphaRowSpacing)
    ) {
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(KeyboardUiDefaults.RowHeight),
                horizontalArrangement = if (index == 2) {
                    Arrangement.spacedBy(5.dp)
                } else {
                    Arrangement.Start
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (index == 2) {
                    KeyVisual(
                        key = keyRegistry.key(
                            id = "number.row$index.more",
                            data = KeyData("number.row$index.more", KeyboardKeyKind.SymbolMore),
                            rowId = "number.row$index"
                        ),
                        keyRegistry = keyRegistry,
                        modifier = Modifier.weight(KeyboardUiDefaults.FunctionKeyWidthFactor),
                        backgroundColor = keyboardColors.keyboardModifierKeySurface
                    ) {
                        Text(
                            text = if (showMoreSymbols) "123" else "=<",
                            color = keyboardColors.keyboardKeyOnSurface,
                            style = keyboardTypography.keyboardSpaceKey
                        )
                    }
                }

                row.forEachIndexed { keyIndex, key ->
                    val commitValue = key.toSymbolCommitValue(variant)
                    CharacterKey(
                        key = keyRegistry.key(
                            id = "number.row$index.$keyIndex",
                            data = KeyData(
                                keyId = "number.row$index.$keyIndex",
                                kind = KeyboardKeyKind.Text,
                                text = commitValue
                            ),
                            rowId = "number.row$index"
                        ),
                        keyRegistry = keyRegistry,
                        modifier = if (index == 2) {
                            Modifier.weight(KeyboardUiDefaults.StandardKeyWidthFactor)
                        } else {
                            Modifier.width(standardKeyTouchWidth)
                        },
                        label = commitValue,
                        showPressPopup = false
                    )
                }

                if (index == 2) {
                    KeyVisual(
                        key = keyRegistry.key(
                            id = "number.row$index.delete",
                            data = KeyData("number.row$index.delete", KeyboardKeyKind.Delete),
                            rowId = "number.row$index"
                        ),
                        keyRegistry = keyRegistry,
                        modifier = Modifier.weight(KeyboardUiDefaults.FunctionKeyWidthFactor),
                        backgroundColor = keyboardColors.keyboardModifierKeySurface
                    ) {
                        BackspaceIcon(modifier = Modifier.size(22.dp), color = keyboardColors.keyboardKeyOnSurface)
                    }
                }
            }
        }
    }
}

private fun String.toSymbolCommitValue(variant: SymbolVariant): String {
    if (variant != SymbolVariant.Chinese) {
        return this
    }

    return when (this) {
        "," -> "，"
        "." -> "。"
        "?" -> "？"
        "!" -> "！"
        ":" -> "："
        ";" -> "；"
        "(" -> "（"
        ")" -> "）"
        "[" -> "【"
        "]" -> "】"
        "{" -> "「"
        "}" -> "」"
        "<" -> "《"
        ">" -> "》"
        "\"" -> "“"
        "'" -> "‘"
        "-" -> "－"
        "_" -> "＿"
        "/" -> "／"
        "\\" -> "＼"
        else -> this
    }
}

@Composable
private fun KeyRow(
    labels: List<String>,
    uppercase: Boolean = false,
    leadingWeight: Float = 0f,
    trailingWeight: Float = 0f,
    showPressPopup: Boolean = true,
    keyRegistry: KeyboardKeyRegistry,
    rowId: String,
    standardKeyTouchWidth: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KeyboardUiDefaults.RowHeight),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingWeight > 0f) {
            Spacer(modifier = Modifier.width(standardKeyTouchWidth * leadingWeight))
        }
        labels.forEach { key ->
            val label = if (uppercase) key.uppercase() else key
            CharacterKey(
                key = keyRegistry.key(
                    id = "$rowId.$key",
                    data = KeyData(
                        keyId = "$rowId.$key",
                        kind = KeyboardKeyKind.Text,
                        text = label,
                        popupLabel = label
                    ),
                    rowId = rowId
                ),
                keyRegistry = keyRegistry,
                modifier = Modifier.width(standardKeyTouchWidth),
                label = label,
                showPressPopup = showPressPopup
            )
        }
        if (trailingWeight > 0f) {
            Spacer(modifier = Modifier.width(standardKeyTouchWidth * trailingWeight))
        }
    }
}

@Composable
private fun AlphaNumBottomRow(
    action: KeyboardAction,
    keyRegistry: KeyboardKeyRegistry,
    modeSwitchLabel: String,
    modeSwitchTarget: String,
    commaValue: String,
    periodValue: String
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val keyboardColors = BrandTheme.colors
    val keyboardTypography = BrandTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KeyboardUiDefaults.RowHeight),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.mode",
                data = KeyData("text.bottom.mode", KeyboardKeyKind.ModeSwitch, text = modeSwitchTarget),
                rowId = "text.bottom"
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(1.15f),
            backgroundColor = keyboardColors.keyboardModifierKeySurface
        ) {
            Text(
                text = modeSwitchLabel,
                color = keyboardColors.keyboardKeyOnSurface,
                style = keyboardTypography.keyboardModeSwitchKey
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.comma",
                data = KeyData("text.bottom.comma", KeyboardKeyKind.Text, text = commaValue),
                rowId = "text.bottom"
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(0.9f),
            backgroundColor = keyboardColors.keyboardModifierKeySurface
        ) {
            Text(
                text = commaValue,
                color = keyboardColors.keyboardKeyOnSurface,
                style = keyboardTypography.keyboardUtilityKey
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.emoji",
                data = KeyData("text.bottom.emoji", KeyboardKeyKind.Emoji),
                rowId = "text.bottom"
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(0.9f),
        ) {
            SmileIcon(modifier = Modifier.size(22.dp), color = keyboardColors.keyboardKeyOnSurface)
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.space",
                data = KeyData("text.bottom.space", KeyboardKeyKind.Space),
                rowId = "text.bottom"
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(3.6f),
        ) {
            Text(
                text = stringResource(R.string.keyboard_key_space),
                color = keyboardColors.keyboardKeyOnSurface,
                style = keyboardTypography.keyboardSpaceKey
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.period",
                data = KeyData("text.bottom.period", KeyboardKeyKind.Text, text = periodValue),
                rowId = "text.bottom"
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(0.9f),
            backgroundColor = keyboardColors.keyboardModifierKeySurface
        ) {
            Text(
                text = periodValue,
                color = keyboardColors.keyboardKeyOnSurface,
                style = keyboardTypography.keyboardUtilityKey
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.enter",
                data = KeyData("text.bottom.enter", KeyboardKeyKind.Enter),
                rowId = "text.bottom"
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(1.25f),
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
private fun KeyVisual(
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
    utilityStyle: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val keyboardColors = BrandTheme.colors
    val isPressed = key.isPressed
    val finalBackgroundColor = when {
        backgroundColor != Color.Unspecified -> backgroundColor
        utilityStyle -> MaterialTheme.colorScheme.surface
        else -> keyboardColors.keyboardKeySurface
    }
    val finalBorderColor = when {
        borderColor != Color.Unspecified -> borderColor
        utilityStyle -> Color.Unspecified
        else -> Color.Unspecified
    }
    val finalPressedOverlayColor = when {
        pressedOverlayColor != Color.Unspecified -> pressedOverlayColor
        utilityStyle -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        else -> keyboardColors.keyboardKeyOnSurface.copy(alpha = 0.08f)
    }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = tween(80, easing = FastOutSlowInEasing),
        label = "keyboardKeyPressScale"
    )

    BoxWithConstraints(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        val touchWidth = maxWidth
        val touchHeight = maxHeight
        val visibleWidth = touchWidth - KeyboardUiDefaults.HorizontalKeyInset * 2
        val visibleHeight = touchHeight - KeyboardUiDefaults.VerticalKeyInset * 2

        Box(
            modifier = Modifier
                .width(visibleWidth)
                .height(visibleHeight)
                .semantics { contentDescription = key.currentData?.text ?: key.id }
                .onGloballyPositioned { coordinates -> keyRegistry.updateVisualBounds(key, coordinates) }
                .scale(scale)
                .shadow(elevation = if (isPressed) 0.dp else restingElevation, shape = shape)
                .clip(shape)
                .background(finalBackgroundColor)
                .then(if (isPressed) Modifier.background(finalPressedOverlayColor) else Modifier)
                .then(if (finalBorderColor != Color.Unspecified) Modifier.border(1.dp, finalBorderColor, shape) else Modifier)
                .padding(vertical = verticalPadding),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

private fun DrawScope.drawKeyboardDiagnostics(
    keyRegistry: KeyboardKeyRegistry,
    touchController: KeyboardTouchController
) {
    // Reads make the draw layer observe geometry/pointer changes without rebuilding key visuals.
    keyRegistry.geometryVersion
    touchController.debugVersion
    for (key in keyRegistry.visibleKeysForDiagnostics()) {
        drawRect(
            color = Color(0xFF00BCD4),
            topLeft = key.geometry.visualBounds.topLeft,
            size = key.geometry.visualBounds.size,
            style = Stroke(width = 1.dp.toPx())
        )
        drawRect(
            color = Color(0xFFFF9800),
            topLeft = key.geometry.touchBounds.topLeft,
            size = key.geometry.touchBounds.size,
            style = Stroke(width = 1.dp.toPx())
        )
        if (key.isPressed) {
            drawRect(
                color = Color(0xFF4CAF50).copy(alpha = 0.28f),
                topLeft = key.geometry.visualBounds.topLeft,
                size = key.geometry.visualBounds.size
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
            paint
        )
    }
}

@Composable
private fun CharacterKey(
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    modifier: Modifier = Modifier,
    label: String,
    showPressPopup: Boolean = true
) {
    val keyboardColors = BrandTheme.colors
    val keyboardTypography = BrandTheme.typography
    val isPressed = key.isPressed

    Box(
        modifier = modifier
            .fillMaxHeight()
            .zIndex(if (isPressed) 1f else 0f),
        contentAlignment = Alignment.Center
    ) {
        KeyVisual(
            key = key,
            keyRegistry = keyRegistry,
            modifier = Modifier.matchParentSize(),
        ) {
            Text(
                text = label,
                color = keyboardColors.keyboardKeyOnSurface.copy(alpha = if (isPressed) 0.28f else 1f),
                style = keyboardTypography.keyboardKey
            )
        }

        if (isPressed && showPressPopup && label.length == 1) {
            CharacterKeyPopup(
                label = label,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-52).dp)
            )
        }
    }
}

@Composable
private fun CharacterKeyPopup(
    label: String,
    modifier: Modifier = Modifier
) {
    val keyboardColors = BrandTheme.colors
    val popupShape = RoundedCornerShape(32.dp)

    Box(
        modifier = modifier
            .size(width = 60.dp, height = 64.dp)
            .shadow(
                elevation = 8.dp,
                shape = popupShape,
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.22f)
            )
            .clip(popupShape)
            .background(keyboardColors.keyboardKeySurface)
            .border(1.dp, Color.Black.copy(alpha = 0.04f), popupShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = keyboardColors.keyboardKeyOnSurface,
            fontSize = 34.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun EmojiPickerPanel(
    modifier: Modifier = Modifier,
    onEmojiPicked: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current

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
                setOnEmojiPickedListener { item ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEmojiPicked(item.emoji)
                }
            }
        },
        update = { picker ->
            picker.setOnEmojiPickedListener { item ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onEmojiPicked(item.emoji)
            }
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
private fun RecordTriggerVisual(
    state: KeyboardUiState,
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry
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

    // Press feedback is driven by the parent keyboard pointer state.
    val isPressed = key.isPressed
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
        is KeyboardUiState.Error -> errorColor.copy(alpha = 0.16f)
        KeyboardUiState.Idle -> primaryColor // Idle glowing cyan
    }

    val iconColor = when (state) {
        KeyboardUiState.Recording -> Color.White
        is KeyboardUiState.Error -> errorColor
        // Pick black/white for legibility against whatever the button fill is,
        // so the mic/spinner stays readable in both light and dark themes.
        else -> if (buttonColor.luminance() > 0.5f) Color.Black else Color.White
    }

    Box(
        modifier = Modifier.size(KeyboardUiDefaults.VoiceRecordTouchSize),
        contentAlignment = Alignment.Center,
    ) {
        val buttonShape = CircleShape
        Box(
            modifier = Modifier
                .size(KeyboardUiDefaults.VoiceRecordVisualSize)
                .onGloballyPositioned { coordinates -> keyRegistry.updateVisualBounds(key, coordinates) }
                .drawBehind {
                    if (state is KeyboardUiState.Recording) {
                        drawCircle(
                            color = buttonColor.copy(alpha = 0.25f * pulseAlpha),
                            radius = 38.dp.toPx() * pulseScale
                        )
                    }
                }
                .scale(pressScale)
                .shadow(
                    elevation = if (state is KeyboardUiState.Recording) 8.dp else 4.dp,
                    shape = buttonShape,
                    ambientColor = buttonColor,
                    spotColor = buttonColor
                )
                .clip(buttonShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = 0.9f),
                            buttonColor
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (state is KeyboardUiState.Transcribing || state is KeyboardUiState.LoadingModel) {
                CircularProgressIndicator(
                    modifier = Modifier.size(KeyboardUiDefaults.VoiceRecordIconSize),
                    color = iconColor,
                    strokeWidth = 2.5.dp
                )
            } else {
                MicIcon(
                    modifier = Modifier.size(KeyboardUiDefaults.VoiceRecordIconSize),
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
fun MicIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Icon(
        painter = painterResource(R.drawable.ic_mic_rounded),
        contentDescription = null,
        modifier = modifier,
        tint = color
    )
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
