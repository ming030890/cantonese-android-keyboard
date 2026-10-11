package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.zIndex
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme

private val AlphaRowSpacing = 0.dp

internal enum class SymbolVariant {
    Chinese,
    English,
}

@Composable
internal fun NumberPanel(
    rowHeight: Dp,
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
            listOf(".", ",", "?", "!", "'", "\"", ":", ";"),
        )
    } else {
        listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "£", "_", "&", "-", "+", "(", ")", "/"),
            listOf("*", "\"", "'", ":", ";", "!", "?"),
        )
    }

    SymbolGridPanel(
        rowHeight = rowHeight,
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
    rowHeight: Dp,
    modifier: Modifier = Modifier,
    keyRegistry: KeyboardKeyRegistry,
    isUppercase: Boolean,
    standardKeyTouchWidth: Dp,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AlphaRowSpacing),
    ) {
        KeyRow(
            rowHeight = rowHeight,
            labels = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            uppercase = isUppercase,
            keyRegistry = keyRegistry,
            rowId = "alpha.row0",
            standardKeyTouchWidth = standardKeyTouchWidth,
        )
        KeyRow(
            rowHeight = rowHeight,
            labels = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            uppercase = isUppercase,
            leadingWeight = 0.5f,
            trailingWeight = 0.5f,
            keyRegistry = keyRegistry,
            rowId = "alpha.row1",
            standardKeyTouchWidth = standardKeyTouchWidth,
        )
        BottomLetterRow(isUppercase = isUppercase, keyRegistry = keyRegistry, rowHeight = rowHeight)
    }
}

@Composable
private fun BottomLetterRow(
    rowHeight: Dp,
    isUppercase: Boolean,
    keyRegistry: KeyboardKeyRegistry,
) {
    val keyboardColors = BrandTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyVisual(
            key = keyRegistry.key(
                id = "alpha.shift",
                data = KeyData("alpha.shift", KeyboardKeyKind.Shift),
                rowId = "alpha.row2",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(KeyboardUiDefaults.FunctionKeyWidthFactor),
            backgroundColor = keyboardColors.keyboardModifierKeySurface,
        ) {
            ShiftIcon(
                modifier = Modifier.size(21.dp),
                color = keyboardColors.keyboardKeyOnSurface,
                filled = isUppercase,
            )
        }
        listOf("z", "x", "c", "v", "b", "n", "m").forEach { key ->
            val label = if (isUppercase) key.uppercase() else key
            CharacterKey(
                key = keyRegistry.key(
                    id = "alpha.row2.$key",
                    data = KeyData(
                        keyId = "alpha.row2.$key",
                        kind = KeyboardKeyKind.Text,
                        text = label,
                        popupLabel = label,
                    ),
                    rowId = "alpha.row2",
                ),
                keyRegistry = keyRegistry,
                modifier = Modifier.weight(KeyboardUiDefaults.StandardKeyWidthFactor),
                label = label,
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "alpha.delete",
                data = KeyData("alpha.delete", KeyboardKeyKind.Delete),
                rowId = "alpha.row2",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(KeyboardUiDefaults.FunctionKeyWidthFactor),
            backgroundColor = keyboardColors.keyboardModifierKeySurface,
        ) {
            BackspaceIcon(
                modifier = Modifier.size(22.dp),
                color = keyboardColors.keyboardKeyOnSurface,
            )
        }
    }
}

@Composable
private fun SymbolGridPanel(
    rowHeight: Dp,
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
        verticalArrangement = Arrangement.spacedBy(AlphaRowSpacing),
    ) {
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = if (index == 2) {
                    Arrangement.spacedBy(5.dp)
                } else {
                    Arrangement.Start
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index == 2) {
                    KeyVisual(
                        key = keyRegistry.key(
                            id = "number.row$index.more",
                            data = KeyData(
                                "number.row$index.more",
                                KeyboardKeyKind.SymbolMore,
                            ),
                            rowId = "number.row$index",
                        ),
                        keyRegistry = keyRegistry,
                        modifier = Modifier.weight(KeyboardUiDefaults.FunctionKeyWidthFactor),
                        backgroundColor = keyboardColors.keyboardModifierKeySurface,
                    ) {
                        Text(
                            text = if (showMoreSymbols) "123" else "=<",
                            color = keyboardColors.keyboardKeyOnSurface,
                            style = keyboardTypography.keyboardModeSwitchKey,
                            maxLines = 1,
                            softWrap = false,
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
                                text = commitValue,
                            ),
                            rowId = "number.row$index",
                        ),
                        keyRegistry = keyRegistry,
                        modifier = if (index == 2) {
                            Modifier.weight(KeyboardUiDefaults.StandardKeyWidthFactor)
                        } else {
                            Modifier.width(standardKeyTouchWidth)
                        },
                        label = commitValue,
                        showPressPopup = false,
                    )
                }

                if (index == 2) {
                    KeyVisual(
                        key = keyRegistry.key(
                            id = "number.row$index.delete",
                            data = KeyData(
                                "number.row$index.delete",
                                KeyboardKeyKind.Delete,
                            ),
                            rowId = "number.row$index",
                        ),
                        keyRegistry = keyRegistry,
                        modifier = Modifier.weight(KeyboardUiDefaults.FunctionKeyWidthFactor),
                        backgroundColor = keyboardColors.keyboardModifierKeySurface,
                    ) {
                        BackspaceIcon(
                            modifier = Modifier.size(22.dp),
                            color = keyboardColors.keyboardKeyOnSurface,
                        )
                    }
                }
            }
        }
    }
}

