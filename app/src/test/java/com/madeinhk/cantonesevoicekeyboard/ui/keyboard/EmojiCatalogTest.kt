package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EmojiCatalogTest {
    private fun catalog() = File("src/main/assets/emoji/catalog.tsv").bufferedReader().use {
        EmojiCatalog.read(it.lineSequence())
    }

    @Test fun completeCatalogueHasNineCategoriesAndUniqueBaseEmoji() {
        val entries = catalog().entries
        assertEquals(9, entries.map { it.category }.distinct().size)
        assertTrue(entries.size > 1700)
        assertEquals(entries.size, entries.map { it.category to it.emoji }.distinct().size)
        assertTrue(entries.sumOf { it.variants.size } > 2800)
        assertTrue(entries.all { it.name != it.emoji && it.name.isNotBlank() })
        assertTrue(entries.any { it.emoji == "🫩" })
    }

    @Test fun searchUsesKeywordsMultipleWordsFlagsAndCompositeNames() {
        val catalog = catalog()
        assertTrue(catalog.search("  HAPPY  ").any { it.emoji == "😀" })
        assertTrue(catalog.search("thumbs up").any { it.emoji == "👍" })
        assertTrue(catalog.search("hong kong").any { it.emoji == "🇭🇰" })
        assertTrue(catalog.search("red hair").any { it.emoji == "👩‍🦰" })
        assertTrue(catalog.search("❤️").any { it.emoji == "❤️" })
        assertTrue(catalog.search("not-an-emoji-keyword").isEmpty())
        assertEquals(catalog.entries.distinctBy { it.emoji }, catalog.search("   "))
        val hearts = catalog.search("heart")
        assertEquals(hearts.size, hearts.map { it.emoji }.distinct().size)
    }

    @Test fun variantsKeepWholeUnicodeSequences() {
        val thumbs = catalog().entries.single { it.emoji == "👍" }
        assertEquals(6, thumbs.variants.size)
        assertTrue("👍🏽" in thumbs.variants)
        assertTrue(catalog().entries.single { it.emoji == "🤝" }.variants.size > 6)
    }

    @Test fun searchCanBeClearedClosedAndEditedWithoutCommitting() {
        var search = EmojiSearchState().open().type("red ").type("heart")
        assertTrue(search.active)
        assertEquals("red heart", search.query)
        search = search.delete()
        assertEquals("red hear", search.query)
        assertEquals("", search.clear().query)
        assertEquals("", search.clear().delete().query)
        assertEquals("red hear", search.type("😀").delete().query)
        assertEquals(EmojiSearchState(), search.close())
    }
    @Test fun recentEmojiMoveToFrontKeepVariantsAndSurviveSerialization() {
        val history = EmojiHistory(listOf("😀", "👍🏽", "🇭🇰", "😀", ""))
            .pick("👩‍🦰").pick("👍🏽")
        val restored = EmojiHistory(history.emoji.joinToString(",").split(','))
        assertEquals(listOf("👍🏽", "👩‍🦰", "😀", "🇭🇰"), restored.emoji)
        assertEquals(restored.emoji, restored.entries(catalog()).map { it.emoji })
        assertEquals("thumbs up", restored.entries(catalog()).first().name)
        assertTrue(EmojiHistory().entries(catalog()).isEmpty())
        assertTrue(EmojiHistory(listOf("obsolete")).entries(catalog()).isEmpty())
    }

    @Test fun recentEmojiRetainOnlyTheLatest64UniqueSelections() {
        var history = EmojiHistory()
        val emoji = catalog().entries.take(65).map { it.emoji }
        emoji.forEach { history = history.pick(it) }
        assertEquals(emoji.reversed().take(64), history.emoji)
        history = history.pick(emoji.last())
        assertEquals(64, history.emoji.size)
        assertEquals(emoji.last(), history.emoji.first())
    }

}
