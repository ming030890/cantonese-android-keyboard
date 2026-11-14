package com.madeinhk.cantonesevoicekeyboard.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import com.madeinhk.cantonesevoicekeyboard.ModelAssetPack
import com.madeinhk.cantonesevoicekeyboard.ModelDownloadStatus
import com.madeinhk.cantonesevoicekeyboard.R

/** Settings and onboarding share the same persisted opt-in and download policy. */
@Composable
internal fun VoiceInputSettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val enabled by KeyboardSettings.voiceInputEnabledFlow.collectAsState()
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(hasPermission()) }
    var status by remember { mutableStateOf<ModelDownloadStatus>(ModelDownloadStatus.NotStarted) }
    var retry by remember { mutableIntStateOf(0) }
    val grantPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    val confirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { retry++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = hasPermission()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enabled, permission, retry) {
        ModelAssetPack.downloadStatusFlow(context, requestDownload = enabled && permission).collect { status = it }
    }
    Column {
        VoiceInputSettingsCard(enabled, { KeyboardSettings.setVoiceInputEnabled(context, it) },
            permissionGranted = permission,
            onGrantPermission = { grantPermission.launch(Manifest.permission.RECORD_AUDIO) })
        if (enabled && permission) ModelStatusCard(status, onRetry = {
            ModelAssetPack.requestDownload(context, retry = true)
            retry++
        }, onConfirmDownload = {
            if (enabled && status == ModelDownloadStatus.RequiresConfirmation && ModelAssetPack.hasUnmeteredNetwork(context)) {
                com.google.android.play.core.assetpacks.AssetPackManagerFactory.getInstance(context)
                    .showConfirmationDialog(confirmation)
            }
        })
    }
}

@Composable
internal fun VoiceInputSettingsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    permissionGranted: Boolean = true,
    onGrantPermission: () -> Unit = {},
) {
    val title = stringResource(R.string.voice_input_title)
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.voice_input_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = onEnabledChange,
                    modifier = Modifier.semantics { contentDescription = title })
            }
            Text(stringResource(R.string.voice_input_description), style = MaterialTheme.typography.bodyMedium)
            if (enabled && !permissionGranted) {
                TextButton(onClick = onGrantPermission) { Text(stringResource(R.string.setup_step_voice_action)) }
            }
        }
    }
}
