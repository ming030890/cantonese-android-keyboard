package com.madeinhk.cantonesevoicekeyboard.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import com.madeinhk.cantonesevoicekeyboard.R

// Material 3 Dynamic Dark Forest Palette shared across settings & onboarding
internal val CardBackground = Color(0xFF1C231F)
internal val CardBorderColor = Color(0xFF26302B)
internal val MiniKeyboardBackground = Color(0xFF141A17)
internal val MiniKeySurface = Color(0xFF232B27)
internal val MintAccentColor = Color(0xFF9FD4B3)
internal val MintOnAccentColor = Color(0xFF0D271A)
internal val PillUnselectedBorder = Color(0xFF4C5851)
internal val PillUnselectedIcon = Color(0xFF8E9C94)
internal val PrimaryTextColor = Color(0xFFDFE5E1)
internal val SecondaryTextColor = Color(0xFF8E9C94)

/**
 * Shared Keyboard Layouts Selector Card used in both Onboarding and Settings.
 * Displays miniature visual previews for each layout (English, Quick, Handwriting)
 * with interactive selection pills, Cangjie version selector, and voice typing caption.
 */
@Composable
fun KeyboardLayoutsSelectorCard(
    enabledLayouts: Set<String>,
    onToggleLayout: (String) -> Unit,
    quickVersion: Int = KeyboardSettings.DEFAULT_QUICK_VERSION,
    onQuickVersionChange: (Int) -> Unit = {},
    title: String? = null,
    description: String? = null,
    inferredReason: String? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorderColor)
    ) {
        if (title == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                KeyboardLayoutsRowContent(
                    enabledLayouts = enabledLayouts,
                    onToggleLayout = onToggleLayout,
                    quickVersion = quickVersion,
                    onQuickVersionChange = onQuickVersionChange
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = PrimaryTextColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (description != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = description,
                                    color = SecondaryTextColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        }
                        if (enabledLayouts.isNotEmpty()) {
                            Text(
                                text = stringResource(id = R.string.setup_step_completed),
                                color = MintAccentColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                    if (!inferredReason.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "💡 $inferredReason",
                            color = MintAccentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    KeyboardLayoutsRowContent(
                        enabledLayouts = enabledLayouts,
                        onToggleLayout = onToggleLayout,
                        quickVersion = quickVersion,
                        onQuickVersionChange = onQuickVersionChange
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyboardLayoutsRowContent(
    enabledLayouts: Set<String>,
    onToggleLayout: (String) -> Unit,
    quickVersion: Int,
    onQuickVersionChange: (Int) -> Unit
) {
    // 1. QWERTY (English)
    val isQwertySelected = enabledLayouts.contains(KeyboardSettings.LAYOUT_ENGLISH)
    KeyboardLayoutItem(
        title = stringResource(R.string.settings_layout_qwerty),
        isSelected = isQwertySelected,
        onToggle = { onToggleLayout(KeyboardSettings.LAYOUT_ENGLISH) },
        preview = { MiniQwertyPreview() }
    )

    // 2. Quick (Cantonese Quick + English)
    val isQuickSelected = enabledLayouts.contains(KeyboardSettings.LAYOUT_QUICK)
    KeyboardLayoutItem(
        title = stringResource(R.string.settings_layout_quick),
        isSelected = isQuickSelected,
        onToggle = { onToggleLayout(KeyboardSettings.LAYOUT_QUICK) },
        preview = { MiniQuickPreview() },
        extraContent = if (isQuickSelected) {
            {
                QuickVersionMiniPills(
                    selectedVersion = quickVersion,
                    onVersionChange = onQuickVersionChange
                )
            }
        } else null
    )

    // 3. Handwriting
    val isHandwritingSelected = enabledLayouts.contains(KeyboardSettings.LAYOUT_HANDWRITING)
    KeyboardLayoutItem(
        title = stringResource(R.string.settings_layout_handwriting),
        isSelected = isHandwritingSelected,
        onToggle = { onToggleLayout(KeyboardSettings.LAYOUT_HANDWRITING) },
        preview = { MiniHandwritingPreview() }
    )
}

@Composable
fun QuickVersionMiniPills(
    selectedVersion: Int,
    onVersionChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            KeyboardSettings.QUICK_VERSION_3 to "3",
            KeyboardSettings.QUICK_VERSION_5 to "5",
        ).forEach { (ver, label) ->
            val isVerSelected = selectedVersion == ver
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (isVerSelected) MintAccentColor else MiniKeySurface,
                border = if (!isVerSelected) BorderStroke(1.dp, CardBorderColor) else null,
                onClick = { onVersionChange(ver) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        color = if (isVerSelected) MintOnAccentColor else PrimaryTextColor,
                        fontSize = 12.sp,
                        fontWeight = if (isVerSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
fun KeyboardLayoutItem(
    title: String,
    isSelected: Boolean,
    onToggle: () -> Unit,
    preview: @Composable () -> Unit,
    enabled: Boolean = true,
    extraContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(148.dp)
            .clickable(enabled = enabled, onClick = onToggle),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Miniature Keyboard Preview Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(102.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MiniKeyboardBackground)
                .border(
                    BorderStroke(1.dp, CardBorderColor),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(4.dp)
        ) {
            preview()
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Selection Pill
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            shape = RoundedCornerShape(50),
            color = if (isSelected) MintAccentColor else Color.Transparent,
            border = if (!isSelected) BorderStroke(1.5.dp, PillUnselectedBorder) else null,
            onClick = { if (enabled) onToggle() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSelected) {
                    Canvas(modifier = Modifier.size(16.dp)) {
                        val stroke = 2.dp.toPx()
                        val w = size.width
                        val h = size.height
                        val path = Path().apply {
                            moveTo(w * 0.16f, h * 0.52f)
                            lineTo(w * 0.42f, h * 0.78f)
                            lineTo(w * 0.86f, h * 0.26f)
                        }
                        drawPath(
                            path = path,
                            color = MintOnAccentColor,
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        color = MintOnAccentColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Canvas(modifier = Modifier.size(16.dp)) {
                        val stroke = 1.5.dp.toPx()
                        drawCircle(
                            color = PillUnselectedIcon,
                            radius = size.width * 0.38f,
                            style = Stroke(stroke)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        color = PrimaryTextColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (extraContent != null) {
            extraContent()
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_feature_voice_typing),
            color = if (isSelected) MintAccentColor else SecondaryTextColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * Miniature QWERTY Preview.
 * Features miniature number superscripts above the top row and standard key arrangement.
 */
@Composable
fun MiniQwertyPreview() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Mini Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniGridIcon()
            MiniMicIcon()
        }

        // Row 1: q¹ w² e³ r⁴ t⁵ y⁶ u⁷ i⁸ o⁹ p⁰
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val letters = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
            val superscripts = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
            letters.forEachIndexed { i, l ->
                MiniKeyWithHint(letter = l, hint = superscripts[i], modifier = Modifier.weight(1f))
            }
        }

        // Row 2: a s d f g h j k l
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").forEach { l ->
                MiniKeyCap(text = l, modifier = Modifier.weight(1f))
            }
        }

        // Row 3: ⇧, z x c v b n m, ⌫
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniKeyCap(text = "⇧", modifier = Modifier.weight(1.2f), textSize = 6.sp)
            listOf("z", "x", "c", "v", "b", "n", "m").forEach { l ->
                MiniKeyCap(text = l, modifier = Modifier.weight(1f))
            }
            MiniKeyCap(text = "⌫", modifier = Modifier.weight(1.2f), textSize = 6.sp)
        }

        // Row 4: ?123, ,, English, ., ↵
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniKeyCap(text = "?123", modifier = Modifier.weight(1.4f), textSize = 4.5.sp)
            MiniKeyCap(text = ",", modifier = Modifier.weight(0.9f))
            MiniKeyCap(text = "English", modifier = Modifier.weight(3.6f), textSize = 5.sp)
            MiniKeyCap(text = ".", modifier = Modifier.weight(0.9f))
            MiniKeyCap(text = "↵", modifier = Modifier.weight(1.2f), textSize = 6.sp)
        }
    }
}

/**
 * Miniature Handwriting Preview.
 * Features top toolbar, clean open drawing canvas, and bottom function row.
 */
@Composable
fun MiniHandwritingPreview() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Mini Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniGridIcon()
            MiniMicIcon()
        }

        // Clean Open Handwriting Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 2.dp)
        )

        // Bottom Function Row: ?123, globe, English, ⌫, ↵
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniKeyCap(text = "?123", modifier = Modifier.weight(1.3f), textSize = 4.5.sp)
            MiniKeyCap(text = "🌐", modifier = Modifier.weight(0.9f), textSize = 4.5.sp)
            MiniKeyCap(text = "English", modifier = Modifier.weight(3.6f), textSize = 5.sp)
            MiniKeyCap(text = "⌫", modifier = Modifier.weight(1.1f), textSize = 6.sp)
            MiniKeyCap(text = "↵", modifier = Modifier.weight(1.2f), textSize = 6.sp)
        }
    }
}

/**
 * Quick (速成) layout preview with Cangjie radicals.
 */
@Composable
fun MiniQuickPreview() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniGridIcon()
            MiniMicIcon()
        }

        // Row 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val row1 = listOf(
                "q" to "手", "w" to "田", "e" to "水", "r" to "口", "t" to "廿",
                "y" to "卜", "u" to "山", "i" to "戈", "o" to "人", "p" to "心"
            )
            row1.forEach { (l, h) ->
                MiniKeyWithHint(letter = l, hint = h, modifier = Modifier.weight(1f))
            }
        }

        // Row 2
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val row2 = listOf(
                "a" to "日", "s" to "尸", "d" to "木", "f" to "火", "g" to "土",
                "h" to "竹", "j" to "十", "k" to "大", "l" to "中"
            )
            row2.forEach { (l, h) ->
                MiniKeyWithHint(letter = l, hint = h, modifier = Modifier.weight(1f))
            }
        }

        // Row 3
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniKeyCap(text = "⇧", modifier = Modifier.weight(1.2f), textSize = 6.sp)
            val row3 = listOf(
                "z" to "重", "x" to "難", "c" to "金", "v" to "女", "b" to "月",
                "n" to "弓", "m" to "一"
            )
            row3.forEach { (l, h) ->
                MiniKeyWithHint(letter = l, hint = h, modifier = Modifier.weight(1f))
            }
            MiniKeyCap(text = "⌫", modifier = Modifier.weight(1.2f), textSize = 6.sp)
        }

        // Row 4
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniKeyCap(text = "?123", modifier = Modifier.weight(1.4f), textSize = 4.5.sp)
            MiniKeyCap(text = ",", modifier = Modifier.weight(0.9f))
            MiniKeyCap(text = "Space", modifier = Modifier.weight(3.6f), textSize = 5.sp)
            MiniKeyCap(text = ".", modifier = Modifier.weight(0.9f))
            MiniKeyCap(text = "↵", modifier = Modifier.weight(1.2f), textSize = 6.sp)
        }
    }
}

