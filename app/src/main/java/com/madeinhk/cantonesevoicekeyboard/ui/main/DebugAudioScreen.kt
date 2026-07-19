package com.madeinhk.cantonesevoicekeyboard.ui.main

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.madeinhk.cantonesevoicekeyboard.DebugAudioSettings
import com.madeinhk.cantonesevoicekeyboard.DebugAudioStore
import com.madeinhk.cantonesevoicekeyboard.DebugAudioSync
import com.madeinhk.cantonesevoicekeyboard.DebugRecordingBackfill
import com.madeinhk.cantonesevoicekeyboard.DebugRecording
import com.madeinhk.cantonesevoicekeyboard.DebugVadSegment
import com.madeinhk.cantonesevoicekeyboard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.io.File

private data class EditingRecording(
    val recording: DebugRecording,
    val initialIsBad: Boolean? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugAudioScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var recordings by remember { mutableStateOf(emptyList<DebugRecording>()) }
    var editing by remember { mutableStateOf<EditingRecording?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var backfillProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val audioPlayer = remember { DebugAudioPlayer() }
    DisposableEffect(audioPlayer) { onDispose { audioPlayer.release() } }

    suspend fun reload() {
        recordings = withContext(Dispatchers.IO) { DebugAudioStore.list(context) }
    }

    fun sync() {
        val folder = DebugAudioSettings.getSyncFolderUri(context) ?: return
        if (syncing) return
        audioPlayer.stop()
        scope.launch {
            syncing = true
            val result = runCatching { DebugAudioSync.syncPending(context, folder) }
            syncing = false
            reload()
            val message = result.fold(
                onSuccess = { context.getString(R.string.debug_sync_result, it.uploaded, it.failed) },
                onFailure = { context.getString(R.string.debug_sync_failed) }
            )
            snackbar.showSnackbar(message)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            DebugAudioSettings.setSyncFolderUri(context, uri)
            scope.launch { snackbar.showSnackbar(context.getString(R.string.debug_folder_saved)) }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        val missing = recordings.filterNot { it.inferenceCompleted }
        if (missing.isNotEmpty()) {
            backfillProgress = 0 to missing.size
            val result = DebugRecordingBackfill.runMissing(context, recordings) { done, total ->
                withContext(Dispatchers.Main) { backfillProgress = done to total }
            }
            backfillProgress = null
            reload()
            snackbar.showSnackbar(
                context.getString(R.string.debug_backfill_result, result.completed, result.failed)
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_audio_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.debug_back)) } },
                actions = {
                    TextButton(onClick = { folderPicker.launch(DebugAudioSettings.getSyncFolderUri(context)) }) {
                        Text(stringResource(R.string.debug_choose_folder))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.debug_recording_count, recordings.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            R.string.debug_annotated_ready_count,
                            recordings.count { it.isAnnotated }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    enabled = recordings.any { it.isAnnotated && !it.isSynced } &&
                        !syncing && backfillProgress == null,
                    onClick = {
                        if (DebugAudioSettings.getSyncFolderUri(context) == null) {
                            folderPicker.launch(null)
                        } else {
                            sync()
                        }
                    }
                ) {
                    Text(if (syncing) stringResource(R.string.debug_syncing) else stringResource(R.string.debug_sync))
                }
            }

            backfillProgress?.let { (done, total) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(22.dp))
                    Text(stringResource(R.string.debug_backfilling_model, done, total))
                }
            }

            if (recordings.isEmpty()) {
                Text(
                    text = stringResource(R.string.debug_no_recordings),
                    modifier = Modifier.padding(top = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recordings, key = { it.id }) { recording ->
                        RecordingCard(
                            recording = recording,
                            playingPath = audioPlayer.playingPath,
                            actionsEnabled = backfillProgress == null && recording.inferenceCompleted,
                            onTogglePlayback = { audioPlayer.toggle(recording.inputAudioFile) },
                            onOpen = { editing = EditingRecording(recording) },
                            onMarkGood = {
                                scope.launch(Dispatchers.IO) {
                                    DebugAudioStore.saveAnnotation(
                                        context,
                                        recording.id,
                                        recording.modelTranscript,
                                        isBad = false
                                    )
                                    withContext(Dispatchers.Main) { reload() }
                                }
                            },
                            onMarkBad = { editing = EditingRecording(recording, initialIsBad = true) }
                        )
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }

    editing?.let { edit ->
        val recording = edit.recording
        TranscriptEditor(
            recording = recording,
            initialIsBad = edit.initialIsBad,
            playingPath = audioPlayer.playingPath,
            onTogglePlayback = audioPlayer::toggle,
            onDismiss = {
                audioPlayer.stop()
                editing = null
            },
            onDelete = {
                audioPlayer.stop()
                scope.launch(Dispatchers.IO) {
                    DebugAudioStore.deleteRecording(context, recording.id)
                    withContext(Dispatchers.Main) {
                        editing = null
                        reload()
                    }
                }
            },
            onSave = { transcript, isBad ->
                audioPlayer.stop()
                scope.launch(Dispatchers.IO) {
                    DebugAudioStore.saveAnnotation(context, recording.id, transcript, isBad)
                    withContext(Dispatchers.Main) {
                        editing = null
                        reload()
                    }
                }
            }
        )
    }
}

@Composable
private fun RecordingCard(
    recording: DebugRecording,
    playingPath: String?,
    actionsEnabled: Boolean,
    onTogglePlayback: () -> Unit,
    onOpen: () -> Unit,
    onMarkGood: () -> Unit,
    onMarkBad: () -> Unit
) {
    val error = MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (recording.isBad) error else MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(recording.createdAtMillis)),
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (recording.isAnnotated) {
                        Text(
                            stringResource(R.string.debug_annotated_badge),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (recording.isBad) stringResource(R.string.debug_bad_badge)
                            else stringResource(R.string.debug_good_badge),
                            color = if (recording.isBad) error else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            stringResource(R.string.debug_unannotated_badge),
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold
                        )
                        if (recording.isBad) {
                            Text(stringResource(R.string.debug_flagged_bad_badge), color = error)
                        }
                    }
                    if (recording.isSynced) Text(stringResource(R.string.debug_synced_badge), color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.debug_model_output_label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = recording.modelTranscript.ifBlank {
                    if (recording.inferenceCompleted) stringResource(R.string.debug_model_no_speech)
                    else stringResource(R.string.debug_model_pending)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            if (recording.isAnnotated) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.debug_corrected_output_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = recording.correctedTranscript,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = stringResource(
                    R.string.debug_recording_details,
                    recording.durationMillis / 1000f,
                    recording.vadSegments.size
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onTogglePlayback, enabled = actionsEnabled) {
                    Text(
                        if (playingPath == recording.inputAudioFile.absolutePath) {
                            stringResource(R.string.debug_stop_audio)
                        } else {
                            stringResource(R.string.debug_quick_play)
                        }
                    )
                }
                if (recording.isAnnotated) {
                    TextButton(onClick = onOpen) {
                        Text(stringResource(R.string.debug_edit_annotation))
                    }
                } else {
                    Button(onClick = onMarkGood, enabled = actionsEnabled) {
                        Text(stringResource(R.string.debug_quick_good))
                    }
                    OutlinedButton(onClick = onMarkBad, enabled = actionsEnabled) {
                        Text(stringResource(R.string.debug_bad_and_correct))
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptEditor(
    recording: DebugRecording,
    initialIsBad: Boolean?,
    playingPath: String?,
    onTogglePlayback: (File) -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var transcript by remember(recording.id) {
        mutableStateOf(recording.correctedTranscript.ifBlank { recording.modelTranscript })
    }
    var isBad by remember(recording.id, initialIsBad) {
        mutableStateOf<Boolean?>(
            when {
                initialIsBad != null -> initialIsBad
                recording.isAnnotated -> recording.isBad
                recording.isBad -> true
                else -> null
            }
        )
    }
    val transcriptFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isBad) {
        if (isBad == true) transcriptFocusRequester.requestFocus()
    }
    var confirmDelete by remember(recording.id) { mutableStateOf(false) }
    var showVadDetails by remember(recording.id) { mutableStateOf(false) }
    val badCorrectionMissing = isBad == true && (
        transcript.isBlank() || transcript.trim() == recording.modelTranscript.trim()
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.debug_correct_transcript)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.debug_whole_input_title),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.debug_whole_input_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = { onTogglePlayback(recording.inputAudioFile) }) {
                    Text(
                        if (playingPath == recording.inputAudioFile.absolutePath) {
                            stringResource(R.string.debug_stop_audio)
                        } else {
                            stringResource(R.string.debug_play_whole_audio)
                        }
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.debug_model_output_label),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = recording.modelTranscript.ifBlank {
                        stringResource(R.string.debug_model_no_speech)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = transcript,
                    onValueChange = { transcript = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(transcriptFocusRequester),
                    minLines = 4,
                    label = { Text(stringResource(R.string.debug_transcript_label)) }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.debug_annotation_quality),
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isBad == false,
                        onClick = {
                            isBad = false
                            transcript = recording.modelTranscript
                        },
                        label = { Text(stringResource(R.string.debug_mark_good)) }
                    )
                    FilterChip(
                        selected = isBad == true,
                        onClick = { isBad = true },
                        label = { Text(stringResource(R.string.debug_mark_bad)) }
                    )
                }
                if (badCorrectionMissing) {
                    Text(
                        text = stringResource(R.string.debug_bad_needs_correction),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showVadDetails = !showVadDetails }) {
                    Text(
                        if (showVadDetails) stringResource(R.string.debug_hide_vad_details)
                        else stringResource(R.string.debug_show_vad_details)
                    )
                }
                if (showVadDetails) {
                    Text(
                        text = stringResource(R.string.debug_model_segments_title),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.debug_model_segments_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (recording.vadSegments.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        VadTimeline(recording)
                        recording.vadSummary?.let { summary ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.debug_vad_summary,
                                    summary.samplesEnqueuedForAsr / 16000f,
                                    summary.samplesReceived / 16000f,
                                    summary.positiveThreshold,
                                    summary.negativeThreshold,
                                    summary.minimumSilenceMillis,
                                    summary.speechPaddingMillis,
                                    summary.queueOverflowCount
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        recording.vadSegments.forEach { segment ->
                            VadSegmentRow(
                                segment = segment,
                                isPlaying = playingPath == segment.audioFile.absolutePath,
                                onTogglePlayback = onTogglePlayback
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.debug_no_vad_segments),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isBad != null,
                onClick = { onSave(transcript, requireNotNull(isBad)) }
            ) { Text(stringResource(R.string.debug_save_annotation)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(
                        stringResource(R.string.debug_remove_recording),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.debug_cancel)) }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.debug_remove_confirm_title)) },
            text = { Text(stringResource(R.string.debug_remove_confirm_desc)) },
            confirmButton = {
                Button(onClick = onDelete) { Text(stringResource(R.string.debug_remove)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.debug_cancel))
                }
            }
        )
    }
}

