package com.madeinhk.cantonesevoicekeyboard.ui.main

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madeinhk.cantonesevoicekeyboard.DebugHandwritingSettings
import com.madeinhk.cantonesevoicekeyboard.HandwritingCanvasGeometry
import com.madeinhk.cantonesevoicekeyboard.PromptedChallengeProgress
import com.madeinhk.cantonesevoicekeyboard.PromptedHandwritingProgress
import com.madeinhk.cantonesevoicekeyboard.PromptedHandwritingStore
import com.madeinhk.cantonesevoicekeyboard.PromptedHandwritingSync
import com.madeinhk.cantonesevoicekeyboard.R
import com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognizer
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.PromptedHandwritingCanvas
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardUiDefaults
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.handwritingCanvasHeight
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.promptedHandwritingBitmap
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.rememberPromptedInkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class ChallengeInventory(
    val labels: List<String>,
    val hash: String,
    val order: List<Int>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptedHandwritingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val saveFailedMessage = stringResource(R.string.challenge_save_failed)
    val folderAccessError = stringResource(R.string.debug_sync_failed)
    val ink = rememberPromptedInkState()
    val measuredKeyboardCanvas = remember(context.resources.configuration.orientation) {
        HandwritingCanvasGeometry.read(context)
    }
    val navigationBarHeight = with(density) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val canvasHeight = measuredKeyboardCanvas?.let { with(density) { it.height.toDp() } }
        ?: handwritingCanvasHeight(navigationBarHeight)
    val canvasWidth = measuredKeyboardCanvas?.let { with(density) { it.width.toDp() } }
    val inventory = remember {
        val bytes = context.assets.open("handwriting/labels.txt").use { it.readBytes() }
        val labels = bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).toList()
        val hash = PromptedHandwritingProgress.inventoryHash(bytes)
        ChallengeInventory(
            labels,
            hash,
            PromptedHandwritingProgress.shuffledClassIds(
                labels,
                PromptedHandwritingProgress.writerId(context),
                hash,
            ),
        )
    }
    var progress by remember {
        mutableStateOf(PromptedHandwritingProgress.load(context, LocalDate.now()))
    }
    var deferred by remember { mutableStateOf(emptySet<Int>()) }
    var currentClassId by remember {
        mutableStateOf(
            PromptedHandwritingProgress.nextClassId(
                inventory.order,
                progress.completedClassIds,
            )
        )
    }
    var processing by remember { mutableStateOf(false) }
    var pendingCount by remember {
        mutableIntStateOf(PromptedHandwritingStore.list(context).count { !it.synced })
    }
    var recognizer by remember { mutableStateOf<HandwritingRecognizer?>(null) }
    var hasSyncFolder by remember {
        mutableStateOf(DebugHandwritingSettings.getSyncFolderUri(context) != null)
    }

    fun chooseNext(updated: PromptedChallengeProgress = progress) {
        currentClassId = PromptedHandwritingProgress.nextClassId(
            inventory.order,
            updated.completedClassIds,
            deferred,
        )
        ink.clear()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            if (persisted) {
                DebugHandwritingSettings.setSyncFolderUri(context, uri)
                hasSyncFolder = true
                PromptedHandwritingSync.enqueue(context)
            } else {
                scope.launch { snackbar.showSnackbar(folderAccessError) }
            }
        }
    }

    LaunchedEffect(Unit) {
        recognizer = withContext(Dispatchers.Default) { HandwritingRecognizer.load(context) }
        if (pendingCount > 0) PromptedHandwritingSync.enqueue(context)
    }
    DisposableEffect(Unit) {
        onDispose {
            recognizer?.close()
            PromptedHandwritingSync.enqueue(context)
        }
    }

    fun submit() {
        val classId = currentClassId ?: return
        val character = inventory.labels[classId]
        val snapshot = ink.snapshot()
        if (!snapshot.isUsable || processing) return
        processing = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bitmap = promptedHandwritingBitmap(snapshot)
                    val recordId = PromptedHandwritingStore.capture(
                        context,
                        character,
                        classId,
                        inventory.hash,
                        snapshot,
                        bitmap,
                    )
                    val engine = recognizer
                    if (engine == null) {
                        PromptedHandwritingStore.finalizeInference(
                            context,
                            recordId,
                            modelInput = null,
                            error = "model_unavailable",
                        )
                    } else {
                        runCatching {
                            val modelInput = engine.modelInputPixels(
                                bitmap.pixels,
                                bitmap.width,
                                bitmap.height,
                            )
                            val predictions = engine.predict(
                                bitmap.pixels,
                                bitmap.width,
                                bitmap.height,
                                10,
                            )
                            PromptedHandwritingStore.finalizeInference(
                                context,
                                recordId,
                                modelInput,
                                predictions = predictions,
                            )
                        }.onFailure {
                            PromptedHandwritingStore.finalizeInference(
                                context,
                                recordId,
                                modelInput = null,
                                error = it.javaClass.simpleName,
                            )
                        }
                    }
                    check(
                        PromptedHandwritingStore.list(context)
                            .firstOrNull { it.id == recordId }
                            ?.readyToSync == true
                    ) { "Could not finalize sample" }
                    PromptedHandwritingProgress.recordAccepted(context, classId)
                }
            }
            processing = false
            result.onSuccess { updated ->
                progress = updated
                pendingCount = PromptedHandwritingStore.list(context).count { !it.synced }
                deferred = deferred - classId
                chooseNext(updated)
                if (updated.todayCount % 10 == 0) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    PromptedHandwritingSync.enqueue(context)
                }
            }.onFailure {
                snackbar.showSnackbar(saveFailedMessage)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.challenge_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.debug_back)) }
                },
                actions = {
                    TextButton(onClick = {
                        folderPicker.launch(DebugHandwritingSettings.getSyncFolderUri(context))
                    }) {
                        Text(stringResource(R.string.debug_choose_folder))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChallengeProgress(
                progress,
                pendingCount,
                Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.challenge_prompt),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                currentClassId?.let(inventory.labels::get).orEmpty(),
                fontSize = 72.sp,
                lineHeight = 80.sp,
                fontWeight = FontWeight.Bold,
            )
            Card(
                modifier = if (canvasWidth != null) {
                    Modifier.width(canvasWidth).height(canvasHeight)
                } else {
                    Modifier
                        .padding(horizontal = KeyboardUiDefaults.KeyboardHorizontalPadding)
                        .fillMaxWidth()
                        .height(canvasHeight)
                },
                shape = RoundedCornerShape(20.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(20.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    PromptedHandwritingCanvas(ink, Modifier.fillMaxSize())
                    if (!ink.hasInk) {
                        Text(
                            stringResource(R.string.keyboard_handwriting_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = ink::undo,
                    enabled = ink.hasInk && !processing,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.challenge_undo)) }
                OutlinedButton(
                    onClick = ink::clear,
                    enabled = ink.hasInk && !processing,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.challenge_clear)) }
                OutlinedButton(
                    onClick = {
                        currentClassId?.let { deferred = deferred + it }
                        chooseNext()
                    },
                    enabled = !processing,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.challenge_skip)) }
            }
            Button(
                onClick = ::submit,
                enabled = ink.snapshot().isUsable && !processing && currentClassId != null,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp),
            ) {
                if (processing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.challenge_next))
                }
            }
            AnimatedVisibility(progress.todayCount > 0 && progress.todayCount % 10 == 0) {
                Text(
                    text = if (progress.todayCount >= PromptedHandwritingProgress.DAILY_GOAL) {
                        stringResource(R.string.challenge_goal_complete)
                    } else {
                        stringResource(R.string.challenge_milestone, progress.todayCount)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (!hasSyncFolder) {
                Text(
                    stringResource(R.string.challenge_choose_drive_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChallengeProgress(
    progress: PromptedChallengeProgress,
    pendingCount: Int,
    modifier: Modifier = Modifier,
) {
    val shown = progress.todayCount.coerceAtMost(PromptedHandwritingProgress.DAILY_GOAL)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(
                    R.string.challenge_daily_progress,
                    shown,
                    PromptedHandwritingProgress.DAILY_GOAL,
                ),
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.challenge_streak, progress.streakDays))
        }
        LinearProgressIndicator(
            progress = { shown / PromptedHandwritingProgress.DAILY_GOAL.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Text(
            stringResource(
                R.string.challenge_lifetime_pending,
                progress.lifetimeCount,
                pendingCount,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}