@Composable
fun MiniKeyCap(
    text: String,
    modifier: Modifier = Modifier,
    textSize: androidx.compose.ui.unit.TextUnit = 6.sp
) {
    Box(
        modifier = modifier
            .height(15.dp)
            .clip(RoundedCornerShape(2.5.dp))
            .background(MiniKeySurface),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = PrimaryTextColor.copy(alpha = 0.9f),
            fontSize = textSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun MiniKeyWithHint(
    letter: String,
    hint: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(15.dp)
            .clip(RoundedCornerShape(2.5.dp))
            .background(MiniKeySurface)
            .padding(vertical = 0.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = hint,
                color = SecondaryTextColor,
                fontSize = 3.5.sp,
                lineHeight = 4.sp,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = letter,
                color = PrimaryTextColor.copy(alpha = 0.9f),
                fontSize = 5.sp,
                lineHeight = 6.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun MiniGridIcon() {
    Canvas(modifier = Modifier.size(9.dp)) {
        val s = size.width * 0.38f
        val gap = size.width * 0.18f
        drawRoundRect(PrimaryTextColor.copy(alpha = 0.7f), Offset(0f, 0f), Size(s, s), CornerRadius(1.dp.toPx()))
        drawRoundRect(PrimaryTextColor.copy(alpha = 0.7f), Offset(s + gap, 0f), Size(s, s), CornerRadius(1.dp.toPx()))
        drawRoundRect(PrimaryTextColor.copy(alpha = 0.7f), Offset(0f, s + gap), Size(s, s), CornerRadius(1.dp.toPx()))
        drawRoundRect(PrimaryTextColor.copy(alpha = 0.7f), Offset(s + gap, s + gap), Size(s, s), CornerRadius(1.dp.toPx()))
    }
}

@Composable
fun MiniMicIcon() {
    Canvas(modifier = Modifier.size(9.dp)) {
        val w = size.width
        val h = size.height
        val micW = w * 0.38f
        val micH = h * 0.52f
        drawRoundRect(
            color = PrimaryTextColor.copy(alpha = 0.7f),
            topLeft = Offset((w - micW) / 2f, h * 0.1f),
            size = Size(micW, micH),
            cornerRadius = CornerRadius(micW / 2f, micW / 2f)
        )
        drawLine(
            color = PrimaryTextColor.copy(alpha = 0.7f),
            start = Offset(w / 2f, h * 0.65f),
            end = Offset(w / 2f, h * 0.9f),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}