internal fun String.toSymbolCommitValue(variant: SymbolVariant): String {
    if (variant != SymbolVariant.Chinese) return this
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
    rowHeight: Dp,
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
            .height(rowHeight),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
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
                        popupLabel = label,
                    ),
                    rowId = rowId,
                ),
                keyRegistry = keyRegistry,
                modifier = Modifier.width(standardKeyTouchWidth),
                label = label,
                showPressPopup = showPressPopup,
            )
        }
        if (trailingWeight > 0f) {
            Spacer(modifier = Modifier.width(standardKeyTouchWidth * trailingWeight))
        }
    }
}

@Composable
internal fun AlphaNumBottomRow(
    rowHeight: Dp,
    action: KeyboardAction,
    keyRegistry: KeyboardKeyRegistry,
    modeSwitchLabel: String,
    modeSwitchTarget: String,
    commaValue: String,
    periodValue: String,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val keyboardColors = BrandTheme.colors
    val keyboardTypography = BrandTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        horizontalArrangement = Arrangement.spacedBy(KeyboardUiDefaults.BottomRowKeySpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.mode",
                data = KeyData(
                    "text.bottom.mode",
                    KeyboardKeyKind.ModeSwitch,
                    text = modeSwitchTarget,
                ),
                rowId = "text.bottom",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(1.3f),
            backgroundColor = keyboardColors.keyboardModifierKeySurface,
        ) {
            Text(
                text = modeSwitchLabel,
                color = keyboardColors.keyboardKeyOnSurface,
                style = keyboardTypography.keyboardModeSwitchKey,
                maxLines = 1,
                softWrap = false,
            )
        }
        val context = LocalContext.current
        val enabledLayouts = remember(context) {
            KeyboardSettings.getEnabledLayouts(context)
        }
        val nextTarget = remember(enabledLayouts) {
            computeNextLayoutTarget(KeyboardInputMode.AlphaNum, enabledLayouts)
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.handwriting",
                data = if (nextTarget != null) {
                    KeyData(
                        "text.bottom.handwriting",
                        KeyboardKeyKind.ModeSwitch,
                        text = nextTarget,
                    )
                } else {
                    KeyData(
                        "text.bottom.handwriting",
                        KeyboardKeyKind.Settings,
                    )
                },
                rowId = "text.bottom",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(1.15f),
            backgroundColor = keyboardColors.keyboardModifierKeySurface,
        ) {
            if (nextTarget != null) {
                KeyboardIcon(
                    modifier = Modifier.size(20.dp),
                    color = keyboardColors.keyboardKeyOnSurface,
                )
            } else {
                SettingsIcon(
                    modifier = Modifier.size(20.dp),
                    color = keyboardColors.keyboardKeyOnSurface,
                )
            }
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.comma",
                data = KeyData(
                    "text.bottom.comma",
                    KeyboardKeyKind.Text,
                    text = commaValue,
                ),
                rowId = "text.bottom",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(0.9f),
            backgroundColor = keyboardColors.keyboardModifierKeySurface,
        ) {
            Text(
                text = commaValue,
                color = keyboardColors.keyboardKeyOnSurface,
                style = keyboardTypography.keyboardUtilityKey,
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.emoji",
                data = KeyData("text.bottom.emoji", KeyboardKeyKind.Emoji),
                rowId = "text.bottom",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(0.9f),
        ) {
            SmileIcon(
                modifier = Modifier.size(22.dp),
                color = keyboardColors.keyboardKeyOnSurface,
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.space",
                data = KeyData("text.bottom.space", KeyboardKeyKind.Space),
                rowId = "text.bottom",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(3.45f),
        ) {
            Text(
                text = stringResource(R.string.keyboard_key_space),
                color = keyboardColors.keyboardKeyOnSurface,
                style = keyboardTypography.keyboardSpaceKey,
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.period",
                data = KeyData("text.bottom.period", KeyboardKeyKind.Text, text = periodValue),
                rowId = "text.bottom",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(0.9f),
            backgroundColor = keyboardColors.keyboardModifierKeySurface,
        ) {
            Text(
                text = periodValue,
                color = keyboardColors.keyboardKeyOnSurface,
                style = keyboardTypography.keyboardUtilityKey,
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "text.bottom.enter",
                data = KeyData("text.bottom.enter", KeyboardKeyKind.Enter),
                rowId = "text.bottom",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(1.25f),
            backgroundColor = primaryColor,
            shape = CircleShape,
        ) {
            KeyboardActionIcon(
                action = action,
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun CharacterKey(
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    modifier: Modifier = Modifier,
    label: String,
    showPressPopup: Boolean = true,
) {
    val keyboardColors = BrandTheme.colors
    val keyboardTypography = BrandTheme.typography
    val isPressed = key.isPressed
    Box(
        modifier = modifier
            .fillMaxHeight()
            .zIndex(if (isPressed) 1f else 0f),
        contentAlignment = Alignment.Center,
    ) {
        KeyVisual(
            key = key,
            keyRegistry = keyRegistry,
            modifier = Modifier.matchParentSize(),
        ) {
            Text(
                text = label,
                color = keyboardColors.keyboardKeyOnSurface.copy(
                    alpha = if (isPressed) 0.28f else 1f,
                ),
                style = keyboardTypography.keyboardKey,
            )
        }

        if (isPressed && showPressPopup && label.length == 1) {
            CharacterKeyPopup(
                label = label,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-52).dp),
            )
        }
    }
}

@Composable
private fun CharacterKeyPopup(
    label: String,
    modifier: Modifier = Modifier,
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
                spotColor = Color.Black.copy(alpha = 0.22f),
            )
            .clip(popupShape)
            .background(keyboardColors.keyboardKeySurface)
            .border(1.dp, Color.Black.copy(alpha = 0.04f), popupShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = keyboardColors.keyboardKeyOnSurface,
            fontSize = 34.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}
