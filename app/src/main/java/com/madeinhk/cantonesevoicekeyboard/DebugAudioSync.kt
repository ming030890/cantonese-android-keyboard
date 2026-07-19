package com.madeinhk.cantonesevoicekeyboard

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DebugSyncResult(val uploaded: Int, val failed: Int)

object DebugAudioSync {
    suspend fun syncPending(context: Context, folderTreeUri: Uri): DebugSyncResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                folderTreeUri,
                DocumentsContract.getTreeDocumentId(folderTreeUri)
            )
            var uploaded = 0
            var failed = 0

            DebugAudioStore.list(context)
                .filter { it.isAnnotated && !it.isSynced }
                .forEach { recording ->
                val success = runCatching {
                    val remoteDirectory = DocumentsContract.createDocument(
                        resolver,
                        rootDocumentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        recording.id
                    ) ?: error("Could not create destination folder")
                    filesFor(recording).forEach { file ->
                        copyFile(resolver, remoteDirectory, file)
                    }
                }.isSuccess
                if (success) {
                    // Only remove the exact local recording after every remote file has been
                    // created and its output stream has closed successfully.
                    if (!DebugAudioStore.deleteRecording(context, recording.id)) {
                        DebugAudioStore.markSynced(context, recording.id, true)
                    }
                    uploaded++
                } else {
                    failed++
                }
            }
            DebugSyncResult(uploaded, failed)
        }

    private fun filesFor(recording: DebugRecording): List<File> =
        if (recording.directory.name == recording.id) {
            recording.directory.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName)
        } else {
            listOf(recording.inputAudioFile)
        }

    private fun copyFile(resolver: ContentResolver, parentUri: Uri, file: File) {
        val mimeType = when (file.extension.lowercase()) {
            "wav" -> "audio/wav"
            "json", "jsonl" -> "application/json"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
        val destination = DocumentsContract.createDocument(resolver, parentUri, mimeType, file.name)
            ?: error("Could not create ${file.name}")
        resolver.openOutputStream(destination, "w")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Could not open ${file.name}")
    }
}
