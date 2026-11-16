package com.madeinhk.cantonesevoicekeyboard.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal val LocalSettingsLightTheme = staticCompositionLocalOf<Boolean?> { null }

internal val isSettingsLightTheme: Boolean
    @Composable get() = LocalSettingsLightTheme.current
        ?: (MaterialTheme.colorScheme.background.luminance() > 0.5f)

internal val ScreenBackground: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFFF7F9F5) else Color(0xFF121714)

internal val CardBackground: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFFF0F5F1) else Color(0xFF1C231F)

internal val CardBorderColor: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFFD3DFD6) else Color(0xFF26302B)

internal val MiniKeyboardBackground: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFFE2EBE5) else Color(0xFF141A17)

internal val MiniKeySurface: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFFFFFFFF) else Color(0xFF232B27)

internal val MintAccentColor: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFF286A46) else Color(0xFF9FD4B3)

internal val MintOnAccentColor: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFFFFFFFF) else Color(0xFF0D271A)

internal val PillUnselectedBorder: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFF73867A) else Color(0xFF4C5851)

internal val PillUnselectedIcon: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFF52675A) else Color(0xFF8E9C94)

internal val PrimaryTextColor: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFF172C20) else Color(0xFFDFE5E1)

internal val SecondaryTextColor: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFF52675A) else Color(0xFF8E9C94)

internal val StatusActiveGreen: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFF1E7E48) else Color(0xFF6DD58C)

internal val StatusWarningAmber: Color
    @Composable get() = if (isSettingsLightTheme) Color(0xFFB26A00) else Color(0xFFFFB74D)

@Composable
internal fun settingsSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MintOnAccentColor,
    checkedTrackColor = MintAccentColor,
    uncheckedThumbColor = SecondaryTextColor,
    uncheckedTrackColor = MiniKeySurface,
    uncheckedBorderColor = PillUnselectedBorder,
)
