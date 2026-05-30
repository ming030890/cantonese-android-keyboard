package com.example.cantonesevoicekeyboard.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Brand design values that aren't part of Material's standard [androidx.compose.material3.ColorScheme]
 * (e.g. the screen's gradient backdrop). Decided once in [CantoneseVoiceKeyboardTheme]
 * alongside the color scheme and read via [BrandTheme.colors], so screens never
 * have to infer light/dark themselves.
 */
@Immutable
data class BrandColors(
    val backgroundGradient: Brush
)

private val DarkBrandColors = BrandColors(
    backgroundGradient = Brush.verticalGradient(listOf(DarkMidnight, CarbonBlack))
)

private val LightBrandColors = BrandColors(
    backgroundGradient = Brush.verticalGradient(listOf(LightGradientTop, LightGradientBottom))
)

private val LocalBrandColors = staticCompositionLocalOf { DarkBrandColors }

/** Accessor for brand design values provided by [CantoneseVoiceKeyboardTheme]. */
object BrandTheme {
    val colors: BrandColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBrandColors.current
}

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    background = DarkMidnight,
    surface = CardGray,
    outline = BorderGray,
    error = ClearRed,
    onPrimary = CarbonBlack,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = TextGray
)

private val LightColorScheme =
  lightColorScheme(
    primary = LightPrimaryTeal,
    background = LightBackground,
    surface = LightSurface,
    outline = LightBorder,
    error = ClearRed,
    onPrimary = Color.White,
    onBackground = LightOnSurface,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightTextGray
  )

@Composable
fun CantoneseVoiceKeyboardTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Default to the hand-tuned brand palettes rather than wallpaper-based dynamic
  // color: the screens use a fixed gradient backdrop, and dynamic dark schemes
  // render card surfaces dark-on-dark against it. Keeping this off also makes
  // screenshot tests deterministic.
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  val brandColors = if (darkTheme) DarkBrandColors else LightBrandColors

  CompositionLocalProvider(LocalBrandColors provides brandColors) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}
