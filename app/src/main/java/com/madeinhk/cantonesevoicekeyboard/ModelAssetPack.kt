package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * High-level download state of the model, suitable for driving UI.
 *
 * The model is split across five on-demand Play Asset Delivery packs; this
 * state aggregates all packs into a single progress signal so the UI can
 * treat the model as one logical resource.
 */
sealed interface ModelDownloadStatus {
    data object NotStarted : ModelDownloadStatus
    data object WaitingForWifi : ModelDownloadStatus
    data object RequiresConfirmation : ModelDownloadStatus
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
 * Resolves the Qwen ASR model directory. The model ships as five sharded
 * on-demand Play Asset Delivery packs ([PACK_A] through [PACK_E]) so each stays
 * under Play's 512 MiB per-pack ceiling.
 *
 * Files in each pack are mmap'd directly by the native loader from their
 * Play-managed locations; we never copy them. To present all packs as one
 * logical model directory (as the native `MultiSafetensors::open` expects),
 * we symlink every file from each pack's `qwen-asr-m4-lmhead-int8/` into a single
 * staging directory in internal storage. Symlinks consume only inodes — zero
 * byte duplication.
 *
 * The staging step is idempotent and fast (a handful of `symlink` syscalls), so it
 * re-runs on every resolve to absorb pack-update path changes.
 */
object ModelAssetPack {
    const val PACK_A = "model_pack_a"
    const val PACK_B = "model_pack_b"
    const val PACK_C = "model_pack_c"
    const val PACK_D = "model_pack_d"
    const val PACK_E = "model_pack_e"
    const val MODEL_DIR_NAME = "qwen-asr-m4-lmhead-int8"
    private val PACKS = listOf(PACK_A, PACK_B, PACK_C, PACK_D, PACK_E)
    private val REQUIRED_MODEL_FILES = (1..5).map { "model-%05d-of-00005.safetensors".format(it) } +
        listOf("vocab.json", "merges.txt")
    private const val TAG = "ModelAssetPack"


    /** True if all packs are already downloaded and the staged dir can be built. */
    fun isModelAvailable(context: Context): Boolean = stageModelDir(context) != null

    private val status = kotlinx.coroutines.flow.MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.NotStarted)
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var initialized = false
    private var requested = false
    private var fetchStarted = false
    private var downloadOwner: String? = null
    private var lastAllowed = false
    private val latest = HashMap<String, AssetPackState>()

    fun hasUnmeteredNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    internal fun downloadAllowed(enabled: Boolean, permission: Boolean, unmetered: Boolean) =
        enabled && permission && unmetered

    internal fun voiceDownloadEnabled(context: Context): Boolean =
        KeyboardSettings.isVoiceInputEnabled(context) &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun allowed(context: Context): Boolean = downloadAllowed(
        KeyboardSettings.isVoiceInputEnabled(context),
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED,
        hasUnmeteredNetwork(context),
    )

