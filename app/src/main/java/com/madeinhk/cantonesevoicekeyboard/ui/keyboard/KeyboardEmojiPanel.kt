package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView

@Composable
fun EmojiPickerPanel(
    modifier: Modifier = Modifier,
    onEmojiPicked: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    if (LocalInspectionMode.current) {
        EmojiPreviewGrid(modifier = modifier)
        return
    }

    AndroidView(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        factory = { context ->
            EmojiPickerView(context).apply {
                emojiGridColumns = 8
                setOnEmojiPickedListener { item ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEmojiPicked(item.emoji)
                }
            }
        },
        update = { picker ->
            picker.setOnEmojiPickedListener { item ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onEmojiPicked(item.emoji)
            }
        },
    )
}

@Composable
private fun EmojiPreviewGrid(modifier: Modifier = Modifier) {
    val emojis = listOf(
        "😀", "😂", "😍", "🥳", "😎", "😭", "😡", "👍",
        "🙏", "👏", "💪", "🔥", "✨", "❤️", "🎉", "✅",
        "🍜", "☕", "🚇", "🏠", "📍", "💬", "🔎", "⭐",
    )
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        emojis.chunked(8).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(34.dp),
                    )
                }
            }
        }
    }
}
