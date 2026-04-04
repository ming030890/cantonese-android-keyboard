package com.example.cantonesevoiceinput

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ModelAssetInstaller {
    private const val ASSET_MODEL_DIR = "models/qwen3-asr-0.6b"

    private val REQUIRED_FILES = listOf(
        "model.safetensors",
        "config.json",
        "vocab.json",
        "merges.txt"
    )

    private val OPTIONAL_FILES = listOf(
        "generation_config.json"
    )

    fun isInstalled(modelDir: File): Boolean {
        return REQUIRED_FILES.all { name -> File(modelDir, name).exists() }
    }

    fun hasBundledModel(assets: AssetManager): Boolean {
        return REQUIRED_FILES.all { name ->
            val path = "$ASSET_MODEL_DIR/$name"
            runCatching { assets.open(path).close() }.isSuccess
        }
    }

    fun detectInstalledWeightFormat(modelDir: File): String {
        return detectWeightFormat(File(modelDir, "model.safetensors"))
    }

    fun ensureInstalled(context: Context): File {
        val modelDir = File(context.filesDir, MainActivity.MODEL_DIR_NAME)
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            error("Unable to create model directory: ${modelDir.absolutePath}")
        }

        for (name in REQUIRED_FILES) {
            copyAssetIfNeeded(context.assets, name, modelDir)
        }
        for (name in OPTIONAL_FILES) {
            copyAssetIfPresent(context.assets, name, modelDir)
        }
        return modelDir
    }

    private fun copyAssetIfPresent(assets: AssetManager, fileName: String, outputDir: File) {
        val assetPath = "$ASSET_MODEL_DIR/$fileName"
        if (runCatching { assets.open(assetPath).close() }.isFailure) {
            return
        }
        copyAssetIfNeeded(assets, fileName, outputDir)
    }

    private fun copyAssetIfNeeded(assets: AssetManager, fileName: String, outputDir: File) {
        val assetPath = "$ASSET_MODEL_DIR/$fileName"
        val outputFile = File(outputDir, fileName)
        val assetLength = assetLength(assets, assetPath)

        val shouldCopy = when {
            !outputFile.exists() -> true
            assetLength != null && outputFile.length() != assetLength -> true
            else -> false
        }

        if (!shouldCopy) {
            return
        }

        val tmpFile = File(outputDir, "$fileName.tmp")
        assets.open(assetPath).use { input ->
            tmpFile.outputStream().use { output ->
                input.copyTo(output, DEFAULT_BUFFER_BYTES)
            }
        }

        if (outputFile.exists() && !outputFile.delete()) {
            error("Unable to replace existing model file: ${outputFile.absolutePath}")
        }
        if (!tmpFile.renameTo(outputFile)) {
            error("Unable to finalize model file: ${outputFile.absolutePath}")
        }
    }

    private fun assetLength(assets: AssetManager, assetPath: String): Long? {
        return runCatching {
            assets.openFd(assetPath).use { fd -> fd.length }
        }.getOrNull()
    }

    private fun detectWeightFormat(modelFile: File): String {
        if (!modelFile.isFile) {
            return "missing"
        }

        return runCatching {
            modelFile.inputStream().buffered().use { input ->
                val headerPrefix = input.readNBytes(8)
                if (headerPrefix.size != 8) {
                    return@use "unknown"
                }

                val headerLength = ByteBuffer
                    .wrap(headerPrefix)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .long

                if (headerLength <= 0L || headerLength > MAX_SAFE_HEADER_BYTES.toLong()) {
                    return@use "unknown"
                }

                val headerBytes = input.readNBytes(headerLength.toInt())
                if (headerBytes.size != headerLength.toInt()) {
                    return@use "unknown"
                }

                val header = headerBytes.toString(Charsets.UTF_8)
                when {
                    "\"quantized\":\"q8_rowwise\"" in header -> "q8-int8"
                    "\"quantized\": \"q8_rowwise\"" in header -> "q8-int8"
                    else -> "bf16"
                }
            }
        }.getOrDefault("unknown")
    }

    private const val DEFAULT_BUFFER_BYTES = 1024 * 1024
    private const val MAX_SAFE_HEADER_BYTES = 8 * 1024 * 1024
}
