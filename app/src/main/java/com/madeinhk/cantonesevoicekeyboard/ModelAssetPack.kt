package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManager
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
 * High-level download state of the model, suitable for driving UI.
 *
 * The model is split across two fast-follow Play Asset Delivery packs; this
 * state aggregates both packs into a single progress signal so the UI can
 * treat the model as one logical resource.
 */
sealed interface ModelDownloadStatus {
    data object NotStarted : ModelDownloadStatus
    data object Pending : ModelDownloadStatus
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadStatus {
        val fraction: Float =
            if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
        val percent: Int = (fraction * 100f).toInt()
    }
    data object Installing : ModelDownloadStatus
    data object Ready : ModelDownloadStatus
    data class Failed(val errorCode: Int) : ModelDownloadStatus
}

/**
 * Resolves the Qwen ASR model directory. The model ships as two sharded
 * fast-follow Play Asset Delivery packs ([PACK_A], [PACK_B]) so each stays
 * under Play's 512 MiB per-pack ceiling.
 *
 * Files in each pack are mmap'd directly by the native loader from their
 * Play-managed locations; we never copy them. To present both packs as one
 * logical model directory (as the native `MultiSafetensors::open` expects),
 * we symlink every file from each pack's `qwen-asr-m4-lmhead-int8/` into a single
 * staging directory in internal storage. Symlinks consume only inodes — zero
 * byte duplication.
 *
 * The staging step is idempotent and fast (~four `symlink` syscalls), so it
 * re-runs on every resolve to absorb pack-update path changes.
 */
object ModelAssetPack {
    const val PACK_A = "model_pack_a"
    const val PACK_B = "model_pack_b"
    const val MODEL_DIR_NAME = "qwen-asr-m4-lmhead-int8"
    private val PACKS = listOf(PACK_A, PACK_B)
    private const val TAG = "ModelAssetPack"

    /** True if both packs are already downloaded and the staged dir can be built. */
    fun isModelAvailable(context: Context): Boolean = stageModelDir(context) != null

    /**
     * Emits live aggregated [ModelDownloadStatus] for the model. Combines the
     * worst-status of the two packs (any failure -> Failed, any pending ->
     * Pending, otherwise sums of download progress) and emits Ready only once
     * both packs are extracted on disk.
     */
    fun downloadStatusFlow(context: Context): Flow<ModelDownloadStatus> = callbackFlow {
        val appContext = context.applicationContext
        val manager = AssetPackManagerFactory.getInstance(appContext)

        if (stageModelDir(appContext) != null) {
            trySend(ModelDownloadStatus.Ready)
            close()
            return@callbackFlow
        }

        val latest = HashMap<String, AssetPackState>()

        fun emitCombined() {
            val combined = combine(appContext, latest)
            Log.i(TAG, "downloadStatusFlow combined=$combined latest=${latest.values.joinToString { describeState(it) }}")
            trySend(combined)
        }

        val listener = AssetPackStateUpdateListener { state ->
            if (state.name() in PACKS) {
                latest[state.name()] = state
                emitCombined()
            }
        }
        manager.registerListener(listener)

        manager.getPackStates(PACKS)
            .addOnSuccessListener { states ->
                PACKS.forEach { p -> states.packStates()[p]?.let { latest[p] = it } }
                Log.i(TAG, "getPackStates success: ${latest.values.joinToString { describeState(it) }}")
                emitCombined()
                manager.fetch(PACKS)
            }
            .addOnFailureListener {
                Log.w(TAG, "getPackStates failed; requesting fetch anyway", it)
                manager.fetch(PACKS)
            }

        awaitClose { manager.unregisterListener(listener) }
    }

    /**
     * Returns the staged model directory, fetching both asset packs first if
     * necessary. Suspends until both packs finish downloading. Returns `null`
     * if either pack could not be made available.
     */
    suspend fun resolveModelDir(context: Context): File? {
        stageModelDir(context)?.let {
            Log.i(TAG, "resolveModelDir: staged model already available at ${it.absolutePath}")
            return it
        }

        val manager = AssetPackManagerFactory.getInstance(context.applicationContext)
        val ready = awaitBothPacks(manager)
        Log.i(TAG, "resolveModelDir: awaitBothPacks ready=$ready")
        if (!ready) return null
        return stageModelDir(context).also {
            Log.i(TAG, "resolveModelDir: stage after fetch result=${it?.absolutePath}")
        }
    }

