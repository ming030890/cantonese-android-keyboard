package com.madeinhk.cantonesevoicekeyboard

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

@Composable
fun MainNavigation(initialDestination: NavKey = Main) {
  val backStack = rememberNavBackStack(initialDestination)
  val openDestination: (NavKey) -> Unit = { key ->
    if (destinationAvailable(key, BuildConfig.INTERNAL_TOOLS)) backStack.add(key)
  }

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider = entryProvider<NavKey>(
      fallback = { key ->
        check(!destinationAvailable(key, BuildConfig.INTERNAL_TOOLS)) { "Unknown destination: $key" }
        // A saved internal screen may be restored after upgrading to a public build.
        NavEntry(key) {
          MainScreen(onItemClick = openDestination, modifier = Modifier.safeDrawingPadding().padding(16.dp))
        }
      }
    ) {
      entry<Main> {
        MainScreen(onItemClick = openDestination, modifier = Modifier.safeDrawingPadding().padding(16.dp))
      }
      entry<Settings> {
        SettingsScreen(
          onBack = { backStack.removeLastOrNull() },
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
