package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.madeinhk.cantonesevoicekeyboard.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import kotlinx.coroutines.launch
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
    R.drawable.quantum_gm_ic_access_time_filled_vd_theme_24,
    R.drawable.gm_filled_emoji_emotions_vd_theme_24,
    R.drawable.gm_filled_emoji_people_vd_theme_24,
    R.drawable.gm_filled_emoji_nature_vd_theme_24,
    R.drawable.gm_filled_emoji_food_beverage_vd_theme_24,
    R.drawable.gm_filled_emoji_transportation_vd_theme_24,
    R.drawable.gm_filled_emoji_events_vd_theme_24,
    R.drawable.gm_filled_emoji_objects_vd_theme_24,
    R.drawable.gm_filled_emoji_symbols_vd_theme_24,
    R.drawable.gm_filled_flag_vd_theme_24,
)
private val categoryNameResources = listOf(
    R.string.emoji_category_recent, R.string.emoji_category_emotions, R.string.emoji_category_people,
    R.string.emoji_category_nature, R.string.emoji_category_food, R.string.emoji_category_travel,
    R.string.emoji_category_activity, R.string.emoji_category_objects, R.string.emoji_category_symbols,
    R.string.emoji_category_flags,
)

@Composable
internal fun EmojiPickerPanel(
    modifier: Modifier = Modifier,
    onEmojiPicked: (String) -> Unit,
    onBackToKeyboard: () -> Unit = {},
    onDelete: () -> Unit = {},
    search: EmojiSearchState = EmojiSearchState(),
    onSearchChanged: (EmojiSearchState) -> Unit = {},
    initialRecentEmoji: List<String>? = null,
) {
    val context = LocalContext.current
    val categoryNames = categoryNameResources.map { stringResource(it) }
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
        mutableStateOf(EmojiHistory(initialRecentEmoji ?: if (preview) emptyList() else
            preferences.getString("pref_key_recent_emoji", "").orEmpty().split(',')))
    }
    val scope = rememberCoroutineScope()
    var variantEntry by remember { mutableStateOf<EmojiEntry?>(null) }
    val gridState = rememberLazyGridState()
    val focusRequester = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminanceForEmoji() < 0.5f
    val selection = if (dark) Color(0xFF215447) else Color(0xFFA8E5CF)
    val selectionText = if (dark) Color(0xFFB7F1DA) else Color(0xFF00513B)
    val control = if (dark) colors.surfaceVariant else Color(0xFFE0EBE3)
    val foreground = BrandTheme.colors.keyboardKeyOnSurface
    val sections = remember(available, recent) {
        emojiCategories.map { (id, _) ->
            id to if (id == "recent") available?.let(recent::entries).orEmpty()
                else available?.entries?.filter { it.category == id }.orEmpty()
        }
    }
    val sectionStarts = remember(sections) {
        var index = 0
        sections.map { (_, entries) ->
            index.also { index += 1 + entries.size.coerceAtLeast(1) }
        }
    }
    val category by remember(sectionStarts) {
        derivedStateOf {
            emojiCategories[sectionStarts.indexOfLast { it <= gridState.firstVisibleItemIndex }.coerceAtLeast(0)].first
        }
    }
    val filtered = remember(available, search.query, recent) {
        when {
            available == null -> emptyList()
            search.query.isNotBlank() -> available.search(search.query)
            recent.emoji.isNotEmpty() -> recent.entries(available)
            else -> available.entries.filter { it.category == "emotions" }
        }
    }
    fun pick(emoji: String) {
        recent = recent.pick(emoji)
        if (!preview) preferences.edit()
            .putString("pref_key_recent_emoji", recent.emoji.joinToString(",")).apply()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        variantEntry = null
        onEmojiPicked(emoji)
    }
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler {
        when {
            variantEntry != null -> variantEntry = null
            search.active -> onSearchChanged(search.close())
            else -> onBackToKeyboard()
        }
    }
    LaunchedEffect(search.active) { if (search.active) focusRequester.requestFocus() }
    LaunchedEffect(search.active, search.query) { gridState.scrollToItem(0) }

    Column(modifier = modifier) {
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            EmojiControl("‹", if (search.active) stringResource(R.string.emoji_close_search) else stringResource(R.string.emoji_back_to_keyboard), Modifier.width(42.dp), foreground) {
                if (search.active) onSearchChanged(search.close()) else onBackToKeyboard()
            }
            Row(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(22.dp))
                    .background(control).clickable { onSearchChanged(search.open()) }
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        handleEmojiSearchKey(event.nativeKeyEvent, search, onSearchChanged)
                    }.focusable().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyboardActionIcon(KeyboardAction.Search, modifier = Modifier.size(20.dp), color = foreground)
                Spacer(Modifier.width(8.dp))
                Text(search.query.ifEmpty { stringResource(R.string.emoji_search) }, color = foreground,
                    fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                if (search.active) Text("│", color = selectionText)
            }
            if (search.active) EmojiControl("×", stringResource(R.string.emoji_clear_search), Modifier.width(42.dp), foreground) {
                onSearchChanged(search.clear())
            }
        }
        if (!search.active) {
            Row(Modifier.fillMaxWidth().height(38.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                emojiCategories.forEachIndexed { index, (id, _) ->
                    Box(Modifier.width(42.dp).height(34.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (category == id) selection else Color.Transparent)
                        .semantics { contentDescription = categoryNames[index]; selected = category == id }
                        .clickable { variantEntry = null; scope.launch { gridState.scrollToItem(sectionStarts[index]) } }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(categoryIcons[index]), contentDescription = null,
                            tint = if (category == id) selectionText else foreground,
                            modifier = Modifier.size(21.dp))
                    }
                }
            }
        }
        if (search.active || variantEntry != null) Text(
            when {
                variantEntry != null -> stringResource(R.string.emoji_choose_variant)
                search.active && search.query.isNotEmpty() -> stringResource(R.string.emoji_results, filtered.size)
                else -> if (recent.emoji.isEmpty()) stringResource(R.string.emoji_category_emotions) else stringResource(R.string.emoji_category_recent)
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
            } else if (!search.active) {
                LazyVerticalGrid(columns = GridCells.Fixed(8), state = gridState) {
                    sections.forEachIndexed { index, (id, entries) ->
                        item(key = "$id.header", span = { GridItemSpan(maxLineSpan) }) {
                            Text(categoryNames[index], fontSize = 12.sp,
                                color = foreground.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
                        }
                        if (entries.isEmpty()) item(key = "$id.empty", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                if (available != null) {
                                    androidx.compose.foundation.Image(
                                        painter = painterResource(R.drawable.mascot_turtle_search_empty),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    if (available == null) stringResource(R.string.emoji_loading) else stringResource(R.string.emoji_recent_empty),
                                    color = foreground.copy(alpha = 0.7f),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                        items(entries, key = { "$id.${it.emoji}" }) { entry ->
                            EmojiCell(entry.emoji, entry.name, entry.variants.size > 1,
                                { pick(entry.emoji) }, { variantEntry = entry })
                        }
                    }
                }
            } else if (filtered.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                ) {
                    if (available != null) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.mascot_turtle_search_empty),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        if (available == null) stringResource(R.string.emoji_loading)
                        else if (category == "recent" && search.query.isBlank()) stringResource(R.string.emoji_recent_empty)
                        else stringResource(R.string.emoji_no_results),
                        color = foreground.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(8), state = gridState) {
                    items(filtered, key = { it.emoji }) { entry ->
                        EmojiCell(entry.emoji, entry.name, entry.variants.size > 1,
                            { pick(entry.emoji) }, { variantEntry = entry })
                    }
                }
            }
        }
        if (!search.active) {
            Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EmojiControl("ABC", stringResource(R.string.emoji_back_to_keyboard), Modifier.width(60.dp), foreground, onBackToKeyboard)
                Box(Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(22.dp)).background(selection), contentAlignment = Alignment.Center) {
                    SmileIcon(modifier = Modifier.size(24.dp), color = selectionText)
                }
                Spacer(Modifier.weight(1f))
                if (variantEntry != null) EmojiControl("‹", stringResource(R.string.emoji_close_variants), Modifier.width(42.dp), foreground) { variantEntry = null }
                EmojiControl("⌫", stringResource(R.string.keyboard_delete), Modifier.width(48.dp), foreground, onDelete)
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
    Box(Modifier.fillMaxWidth().height(52.dp)
        .semantics { contentDescription = name }
        .combinedClickable(onClick = onClick, onLongClick = if (hasVariants) onLongClick else null,
            onLongClickLabel = if (hasVariants) stringResource(R.string.emoji_choose_skin_tone) else null),
        contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 32.sp)
        if (hasVariants) Text("◢", fontSize = 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 3.dp))
    }
}
