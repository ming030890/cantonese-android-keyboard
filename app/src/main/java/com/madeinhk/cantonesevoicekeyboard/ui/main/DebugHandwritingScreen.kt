package com.madeinhk.cantonesevoicekeyboard.ui.main

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.madeinhk.cantonesevoicekeyboard.DebugHandwritingRecord
import com.madeinhk.cantonesevoicekeyboard.DebugHandwritingSettings
import com.madeinhk.cantonesevoicekeyboard.DebugHandwritingStore
import com.madeinhk.cantonesevoicekeyboard.DebugHandwritingSync
import com.madeinhk.cantonesevoicekeyboard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class EditingHandwriting(
    val record: DebugHandwritingRecord,
    val initialIsBad: Boolean? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugHandwritingScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var records by remember { mutableStateOf(emptyList<DebugHandwritingRecord>()) }
    var editing by remember { mutableStateOf<EditingHandwriting?>(null) }
    var syncing by remember { mutableStateOf(false) }

    suspend fun reload() {
        records = withContext(Dispatchers.IO) { DebugHandwritingStore.list(context) }
    }

    fun sync() {
        val folder = DebugHandwritingSettings.getSyncFolderUri(context) ?: return
        if (syncing) return
        scope.launch {
            syncing = true
            val result = runCatching { DebugHandwritingSync.syncPending(context, folder) }
            syncing = false
            reload()
            snackbar.showSnackbar(
                result.fold(
                    onSuccess = { context.getString(R.string.debug_sync_result, it.uploaded, it.failed) },
                    onFailure = { context.getString(R.string.debug_sync_failed) },
                )
            )
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            DebugHandwritingSettings.setSyncFolderUri(context, uri)
            scope.launch { snackbar.showSnackbar(context.getString(R.string.debug_folder_saved)) }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_handwriting_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.debug_back)) } },
                actions = {
                    TextButton(
                        onClick = {
                            folderPicker.launch(DebugHandwritingSettings.getSyncFolderUri(context))
                        }
                    ) {
                        Text(stringResource(R.string.debug_choose_folder))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.debug_handwriting_count, records.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = records.any { it.isAnnotated && !it.isSynced } && !syncing,
                    onClick = {
                        if (DebugHandwritingSettings.getSyncFolderUri(context) == null) {
                            folderPicker.launch(null)
                        } else {
                            sync()
                        }
                    },
                ) {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.debug_sync))
                    }
                }
            }
            if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.debug_handwriting_no_samples),
                    modifier = Modifier.padding(top = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(records, key = { it.id }) { record ->
                        HandwritingCard(
                            record = record,
                            onOpen = { editing = EditingHandwriting(record) },
                            onMarkGood = {
                                val baseline = record.chosenCharacter.ifBlank { record.modelTop1 }
                                scope.launch(Dispatchers.IO) {
                                    DebugHandwritingStore.saveAnnotation(context, record.id, baseline, false)
                                    withContext(Dispatchers.Main) { reload() }
                                }
                            },
                            onMarkBad = { editing = EditingHandwriting(record, initialIsBad = true) },
                        )
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }

    editing?.let { edit ->
        val record = edit.record
        HandwritingEditor(
            record = record,
            initialIsBad = edit.initialIsBad,
            onDismiss = { editing = null },
            onDelete = {
                scope.launch(Dispatchers.IO) {
                    DebugHandwritingStore.delete(context, record.id)
                    withContext(Dispatchers.Main) {
                        editing = null
                        reload()
                    }
                }
            },
            onSave = { correction, isBad ->
                scope.launch(Dispatchers.IO) {
                    DebugHandwritingStore.saveAnnotation(context, record.id, correction, isBad)
                    withContext(Dispatchers.Main) {
                        editing = null
                        reload()
                    }
                }
            },
        )
    }
}

