package com.example.cantonesevoicekeyboard

import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * High-level download state of the model asset pack, suitable for driving UI.
 */
sealed interface ModelDownloadStatus {
    /** No download started yet (e.g. just queried, fetch about to begin). */
    data object NotStarted : ModelDownloadStatus

    /** Queued / waiting (pending, waiting for Wi-Fi, awaiting user confirmation). */
    data object Pending : ModelDownloadStatus

    /** Actively downloading bytes from Play. */
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadStatus {
        /** Download completion in the range 0f..1f (0f if total size is unknown). */
        val fraction: Float =
            if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

        /** Whole-number percentage 0..100. */
        val percent: Int = (fraction * 100f).toInt()
    }

    /** Downloaded; being unpacked/installed onto the file system. */
    data object Installing : ModelDownloadStatus

    /** Pack is on disk and ready to use. */
    data object Ready : ModelDownloadStatus

    /** Download failed or was cancelled. [errorCode] is the Play error code. */
    data class Failed(val errorCode: Int) : ModelDownloadStatus
}

/**
 * Resolves the Qwen ASR model directory, which ships as a fast-follow
 * Play Asset Delivery pack ([PACK_NAME]).
 *
 * Fast-follow packs download automatically right after the app is installed
 * from Play, so by the time the keyboard is first used the pack is usually
 * already on disk. If it is not yet available, [resolveModelDir] requests a
 * fetch and suspends until it completes.
 *
 * For local testing, install the app with `bundletool ... --local-testing`
 * (see `deploy.sh`); that path makes the pack resolvable exactly as it would
 * be in production, with no sideloading required.
 */
object ModelAssetPack {
    const val PACK_NAME = "model_pack"
    const val MODEL_DIR_NAME = "qwen-asr-int8dec"
    private const val TAG = "ModelAssetPack"

    /** True if the pack is already downloaded and its files are on disk. */
    fun isModelAvailable(context: Context): Boolean = locateModelDir(context) != null

    /**
     * Emits live [ModelDownloadStatus] updates for the model pack, suitable for
     * showing download progress in the UI.
     *
     * On collection it seeds the current state, ensures a fetch is running (so a
     * fast-follow pack that hasn't arrived yet starts downloading), and then
     * streams progress until the pack is [ModelDownloadStatus.Ready] or fails.
     * Unregisters its listener automatically when collection stops.
     */
    fun downloadStatusFlow(context: Context): Flow<ModelDownloadStatus> = callbackFlow {
        val appContext = context.applicationContext
        val manager = AssetPackManagerFactory.getInstance(appContext)

        // Fast path: already extracted on disk.
        if (locateModelDir(appContext) != null) {
            trySend(ModelDownloadStatus.Ready)
            close()
            return@callbackFlow
        }

        val listener = AssetPackStateUpdateListener { state ->
            if (state.name() == PACK_NAME) {
                trySend(state.toDownloadStatus())
            }
        }
        manager.registerListener(listener)

        // Seed the current state, then make sure a fetch is in flight so progress
        // events actually start flowing.
        manager.getPackStates(listOf(PACK_NAME))
            .addOnSuccessListener { states ->
                states.packStates()[PACK_NAME]?.let { trySend(it.toDownloadStatus()) }
                manager.fetch(listOf(PACK_NAME))
            }
            .addOnFailureListener {
                Log.w(TAG, "getPackStates failed; requesting fetch anyway", it)
                manager.fetch(listOf(PACK_NAME))
            }

        awaitClose { manager.unregisterListener(listener) }
    }

    private fun AssetPackState.toDownloadStatus(): ModelDownloadStatus = when (status()) {
        AssetPackStatus.COMPLETED -> ModelDownloadStatus.Ready
        AssetPackStatus.DOWNLOADING ->
            ModelDownloadStatus.Downloading(bytesDownloaded(), totalBytesToDownload())
        AssetPackStatus.TRANSFERRING -> ModelDownloadStatus.Installing
        AssetPackStatus.PENDING,
        AssetPackStatus.WAITING_FOR_WIFI,
        AssetPackStatus.REQUIRES_USER_CONFIRMATION -> ModelDownloadStatus.Pending
        AssetPackStatus.FAILED,
        AssetPackStatus.CANCELED -> ModelDownloadStatus.Failed(errorCode())
        else -> ModelDownloadStatus.NotStarted
    }

    private fun locateModelDir(context: Context): File? {
        val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
        val assetsPath = manager.getPackLocation(PACK_NAME)?.assetsPath() ?: return null
        val dir = File(assetsPath, MODEL_DIR_NAME)
        return dir.takeIf { it.exists() && it.isDirectory }
    }

    /**
     * Returns the model directory, fetching the asset pack first if necessary.
     * Suspends until the pack finishes downloading. Returns `null` if the pack
     * could not be made available (e.g. download failed/cancelled).
     */
    suspend fun resolveModelDir(context: Context): File? {
        locateModelDir(context)?.let { return it }

        val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
        return suspendCancellableCoroutine { cont ->
            val listener = object : AssetPackStateUpdateListener {
                override fun onStateUpdate(state: AssetPackState) {
                    if (state.name() != PACK_NAME || cont.isCompleted) return
                    when (state.status()) {
                        AssetPackStatus.COMPLETED -> {
                            manager.unregisterListener(this)
                            cont.resume(locateModelDir(context))
                        }
                        AssetPackStatus.FAILED, AssetPackStatus.CANCELED -> {
                            Log.e(
                                TAG,
                                "Asset pack '$PACK_NAME' download ended: status=${state.status()}, errorCode=${state.errorCode()}"
                            )
                            manager.unregisterListener(this)
                            cont.resume(null)
                        }
                        else -> {
                            Log.i(
                                TAG,
                                "Asset pack '$PACK_NAME' status=${state.status()} " +
                                    "(${state.bytesDownloaded()}/${state.totalBytesToDownload()} bytes)"
                            )
                        }
                    }
                }
            }
            manager.registerListener(listener)
            cont.invokeOnCancellation { manager.unregisterListener(listener) }
            manager.fetch(listOf(PACK_NAME)).addOnFailureListener { e ->
                if (!cont.isCompleted) {
                    Log.e(TAG, "Failed to start asset pack '$PACK_NAME' fetch", e)
                    manager.unregisterListener(listener)
                    cont.resume(null)
                }
            }
        }
    }
}
