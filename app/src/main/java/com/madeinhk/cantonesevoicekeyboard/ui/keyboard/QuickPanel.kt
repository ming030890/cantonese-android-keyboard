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
import androidx.compose.ui.zIndex
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme

private val AlphaRowSpacing = 0.dp

private val CangjieRadicals = mapOf(
    "q" to "手", "w" to "田", "e" to "水", "r" to "口", "t" to "廿",
    "y" to "卜", "u" to "山", "i" to "戈", "o" to "人", "p" to "心",
    "a" to "日", "s" to "尸", "d" to "木", "f" to "火", "g" to "土",
    "h" to "竹", "j" to "十", "k" to "大", "l" to "中",
    "z" to "重", "x" to "難", "c" to "金", "v" to "女", "b" to "月",
    "n" to "弓", "m" to "一"
)

@Composable
fun QuickPanel(
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
        QuickKeyRow(
            rowHeight = rowHeight,
            labels = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            uppercase = isUppercase,
            keyRegistry = keyRegistry,
            rowId = "quick.row0",
            standardKeyTouchWidth = standardKeyTouchWidth,
        )
        QuickKeyRow(
            rowHeight = rowHeight,
            labels = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            uppercase = isUppercase,
            leadingWeight = 0.5f,
            trailingWeight = 0.5f,
            keyRegistry = keyRegistry,
            rowId = "quick.row1",
            standardKeyTouchWidth = standardKeyTouchWidth,
        )
        QuickBottomLetterRow(
            isUppercase = isUppercase,
            keyRegistry = keyRegistry,
            rowHeight = rowHeight,
        )
    }
}

@Composable
private fun QuickKeyRow(
    rowHeight: Dp,
    labels: List<String>,
    uppercase: Boolean,
    keyRegistry: KeyboardKeyRegistry,
    rowId: String,
    standardKeyTouchWidth: Dp,
    leadingWeight: Float = 0f,
    trailingWeight: Float = 0f,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingWeight > 0f) {
            Spacer(modifier = Modifier.weight(leadingWeight))
        }
        labels.forEachIndexed { index, letter ->
            val commitValue = if (uppercase) letter.uppercase() else letter
            val radical = CangjieRadicals[letter].orEmpty()
            QuickCharacterKey(
                key = keyRegistry.key(
                    id = "$rowId.$index",
                    data = KeyData(
                        keyId = "$rowId.$index",
                        kind = KeyboardKeyKind.Text,
                        text = commitValue,
                    ),
                    rowId = rowId,
                ),
                keyRegistry = keyRegistry,
                modifier = Modifier.width(standardKeyTouchWidth),
                label = commitValue,
                radical = radical,
            )
        }
        if (trailingWeight > 0f) {
            Spacer(modifier = Modifier.weight(trailingWeight))
        }
    }
}