@Composable
private fun VadTimeline(recording: DebugRecording) {
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    val segmentColor = MaterialTheme.colorScheme.primary
    val duration = recording.durationMillis.coerceAtLeast(1L).toFloat()
    Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
        drawRoundRect(baseColor, cornerRadius = CornerRadius(size.height / 2f))
        recording.vadSegments.forEach { segment ->
            val left = (segment.startMillis / duration).coerceIn(0f, 1f) * size.width
            val right = (segment.endMillis / duration).coerceIn(0f, 1f) * size.width
            drawRoundRect(
                color = segmentColor,
                topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
                size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(2f), size.height),
                cornerRadius = CornerRadius(size.height / 2f)
            )
        }
    }
}

@Composable
private fun VadSegmentRow(
    segment: DebugVadSegment,
    isPlaying: Boolean,
    onTogglePlayback: (File) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.debug_segment_title,
                            segment.id,
                            segment.startMillis / 1000f,
                            segment.endMillis / 1000f
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.debug_segment_vad_details,
                            segment.scoreMin,
                            segment.scoreMean,
                            segment.scoreMax,
                            segment.endReason
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { onTogglePlayback(segment.audioFile) }) {
                    Text(
                        if (isPlaying) stringResource(R.string.debug_stop_audio)
                        else stringResource(R.string.debug_play_segment)
                    )
                }
            }
            if (segment.transcript.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = segment.transcript,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private class DebugAudioPlayer {
    private var player: MediaPlayer? = null
    var playingPath by mutableStateOf<String?>(null)
        private set

    fun toggle(file: File) {
        if (playingPath == file.absolutePath) {
            stop()
            return
        }
        stop()
        runCatching {
            MediaPlayer().also { mediaPlayer ->
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.setOnCompletionListener { stop() }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
                mediaPlayer.prepare()
                player = mediaPlayer
                playingPath = file.absolutePath
                mediaPlayer.start()
            }
        }.onFailure { stop() }
    }

    fun stop() {
        runCatching { player?.stop() }
        player?.release()
        player = null
        playingPath = null
    }

    fun release() = stop()
}
