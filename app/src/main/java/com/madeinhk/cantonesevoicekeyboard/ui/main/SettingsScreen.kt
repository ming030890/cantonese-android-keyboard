package com.madeinhk.cantonesevoicekeyboard.ui.main

import android.content.ComponentName
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.madeinhk.cantonesevoicekeyboard.CantoneseInputMethodService
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
import androidx.compose.ui.graphics.luminance
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
    var quickVersion by remember {
        mutableIntStateOf(KeyboardSettings.getQuickVersion(context))
    }
    var hapticFeedback by remember {
        mutableStateOf(KeyboardSettings.isHapticFeedbackEnabled(context))
    }
    var autoCapitalization by remember {
        mutableStateOf(KeyboardSettings.isAutoCapitalizationEnabled(context))
    }

    var quickLearning by remember { mutableStateOf(KeyboardSettings.isQuickLearningEnabled(context)) }
    SettingsScreenContent(
        quickLearning = quickLearning,
        onQuickLearningChange = { enabled ->
            KeyboardSettings.setQuickLearningEnabled(context, enabled)
            quickLearning = enabled
        },
        onClearQuickHistory = {
            com.madeinhk.cantonesevoicekeyboard.quick.QuickPersonalizationStore.get(context).clear()
            Toast.makeText(context, R.string.settings_quick_history_cleared, Toast.LENGTH_SHORT).show()
        },
        enabledLayouts = enabledLayouts,
        onConfigureSystemLayouts = if (Build.VERSION.SDK_INT < 34) ({
            context.getSystemService(InputMethodManager::class.java)?.showInputMethodAndSubtypeEnabler(
                ComponentName(context, CantoneseInputMethodService::class.java).flattenToShortString()
            )
        }) else null,
        onToggleLayout = { layoutId ->
            enabledLayouts = KeyboardSettings.handleLayoutToggle(context, enabledLayouts, layoutId)
        },
        quickVersion = quickVersion,
        onQuickVersionChange = { version ->
            KeyboardSettings.setQuickVersion(context, version)
            quickVersion = version
        },
        hapticFeedback = hapticFeedback,
        onHapticFeedbackChange = { enabled ->
            KeyboardSettings.setHapticFeedbackEnabled(context, enabled)
            hapticFeedback = enabled
        },
        autoCapitalization = autoCapitalization,
        onAutoCapitalizationChange = { enabled ->
            KeyboardSettings.setAutoCapitalizationEnabled(context, enabled)
            autoCapitalization = enabled
        },
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
fun SettingsScreenContent(
    enabledLayouts: Set<String>,
    onToggleLayout: (String) -> Unit,
    onBack: () -> Unit,
    quickVersion: Int = KeyboardSettings.DEFAULT_QUICK_VERSION,
    onQuickVersionChange: (Int) -> Unit = {},
    hapticFeedback: Boolean = true,
    onHapticFeedbackChange: (Boolean) -> Unit = {},
    autoCapitalization: Boolean = true,
    onAutoCapitalizationChange: (Boolean) -> Unit = {},
    quickLearning: Boolean = true,
    onQuickLearningChange: (Boolean) -> Unit = {},
    onClearQuickHistory: () -> Unit = {},
    onConfigureSystemLayouts: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val lightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    CompositionLocalProvider(LocalSettingsLightTheme provides lightTheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (lightTheme) MaterialTheme.colorScheme.background else Color(0xFF121714))
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

                // Large Title ("Settings" / "鍵盤設定")
                Text(
                    text = stringResource(R.string.settings_screen_title),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Normal,
                    color = PrimaryTextColor,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                // Subtitle indicating voice typing is included in all layouts
                Text(
                    text = stringResource(R.string.settings_all_layouts_voice),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MintAccentColor,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Keyboard Layouts Carousel Card
                KeyboardLayoutsSelectorCard(
                    enabledLayouts = enabledLayouts,
                    onToggleLayout = onToggleLayout,
                    quickVersion = quickVersion,
                    onQuickVersionChange = onQuickVersionChange,
                    showQuickVersionSelector = false,
                )

                if (onConfigureSystemLayouts != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onConfigureSystemLayouts) {
                        Text(stringResource(R.string.settings_system_layouts), color = MintAccentColor)
                    }
                    Text(
                        stringResource(R.string.settings_system_layouts_description),
                        color = SecondaryTextColor,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                // Layout-specific Settings (Quick 3 and 5 only shown when Quick is enabled)
                if (enabledLayouts.contains(KeyboardSettings.LAYOUT_QUICK)) {
                    Spacer(modifier = Modifier.height(28.dp))

                    Text(
                        text = stringResource(R.string.settings_layout_specific_header, stringResource(R.string.settings_layout_quick)),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MintAccentColor,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    QuickVersionCard(
                        selectedVersion = quickVersion,
                        onVersionChange = onQuickVersionChange
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    QuickLearningCard(quickLearning, onQuickLearningChange, onClearQuickHistory)
                }

                // Preferences Header
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = stringResource(R.string.settings_preferences_header),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MintAccentColor,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (enabledLayouts.any { it == KeyboardSettings.LAYOUT_ENGLISH || it == KeyboardSettings.LAYOUT_QUICK }) {
                    AutoCapitalizationCard(
                        checked = autoCapitalization,
                        onCheckedChange = onAutoCapitalizationChange
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                HapticFeedbackCard(
                    checked = hapticFeedback,
                    onCheckedChange = onHapticFeedbackChange
                )

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun SettingsBackButton(onBack: () -> Unit) {
    val arrowColor = PrimaryTextColor
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(MiniKeySurface)
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            val w = size.width
            val h = size.height
            val midY = h / 2f
            drawLine(
                color = arrowColor,
                start = Offset(w * 0.15f, midY),
                end = Offset(w * 0.85f, midY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = arrowColor,
                start = Offset(w * 0.15f, midY),
                end = Offset(w * 0.5f, midY - h * 0.35f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = arrowColor,
                start = Offset(w * 0.15f, midY),
                end = Offset(w * 0.5f, midY + h * 0.35f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}



@Composable
internal fun QuickLearningCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorderColor),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_quick_learning_title),
                    modifier = Modifier.weight(1f), fontSize = 16.sp, color = PrimaryTextColor)
                Switch(checked = checked, onCheckedChange = onCheckedChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MintOnAccentColor,
                        checkedTrackColor = MintAccentColor,
                        uncheckedThumbColor = SecondaryTextColor,
                        uncheckedTrackColor = MiniKeySurface,
                        uncheckedBorderColor = PillUnselectedBorder,
                    ))
            }
            Text(stringResource(R.string.settings_quick_learning_desc),
                fontSize = 13.sp, lineHeight = 18.sp, color = SecondaryTextColor)
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.settings_quick_clear_history), color = MintAccentColor)
            }
        }
    }
}

@Composable
fun QuickVersionCard(
    selectedVersion: Int,
    onVersionChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_quick_version_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = PrimaryTextColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_quick_version_desc),
                fontSize = 13.sp,
                color = SecondaryTextColor,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(
                    KeyboardSettings.QUICK_VERSION_3 to stringResource(R.string.settings_quick_version_3),
                    KeyboardSettings.QUICK_VERSION_5 to stringResource(R.string.settings_quick_version_5),
                ).forEach { (ver, label) ->
                    val isVerSelected = selectedVersion == ver
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isVerSelected) MintAccentColor else MiniKeySurface,
                        border = if (!isVerSelected) BorderStroke(1.dp, CardBorderColor) else null,
                        onClick = { onVersionChange(ver) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = label,
                                color = if (isVerSelected) MintOnAccentColor else PrimaryTextColor,
                                fontSize = 14.sp,
                                fontWeight = if (isVerSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}



/**
 * Haptic Feedback Card under Preferences.
 */
@Composable
fun HapticFeedbackCard(
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
                    text = stringResource(R.string.settings_haptic_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = PrimaryTextColor
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.settings_haptic_desc),
                    fontSize = 13.sp,
                    color = SecondaryTextColor,
                    lineHeight = 17.sp
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MintOnAccentColor,
                    checkedTrackColor = MintAccentColor,
                    uncheckedThumbColor = SecondaryTextColor,
                    uncheckedTrackColor = MiniKeySurface,
                    uncheckedBorderColor = PillUnselectedBorder
                )
            )
        }
    }
}

/**
 * Auto-capitalization Card under Preferences.
 */
@Composable
fun AutoCapitalizationCard(
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
                    text = stringResource(R.string.settings_autocap_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = PrimaryTextColor
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.settings_autocap_desc),
                    fontSize = 13.sp,
                    color = SecondaryTextColor,
                    lineHeight = 17.sp
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MintOnAccentColor,
                    checkedTrackColor = MintAccentColor,
                    uncheckedThumbColor = SecondaryTextColor,
                    uncheckedTrackColor = MiniKeySurface,
                    uncheckedBorderColor = PillUnselectedBorder
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
        onBack = {}
    )
}
