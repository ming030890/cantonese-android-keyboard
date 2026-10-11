package com.madeinhk.cantonesevoicekeyboard

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.madeinhk.cantonesevoicekeyboard.ui.main.MainScreen
import com.madeinhk.cantonesevoicekeyboard.ui.main.SettingsScreen
import com.madeinhk.cantonesevoicekeyboard.ui.main.DebugAudioScreen
import com.madeinhk.cantonesevoicekeyboard.ui.main.DebugHandwritingScreen
import com.madeinhk.cantonesevoicekeyboard.ui.main.PromptedHandwritingScreen

@Composable
fun MainNavigation(initialDestination: NavKey = Main) {
  val backStack = rememberNavBackStack(initialDestination)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(onItemClick = { navKey -> backStack.add(navKey) }, modifier = Modifier.safeDrawingPadding().padding(16.dp))
        }
        entry<Settings> {
          SettingsScreen(
            onBack = { backStack.removeLastOrNull() },
            onItemClick = { navKey -> backStack.add(navKey) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
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
      },
  )
}
