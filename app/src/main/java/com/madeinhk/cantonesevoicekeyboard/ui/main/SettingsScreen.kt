package com.madeinhk.cantonesevoicekeyboard.ui.main

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import com.madeinhk.cantonesevoicekeyboard.R

// Material 3 Dynamic Dark Forest Palette matching reference screenshot
private val ScreenBackground = Color(0xFF121714)
private val CardBackground = Color(0xFF1C231F)
private val CardBorderColor = Color(0xFF26302B)
private val MiniKeyboardBackground = Color(0xFF141A17)
private val MiniKeySurface = Color(0xFF232B27)
private val MintAccentColor = Color(0xFF9FD4B3)
private val MintOnAccentColor = Color(0xFF0D271A)
private val PillUnselectedBorder = Color(0xFF4C5851)
private val PillUnselectedIcon = Color(0xFF8E9C94)
private val PrimaryTextColor = Color(0xFFDFE5E1)
private val SecondaryTextColor = Color(0xFF8E9C94)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onItemClick: (NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var enabledLayouts by remember {
        mutableStateOf(KeyboardSettings.getEnabledLayouts(context))
    }
    var multilingualTyping by remember {
        mutableStateOf(KeyboardSettings.isMultilingualTypingEnabled(context))
    }

    SettingsScreenContent(
        enabledLayouts = enabledLayouts,
        onToggleLayout = { layoutId ->
            if (enabledLayouts.contains(layoutId) && enabledLayouts.size <= 1) {
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_at_least_one_layout),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val updated = KeyboardSettings.toggleLayout(context, layoutId)
                enabledLayouts = updated
            }
        },
        multilingualTyping = multilingualTyping,
        onMultilingualTypingChange = { enabled ->
            KeyboardSettings.setMultilingualTypingEnabled(context, enabled)
            multilingualTyping = enabled
        },
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
fun SettingsScreenContent(
    enabledLayouts: Set<String>,
    onToggleLayout: (String) -> Unit,
    multilingualTyping: Boolean,
    onMultilingualTypingChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Circular Back Arrow Button
            SettingsBackButton(onBack = onBack)

            Spacer(modifier = Modifier.height(16.dp))

            // Large Title ("English (UK)")
            Text(
                text = stringResource(R.string.settings_screen_title),
                fontSize = 34.sp,
                fontWeight = FontWeight.Normal,
                color = PrimaryTextColor,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Keyboard Layouts Carousel Card
            KeyboardLayoutsSelectorCard(
                enabledLayouts = enabledLayouts,
                onToggleLayout = onToggleLayout
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Language Settings Header
            Text(
                text = stringResource(R.string.settings_language_settings_header),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MintAccentColor,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Multilingual Typing Card
            MultilingualTypingCard(
                checked = multilingualTyping,
                onCheckedChange = onMultilingualTypingChange
            )

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun SettingsBackButton(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xFF1F2723))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            val w = size.width
            val h = size.height
            val midY = h / 2f
            drawLine(
                color = PrimaryTextColor,
                start = Offset(w * 0.15f, midY),
                end = Offset(w * 0.85f, midY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = PrimaryTextColor,
                start = Offset(w * 0.15f, midY),
                end = Offset(w * 0.5f, midY - h * 0.35f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = PrimaryTextColor,
                start = Offset(w * 0.15f, midY),
                end = Offset(w * 0.5f, midY + h * 0.35f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Hero Layout Selector Card with horizontal scrolling carousel.
 * The item sizing allows the 3rd layout to peek in from the right edge.
 */
@Composable
fun KeyboardLayoutsSelectorCard(
    enabledLayouts: Set<String>,
    onToggleLayout: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 1. QWERTY (English)
            val isQwertySelected = enabledLayouts.contains(KeyboardSettings.LAYOUT_ENGLISH)
            KeyboardLayoutItem(
                title = stringResource(R.string.settings_layout_qwerty),
                isSelected = isQwertySelected,
                onToggle = { onToggleLayout(KeyboardSettings.LAYOUT_ENGLISH) },
                preview = { MiniQwertyPreview() }
            )

            // 2. Handwriting
            val isHandwritingSelected = enabledLayouts.contains(KeyboardSettings.LAYOUT_HANDWRITING)
            KeyboardLayoutItem(
                title = stringResource(R.string.settings_layout_handwriting),
                isSelected = isHandwritingSelected,
                onToggle = { onToggleLayout(KeyboardSettings.LAYOUT_HANDWRITING) },
                preview = { MiniHandwritingPreview() }
            )

            // 3. Peeking Layout Item (e.g. QWERTZ / AZERTY / PC)
            KeyboardLayoutItem(
                title = "QWERTZ",
                isSelected = false,
                enabled = false,
                onToggle = {},
                preview = { MiniPeekingPreview() }
            )
        }
    }
}

@Composable
private fun KeyboardLayoutItem(
    title: String,
    isSelected: Boolean,
    onToggle: () -> Unit,
    preview: @Composable () -> Unit,
    enabled: Boolean = true,
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
    }
}

/**
 * Miniature QWERTY Preview matching reference screenshot.
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
 * Miniature Handwriting Preview matching reference screenshot.
 * Features top toolbar, clean open drawing canvas (no divider lines), and bottom function row.
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
 * Peeking layout preview on the right edge.
 */
@Composable
fun MiniPeekingPreview() {
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
            listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p").forEach { l ->
                MiniKeyCap(text = l, modifier = Modifier.weight(1f))
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
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").forEach { l ->
                MiniKeyCap(text = l, modifier = Modifier.weight(1f))
            }
        }

        // Row 3
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniKeyCap(text = "⇧", modifier = Modifier.weight(1.2f), textSize = 6.sp)
            listOf("y", "x", "c", "v", "b", "n", "m").forEach { l ->
                MiniKeyCap(text = l, modifier = Modifier.weight(1f))
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
private fun MiniKeyCap(
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
private fun MiniKeyWithHint(
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
private fun MiniGridIcon() {
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
private fun MiniMicIcon() {
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

/**
 * Multilingual Typing Card matching reference screenshot.
 * Features an M3 toggle switch with an 'X' icon inside the thumb when off.
 */
@Composable
fun MultilingualTypingCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_multilingual_typing_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = PrimaryTextColor
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.settings_multilingual_typing_desc),
                    fontSize = 13.sp,
                    color = SecondaryTextColor,
                    lineHeight = 17.sp
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                thumbContent = {
                    if (!checked) {
                        Canvas(modifier = Modifier.size(10.dp)) {
                            val stroke = 1.8.dp.toPx()
                            drawLine(
                                color = Color(0xFF6B7871),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = Color(0xFF6B7871),
                                start = Offset(size.width, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MintOnAccentColor,
                    checkedTrackColor = MintAccentColor,
                    uncheckedThumbColor = Color(0xFF38433D),
                    uncheckedTrackColor = Color(0xFF1E2622),
                    uncheckedBorderColor = Color(0xFF4C5851)
                )
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121714)
@Composable
fun SettingsScreenPreview() {
    SettingsScreenContent(
        enabledLayouts = KeyboardSettings.DEFAULT_LAYOUTS,
        onToggleLayout = {},
        multilingualTyping = false,
        onMultilingualTypingChange = {},
        onBack = {}
    )
}
