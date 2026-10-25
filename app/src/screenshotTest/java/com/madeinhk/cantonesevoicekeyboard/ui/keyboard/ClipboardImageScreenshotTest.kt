package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

@PreviewTest
@Preview(name = "Image paste thumbnail", widthDp = 180, heightDp = 60, showBackground = true)
@Composable
fun ClipboardImageThumbnailScreenshot() = ImageChipPreview()

@PreviewTest
@Preview(name = "Image paste unavailable", widthDp = 180, heightDp = 60, showBackground = true)
@Composable
fun ClipboardImageUnavailableScreenshot() = ImageChipPreview(unavailable = true)

@PreviewTest
@Preview(name = "Sensitive image paste", widthDp = 180, heightDp = 60, showBackground = true)
@Composable
fun ClipboardImageSensitiveScreenshot() = ImageChipPreview(sensitive = true)

@Composable
private fun ImageChipPreview(sensitive: Boolean = false, unavailable: Boolean = false) {
    val thumbnail = remember {
        val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(94, 183, 227))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(255, 216, 96)
        canvas.drawCircle(95f, 24f, 15f, paint)
        paint.color = Color.rgb(45, 125, 81)
        canvas.drawOval(-30f, 48f, 160f, 145f, paint)
        bitmap.asImageBitmap()
    }
    val registry = remember { KeyboardKeyRegistry() }
    CantoneseVoiceKeyboardTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ClipboardToolbarChip(
                key = registerTextToolbarKeys(registry).clipboard,
                keyRegistry = registry,
                clipboard = ClipboardSuggestion(
                    token = 1L,
                    preview = null,
                    isSensitive = sensitive,
                    imageUri = if (sensitive) null else "content://test/image",
                    mimeType = "image/png",
                ),
                keyboardColors = BrandTheme.colors,
                thumbnail = thumbnail.takeUnless { unavailable },
            )
        }
    }
}
