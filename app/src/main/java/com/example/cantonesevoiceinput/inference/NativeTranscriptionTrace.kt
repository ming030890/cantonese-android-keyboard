package com.example.cantonesevoiceinput.inference

data class NativeTranscriptionTrace(
    val text: String,
    val sampleCount: Int,
    val inputCopyNs: Long,
    val nativeInferenceNs: Long,
    val totalNs: Long
) {
    val jniOverheadNs: Long
        get() = (totalNs - inputCopyNs - nativeInferenceNs).coerceAtLeast(0L)
}