    /** A process-wide listener also guards downloads after the setup screen closes. */
    private fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val manager = AssetPackManagerFactory.getInstance(context)
        manager.registerListener { state ->
            if (state.name() in PACKS) mainHandler.post {
                latest[state.name()] = state
                reconcile(context)
            }
        }
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                mainHandler.post { reconcile(context) }
            }
            override fun onLost(network: android.net.Network) {
                mainHandler.post { reconcile(context) }
            }
        })
        manager.getPackStates(PACKS).addOnSuccessListener { states ->
            latest.putAll(states.packStates())
            reconcile(context)
        }.addOnFailureListener { reconcile(context) }
    }

    private fun reconcile(context: Context) {
        val manager = AssetPackManagerFactory.getInstance(context)
        val enabled = KeyboardSettings.isVoiceInputEnabled(context)
        val permitted = allowed(context)
        if (!permitted) {
            // PAD also pauses large downloads on loss of Wi-Fi. Explicitly cancel here
            // to respect metered Wi-Fi and a voice opt-out, too. Never consent to mobile data.
            if (fetchStarted || lastAllowed) manager.cancel(PACKS)
            fetchStarted = false
        }
        lastAllowed = permitted
        status.value = when {
            !enabled -> ModelDownloadStatus.NotStarted
            stageModelDir(context) != null -> ModelDownloadStatus.Ready
            !permitted && requested -> ModelDownloadStatus.WaitingForWifi
            else -> combineStates(latest)
        }
        if (requested && permitted && downloadOwner != null && !fetchStarted && status.value != ModelDownloadStatus.Ready) {
            fetchStarted = true
            status.value = ModelDownloadStatus.Pending
            val owner = downloadOwner
            manager.fetch(PACKS).addOnSuccessListener { states ->
                if (downloadOwner != owner) return@addOnSuccessListener
                latest.putAll(states.packStates())
                // Recheck after asynchronous fetch: a setting/network may have changed.
                reconcile(context)
            }.addOnFailureListener { error ->
                if (downloadOwner != owner) return@addOnFailureListener
                if (allowed(context)) {
                    val code = (error as? com.google.android.play.core.assetpacks.AssetPackException)?.errorCode
                        ?: com.google.android.play.core.assetpacks.model.AssetPackErrorCode.INTERNAL_ERROR
                    status.value = ModelDownloadStatus.Failed(code)
                } else reconcile(context)
            }
        }
    }

    fun requestDownload(context: Context, retry: Boolean = false) {
        val app = context.applicationContext
        mainHandler.post {
            initialize(app)
            requested = voiceDownloadEnabled(app)
            reconcile(app)
            if (requested && status.value != ModelDownloadStatus.Ready) {
                VoiceModelDownloadWorker.enqueue(app, retry)
            }
        }
    }

    fun cancelDownload(context: Context) {
        val app = context.applicationContext
        mainHandler.post {
            requested = false
            downloadOwner = null
            VoiceModelDownloadWorker.cancel(app)
            AssetPackManagerFactory.getInstance(app).cancel(PACKS)
            fetchStarted = false
            status.value = ModelDownloadStatus.NotStarted
        }
    }

    internal suspend fun beginDownload(context: Context, owner: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            initialize(context)
            downloadOwner = owner
            requested = voiceDownloadEnabled(context)
            fetchStarted = false
            reconcile(context)
        }

    internal suspend fun endDownload(context: Context, owner: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            if (downloadOwner == owner) {
                downloadOwner = null
                fetchStarted = false
                if (status.value != ModelDownloadStatus.Ready &&
                    status.value != ModelDownloadStatus.RequiresConfirmation && status.value !is ModelDownloadStatus.Failed) {
                    AssetPackManagerFactory.getInstance(context).cancel(PACKS)
                }
            }
        }

    /** Observation alone never starts a download. */
    fun downloadStatusFlow(context: Context, requestDownload: Boolean = false): Flow<ModelDownloadStatus> {
        val app = context.applicationContext
        mainHandler.post { initialize(app); reconcile(app) }
        if (requestDownload) requestDownload(app)
        return status
    }

    /** Wait for the policy-controlled download; never fetch directly from a model loader. */
    suspend fun resolveModelDir(context: Context): File? {
        if (!KeyboardSettings.isVoiceInputEnabled(context)) return null
        stageModelDir(context)?.let { return it }
        val result = downloadStatusFlow(context, requestDownload = true).first { state ->
            state is ModelDownloadStatus.Ready || state is ModelDownloadStatus.Failed ||
                !KeyboardSettings.isVoiceInputEnabled(context)
        }
        return if (result == ModelDownloadStatus.Ready) stageModelDir(context) else null
    }

    /**
     * If all packs are extracted on disk, builds (or refreshes) a single
     * staging dir in internal storage containing symlinks to every file in
     * each pack's `qwen-asr-m4-lmhead-int8/` directory, and returns it.
     *
     * Returns null if either pack is not yet available.
     */
    @Synchronized
    private fun stageModelDir(context: Context): File? {
        val appContext = context.applicationContext
        val manager = AssetPackManagerFactory.getInstance(appContext)

        val allLocations = runCatching { manager.packLocations }.getOrNull() ?: emptyMap()
        val packDirs = PACKS.map { pack ->
            val location = manager.getPackLocation(pack) ?: allLocations[pack]
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

        // Clean out any stale or orphaned symlinks from previous model releases
        // (e.g. migrating from 2 shards to 5 shards) so broken symlinks never
        // corrupt MultiSafetensors loading.
        staged.list()?.forEach { name ->
            File(staged, name).delete()
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

        // Verify every required model shard and metadata file is present,
        // resolves to a real target, and is non-empty.
        val missingOrEmpty = REQUIRED_MODEL_FILES.filter { filename ->
            val file = File(staged, filename)
            !file.exists() || file.length() <= 0L
        }
        if (missingOrEmpty.isNotEmpty()) {
            Log.w(TAG, "stageModelDir: incomplete or invalid model parts: $missingOrEmpty")
            return null
        }

        return staged
    }

    internal fun combineStates(latest: Map<String, AssetPackState>): ModelDownloadStatus {
        latest.values.firstOrNull {
            it.status() == AssetPackStatus.FAILED || it.status() == AssetPackStatus.CANCELED
        }?.let { return ModelDownloadStatus.Failed(it.errorCode()) }

        if (latest.values.any { it.status() == AssetPackStatus.REQUIRES_USER_CONFIRMATION }) {
            return ModelDownloadStatus.RequiresConfirmation
        }
        if (latest.values.any { it.status() == AssetPackStatus.WAITING_FOR_WIFI }) {
            return ModelDownloadStatus.WaitingForWifi
        }
        if (latest.size < PACKS.size) return ModelDownloadStatus.NotStarted
        if (latest.values.any { it.status() == AssetPackStatus.TRANSFERRING }) {
            return ModelDownloadStatus.Installing
        }
        if (latest.values.any {
                it.status() == AssetPackStatus.PENDING
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