@Composable
private fun HandwritingCard(
    record: DebugHandwritingRecord,
    onOpen: () -> Unit,
    onMarkGood: () -> Unit,
    onMarkBad: () -> Unit,
) {
    val bitmap = remember(record.id) { BitmapFactory.decodeFile(record.inputImageFile.absolutePath) }
    val error = MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (record.isBad) error else MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(record.createdAtMillis)),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (record.isAnnotated) stringResource(R.string.debug_annotated_badge)
                    else stringResource(R.string.debug_unannotated_badge),
                    color = if (record.isAnnotated) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            bitmap?.let {
                HandwritingPreview(
                    bitmap = it.asImageBitmap(),
                    size = 180.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.debug_handwriting_chosen) + ": " +
                    record.chosenCharacter.ifBlank { stringResource(R.string.debug_handwriting_none_chosen) },
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.debug_handwriting_model_top1) + ": " +
                    record.modelTop1.ifBlank { stringResource(R.string.debug_handwriting_no_candidates) },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.isAnnotated) {
                Text(
                    text = stringResource(R.string.debug_handwriting_correction) + ": " + record.correctedCharacter,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (record.candidates.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.debug_handwriting_candidates) + ": " +
                        record.candidates.joinToString(" ") { it.text },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (record.isAnnotated) {
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.debug_edit_annotation)) }
                } else {
                                    Button(
                                        onClick = onMarkGood,
                                        enabled = record.chosenCharacter.isNotBlank() || record.modelTop1.isNotBlank(),
                                    ) { Text(stringResource(R.string.debug_quick_good)) }
                    OutlinedButton(onClick = onMarkBad) { Text(stringResource(R.string.debug_bad_and_correct)) }
                }
            }
            if (record.isSynced) Text(stringResource(R.string.debug_synced_badge), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HandwritingEditor(
    record: DebugHandwritingRecord,
    initialIsBad: Boolean?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String, Boolean) -> Unit,
) {
    val bitmap = remember(record.id) { BitmapFactory.decodeFile(record.inputImageFile.absolutePath) }
    val baseline = record.chosenCharacter.ifBlank { record.modelTop1 }
    var correction by remember(record.id) {
        mutableStateOf(record.correctedCharacter.ifBlank { baseline })
    }
    var isBad by remember(record.id, initialIsBad) {
        mutableStateOf<Boolean?>(initialIsBad ?: if (record.isAnnotated) record.isBad else null)
    }
    var confirmDelete by remember(record.id) { mutableStateOf(false) }
    val canSave = isBad != null && correction.trim().isNotBlank() &&
        (isBad != true || correction.trim() != baseline.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.debug_handwriting_annotation_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            ) {
                bitmap?.let {
                    HandwritingPreview(
                        bitmap = it.asImageBitmap(),
                        size = 240.dp,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.debug_handwriting_chosen) + ": " +
                        record.chosenCharacter.ifBlank { stringResource(R.string.debug_handwriting_none_chosen) },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.debug_handwriting_candidates) + ": " +
                        record.candidates.joinToString(" ") { it.text }.ifBlank {
                            stringResource(R.string.debug_handwriting_no_candidates)
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = correction,
                    onValueChange = { correction = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.debug_handwriting_correction)) },
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isBad == false,
                        onClick = {
                            isBad = false
                            correction = baseline
                        },
                        label = { Text(stringResource(R.string.debug_mark_good)) },
                    )
                    FilterChip(
                        selected = isBad == true,
                        onClick = { isBad = true },
                        label = { Text(stringResource(R.string.debug_mark_bad)) },
                    )
                }
                if (isBad == true && correction.trim() == baseline.trim()) {
                    Text(
                        text = stringResource(R.string.debug_bad_needs_correction),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onSave(correction, requireNotNull(isBad)) }) {
                Text(stringResource(R.string.debug_save_annotation))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.debug_remove_recording), color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.debug_cancel)) }
            }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.debug_handwriting_remove_confirm_title)) },
            text = { Text(stringResource(R.string.debug_handwriting_remove_confirm_desc)) },
            confirmButton = { Button(onClick = onDelete) { Text(stringResource(R.string.debug_remove)) } },
            dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.debug_cancel)) } },
        )
    }
}

@Composable
private fun HandwritingPreview(
    bitmap: ImageBitmap,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .sizeIn(maxWidth = size)
            .aspectRatio(1f)
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.debug_handwriting_model_input),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
