package com.madeinhk.cantonesevoicekeyboard.ui.main

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import com.madeinhk.cantonesevoicekeyboard.ModelAssetPack
import com.madeinhk.cantonesevoicekeyboard.ModelDownloadStatus
import com.madeinhk.cantonesevoicekeyboard.R

/** Unified Voice Typing + Offline Speech Model management card on Home/Settings. */
@Composable
internal fun VoiceInputSettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val enabled by KeyboardSettings.voiceInputEnabledFlow.collectAsState()
    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    var permission by remember { mutableStateOf(hasPermission()) }
    var permissionRequestedOnce by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<ModelDownloadStatus>(ModelDownloadStatus.NotStarted) }
    var retry by remember { mutableIntStateOf(0) }

    val grantPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        if (!granted && permissionRequestedOnce) {
            val activity = context as? Activity
            if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                )
            }
        }
        permissionRequestedOnce = true
    }
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

    VoiceInputSettingsCard(
        enabled = enabled,
        onEnabledChange = { KeyboardSettings.setVoiceInputEnabled(context, it) },
        permissionGranted = permission,
        onGrantPermission = { grantPermission.launch(Manifest.permission.RECORD_AUDIO) },
        modelStatus = if (enabled && permission) status else null,
        onRetry = {
            ModelAssetPack.requestDownload(context, retry = true)
            retry++
        },
        onConfirmDownload = {
            if (enabled && status == ModelDownloadStatus.RequiresConfirmation && ModelAssetPack.hasUnmeteredNetwork(context)) {
                com.google.android.play.core.assetpacks.AssetPackManagerFactory.getInstance(context)
                    .showConfirmationDialog(confirmation)
            }
        },
    )
}

@Composable
internal fun VoiceInputSettingsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    permissionGranted: Boolean = true,
    onGrantPermission: () -> Unit = {},
    modelStatus: ModelDownloadStatus? = null,
    onRetry: () -> Unit = {},
    onConfirmDownload: () -> Unit = {},
) {
    val title = stringResource(R.string.voice_input_title)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.dp, CardBorderColor),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = PrimaryTextColor,
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = settingsSwitchColors(),
                    modifier = Modifier.semantics { contentDescription = title },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.voice_input_description),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = SecondaryTextColor,
            )

            if (enabled && !permissionGranted) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = CardBorderColor)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.setup_step_voice_desc),
                        color = StatusWarningAmber,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = onGrantPermission,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MintAccentColor,
                            contentColor = MintOnAccentColor,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setup_step_voice_action),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else if (enabled && permissionGranted && modelStatus != null) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = CardBorderColor)
                Spacer(modifier = Modifier.height(14.dp))
                ModelStatusInlineContent(
                    status = modelStatus,
                    onRetry = onRetry,
                    onConfirmDownload = onConfirmDownload,
                )
            }
        }
    }
}
