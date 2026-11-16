package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

class MainActivity : ComponentActivity() {
  companion object {
    const val EXTRA_DESTINATION = "destination"
    const val DESTINATION_SETTINGS = "settings"
  }

  private var targetDestination by mutableStateOf<NavKey>(Main)
  private var navigationGeneration by mutableIntStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    KeyboardSettings.init(this)
    targetDestination = computeAndPersistDestination(intent)

    enableEdgeToEdge()
    setContent {
      CantoneseVoiceKeyboardTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(
            initialDestination = targetDestination,
            navigationGeneration = navigationGeneration,
          )
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val nextDestination = computeAndPersistDestination(intent)
    if (nextDestination == Settings) {
      targetDestination = Settings
      navigationGeneration++
    }
  }

  private fun computeAndPersistDestination(currentIntent: Intent?): NavKey {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val keyboardEnabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
    val currentIme = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
    val keyboardSelected = currentIme?.contains(packageName) == true
    val isSettingsRequested = currentIntent?.getStringExtra(EXTRA_DESTINATION) == DESTINATION_SETTINGS
    val onboardingCompleted = KeyboardSettings.isOnboardingCompleted(this)

    if (isSettingsRequested || (keyboardEnabled && keyboardSelected)) {
      KeyboardSettings.setOnboardingCompleted(this, true)
    }

    return resolveInitialDestination(
      onboardingCompleted = onboardingCompleted,
      keyboardEnabled = keyboardEnabled,
      keyboardSelected = keyboardSelected,
      isSettingsRequested = isSettingsRequested,
    )
  }
}
