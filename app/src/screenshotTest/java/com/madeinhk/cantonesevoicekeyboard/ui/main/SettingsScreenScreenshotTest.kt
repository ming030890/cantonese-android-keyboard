package com.madeinhk.cantonesevoicekeyboard.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

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
    multilingualTyping: Boolean = false,
) {
    CantoneseVoiceKeyboardTheme(darkTheme = darkTheme, dynamicColor = false) {
        SettingsScreenContent(
            enabledLayouts = enabledLayouts,
            onToggleLayout = {},
            multilingualTyping = multilingualTyping,
            onMultilingualTypingChange = {},
            quickVersion = quickVersion,
            onQuickVersionChange = {},
            onBack = {}
        )
    }
}