    private suspend fun awaitBothPacks(manager: AssetPackManager): Boolean =
        suspendCancellableCoroutine { cont ->
            val completed = HashSet<String>()
            val listener = object : AssetPackStateUpdateListener {
                override fun onStateUpdate(state: AssetPackState) {
                    if (state.name() !in PACKS || cont.isCompleted) return
                    when (state.status()) {
                        AssetPackStatus.COMPLETED -> {
                            Log.i(TAG, "awaitBothPacks completed: ${describeState(state)}")
                            completed.add(state.name())
                            if (completed.containsAll(PACKS)) {
                                manager.unregisterListener(this)
                                cont.resume(true)
                            }
                        }
                        AssetPackStatus.FAILED, AssetPackStatus.CANCELED -> {
                            Log.e(TAG, "Pack '${state.name()}' ended: status=${state.status()}, errorCode=${state.errorCode()}")
                            manager.unregisterListener(this)
                            cont.resume(false)
                        }
                        else -> Log.i(TAG, "awaitBothPacks update: ${describeState(state)}")
                    }
                }
            }
            manager.registerListener(listener)
            cont.invokeOnCancellation { manager.unregisterListener(listener) }
            manager.fetch(PACKS).addOnFailureListener { e ->
                if (!cont.isCompleted) {
                    Log.e(TAG, "Failed to start asset pack fetch", e)
                    manager.unregisterListener(listener)
                    cont.resume(false)
                }
            }
        }

    /**
     * If both packs are extracted on disk, builds (or refreshes) a single
     * staging dir in internal storage containing symlinks to every file in
     * each pack's `qwen-asr-m4-lmhead-int8/` directory, and returns it.
     *
     * Returns null if either pack is not yet available.
     */
    private fun stageModelDir(context: Context): File? {
        val appContext = context.applicationContext
        val manager = AssetPackManagerFactory.getInstance(appContext)

        val packDirs = PACKS.map { pack ->
            val location = manager.getPackLocation(pack)
            val assetsPath = location?.assetsPath()
            Log.i(TAG, "stageModelDir: pack=$pack location=${location != null} assetsPath=$assetsPath")
            if (assetsPath == null) return null
            val dir = File(assetsPath, MODEL_DIR_NAME)
            if (!dir.exists() || !dir.isDirectory) {
                Log.w(TAG, "stageModelDir: missing model dir for $pack: ${dir.absolutePath}")
                return null
            }
            dir
        }

        val staged = File(appContext.filesDir, MODEL_DIR_NAME)
        if (!staged.exists() && !staged.mkdirs()) {
            Log.e(TAG, "stageModelDir: failed to create staged dir ${staged.absolutePath}")
            return null
        }

        // Rebuild symlinks every time: cheap, and absorbs pack-update path
        // changes when Play swaps in a new pack version.
        for (pack in packDirs) {
            val files = pack.listFiles() ?: continue
            for (src in files) {
                if (!src.isFile) continue
                val link = File(staged, src.name)
                try {
                    // Always delete the link first. link.delete() successfully deletes
                    // both real files and broken/valid symbolic links. We must delete it
                    // unconditionally because link.exists() returns false for broken symlinks,
                    // which would cause delete() to be skipped and Os.symlink to fail with EEXIST.
                    link.delete()
                    Os.symlink(src.absolutePath, link.absolutePath)
                } catch (e: ErrnoException) {
                    Log.e(TAG, "symlink ${src.name} failed", e)
                    return null
                }
            }
        }
        return staged
    }

    private fun describeState(state: AssetPackState): String =
        "${state.name()} status=${state.status()} bytes=${state.bytesDownloaded()}/${state.totalBytesToDownload()} error=${state.errorCode()}"

    private fun combine(context: Context, latest: Map<String, AssetPackState>): ModelDownloadStatus {
        if (stageModelDir(context) != null) return ModelDownloadStatus.Ready
        if (latest.size < PACKS.size) return ModelDownloadStatus.NotStarted

        latest.values.firstOrNull {
            it.status() == AssetPackStatus.FAILED || it.status() == AssetPackStatus.CANCELED
        }?.let { return ModelDownloadStatus.Failed(it.errorCode()) }

        if (latest.values.any { it.status() == AssetPackStatus.TRANSFERRING }) {
            return ModelDownloadStatus.Installing
        }
        if (latest.values.any {
                it.status() == AssetPackStatus.PENDING ||
                    it.status() == AssetPackStatus.WAITING_FOR_WIFI ||
                    it.status() == AssetPackStatus.REQUIRES_USER_CONFIRMATION
            }) {
            return ModelDownloadStatus.Pending
        }
        if (latest.values.all { it.status() == AssetPackStatus.DOWNLOADING || it.status() == AssetPackStatus.COMPLETED }) {
            val downloaded = latest.values.sumOf { it.bytesDownloaded() }
            val total = latest.values.sumOf { it.totalBytesToDownload() }
            return ModelDownloadStatus.Downloading(downloaded, total)
        }
        return ModelDownloadStatus.NotStarted
    }
}
