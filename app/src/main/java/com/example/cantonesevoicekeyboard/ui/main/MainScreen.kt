package com.example.cantonesevoicekeyboard.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.compose.ui.res.stringResource
import com.example.cantonesevoicekeyboard.R
import com.example.cantonesevoicekeyboard.data.DefaultDataRepository
import com.example.cantonesevoicekeyboard.theme.*
import java.io.File

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  var permissionGranted by remember { mutableStateOf(false) }
  var keyboardEnabled by remember { mutableStateOf(false) }
  var keyboardSelected by remember { mutableStateOf(false) }
  var modelSynced by remember { mutableStateOf(false) }

  fun refreshStatus() {
    permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    keyboardEnabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }
    
    val currentIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    keyboardSelected = currentIme?.contains(context.packageName) == true
    
    val externalDir = context.getExternalFilesDir(null)
    modelSynced = if (externalDir != null) {
      val modelDir = File(externalDir, "qwen-asr-int8dec")
      modelDir.exists() && modelDir.isDirectory && (modelDir.list()?.isNotEmpty() == true)
    } else {
      false
    }
  }

  // Refresh status when window gains focus (e.g. returning from system dialog overlays like the IME picker)
  val windowInfo = LocalWindowInfo.current
  LaunchedEffect(windowInfo.isWindowFocused) {
    if (windowInfo.isWindowFocused) {
      refreshStatus()
    }
  }

  // Observe lifecycle to reload when returning from system Settings page
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

  // Permission launcher
  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
    onResult = { isGranted ->
      permissionGranted = isGranted
      refreshStatus()
    }
  )

  // Layout Container
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        brush = Brush.verticalGradient(
          colors = listOf(
            DarkMidnight, // Deep indigo midnight
            CarbonBlack  // Dark carbon black
          )
        )
      )
  ) {
    Column(
      modifier = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      AppHeader()

      // Quick Setup Walkthrough Cards
      SetupStepCard(
        stepNumber = 1,
        title = stringResource(id = R.string.setup_step_1_title),
        description = stringResource(id = R.string.setup_step_1_desc),
        isCompleted = permissionGranted,
        actionText = stringResource(id = R.string.setup_step_1_action),
        onActionClick = {
          permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
      )

      SetupStepCard(
        stepNumber = 2,
        title = stringResource(id = R.string.setup_step_2_title),
        description = stringResource(id = R.string.setup_step_2_desc),
        isCompleted = keyboardEnabled,
        actionText = stringResource(id = R.string.setup_step_2_action),
        onActionClick = {
          context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
      )

      SetupStepCard(
        stepNumber = 3,
        title = stringResource(id = R.string.setup_step_3_title),
        description = stringResource(id = R.string.setup_step_3_desc),
        isCompleted = keyboardSelected,
        actionText = stringResource(id = R.string.setup_step_3_action),
        onActionClick = {
          val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
          imm.showInputMethodPicker()
        }
      )

      Spacer(modifier = Modifier.height(6.dp))

      // Model Sync Status
      ModelStatusCard(isSynced = modelSynced)

      Spacer(modifier = Modifier.height(12.dp))

      // Interactive Test Sandbox
      TestSandboxCard()

      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}

@Composable
fun AppHeader(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    MicLogo(modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
    Text(
      text = stringResource(id = R.string.app_header_title),
      color = Color.White,
      fontSize = 22.sp,
      fontWeight = FontWeight.Black,
      letterSpacing = 1.sp
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = stringResource(id = R.string.app_header_subtitle),
      color = MaterialTheme.colorScheme.primary,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 2.sp
    )
    Spacer(modifier = Modifier.height(16.dp))
  }
}

@Composable
fun MicLogo(modifier: Modifier = Modifier) {
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val scale by infiniteTransition.animateFloat(
    initialValue = 1.0f,
    targetValue = 1.06f,
    animationSpec = infiniteRepeatable(
      animation = tween(1500, easing = EaseInOutSine),
      repeatMode = RepeatMode.Reverse
    ),
    label = "logoPulse"
  )

  val primaryColor = MaterialTheme.colorScheme.primary
  val outlineColor = MaterialTheme.colorScheme.outline
  val surfaceColor = MaterialTheme.colorScheme.surface
  val backgroundColor = MaterialTheme.colorScheme.background

  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center
  ) {
    // Outer glowing ring
    Box(
      modifier = Modifier
        .size(90.dp * scale)
        .clip(CircleShape)
        .background(primaryColor.copy(alpha = 0.08f))
        .border(1.5.dp, primaryColor.copy(alpha = 0.2f), CircleShape)
    )
    
    // Inner logo container
    Box(
      modifier = Modifier
        .size(70.dp)
        .clip(CircleShape)
        .background(
          brush = Brush.radialGradient(
            colors = listOf(
              surfaceColor,
              backgroundColor
            )
          )
        )
        .border(1.dp, outlineColor, CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Canvas(modifier = Modifier.size(32.dp)) {
        val w = size.width
        val h = size.height

        // Draw similar Mic shape as KeyboardScreen to establish identity
        val micW = w * 0.36f
        val micH = h * 0.48f
        val micX = (w - micW) / 2f
        val micY = h * 0.16f

        drawRoundRect(
          color = primaryColor,
          topLeft = Offset(micX, micY),
          size = Size(micW, micH),
          cornerRadius = CornerRadius(micW / 2f, micW / 2f)
        )

        val standW = w * 0.54f
        val standH = h * 0.22f
        val standX = (w - standW) / 2f
        val standY = micY + micH * 0.52f

        val standPath = Path().apply {
          arcTo(
            rect = Rect(standX, standY - standH * 0.2f, standX + standW, standY + standH),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 180f,
            forceMoveTo = true
          )
        }

        drawPath(
          path = standPath,
          color = primaryColor,
          style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        val stemX = w / 2f
        val stemYStart = standY + standH
        val stemYEnd = h * 0.82f

        drawLine(
          color = primaryColor,
          start = Offset(stemX, stemYStart),
          end = Offset(stemX, stemYEnd),
          strokeWidth = 2.5.dp.toPx(),
          cap = StrokeCap.Round
        )

        val baseXStart = w * 0.32f
        val baseXEnd = w * 0.68f
        drawLine(
          color = primaryColor,
          start = Offset(baseXStart, stemYEnd),
          end = Offset(baseXEnd, stemYEnd),
          strokeWidth = 2.5.dp.toPx(),
          cap = StrokeCap.Round
        )
      }
    }
  }
}

@Composable
fun SetupStepCard(
  stepNumber: Int,
  title: String,
  description: String,
  isCompleted: Boolean,
  actionText: String,
  onActionClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val outlineColor = MaterialTheme.colorScheme.outline
  val textVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    ),
    border = BorderStroke(
      width = 1.dp,
      color = if (isCompleted) SuccessGreen.copy(alpha = 0.3f) else outlineColor
    )
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Circular Badge
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
              if (isCompleted) SuccessGreen.copy(alpha = 0.15f)
              else outlineColor
            )
            .border(
              width = 1.5.dp,
              color = if (isCompleted) SuccessGreen else textVariantColor,
              shape = CircleShape
            ),
          contentAlignment = Alignment.Center
        ) {
          if (isCompleted) {
            Text("✓", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
          } else {
            Text(stepNumber.toString(), color = textVariantColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Text Area
        Column {
          Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = description,
            color = textVariantColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal
          )
        }
      }
      
      Spacer(modifier = Modifier.width(12.dp))
      
      // Action button or Status indicator
      if (isCompleted) {
        Text(
          text = stringResource(id = R.string.setup_step_completed),
          color = SuccessGreen,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 8.dp)
        )
      } else {
        Button(
          onClick = onActionClick,
          shape = RoundedCornerShape(20.dp),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
          Text(
            text = actionText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }
  }
}

@Composable
fun ModelStatusCard(
  isSynced: Boolean,
  modifier: Modifier = Modifier
) {
  val textVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    ),
    border = BorderStroke(
      width = 1.dp,
      color = if (isSynced) SuccessGreen.copy(alpha = 0.3f) else AlertAmber.copy(alpha = 0.3f)
    )
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .clip(CircleShape)
          .background(
            if (isSynced) SuccessGreen.copy(alpha = 0.15f)
            else AlertAmber.copy(alpha = 0.15f)
          )
          .border(
            width = 1.5.dp,
            color = if (isSynced) SuccessGreen else AlertAmber,
            shape = CircleShape
          ),
        contentAlignment = Alignment.Center
      ) {
        if (isSynced) {
          Text("✓", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        } else {
          Text("⚠", color = AlertAmber, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
      }
      
      Spacer(modifier = Modifier.width(16.dp))
      
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(id = R.string.model_status_title),
          color = Color.White,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = if (isSynced) stringResource(id = R.string.model_status_ready)
                 else stringResource(id = R.string.model_status_missing),
          color = textVariantColor,
          fontSize = 12.sp,
          fontWeight = FontWeight.Normal
        )
      }
    }
  }
}