@Composable
private fun QuickBottomLetterRow(
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
                id = "quick.shift",
                data = KeyData(
                    keyId = "quick.shift",
                    kind = KeyboardKeyKind.Shift,
                    rolloverEligible = false,
                ),
                rowId = "quick.row2",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(KeyboardUiDefaults.FunctionKeyWidthFactor),
            backgroundColor = keyboardColors.keyboardModifierKeySurface,
        ) {
            ShiftIcon(
                modifier = Modifier.size(20.dp),
                color = keyboardColors.keyboardKeyOnSurface,
                filled = isUppercase,
            )
        }
        listOf("z", "x", "c", "v", "b", "n", "m").forEachIndexed { index, letter ->
            val commitValue = if (isUppercase) letter.uppercase() else letter
            val radical = CangjieRadicals[letter].orEmpty()
            QuickCharacterKey(
                key = keyRegistry.key(
                    id = "quick.row2.$index",
                    data = KeyData(
                        keyId = "quick.row2.$index",
                        kind = KeyboardKeyKind.Text,
                        text = commitValue,
                    ),
                    rowId = "quick.row2",
                ),
                keyRegistry = keyRegistry,
                modifier = Modifier.weight(KeyboardUiDefaults.StandardKeyWidthFactor),
                label = commitValue,
                radical = radical,
            )
        }
        KeyVisual(
            key = keyRegistry.key(
                id = "quick.delete",
                data = KeyData(
                    keyId = "quick.delete",
                    kind = KeyboardKeyKind.Delete,
                    repeatable = true,
                    rolloverEligible = false,
                ),
                rowId = "quick.row2",
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
private fun QuickCharacterKey(
    key: KeyModel,
    keyRegistry: KeyboardKeyRegistry,
    modifier: Modifier = Modifier,
    label: String,
    radical: String,
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
            Box(modifier = Modifier.fillMaxSize()) {
                if (radical.isNotEmpty()) {
                    Text(
                        text = radical,
                        color = keyboardColors.keyboardKeyOnSurface.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 5.dp),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    text = label,
                    color = keyboardColors.keyboardKeyOnSurface.copy(
                        alpha = if (isPressed) 0.28f else 1f,
                    ),
                    style = keyboardTypography.keyboardKey,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        if (isPressed && showPressPopup && label.length == 1) {
            QuickCharacterKeyPopup(
                label = label,
                radical = radical,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-52).dp),
            )
        }
    }
}

@Composable
private fun QuickCharacterKeyPopup(
    label: String,
    radical: String,
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (radical.isNotEmpty()) {
                Text(
                    text = radical,
                    color = keyboardColors.keyboardKeyOnSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                text = label,
                color = keyboardColors.keyboardKeyOnSurface,
                fontSize = 30.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun QuickBottomRow(
    rowHeight: Dp,
    action: KeyboardAction,
    keyRegistry: KeyboardKeyRegistry,
    modeSwitchLabel: String = "?123",
    modeSwitchTarget: String = "number.chinese",
    commaValue: String = "，",
    periodValue: String = "。",
) {
    val keyboardColors = BrandTheme.colors
    val keyboardTypography = BrandTheme.typography
    val primaryColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyVisual(
            key = keyRegistry.key(
                id = "quick.bottom.mode",
                data = KeyData(
                    keyId = "quick.bottom.mode",
                    kind = KeyboardKeyKind.ModeSwitch,
                    text = modeSwitchTarget,
                ),
                rowId = "quick.bottom",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(1.3f),
            backgroundColor = keyboardColors.keyboardModifierKeySurface,
        ) {
            ModeKeyLabel(modeSwitchLabel)
        }

        KeyVisual(
            key = keyRegistry.key(
                id = "quick.bottom.comma",
                data = KeyData(
                    "quick.bottom.comma",
                    KeyboardKeyKind.Text,
                    text = commaValue,
                ),
                rowId = "quick.bottom",
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
                id = "quick.bottom.emoji",
                data = KeyData("quick.bottom.emoji", KeyboardKeyKind.Emoji),
                rowId = "quick.bottom",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(0.9f),
        ) {
            SmileIcon(
                modifier = Modifier.size(30.dp),
                color = keyboardColors.keyboardKeyOnSurface,
            )
        }

        KeyVisual(
            key = keyRegistry.key(
                id = "quick.bottom.space",
                data = KeyData("quick.bottom.space", KeyboardKeyKind.Space),
                rowId = "quick.bottom",
            ),
            keyRegistry = keyRegistry,
            modifier = Modifier.weight(4.6f),
        ) {
            Text(
                text = stringResource(R.string.keyboard_key_space),
                color = keyboardColors.keyboardKeyOnSurface,
                style = keyboardTypography.keyboardSpaceKey,
            )
        }

        KeyVisual(
            key = keyRegistry.key(
                id = "quick.bottom.period",
                data = KeyData("quick.bottom.period", KeyboardKeyKind.Text, text = periodValue),
                rowId = "quick.bottom",
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
                id = "quick.bottom.enter",
                data = KeyData("quick.bottom.enter", KeyboardKeyKind.Enter),
                rowId = "quick.bottom",
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
