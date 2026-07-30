package com.madeinhk.cantonesevoicekeyboard

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class PromptedSyncResult(val uploaded: Int, val failed: Int)

internal object PromptedHandwritingSync {
    private const val ROOT_NAME = "prompted_handwriting_eval"
    private const val VERSION_NAME = "v1"
    private const val WORK_NAME = "prompted-handwriting-eval-sync"

    fun enqueue(context: Context) {
        if (DebugHandwritingSettings.getSyncFolderUri(context) == null) return
        val request = OneTimeWorkRequestBuilder<PromptedHandwritingSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    suspend fun syncPending(context: Context, treeUri: Uri): PromptedSyncResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val root = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val collection = getOrCreateDirectory(resolver, treeUri, root, ROOT_NAME)
            val version = getOrCreateDirectory(resolver, treeUri, collection, VERSION_NAME)
            var uploaded = 0
            var failed = 0
            PromptedHandwritingStore.list(context)
                .filter { it.readyToSync && !it.synced }
                .forEach { record ->
                    val success = runCatching {
                        val metadata = org.json.JSONObject(
                            File(record.directory, "sample.json").readText(),
                        )
                        val writer = safeSegment(metadata.getString("writer_id"))
                        val date = safeSegment(metadata.getString("challenge_local_date"))
                        val writerDirectory = getOrCreateDirectory(resolver, treeUri, version, writer)
                        val dateDirectory = getOrCreateDirectory(
                            resolver,
                            treeUri,
                            writerDirectory,
                            date,
                        )
                        uploadIdempotently(resolver, treeUri, dateDirectory, record)
                    }.isSuccess
                    if (success) {
                        if (PromptedHandwritingStore.markSynced(context, record.id)) {
                            PromptedHandwritingStore.deletePayload(context, record.id)
                            uploaded++
                        } else {
                            failed++
                        }
                    } else {
                        failed++
                    }
                }
            PromptedSyncResult(uploaded, failed)
        }

    private fun uploadIdempotently(
        resolver: ContentResolver,
        treeUri: Uri,
        parent: Uri,
        record: PromptedHandwritingRecord,
    ) {
        val finalName = "${record.id}.zip"
        if (findChild(resolver, treeUri, parent, finalName) != null) return
        val partialName = "$finalName.part"
        findChild(resolver, treeUri, parent, partialName)?.let {
            DocumentsContract.deleteDocument(resolver, it)
        }
        val partial = DocumentsContract.createDocument(
            resolver,
            parent,
            "application/octet-stream",
            partialName,
        ) ?: error("Could not create $partialName")
        try {
            resolver.openOutputStream(partial, "w")?.use { output ->
                ZipOutputStream(output).use { zip ->
                    record.directory.listFiles().orEmpty()
                        .filter(File::isFile)
                        .sortedBy(File::getName)
                        .forEach { file ->
                            val entry = ZipEntry(file.name).apply { time = 0L }
                            zip.putNextEntry(entry)
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                }
            } ?: error("Could not write $partialName")
            DocumentsContract.renameDocument(resolver, partial, finalName)
                ?: error("Could not finalize $finalName")
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, partial) }
            throw error
        }
    }

    private fun getOrCreateDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        parent: Uri,
        name: String,
    ): Uri = findChild(resolver, treeUri, parent, name)
        ?: DocumentsContract.createDocument(
            resolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        )
        ?: error("Could not create directory $name")

    private fun findChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parent: Uri,
        name: String,
    ): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            }
        }
        return null
    }

    private fun safeSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

internal class PromptedHandwritingSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val folder = DebugHandwritingSettings.getSyncFolderUri(applicationContext)
            ?: return Result.success()
        return runCatching {
            PromptedHandwritingSync.syncPending(applicationContext, folder)
        }.fold(
            onSuccess = { if (it.failed == 0) Result.success() else Result.retry() },
            onFailure = { Result.retry() },
        )
    }
}
