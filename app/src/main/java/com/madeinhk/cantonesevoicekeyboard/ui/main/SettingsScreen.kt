package com.madeinhk.cantonesevoicekeyboard.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import com.madeinhk.cantonesevoicekeyboard.BuildConfig
import com.madeinhk.cantonesevoicekeyboard.CantoneseInputMethodService
import com.madeinhk.cantonesevoicekeyboard.DebugAudio
import com.madeinhk.cantonesevoicekeyboard.DebugAudioSettings
import com.madeinhk.cantonesevoicekeyboard.DebugHandwriting
import com.madeinhk.cantonesevoicekeyboard.DebugHandwritingSettings
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import com.madeinhk.cantonesevoicekeyboard.PromptedHandwriting
import com.madeinhk.cantonesevoicekeyboard.R

@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onOpenDestination: (NavKey) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
    var quickLearning by remember {
        mutableStateOf(KeyboardSettings.isQuickLearningEnabled(context))
    }
    var storeDebugAudio by remember {
        mutableStateOf(DebugAudioSettings.isStoreDebugAudioEnabled(context))
    }
    var storeDebugHandwriting by remember {
        mutableStateOf(DebugHandwritingSettings.isStoreDebugHandwritingEnabled(context))
    }

    fun queryKeyboardEnabled(): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        return imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
    }

    fun queryKeyboardSelected(): Boolean {
        val currentIme = AndroidSettings.Secure.getString(
            context.contentResolver,
            AndroidSettings.Secure.DEFAULT_INPUT_METHOD
        )
        return currentIme?.contains(context.packageName) == true
    }

    var keyboardEnabled by remember { mutableStateOf(queryKeyboardEnabled()) }
    var keyboardSelected by remember { mutableStateOf(queryKeyboardSelected()) }

    fun refreshImeStatus() {
        keyboardEnabled = queryKeyboardEnabled()
        keyboardSelected = queryKeyboardSelected()
        enabledLayouts = KeyboardSettings.getEnabledLayouts(context)
        quickVersion = KeyboardSettings.getQuickVersion(context)
    }

    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (windowInfo.isWindowFocused) {
            refreshImeStatus()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshImeStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showPrivacyNotice by remember { mutableStateOf(false) }
    if (showPrivacyNotice) PrivacyNoticeDialog(onDismiss = { showPrivacyNotice = false })

    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) OpenSourceLicensesDialog(onDismiss = { showLicenses = false })

    SettingsScreenContent(
        keyboardEnabled = keyboardEnabled,
        keyboardSelected = keyboardSelected,
        onEnableKeyboard = {
            context.startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
        },
        onPickKeyboard = {
            context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        },
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
        voiceOptions = { VoiceInputSettings() },
        onBack = onBack,
        onPrivacyNotice = { showPrivacyNotice = true },
        onLicenses = { showLicenses = true },
        developerOptions = if (BuildConfig.INTERNAL_TOOLS) ({
            DeveloperOptionsCard(
                storeDebugAudio = storeDebugAudio,
                onStoreDebugAudioChange = { enabled ->
                    DebugAudioSettings.setStoreDebugAudioEnabled(context, enabled)
                    storeDebugAudio = enabled
                },
                onOpenDebugAudio = { onOpenDestination(DebugAudio) },
                storeDebugHandwriting = storeDebugHandwriting,
                onStoreDebugHandwritingChange = { enabled ->
                    DebugHandwritingSettings.setStoreDebugHandwritingEnabled(context, enabled)
                    storeDebugHandwriting = enabled
                },
                onOpenDebugHandwriting = { onOpenDestination(DebugHandwriting) },
                onOpenPromptedHandwriting = { onOpenDestination(PromptedHandwriting) },
            )
        }) else null,
        modifier = modifier
    )
}

