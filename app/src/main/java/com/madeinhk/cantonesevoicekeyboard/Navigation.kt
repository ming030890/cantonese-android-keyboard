package com.madeinhk.cantonesevoicekeyboard

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.madeinhk.cantonesevoicekeyboard.ui.main.MainScreen
import com.madeinhk.cantonesevoicekeyboard.ui.main.SettingsScreen
import com.madeinhk.cantonesevoicekeyboard.ui.main.DebugAudioScreen
import com.madeinhk.cantonesevoicekeyboard.ui.main.DebugHandwritingScreen
import com.madeinhk.cantonesevoicekeyboard.ui.main.PromptedHandwritingScreen

internal fun destinationAvailable(key: NavKey, internalTools: Boolean): Boolean = when (key) {
  DebugAudio, DebugHandwriting, PromptedHandwriting -> internalTools
  else -> true
}

internal fun resolveInitialDestination(
  onboardingCompleted: Boolean,
  keyboardEnabled: Boolean,
  keyboardSelected: Boolean,
  isSettingsRequested: Boolean,
): NavKey {
  return if (isSettingsRequested || onboardingCompleted || (keyboardEnabled && keyboardSelected)) {
    Settings
  } else {
    Main
  }
}

@Composable
fun MainNavigation(
  initialDestination: NavKey = Main,
  navigationGeneration: Int = 0,
) {
  val backStack = rememberNavBackStack(initialDestination)

  LaunchedEffect(navigationGeneration, initialDestination) {
    if (navigationGeneration > 0 && initialDestination == Settings && backStack.lastOrNull() != Settings) {
      backStack.clear()
      backStack.add(Settings)
    }
  }

  val openDestination: (NavKey) -> Unit = { key ->
    if (destinationAvailable(key, BuildConfig.INTERNAL_TOOLS)) {
      if (key == Settings && backStack.size == 1 && backStack.lastOrNull() == Main) {
        // Replace completed onboarding with Home/Settings so back exits instead of returning to setup.
        backStack[0] = Settings
      } else {
        backStack.add(key)
      }
    }
  }

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider = entryProvider<NavKey>(
      fallback = { key ->
        check(!destinationAvailable(key, BuildConfig.INTERNAL_TOOLS)) { "Unknown destination: $key" }
        // A saved internal screen may be restored after upgrading to a public build.
        NavEntry(key) {
          MainScreen(onItemClick = openDestination, modifier = Modifier.safeDrawingPadding())
        }
      }
    ) {
      entry<Main> {
        MainScreen(onItemClick = openDestination, modifier = Modifier.safeDrawingPadding())
      }
      entry<Settings> {
        SettingsScreen(
          onBack = if (backStack.size > 1) ({ backStack.removeLastOrNull() }) else null,
          onOpenDestination = openDestination,
          modifier = Modifier.safeDrawingPadding()
        )
      }
      if (BuildConfig.INTERNAL_TOOLS) {
        entry<DebugAudio> {
          DebugAudioScreen(
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<DebugHandwriting> {
          DebugHandwritingScreen(
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<PromptedHandwriting> {
          PromptedHandwritingScreen(
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
      }
    },
  )
}
