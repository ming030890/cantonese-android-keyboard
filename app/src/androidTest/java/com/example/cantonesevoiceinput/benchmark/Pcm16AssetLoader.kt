package com.example.cantonesevoiceinput.benchmark

import android.content.Context
import java.io.FileNotFoundException

object Pcm16AssetLoader {
    fun loadMono16kPcmFloatArray(context: Context, assetName: String): FloatArray {
        val bytes = context.assets.open(assetName).use { input -> input.readBytes() }
        check(bytes.size % 2 == 0) { "PCM asset must contain 16-bit samples: $assetName" }

        val samples = FloatArray(bytes.size / 2)
        var byteIndex = 0
        var sampleIndex = 0
        while (byteIndex < bytes.size) {
            val low = bytes[byteIndex].toInt() and 0xff
            val high = bytes[byteIndex + 1].toInt()
            val sample = (high shl 8) or low
            samples[sampleIndex] = sample.toShort() / 32768.0f
            byteIndex += 2
            sampleIndex += 1
        }
        return samples
    }

    fun isPresent(context: Context, assetName: String): Boolean {
        val normalized = assetName.substringAfterLast('/')
        val parent = assetName.substringBeforeLast('/', missingDelimiterValue = "")
        return runCatching {
            val names = context.assets.list(parent)
                ?: throw FileNotFoundException("Missing asset directory: $parent")
            names.any { it == normalized }
        }.getOrDefault(false)
    }
}