@Composable
fun SettingsScreenContent(
    enabledLayouts: Set<String>,
    onToggleLayout: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    keyboardEnabled: Boolean = true,
    keyboardSelected: Boolean = true,
    onEnableKeyboard: () -> Unit = {},
    onPickKeyboard: () -> Unit = {},
    quickVersion: Int = KeyboardSettings.DEFAULT_QUICK_VERSION,
    onQuickVersionChange: (Int) -> Unit = {},
    hapticFeedback: Boolean = true,
    onHapticFeedbackChange: (Boolean) -> Unit = {},
    autoCapitalization: Boolean = true,
    onAutoCapitalizationChange: (Boolean) -> Unit = {},
    quickLearning: Boolean = true,
    onQuickLearningChange: (Boolean) -> Unit = {},
    onClearQuickHistory: () -> Unit = {},
    voiceOptions: @Composable () -> Unit = { VoiceInputSettingsCard(enabled = true, onEnabledChange = {}) },
    onConfigureSystemLayouts: (() -> Unit)? = null,
    onPrivacyNotice: () -> Unit = {},
    onLicenses: () -> Unit = {},
    developerOptions: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val lightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    CompositionLocalProvider(LocalSettingsLightTheme provides lightTheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenBackground)
        ) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (onBack != null) {
                    SettingsBackButton(onBack = onBack)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Compact Branded Home Header (Turtle badge + App Name + Live Status Pill)
                HomeTurtleHeader(
                    keyboardEnabled = keyboardEnabled,
                    keyboardSelected = keyboardSelected,
                )

                // Contextual Setup Banner (shown ONLY if disabled or switched to another keyboard)
                AnimatedVisibility(visible = !keyboardEnabled || !keyboardSelected) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        KeyboardInactiveBanner(
                            keyboardEnabled = keyboardEnabled,
                            onEnableKeyboard = onEnableKeyboard,
                            onPickKeyboard = onPickKeyboard,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Section 1: Keyboards
                SectionHeader(text = stringResource(R.string.settings_keyboards_header))
                Spacer(modifier = Modifier.height(8.dp))

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

                // Section 2: Voice Typing & Offline Model
                Spacer(modifier = Modifier.height(24.dp))
                SectionHeader(text = stringResource(R.string.voice_input_title))
                Spacer(modifier = Modifier.height(8.dp))
                voiceOptions()

                // Section 3: Layout-specific Settings (Quick 3 and 5 only shown when Quick is enabled)
                if (enabledLayouts.contains(KeyboardSettings.LAYOUT_QUICK)) {
                    Spacer(modifier = Modifier.height(24.dp))
                    SectionHeader(
                        text = stringResource(
                            R.string.settings_layout_specific_header,
                            stringResource(R.string.settings_layout_quick)
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    QuickVersionCard(
                        selectedVersion = quickVersion,
                        onVersionChange = onQuickVersionChange
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    QuickLearningCard(quickLearning, onQuickLearningChange, onClearQuickHistory)
                }

                // Section 4: General Preferences
                Spacer(modifier = Modifier.height(24.dp))
                SectionHeader(text = stringResource(R.string.settings_preferences_header))
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

                // Section 5: Privacy & Open Source
                Spacer(modifier = Modifier.height(24.dp))
                SectionHeader(text = stringResource(R.string.settings_privacy_about_header))
                Spacer(modifier = Modifier.height(8.dp))

                PrivacyNoticeCard(onClick = onPrivacyNotice)
                Spacer(modifier = Modifier.height(12.dp))
                OpenSourceNoticesCard(onClick = onLicenses)

                if (developerOptions != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    developerOptions()
                }
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MintAccentColor,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun HomeTurtleHeader(
    keyboardEnabled: Boolean,
    keyboardSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val isActive = keyboardEnabled && keyboardSelected
    val statusColor = when {
        isActive -> StatusActiveGreen
        keyboardEnabled -> SecondaryTextColor
        else -> StatusWarningAmber
    }
    val statusDot = when {
        isActive -> "●"
        keyboardEnabled -> "○"
        else -> "⚠"
    }
    val statusText = when {
        isActive -> stringResource(R.string.home_status_active)
        keyboardEnabled -> stringResource(R.string.home_status_unselected)
        else -> stringResource(R.string.home_status_disabled)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(CardBackground)
                .border(1.dp, CardBorderColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.mascot_turtle_hero),
                contentDescription = null,
                modifier = Modifier.size(42.dp),
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryTextColor,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusDot,
                    fontSize = 11.sp,
                    color = statusColor,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                )
            }
        }
    }
}

@Composable
private fun KeyboardInactiveBanner(
    keyboardEnabled: Boolean,
    onEnableKeyboard: () -> Unit,
    onPickKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appName = stringResource(R.string.app_name)
    val accent = if (keyboardEnabled) MintAccentColor else StatusWarningAmber
    val title = if (keyboardEnabled) {
        stringResource(R.string.home_banner_switch_title, appName)
    } else {
        stringResource(R.string.home_banner_enable_title)
    }
    val desc = if (keyboardEnabled) {
        stringResource(R.string.home_banner_switch_desc)
    } else {
        stringResource(R.string.home_banner_enable_desc, appName)
    }
    val actionLabel = if (keyboardEnabled) {
        stringResource(R.string.home_banner_switch_action)
    } else {
        stringResource(R.string.home_banner_enable_action)
    }
    val onAction = if (keyboardEnabled) onPickKeyboard else onEnableKeyboard

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryTextColor,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = desc,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = SecondaryTextColor,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MintAccentColor,
                    contentColor = MintOnAccentColor,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SettingsBackButton(onBack: () -> Unit) {
    val arrowColor = PrimaryTextColor
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
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
                Text(
                    stringResource(R.string.settings_quick_learning_title),
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = PrimaryTextColor
                )
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = settingsSwitchColors(),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_quick_learning_desc),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = SecondaryTextColor
            )
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
                    fontWeight = FontWeight.Medium,
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
                colors = settingsSwitchColors(),
            )
        }
    }
}

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
                    fontWeight = FontWeight.Medium,
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
                colors = settingsSwitchColors(),
            )
        }
    }
}

@Composable
internal fun PrivacyNoticeCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.privacy_notice_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = PrimaryTextColor,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.privacy_notice_action),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MintAccentColor,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.privacy_notice_card_summary),
                fontSize = 13.sp,
                color = SecondaryTextColor,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
internal fun OpenSourceNoticesCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.open_source_licenses),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = PrimaryTextColor,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.privacy_notice_action),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MintAccentColor,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.open_source_card_summary),
                fontSize = 13.sp,
                color = SecondaryTextColor,
                lineHeight = 18.sp,
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
    )
}
