package com.madeinhk.cantonesevoicekeyboard.ui.main

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.madeinhk.cantonesevoicekeyboard.ModelDownloadStatus
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

@PreviewTest
@Preview(name = "Speech model waiting for Wi-Fi", widthDp = 360, heightDp = 180)
@Composable
fun ModelDownloadWaitingWifiScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        ModelStatusCard(ModelDownloadStatus.WaitingForWifi)
    }
}

@PreviewTest
@Preview(name = "Speech model needs Play confirmation", widthDp = 360, heightDp = 180)
@Composable
fun ModelDownloadConfirmationScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        ModelStatusCard(ModelDownloadStatus.RequiresConfirmation)
    }
}

@PreviewTest
@Preview(name = "Speech model waiting for Wi-Fi Chinese", locale = "b+zh+Hant+HK", widthDp = 360, heightDp = 180)
@Composable
fun ModelDownloadWaitingWifiChineseScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        ModelStatusCard(ModelDownloadStatus.WaitingForWifi)
    }
}


@PreviewTest
@Preview(name = "Optional voice off", widthDp = 360, heightDp = 200)
@Composable
fun VoiceDisabledSettingsScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        VoiceInputSettingsCard(enabled = false, onEnabledChange = {})
    }
}

@PreviewTest
@Preview(name = "Voice on needs permission", widthDp = 360, heightDp = 240)
@Composable
fun VoicePermissionSettingsScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        VoiceInputSettingsCard(enabled = true, onEnabledChange = {}, permissionGranted = false)
    }
}

@PreviewTest
@Preview(name = "Typing setup without voice", widthDp = 360, heightDp = 350)
@Composable
fun SetupWithoutVoiceScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        KeyboardSetupFlowCard(voiceEnabled = false, keyboardEnabled = true, keyboardSelected = false,
            permissionGranted = false, onEnableKeyboard = {}, onPickKeyboard = {}, onGrantPermission = {})
    }
}

@PreviewTest
@Preview(name = "Setup copy English", widthDp = 320, heightDp = 480)
@Preview(name = "Setup copy Traditional Chinese", locale = "b+zh+Hant+HK", widthDp = 320, heightDp = 480)
@Preview(name = "Setup copy Simplified Chinese", locale = "b+zh+Hans", widthDp = 320, heightDp = 480)
@Composable
fun SetupCopyReviewScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        KeyboardSetupFlowCard(voiceEnabled = true, keyboardEnabled = false, keyboardSelected = false,
            permissionGranted = false, onEnableKeyboard = {}, onPickKeyboard = {}, onGrantPermission = {})
    }
}

@PreviewTest
@Preview(name = "Speech model downloading turtle progress", widthDp = 360, heightDp = 180)
@Composable
fun ModelDownloadProgressScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        ModelStatusCard(ModelDownloadStatus.Downloading(bytesDownloaded = 64L, totalBytes = 100L))
    }
}

@PreviewTest
@Preview(name = "Keyboard setup all done mascot card", widthDp = 360, heightDp = 180)
@Composable
fun KeyboardReadyMascotScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        KeyboardReadyCard()
    }
}

@PreviewTest
@Preview(name = "App header turtle mascot hero", widthDp = 360, heightDp = 210)
@Composable
fun AppHeaderMascotScreenshot() {
    CantoneseVoiceKeyboardTheme(darkTheme = true, dynamicColor = false) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .background(brush = com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme.colors.backgroundGradient)
        ) {
            AppHeader()
        }
    }
}

@PreviewTest
@Preview(name = "Main setup screen mascot dark", widthDp = 412, heightDp = 980)
@Composable
fun MainScreenMascotDarkScreenshot() = MainScreenMascotPreview(dark = true)

@PreviewTest
@Preview(name = "Main setup screen mascot light", widthDp = 412, heightDp = 980)
@Composable
fun MainScreenMascotLightScreenshot() = MainScreenMascotPreview(dark = false)

@Composable
private fun MainScreenMascotPreview(dark: Boolean) {
    CantoneseVoiceKeyboardTheme(darkTheme = dark, dynamicColor = false) {
        MainScreenContent(
            voiceEnabled = true,
            permissionGranted = false,
            keyboardEnabled = false,
            keyboardSelected = false,
            modelStatus = ModelDownloadStatus.NotStarted,
            onGrantPermission = {},
            onEnableKeyboard = {},
            onPickKeyboard = {},
            onModelRetry = {},
            storeDebugAudio = false,
            onStoreDebugAudioChange = {},
        )
    }
}
