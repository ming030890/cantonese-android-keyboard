package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode only while the chip is visible; never read sensitive clipboard content. */
@Composable
internal fun rememberClipboardThumbnail(clipboard: ClipboardSuggestion): ImageBitmap? {
    if (clipboard.isSensitive || clipboard.imageUri == null || LocalInspectionMode.current) return null
    val resolver = LocalContext.current.contentResolver
    // Reset immediately on a new clip, including when the provider reuses a URI.
    return key(clipboard.token, clipboard.imageUri, resolver) {
        val thumbnail by produceState<ImageBitmap?>(null) {
            value = withContext(Dispatchers.IO) {
                try {
                    val source = ImageDecoder.createSource(resolver, Uri.parse(clipboard.imageUri))
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val longestSide = maxOf(info.size.width, info.size.height)
                        if (longestSide > 128) {
                            decoder.setTargetSize(
                                (info.size.width * 128L / longestSide).toInt().coerceAtLeast(1),
                                (info.size.height * 128L / longestSide).toInt().coerceAtLeast(1),
                            )
                        }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }.asImageBitmap()
                } catch (_: Exception) {
                    // Providers can revoke access or remove a clip before decoding finishes.
                    null
                }
            }
        }
        thumbnail
    }
}
