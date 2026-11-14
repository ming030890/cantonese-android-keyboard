package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.madeinhk.cantonesevoicekeyboard.hwr.StrokePoint
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

@PreviewTest
@Preview(name = "Handwriting ink light", widthDp = 412, heightDp = 240)
@Composable
fun HandwritingInkLight() = InkSample(false)

@PreviewTest
@Preview(name = "Handwriting ink dark", widthDp = 412, heightDp = 240)
@Composable
fun HandwritingInkDark() = InkSample(true)

@Composable
private fun InkSample(dark: Boolean) {
    val density = LocalDensity.current.density
    val strokes = remember(density) {
        // A multi-stroke character, a flowing curve and a dot exercise the real renderer.
        listOf(
            listOf(133f to 42f, 142f to 48f, 148f to 57f),
            listOf(112f to 80f, 145f to 77f, 144f to 122f, 143f to 172f, 137f to 183f, 124f to 174f),
            listOf(96f to 112f, 120f to 108f, 110f to 134f, 93f to 153f, 72f to 169f),
            listOf(185f to 74f, 169f to 97f, 151f to 113f),
            listOf(151f to 99f, 171f to 132f, 195f to 153f, 214f to 163f),
            listOf(253f to 130f, 268f to 97f, 289f to 83f, 309f to 88f, 321f to 108f,
                316f to 129f, 297f to 141f, 277f to 139f, 268f to 126f,
                280f to 113f, 301f to 122f, 319f to 148f, 340f to 159f),
            listOf(345f to 79f),
        ).map { stroke -> stroke.mapIndexed { i, (x, y) -> StrokePoint(x * density, y * density, i.toLong()) } }
    }
    CantoneseVoiceKeyboardTheme(darkTheme = dark, dynamicColor = false) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            HandwritingInk(strokes, Modifier.fillMaxSize())
        }
    }
}
