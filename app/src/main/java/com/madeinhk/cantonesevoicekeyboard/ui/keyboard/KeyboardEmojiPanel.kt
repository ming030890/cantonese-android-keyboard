package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.emoji2.emojipicker.R as EmojiR
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madeinhk.cantonesevoicekeyboard.theme.BrandTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val emojiCategories = listOf(
    "recent" to "◷", "emotions" to "☺", "people" to "♙",
    "animals_nature" to "❀", "food_drink" to "♨", "travel_places" to "✈",
    "activity" to "⚽", "objects" to "♧", "symbols" to "♡", "flags" to "⚑",
)
private val categoryIcons = listOf(
    EmojiR.drawable.quantum_gm_ic_access_time_filled_vd_theme_24,
    EmojiR.drawable.gm_filled_emoji_emotions_vd_theme_24,
    EmojiR.drawable.gm_filled_emoji_people_vd_theme_24,
    EmojiR.drawable.gm_filled_emoji_nature_vd_theme_24,
    EmojiR.drawable.gm_filled_emoji_food_beverage_vd_theme_24,
    EmojiR.drawable.gm_filled_emoji_transportation_vd_theme_24,
    EmojiR.drawable.gm_filled_emoji_events_vd_theme_24,
    EmojiR.drawable.gm_filled_emoji_objects_vd_theme_24,
    EmojiR.drawable.gm_filled_emoji_symbols_vd_theme_24,
    EmojiR.drawable.gm_filled_flag_vd_theme_24,
)
private val categoryNames = listOf(
    "Recent emoji", "Smileys and emotions", "People", "Animals and nature", "Food and drink",
    "Travel and places", "Activities", "Objects", "Symbols", "Flags",
)

