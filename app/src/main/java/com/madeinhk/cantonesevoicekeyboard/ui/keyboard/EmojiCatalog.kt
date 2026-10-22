package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import java.util.Locale

internal data class EmojiEntry(
    val category: String,
    val emoji: String,
    val name: String,
    val keywords: String,
    val variants: List<String>,
)

internal class EmojiCatalog(val entries: List<EmojiEntry>) {
    private val searchable = entries.map { entry ->
        entry to "${entry.name} ${entry.keywords}".lowercase(Locale.ROOT)
    }

    fun search(query: String): List<EmojiEntry> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
            .filter(String::isNotEmpty)
        if (words.isEmpty()) return entries.distinctBy { it.emoji }
        return searchable.filter { (entry, keywords) ->
            entry.emoji == query.trim() || words.all { it in keywords }
        }.map { it.first }.distinctBy { it.emoji }
    }

    companion object {
        fun read(lines: Sequence<String>): EmojiCatalog = EmojiCatalog(lines
            .filter(String::isNotBlank)
            .map { line ->
                val fields = line.split('\t')
                require(fields.size == 5) { "Invalid emoji catalogue row" }
                EmojiEntry(fields[0], fields[1], fields[2], fields[3], fields[4].split(','))
            }.toList())
    }
}

/** Search typing is local to the picker; only choosing an emoji reaches the editor. */
internal data class EmojiSearchState(val active: Boolean = false, val query: String = "") {
    fun open() = copy(active = true)
    fun type(text: String) = copy(query = query + text)
    fun delete() = copy(query = query.dropLast(if (query.lastOrNull()?.isLowSurrogate() == true) 2 else 1))
    fun clear() = copy(query = "")
    fun close() = EmojiSearchState()
}

/** Most recent first; keep complete sequences and the legacy picker's comma-separated format. */
internal class EmojiHistory(emoji: List<String> = emptyList()) {
    val emoji = emoji.filter(String::isNotBlank).distinct().take(64)

    fun pick(value: String) = EmojiHistory(listOf(value) + emoji)

    fun entries(catalog: EmojiCatalog): List<EmojiEntry> = emoji.mapNotNull { value ->
        catalog.entries.firstOrNull { it.emoji == value || value in it.variants }?.copy(emoji = value)
    }
}
