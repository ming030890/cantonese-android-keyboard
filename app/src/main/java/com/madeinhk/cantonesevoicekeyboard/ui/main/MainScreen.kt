package com.madeinhk.cantonesevoicekeyboard.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import com.madeinhk.cantonesevoicekeyboard.DebugAudio
import com.madeinhk.cantonesevoicekeyboard.DebugAudioSettings
import com.madeinhk.cantonesevoicekeyboard.DebugHandwriting
import com.madeinhk.cantonesevoicekeyboard.DebugHandwritingSettings
import com.madeinhk.cantonesevoicekeyboard.KeyboardSettings
import com.madeinhk.cantonesevoicekeyboard.ModelAssetPack
import com.madeinhk.cantonesevoicekeyboard.ModelDownloadStatus
import com.madeinhk.cantonesevoicekeyboard.PromptedHandwriting
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.Settings as KeyboardSettingsNavKey
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  val voiceEnabled by KeyboardSettings.voiceInputEnabledFlow.collectAsState()
  var permissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    )
  }
  var keyboardEnabled by remember { mutableStateOf(false) }
  var keyboardSelected by remember { mutableStateOf(false) }
  var completedNavigationSent by remember { mutableStateOf(false) }

  fun completeOnboarding() {
    if (completedNavigationSent) return
    completedNavigationSent = true
    KeyboardSettings.setOnboardingCompleted(context, true)
    onItemClick(KeyboardSettingsNavKey)
  }

  fun refreshStatus() {
    permissionGranted =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    keyboardEnabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }

    val currentIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    keyboardSelected = currentIme?.contains(context.packageName) == true
  }

  val windowInfo = LocalWindowInfo.current
  LaunchedEffect(windowInfo.isWindowFocused) {
    if (windowInfo.isWindowFocused) {
      refreshStatus()
    }
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        refreshStatus()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val allCompleted = keyboardEnabled && keyboardSelected && (!voiceEnabled || permissionGranted)
  LaunchedEffect(allCompleted) {
    if (allCompleted) {
      if (voiceEnabled && permissionGranted) {
        ModelAssetPack.requestDownload(context)
      }
      completeOnboarding()
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
    onResult = { isGranted ->
      permissionGranted = isGranted
      if (isGranted) {
        ModelAssetPack.requestDownload(context)
      } else {
        // Never trap the user on Step 3 if they deny the OS permission dialog.
        KeyboardSettings.setVoiceInputEnabled(context, false)
      }
      completeOnboarding()
    }
  )

  var showPrivacyNotice by remember { mutableStateOf(false) }
  if (showPrivacyNotice) PrivacyNoticeDialog(onDismiss = { showPrivacyNotice = false })

  MainScreenContent(
    voiceEnabled = voiceEnabled,
    onSkipVoice = {
      KeyboardSettings.setVoiceInputEnabled(context, false)
      completeOnboarding()
    },
    permissionGranted = permissionGranted,
    keyboardEnabled = keyboardEnabled,
    keyboardSelected = keyboardSelected,
    modelStatus = ModelDownloadStatus.NotStarted,
    onGrantPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
    onEnableKeyboard = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
    onPickKeyboard = {
      val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.showInputMethodPicker()
    },
    onModelRetry = {},
    storeDebugAudio = DebugAudioSettings.isStoreDebugAudioEnabled(context),
    onStoreDebugAudioChange = {},
    onOpenDebugAudio = { onItemClick(DebugAudio) },
    storeDebugHandwriting = DebugHandwritingSettings.isStoreDebugHandwritingEnabled(context),
    onStoreDebugHandwritingChange = {},
    onOpenDebugHandwriting = { onItemClick(DebugHandwriting) },
    onOpenPromptedHandwriting = { onItemClick(PromptedHandwriting) },
    onOpenSettings = { completeOnboarding() },
    onPrivacyNotice = { showPrivacyNotice = true },
    modifier = modifier
  )
}

/**
 * Full-screen, focused 1-step-at-a-time onboarding wizard.
 * Holds no Android runtime dependencies so it renders cleanly in @Preview and screenshot tests.
 */
@Composable
fun MainScreenContent(
  voiceEnabled: Boolean = true,
  onSkipVoice: () -> Unit = {},
  permissionGranted: Boolean,
  keyboardEnabled: Boolean,
  keyboardSelected: Boolean,
  modelStatus: ModelDownloadStatus = ModelDownloadStatus.NotStarted,
  onGrantPermission: () -> Unit,
  onEnableKeyboard: () -> Unit,
  onPickKeyboard: () -> Unit,
  onModelRetry: () -> Unit = {},
  onConfirmModelDownload: () -> Unit = {},
  storeDebugAudio: Boolean = false,
  onStoreDebugAudioChange: (Boolean) -> Unit = {},
  onOpenDebugAudio: () -> Unit = {},
  storeDebugHandwriting: Boolean = false,
  onStoreDebugHandwritingChange: (Boolean) -> Unit = {},
  onOpenDebugHandwriting: () -> Unit = {},
  onOpenPromptedHandwriting: () -> Unit = {},
  onOpenSettings: () -> Unit = {},
  onPrivacyNotice: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val lightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
  val stepCount = if (voiceEnabled) 3 else 2
  val activeStep = when {
    !keyboardEnabled -> 1
    !keyboardSelected -> 2
    else -> stepCount
  }

  CompositionLocalProvider(LocalSettingsLightTheme provides lightTheme) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(ScreenBackground)
    ) {
      Column(
        modifier = modifier
          .fillMaxSize()
          .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Top Brand + Progress Header
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = stringResource(R.string.app_header_title),
            color = SecondaryTextColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
          )
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MintAccentColor.copy(alpha = 0.14f),
          ) {
            Text(
              text = stringResource(R.string.setup_step_progress, activeStep, stepCount),
              color = MintAccentColor,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Segmented Capsule Progress Bar
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for (i in 1..stepCount) {
            val filled = i <= activeStep
            Box(
              modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (filled) MintAccentColor else CardBorderColor)
            )
          }
        }

        // Center Hero Stage (1 focused action per step)
        AnimatedContent(
          targetState = activeStep,
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
          transitionSpec = {
            if (targetState > initialState) {
              (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(260))) togetherWith
                (slideOutHorizontally(tween(220)) { -it / 3 } + fadeOut(tween(180)))
            } else {
              (slideInHorizontally(tween(260)) { -it / 3 } + fadeIn(tween(260))) togetherWith
                (slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(180)))
            }
          },
          label = "onboardingStepTransition",
        ) { step ->
          val appName = stringResource(R.string.app_name)
          val stepTitle = when (step) {
            1 -> stringResource(R.string.setup_step_enable_title)
            2 -> stringResource(R.string.setup_step_switch_title)
            else -> stringResource(R.string.onboarding_voice_title)
          }
          val stepDesc = when (step) {
            1 -> stringResource(R.string.setup_step_enable_desc, appName)
            2 -> stringResource(R.string.setup_step_switch_desc, appName)
            else -> stringResource(R.string.onboarding_voice_desc)
          }
          val stepBadge = when (step) {
            1 -> "1"
            2 -> "2"
            else -> "🎤"
          }

          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            OnboardingTurtleHero(badgeText = stepBadge)

            Spacer(modifier = Modifier.height(32.dp))

            Text(
              text = stepTitle,
              color = PrimaryTextColor,
              fontSize = 26.sp,
              lineHeight = 32.sp,
              fontWeight = FontWeight.Bold,
              textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
              text = stepDesc,
              color = SecondaryTextColor,
              fontSize = 15.sp,
              lineHeight = 22.sp,
              textAlign = TextAlign.Center,
            )
          }
        }

        // Bottom Pinned Action Bar
        val primaryLabel = when (activeStep) {
          1 -> stringResource(R.string.setup_step_enable_action)
          2 -> stringResource(R.string.setup_step_switch_action)
          else -> stringResource(R.string.onboarding_voice_allow)
        }
        val primaryAction: () -> Unit = when (activeStep) {
          1 -> onEnableKeyboard
          2 -> onPickKeyboard
          else -> if (permissionGranted) onOpenSettings else onGrantPermission
        }

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Surface(
            onClick = onPrivacyNotice,
            shape = RoundedCornerShape(14.dp),
            color = CardBackground,
            border = BorderStroke(1.dp, CardBorderColor),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = stringResource(R.string.privacy_notice_headline),
                color = SecondaryTextColor,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text(
                text = stringResource(R.string.privacy_notice_title),
                color = MintAccentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          Button(
            onClick = primaryAction,
            modifier = Modifier
              .fillMaxWidth()
              .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = MintAccentColor,
              contentColor = MintOnAccentColor,
            ),
          ) {
            Text(
              text = primaryLabel,
              fontSize = 16.sp,
              fontWeight = FontWeight.SemiBold,
            )
          }

          if (activeStep == 3 && voiceEnabled && !permissionGranted) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
              onClick = onSkipVoice,
              modifier = Modifier.heightIn(min = 44.dp),
            ) {
              Text(
                text = stringResource(R.string.setup_step_voice_skip),
                color = SecondaryTextColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
              )
            }
          } else {
            Spacer(modifier = Modifier.height(16.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun OnboardingTurtleHero(
  badgeText: String,
  modifier: Modifier = Modifier,
) {
  val accent = MintAccentColor
  val cardBg = CardBackground
  val screenBg = ScreenBackground
  val border = CardBorderColor

  Box(
    modifier = modifier.size(148.dp),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .size(148.dp)
        .clip(CircleShape)
        .background(accent.copy(alpha = 0.08f))
        .border(1.dp, accent.copy(alpha = 0.22f), CircleShape)
    )
    Box(
      modifier = Modifier
        .size(120.dp)
        .clip(CircleShape)
        .background(Brush.radialGradient(listOf(cardBg, screenBg)))
        .border(1.dp, border, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Image(
        painter = painterResource(R.drawable.mascot_turtle_hero),
        contentDescription = null,
        modifier = Modifier.size(92.dp),
      )
    }
    Box(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(6.dp)
        .size(34.dp)
        .clip(CircleShape)
        .background(accent)
        .border(2.dp, screenBg, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = badgeText,
        color = MintOnAccentColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
fun AppHeader(
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    MicLogo(modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
    Text(
      text = stringResource(id = R.string.app_header_title),
      color = PrimaryTextColor,
      fontSize = 22.sp,
      fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = stringResource(id = R.string.app_header_subtitle),
      color = MintAccentColor,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
    )
    Spacer(modifier = Modifier.height(16.dp))
  }
}

@Composable
fun MicLogo(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .size(64.dp)
      .clip(CircleShape)
      .background(CardBackground)
      .border(1.dp, CardBorderColor, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      painter = painterResource(R.drawable.mascot_turtle_hero),
      contentDescription = null,
      modifier = Modifier.size(48.dp),
    )
  }
}

@Composable
fun KeyboardReadyCard(
  onOpenSettings: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = CardBackground),
    border = BorderStroke(1.dp, StatusActiveGreen.copy(alpha = 0.45f)),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Image(
          painter = painterResource(R.drawable.mascot_turtle_celebrate),
          contentDescription = null,
          modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(id = R.string.setup_all_done_title),
            color = PrimaryTextColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.setup_all_done_desc, stringResource(R.string.app_name)),
            color = SecondaryTextColor,
            fontSize = 13.sp,
            lineHeight = 18.sp,
          )
        }
      }
      if (onOpenSettings != null) {
        Spacer(modifier = Modifier.height(12.dp))
        Button(
          onClick = onOpenSettings,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = MintAccentColor,
            contentColor = MintOnAccentColor,
          ),
        ) {
          Text(stringResource(R.string.setup_all_done_settings), fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}

@Composable
fun KeyboardSetupFlowCard(
  voiceEnabled: Boolean = true,
  keyboardEnabled: Boolean,
  keyboardSelected: Boolean,
  permissionGranted: Boolean,
  onEnableKeyboard: () -> Unit,
  onPickKeyboard: () -> Unit,
  onGrantPermission: () -> Unit,
  onSkipVoice: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val completedCount = (if (keyboardEnabled) 1 else 0) +
    (if (keyboardSelected) 1 else 0) +
    (if (voiceEnabled && permissionGranted) 1 else 0)
  val stepCount = if (voiceEnabled) 3 else 2
  val activeStepIndex = when {
    !keyboardEnabled -> 1
    !keyboardSelected -> 2
    else -> stepCount
  }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = CardBackground),
    border = BorderStroke(1.dp, CardBorderColor),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(id = R.string.setup_flow_title),
          color = PrimaryTextColor,
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = stringResource(id = R.string.setup_step_progress, activeStepIndex, stepCount),
          color = MintAccentColor,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      LinearProgressIndicator(
        progress = { completedCount.toFloat() / stepCount },
        modifier = Modifier
          .fillMaxWidth()
          .height(4.dp)
          .clip(RoundedCornerShape(2.dp)),
        color = MintAccentColor,
        trackColor = CardBorderColor,
      )

      Spacer(modifier = Modifier.height(16.dp))

      SetupStepRow(
        stepNumber = 1,
        title = stringResource(id = R.string.setup_step_enable_title),
        description = stringResource(R.string.setup_step_enable_desc, stringResource(R.string.app_name)),
        isCompleted = keyboardEnabled,
        isActive = !keyboardEnabled,
        actionText = stringResource(id = R.string.setup_step_enable_action),
        onActionClick = onEnableKeyboard,
      )

      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = CardBorderColor)

      SetupStepRow(
        stepNumber = 2,
        title = stringResource(id = R.string.setup_step_switch_title),
        description = if (!keyboardEnabled) stringResource(id = R.string.setup_step_locked_hint)
        else stringResource(R.string.setup_step_switch_desc, stringResource(R.string.app_name)),
        isCompleted = keyboardSelected,
        isActive = keyboardEnabled && !keyboardSelected,
        actionText = stringResource(id = R.string.setup_step_switch_action),
        onActionClick = onPickKeyboard,
      )

      if (voiceEnabled) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = CardBorderColor)

        SetupStepRow(
          stepNumber = 3,
          title = stringResource(id = R.string.setup_step_voice_title),
          description = if (!keyboardSelected) stringResource(id = R.string.setup_step_locked_hint)
          else stringResource(id = R.string.setup_step_voice_desc),
          isCompleted = permissionGranted,
          isActive = keyboardSelected && !permissionGranted,
          actionText = stringResource(id = R.string.setup_step_voice_action),
          onActionClick = onGrantPermission,
          secondaryActionText = if (onSkipVoice != null) stringResource(id = R.string.setup_step_voice_skip) else null,
          onSecondaryActionClick = onSkipVoice,
        )
      }
    }
  }
}

