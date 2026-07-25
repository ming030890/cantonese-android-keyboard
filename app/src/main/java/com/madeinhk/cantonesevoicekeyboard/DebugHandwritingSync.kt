package com.madeinhk.cantonesevoicekeyboard

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DebugHandwritingSync {
    suspend fun syncPending(context: Context, folderTreeUri: Uri): DebugSyncResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                folderTreeUri,
                DocumentsContract.getTreeDocumentId(folderTreeUri)
            )
            var uploaded = 0
            var failed = 0
            DebugHandwritingStore.list(context)
                .filter { it.isAnnotated && !it.isSynced }
                .forEach { record ->
                    val success = runCatching {
                        val remoteDirectory = DocumentsContract.createDocument(
                            resolver,
                            rootDocumentUri,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            record.id
                        ) ?: error("Could not create destination folder")
                        record.directory.listFiles().orEmpty()
                            .filter(File::isFile)
                            .sortedBy(File::getName)
                            .forEach { file -> copyFile(resolver, remoteDirectory, file) }
                    }.isSuccess
                    if (success) {
                        if (!DebugHandwritingStore.delete(context, record.id)) {
                            DebugHandwritingStore.markSynced(context, record.id, true)
                        }
                        uploaded++
                    } else {
                        failed++
                    }
                }
            DebugSyncResult(uploaded, failed)
        }

    private fun copyFile(resolver: ContentResolver, parentUri: Uri, file: File) {
        val mimeType = when (file.extension.lowercase()) {
            "png" -> "image/png"
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
