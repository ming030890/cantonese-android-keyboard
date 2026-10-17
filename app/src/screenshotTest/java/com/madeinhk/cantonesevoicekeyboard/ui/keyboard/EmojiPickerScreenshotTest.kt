package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme
import com.madeinhk.cantonesevoicekeyboard.theme.CantoneseVoiceKeyboardTheme

@PreviewTest
@Preview(name = "Emoji search results", widthDp = 412, heightDp = 345)
@Preview(name = "Emoji search narrow", widthDp = 320, heightDp = 300)
@Composable
fun EmojiSearchScreenshot() = EmojiSearchPreview("heart")

@PreviewTest
@Preview(name = "Emoji search empty results", widthDp = 412, heightDp = 345)
@Composable
fun EmojiNoResultsScreenshot() = EmojiSearchPreview("qwerty")

@PreviewTest
@Preview(name = "Emoji picker dark", widthDp = 412, heightDp = 345)
@Composable
fun EmojiDarkScreenshot() = EmojiSearchPreview(null, dark = true)

@Composable
private fun EmojiSearchPreview(query: String?, dark: Boolean = false) {
    CantoneseVoiceKeyboardTheme(darkTheme = dark) {
        EmojiPickerPanel(
            modifier = Modifier.fillMaxSize().background(BrandTheme.colors.keyboardBackground).padding(4.dp),
            onEmojiPicked = {}, initialSearch = query,
        )
    }
}

@PreviewTest
@Preview(name = "Emoji search dark", widthDp = 412, heightDp = 345)
@Composable
fun EmojiDarkSearchScreenshot() = EmojiSearchPreview("heart", dark = true)