@Composable
fun SetupStepRow(
  stepNumber: Int,
  title: String,
  description: String,
  isCompleted: Boolean,
  isActive: Boolean,
  actionText: String,
  onActionClick: () -> Unit,
  modifier: Modifier = Modifier,
  secondaryActionText: String? = null,
  onSecondaryActionClick: (() -> Unit)? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(32.dp)
          .clip(CircleShape)
          .background(
            when {
              isCompleted -> StatusActiveGreen.copy(alpha = 0.15f)
              isActive -> MintAccentColor.copy(alpha = 0.15f)
              else -> MiniKeySurface
            }
          )
          .border(
            width = 1.5.dp,
            color = when {
              isCompleted -> StatusActiveGreen
              isActive -> MintAccentColor
              else -> CardBorderColor
            },
            shape = CircleShape,
          ),
        contentAlignment = Alignment.Center,
      ) {
        if (isCompleted) {
          Text("✓", color = StatusActiveGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        } else {
          Text(
            stepNumber.toString(),
            color = if (isActive) MintAccentColor else SecondaryTextColor.copy(alpha = 0.6f),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
          )
        }
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column {
        Text(
          text = title,
          color = if (isActive || isCompleted) PrimaryTextColor else SecondaryTextColor.copy(alpha = 0.7f),
          fontSize = 14.sp,
          fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = description,
          color = if (isActive) SecondaryTextColor else SecondaryTextColor.copy(alpha = 0.6f),
          fontSize = 12.sp,
          lineHeight = 16.sp,
        )
      }
    }

    Spacer(modifier = Modifier.width(8.dp))

    if (isCompleted) {
      Text(
        text = stringResource(id = R.string.setup_step_completed),
        color = StatusActiveGreen,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    } else if (isActive) {
      Column(horizontalAlignment = Alignment.End) {
        Button(
          onClick = onActionClick,
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = MintAccentColor,
            contentColor = MintOnAccentColor,
          ),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
          Text(text = actionText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (secondaryActionText != null && onSecondaryActionClick != null) {
          TextButton(
            onClick = onSecondaryActionClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.heightIn(min = 28.dp),
          ) {
            Text(text = secondaryActionText, fontSize = 11.sp, color = SecondaryTextColor)
          }
        }
      }
    }
  }
}

@Composable
internal fun ModelStatusInlineContent(
  status: ModelDownloadStatus,
  onRetry: () -> Unit = {},
  onConfirmDownload: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val isReady = status is ModelDownloadStatus.Ready
  val isError = status is ModelDownloadStatus.Failed
  val accent = when {
    isReady -> StatusActiveGreen
    isError -> StatusWarningAmber
    else -> MintAccentColor
  }

  if (isReady) {
    Row(
      modifier = modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(22.dp)
          .clip(CircleShape)
          .background(StatusActiveGreen.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
      ) {
        Text("✓", color = StatusActiveGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
      }
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = stringResource(id = R.string.model_status_compact_ready),
        color = SecondaryTextColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
      )
    }
    return
  }

  val subtitle = when (status) {
    is ModelDownloadStatus.Ready -> stringResource(id = R.string.model_status_ready)
    is ModelDownloadStatus.Downloading ->
      stringResource(id = R.string.model_status_downloading, status.percent)
    is ModelDownloadStatus.Installing -> stringResource(id = R.string.model_status_installing)
    is ModelDownloadStatus.WaitingForWifi -> stringResource(id = R.string.model_status_waiting_wifi)
    is ModelDownloadStatus.RequiresConfirmation -> stringResource(id = R.string.model_status_confirmation)
    is ModelDownloadStatus.Pending -> stringResource(id = R.string.model_status_preparing)
    is ModelDownloadStatus.NotStarted -> stringResource(id = R.string.model_status_preparing)
    is ModelDownloadStatus.Failed -> stringResource(id = R.string.model_status_failed)
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .then(if (isError) Modifier.clickable { onRetry() } else Modifier),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (status is ModelDownloadStatus.Failed || status is ModelDownloadStatus.Downloading) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .clip(CircleShape)
          .background(accent.copy(alpha = 0.15f))
          .border(width = 1.5.dp, color = accent, shape = CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        if (status is ModelDownloadStatus.Failed) {
          Text("⚠", color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        } else if (status is ModelDownloadStatus.Downloading) {
          CircularProgressIndicator(
            progress = { status.fraction },
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = accent,
          )
        }
      }
    } else {
      Image(
        painter = painterResource(R.drawable.mascot_turtle_downloading),
        contentDescription = null,
        modifier = Modifier.size(40.dp),
      )
    }

    Spacer(modifier = Modifier.width(12.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(id = R.string.model_status_title),
        color = PrimaryTextColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = subtitle,
        color = SecondaryTextColor,
        fontSize = 12.sp,
        lineHeight = 17.sp,
      )
      if (status is ModelDownloadStatus.RequiresConfirmation) {
        TextButton(
          onClick = onConfirmDownload,
          contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
          Text(stringResource(R.string.model_download_confirm), color = MintAccentColor, fontWeight = FontWeight.SemiBold)
        }
      } else if (status is ModelDownloadStatus.Failed) {
        TextButton(
          onClick = onRetry,
          contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
          Text(stringResource(R.string.model_retry_action), color = StatusWarningAmber, fontWeight = FontWeight.SemiBold)
        }
      }
      if (status is ModelDownloadStatus.Downloading) {
        Spacer(modifier = Modifier.height(4.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
          val crawlerSize = 24.dp
          val clampedProgress = status.fraction.coerceIn(0f, 1f)
          val crawlerX = (maxWidth - crawlerSize).coerceAtLeast(0.dp) * clampedProgress
          Column(modifier = Modifier.fillMaxWidth()) {
            Image(
              painter = painterResource(R.drawable.mascot_turtle_downloading),
              contentDescription = null,
              modifier = Modifier
                .offset(x = crawlerX)
                .size(crawlerSize),
            )
            Spacer(modifier = Modifier.height(2.dp))
            LinearProgressIndicator(
              progress = { clampedProgress },
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp)),
              color = accent,
              trackColor = CardBorderColor,
            )
          }
        }
      } else if (status is ModelDownloadStatus.Pending || status is ModelDownloadStatus.Installing) {
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp)),
          color = accent,
          trackColor = CardBorderColor,
        )
      }
    }
  }
}

