package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

class MainActivity : ComponentActivity() {
  companion object {
    const val EXTRA_DESTINATION = "destination"
    const val DESTINATION_SETTINGS = "settings"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    KeyboardSettings.init(this)

    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val keyboardEnabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
    val currentIme = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
    val keyboardSelected = currentIme?.contains(packageName) == true
    val isOnboardingComplete = keyboardEnabled && keyboardSelected

    val isSettingsRequested = intent?.getStringExtra(EXTRA_DESTINATION) == DESTINATION_SETTINGS

    val initialDestination = if (isSettingsRequested || isOnboardingComplete) {
      Settings
    } else {
      Main
    }

    enableEdgeToEdge()
    setContent {
      CantoneseVoiceKeyboardTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(initialDestination = initialDestination)
        }
      }
    }
  }
}
