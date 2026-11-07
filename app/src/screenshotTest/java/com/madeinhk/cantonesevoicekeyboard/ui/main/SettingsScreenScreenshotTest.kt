package com.madeinhk.cantonesevoicekeyboard.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

@PreviewTest
@Preview(name = "Haptic feedback setting", widthDp = 360, heightDp = 200)
@Composable
fun HapticFeedbackScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        Box(Modifier.fillMaxSize().background(Color(0xFF121714)).padding(16.dp)) {
            HapticFeedbackCard(checked = true, onCheckedChange = {})
        }
    }
}

@PreviewTest
@Preview(
    name = "Settings screen both layouts selected",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
)
@Composable
fun SettingsScreenBothLayoutsSelectedScreenshot() {
    SettingsScreenScreenshotContent(
        enabledLayouts = setOf(KeyboardSettings.LAYOUT_ENGLISH, KeyboardSettings.LAYOUT_HANDWRITING),
        darkTheme = true
    )
}

@PreviewTest
@Preview(
    name = "Settings screen only english selected",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
)
@Composable
fun SettingsScreenOnlyEnglishSelectedScreenshot() {
    SettingsScreenScreenshotContent(
        enabledLayouts = setOf(KeyboardSettings.LAYOUT_ENGLISH),
        darkTheme = true
    )
}

@PreviewTest
@Preview(
    name = "Settings screen only handwriting selected",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
)
@Composable
fun SettingsScreenOnlyHandwritingSelectedScreenshot() {
    SettingsScreenScreenshotContent(
        enabledLayouts = setOf(KeyboardSettings.LAYOUT_HANDWRITING),
        darkTheme = true
    )
}

@PreviewTest
@Preview(
    name = "Settings screen all layouts selected",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
)
@Composable
fun SettingsScreenAllThreeLayoutsSelectedScreenshot() {
    SettingsScreenScreenshotContent(
        enabledLayouts = setOf(
            KeyboardSettings.LAYOUT_QUICK,
            KeyboardSettings.LAYOUT_ENGLISH,
            KeyboardSettings.LAYOUT_HANDWRITING
        ),
        darkTheme = true
    )
}

@PreviewTest
@Preview(
    name = "Settings screen only quick selected",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
)
@Composable
fun SettingsScreenOnlyQuickSelectedScreenshot() {
    SettingsScreenScreenshotContent(
        enabledLayouts = setOf(KeyboardSettings.LAYOUT_QUICK),
        darkTheme = true
    )
}

@PreviewTest
@Preview(
    name = "Settings screen quick version 5 selected",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
)
@Composable
fun SettingsScreenQuickVersion5SelectedScreenshot() {
    SettingsScreenScreenshotContent(
        enabledLayouts = setOf(
            KeyboardSettings.LAYOUT_QUICK,
            KeyboardSettings.LAYOUT_ENGLISH,
            KeyboardSettings.LAYOUT_HANDWRITING
        ),
        quickVersion = KeyboardSettings.QUICK_VERSION_5,
        darkTheme = true
    )
}

@PreviewTest
@Preview(
    name = "Settings screen light theme",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
)
@Composable
fun SettingsScreenLightThemeScreenshot() {
    SettingsScreenScreenshotContent(
        enabledLayouts = setOf(KeyboardSettings.LAYOUT_ENGLISH, KeyboardSettings.LAYOUT_HANDWRITING),
        darkTheme = false
    )
}

@Composable
private fun SettingsScreenScreenshotContent(
    enabledLayouts: Set<String>,
    quickVersion: Int = KeyboardSettings.DEFAULT_QUICK_VERSION,
    darkTheme: Boolean = true,
) {
    CantoneseVoiceKeyboardTheme(darkTheme = darkTheme, dynamicColor = false) {
        SettingsScreenContent(
            enabledLayouts = enabledLayouts,
            onToggleLayout = {},
            quickVersion = quickVersion,
            onQuickVersionChange = {},
            onBack = {}
        )
    }
}

@PreviewTest
@Preview(name = "Android 12 and 13 system layout setup", widthDp = 320, heightDp = 1000, fontScale = 1.3f)
@Composable
fun SettingsLegacySystemLayoutsScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        SettingsScreenContent(
            enabledLayouts = KeyboardSettings.DEFAULT_LAYOUTS,
            onToggleLayout = {},
            onBack = {},
            onConfigureSystemLayouts = {},
        )
    }
}

@PreviewTest
@Preview(name = "Settings all layouts light theme", widthDp = 412, heightDp = 1050)
@Composable
fun SettingsAllLayoutsLightScreenshot() {
    SettingsScreenScreenshotContent(
        enabledLayouts = KeyboardSettings.DEFAULT_LAYOUTS + KeyboardSettings.LAYOUT_HANDWRITING,
        darkTheme = false,
    )
}

@PreviewTest
@Preview(name = "Settings Quick 5 light large text", widthDp = 320, heightDp = 1100, fontScale = 1.3f)
@Composable
fun SettingsQuickLightLargeTextScreenshot() {
    SettingsScreenScreenshotContent(
        enabledLayouts = setOf(KeyboardSettings.LAYOUT_QUICK),
        quickVersion = KeyboardSettings.QUICK_VERSION_5,
        darkTheme = false,
    )
}

@PreviewTest
@Preview(name = "Quick learning enabled", widthDp = 360, heightDp = 250)
@Composable
fun QuickLearningEnabledScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        Box(Modifier.fillMaxSize().background(Color(0xFF121714)).padding(16.dp)) {
            QuickLearningCard(true, {}, {})
        }
    }
}

@PreviewTest
@Preview(name = "Quick learning disabled", widthDp = 320, heightDp = 290, fontScale = 1.3f)
@Composable
fun QuickLearningDisabledScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = false, dynamicColor = false) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSettingsLightTheme provides true) {
            Box(Modifier.fillMaxSize().background(Color(0xFFF7F9F5)).padding(16.dp)) {
                QuickLearningCard(false, {}, {})
            }
        }
    }
}