@Composable
fun ModelStatusCard(
  status: ModelDownloadStatus,
  modifier: Modifier = Modifier,
  onRetry: () -> Unit = {},
  onConfirmDownload: () -> Unit = {},
) {
  val isReady = status is ModelDownloadStatus.Ready
  val isError = status is ModelDownloadStatus.Failed
  val borderColor = when {
    isReady -> StatusActiveGreen.copy(alpha = 0.3f)
    isError -> StatusWarningAmber.copy(alpha = 0.4f)
    else -> CardBorderColor
  }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = CardBackground),
    border = BorderStroke(1.dp, borderColor),
  ) {
    Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
      ModelStatusInlineContent(
        status = status,
        onRetry = onRetry,
        onConfirmDownload = onConfirmDownload,
      )
    }
  }
}

@Composable
fun DeveloperOptionsCard(
  storeDebugAudio: Boolean,
  onStoreDebugAudioChange: (Boolean) -> Unit,
  onOpenDebugAudio: () -> Unit = {},
  storeDebugHandwriting: Boolean = false,
  onStoreDebugHandwritingChange: (Boolean) -> Unit = {},
  onOpenDebugHandwriting: () -> Unit = {},
  onOpenPromptedHandwriting: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = CardBackground),
    border = BorderStroke(1.dp, CardBorderColor),
  ) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(id = R.string.developer_tools_title),
          color = SecondaryTextColor,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = if (expanded) "▲" else "▼",
          color = SecondaryTextColor,
          fontSize = 12.sp,
        )
      }

      if (expanded) {
        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = CardBorderColor)
        Spacer(modifier = Modifier.height(14.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(id = R.string.developer_store_audio_title),
              color = PrimaryTextColor,
              fontSize = 15.sp,
              fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
              text = stringResource(id = R.string.developer_store_audio_desc),
              color = SecondaryTextColor,
              fontSize = 12.sp,
              lineHeight = 17.sp,
            )
          }
          Spacer(modifier = Modifier.width(16.dp))
          Switch(
            checked = storeDebugAudio,
            onCheckedChange = onStoreDebugAudioChange,
            colors = settingsSwitchColors(),
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(
          onClick = onOpenDebugAudio,
          enabled = storeDebugAudio,
          modifier = Modifier.align(Alignment.End),
        ) {
          Text(stringResource(R.string.developer_manage_audio), color = if (storeDebugAudio) MintAccentColor else SecondaryTextColor)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(id = R.string.developer_store_handwriting_title),
              color = PrimaryTextColor,
              fontSize = 15.sp,
              fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
              text = stringResource(id = R.string.developer_store_handwriting_desc),
              color = SecondaryTextColor,
              fontSize = 12.sp,
              lineHeight = 17.sp,
            )
          }
          Spacer(modifier = Modifier.width(16.dp))
          Switch(
            checked = storeDebugHandwriting,
            onCheckedChange = onStoreDebugHandwritingChange,
            colors = settingsSwitchColors(),
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(
          onClick = onOpenDebugHandwriting,
          enabled = storeDebugHandwriting,
          modifier = Modifier.align(Alignment.End),
        ) {
          Text(stringResource(R.string.developer_manage_handwriting), color = if (storeDebugHandwriting) MintAccentColor else SecondaryTextColor)
        }
        Button(
          onClick = onOpenPromptedHandwriting,
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = MintAccentColor,
            contentColor = MintOnAccentColor,
          ),
        ) {
          Text(stringResource(R.string.challenge_open), fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  CantoneseVoiceKeyboardTheme {
    MainScreenContent(
      voiceEnabled = true,
      permissionGranted = false,
      keyboardEnabled = false,
      keyboardSelected = false,
      onGrantPermission = {},
      onEnableKeyboard = {},
      onPickKeyboard = {},
    )
  }
}