@Composable
fun EmojiPickerPanel(
    modifier: Modifier = Modifier,
    onEmojiPicked: (String) -> Unit,
    onBackToKeyboard: () -> Unit = {},
    onDelete: () -> Unit = {},
    // Also used by previews to render exactly the production search UI.
    initialSearch: String? = null,
) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val haptics = LocalHapticFeedback.current
    val catalog by produceState<EmojiCatalog?>(null, context) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("emoji/catalog.tsv").bufferedReader().use {
                EmojiCatalog.read(it.lineSequence())
            }
        }
    }
    // Layoutlib does not run asynchronous effects. It reads the same production catalogue.
    val available = if (preview) remember(context) {
        context.assets.open("emoji/catalog.tsv").bufferedReader().use {
            EmojiCatalog.read(it.lineSequence())
        }
    } else catalog
    // Keep the previous AndroidX picker's preference format so upgrades retain recents.
    val preferences = remember(context) {
        context.getSharedPreferences("androidx.emoji2.emojipicker.preferences", 0)
    }
    var recent by remember {
        mutableStateOf(if (preview) emptyList() else preferences.getString("pref_key_recent_emoji", "")
            .orEmpty().split(',').filter(String::isNotEmpty).distinct().take(64))
    }
    var category by remember { mutableStateOf("emotions") }
    var search by remember { mutableStateOf(EmojiSearchState(initialSearch != null, initialSearch.orEmpty())) }
    var variantEntry by remember { mutableStateOf<EmojiEntry?>(null) }
    val gridState = rememberLazyGridState()
    val focusRequester = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminanceForEmoji() < 0.5f
    val selection = if (dark) Color(0xFF215447) else Color(0xFFA8E5CF)
    val selectionText = if (dark) Color(0xFFB7F1DA) else Color(0xFF00513B)
    val control = if (dark) colors.surfaceVariant else Color(0xFFE0EBE3)
    val foreground = BrandTheme.colors.keyboardKeyOnSurface
    val filtered = remember(available, search.query, category, recent) {
        when {
            available == null -> emptyList()
            search.active && search.query.isNotBlank() -> available.search(search.query)
            category == "recent" -> recent.mapNotNull { emoji ->
                available.entries.firstOrNull { emoji in it.variants }?.copy(emoji = emoji)
            }
            else -> available.entries.filter { it.category == category }
        }
    }
    fun pick(emoji: String) {
        recent = (listOf(emoji) + recent).distinct().take(64)
        if (!preview) preferences.edit()
            .putString("pref_key_recent_emoji", recent.joinToString(",")).apply()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        variantEntry = null
        onEmojiPicked(emoji)
    }
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler {
        when {
            variantEntry != null -> variantEntry = null
            search.active -> search = search.close()
            else -> onBackToKeyboard()
        }
    }
    LaunchedEffect(search.active) { if (search.active) focusRequester.requestFocus() }
    LaunchedEffect(category, search.query) { gridState.scrollToItem(0) }

    Column(modifier = modifier) {
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            EmojiControl("‹", "Back to keyboard", Modifier.width(42.dp), foreground) {
                if (search.active) search = search.close() else onBackToKeyboard()
            }
            Row(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(22.dp))
                    .background(control).clickable { search = search.open() }
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (!search.active || event.type != KeyEventType.KeyDown) false
                        else when (event.key) {
                            Key.Backspace -> { search = search.delete(); true }
                            Key.Escape -> { search = search.close(); true }
                            Key.Enter -> { true }
                            else -> {
                                val code = event.utf16CodePoint
                                if (code >= 32 && !event.isCtrlPressed && !event.isMetaPressed) {
                                    search = search.type(String(Character.toChars(code))); true
                                } else false
                            }
                        }
                    }.focusable().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyboardActionIcon(KeyboardAction.Search, modifier = Modifier.size(20.dp), color = foreground)
                Spacer(Modifier.width(8.dp))
                Text(search.query.ifEmpty { "Search emoji" }, color = foreground,
                    fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                if (search.active) Text("│", color = selectionText)
            }
            if (search.active) EmojiControl("×", "Clear search", Modifier.width(42.dp), foreground) {
                search = search.clear()
            }
        }
        if (!search.active) {
            Row(Modifier.fillMaxWidth().height(38.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                emojiCategories.forEachIndexed { index, (id, _) ->
                    Box(Modifier.width(42.dp).height(34.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (category == id) selection else Color.Transparent)
                        .semantics { contentDescription = categoryNames[index]; selected = category == id }
                        .clickable { category = id; variantEntry = null }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(categoryIcons[index]), contentDescription = null,
                            tint = if (category == id) selectionText else foreground,
                            modifier = Modifier.size(21.dp))
                    }
                }
            }
        }
        Text(
            when {
                variantEntry != null -> "Choose a variant"
                search.active && search.query.isNotEmpty() -> "${filtered.size} results"
                else -> categoryNames[emojiCategories.indexOfFirst { it.first == category }]
            },
            fontSize = 12.sp, color = foreground.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val variants = variantEntry
            if (variants != null) {
                LazyVerticalGrid(columns = GridCells.Fixed(8)) {
                    items(variants.variants) { emoji ->
                        EmojiCell(emoji, variants.name, false, { pick(emoji) }, {})
                    }
                }
            } else if (filtered.isEmpty()) {
                Text(if (available == null) "Loading emoji…" else if (category == "recent" && search.query.isBlank())
                    "Your recent emoji will appear here" else "No emoji found. Try another search.",
                    color = foreground.copy(alpha = 0.7f), fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp))
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(8), state = gridState) {
                    items(filtered, key = { it.emoji }) { entry ->
                        EmojiCell(entry.emoji, entry.name, entry.variants.size > 1,
                            { pick(entry.emoji) }, { variantEntry = entry })
                    }
                }
            }
        }
        if (search.active) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { letters ->
                    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = if (letters.length == 9) 12.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        letters.forEach { letter ->
                            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp))
                                .background(BrandTheme.colors.keyboardKeySurface)
                                .clickable { search = search.type(letter.toString()) }, contentAlignment = Alignment.Center) {
                                Text(letter.toString(), color = foreground, fontSize = 18.sp)
                            }
                        }
                        if (letters.length == 7) EmojiControl("⌫", "Delete search character", Modifier.weight(1.4f), foreground) {
                            search = search.delete()
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EmojiControl("ABC", "Back to keyboard", Modifier.width(60.dp), foreground, onBackToKeyboard)
            if (search.active) {
                EmojiControl("space", "Search space", Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(control), foreground) {
                    search = search.type(" ")
                }
                EmojiControl("Done", "Close search", Modifier.width(60.dp), selectionText) { search = search.close() }
            } else {
                Box(Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(22.dp)).background(selection), contentAlignment = Alignment.Center) {
                    SmileIcon(modifier = Modifier.size(24.dp), color = selectionText)
                }
                Spacer(Modifier.weight(1f))
                if (variantEntry != null) EmojiControl("‹", "Close variants", Modifier.width(42.dp), foreground) { variantEntry = null }
                EmojiControl("⌫", "Delete", Modifier.width(48.dp), foreground, onDelete)
            }
        }
    }
}

private fun Color.luminanceForEmoji() = (red + green + blue) / 3f

@Composable
private fun EmojiControl(label: String, description: String, modifier: Modifier, color: Color, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().semantics { contentDescription = description }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 18.sp, color = color)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(emoji: String, name: String, hasVariants: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(46.dp)
        .semantics { contentDescription = name }
        .combinedClickable(onClick = onClick, onLongClick = if (hasVariants) onLongClick else null,
            onLongClickLabel = if (hasVariants) "Choose skin tone or variant" else null),
        contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 28.sp)
        if (hasVariants) Text("◢", fontSize = 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 3.dp))
    }
}