@Composable
fun TestSandboxCard(
  modifier: Modifier = Modifier
) {
  var textState by remember { mutableStateOf("") }
  val primaryColor = MaterialTheme.colorScheme.primary
  val outlineColor = MaterialTheme.colorScheme.outline
  val errorColor = MaterialTheme.colorScheme.error
  val surfaceColor = MaterialTheme.colorScheme.surface
  val textVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 12.dp),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(
      containerColor = surfaceColor.copy(alpha = 0.8f)
    ),
    border = BorderStroke(1.dp, outlineColor)
  ) {
    Column(
      modifier = Modifier.padding(16.dp)
    ) {
      Text(
        text = stringResource(id = R.string.sandbox_title),
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = stringResource(id = R.string.sandbox_desc),
        color = textVariantColor,
        fontSize = 12.sp,
        lineHeight = 16.sp
      )
      
      Spacer(modifier = Modifier.height(12.dp))
      
      OutlinedTextField(
        value = textState,
        onValueChange = { textState = it },
        modifier = Modifier
          .fillMaxWidth()
          .height(100.dp),
        placeholder = {
          Text(
            text = stringResource(id = R.string.sandbox_placeholder),
            color = PlaceholderGray,
            fontSize = 14.sp
          )
        },
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = primaryColor,
          unfocusedBorderColor = outlineColor,
          focusedContainerColor = CarbonBlack.copy(alpha = 0.5f),
          unfocusedContainerColor = CarbonBlack.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
      )
      
      if (textState.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          TextButton(
            onClick = { textState = "" },
            colors = ButtonDefaults.textButtonColors(contentColor = errorColor)
          ) {
            Text(stringResource(id = R.string.sandbox_clear), fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  CantoneseVoiceKeyboardTheme {
    MainScreen(onItemClick = {})
  }
}

