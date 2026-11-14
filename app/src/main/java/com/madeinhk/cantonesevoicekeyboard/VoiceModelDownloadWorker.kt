package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/** Keeps an opt-in queued across process death, without relaxing the network constraint. */
class VoiceModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!ModelAssetPack.voiceDownloadEnabled(applicationContext)) return Result.success()
        try {
            ModelAssetPack.beginDownload(applicationContext, id.toString())
            val result = withTimeout(TimeUnit.MINUTES.toMillis(9)) {
                ModelAssetPack.downloadStatusFlow(applicationContext).first {
                    it == ModelDownloadStatus.Ready || it is ModelDownloadStatus.Failed ||
                        it == ModelDownloadStatus.RequiresConfirmation
                }
            }
            return if (result is ModelDownloadStatus.Failed) Result.retry() else Result.success()
        } catch (_: TimeoutCancellationException) {
            return Result.retry()
        } finally {
            withContext(NonCancellable) { ModelAssetPack.endDownload(applicationContext, id.toString()) }
        }
    }

    companion object {
        private const val WORK_NAME = "voice-model-download"
        internal fun enqueue(context: Context, retry: Boolean) {
            val request = OneTimeWorkRequestBuilder<VoiceModelDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME,
                if (retry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
        internal fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }
}
