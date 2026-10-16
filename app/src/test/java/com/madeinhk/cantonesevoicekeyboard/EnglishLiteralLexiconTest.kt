package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.EnglishLiteralLexicon
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EnglishLiteralLexiconTest {
    @Test fun standardWordsAreProtectedWithoutAcceptingCommonMistaps() {
        val lexicon=File("src/main/assets/english/validity/en_US.dic").reader().use {EnglishLiteralLexicon.fromReader(it)}
        for(word in listOf("typo","TyPo","emoji","metadata","workflow","type","keyboard"))
            assertTrue(word,lexicon.contains(word))
        for(word in listOf("teh","helllo","wprld","srill","Xentara"))
            assertFalse(word,lexicon.contains(word))
    }
    @Test fun compoundOnlyEntriesAndAffixFlagsAreNotStandaloneWords() {
        val lexicon=EnglishLiteralLexicon.fromReader("3\nexample/SM\ncompound/c\nName\n".reader())
        assertTrue(lexicon.contains("example"));assertTrue(lexicon.contains("name"))
        assertFalse(lexicon.contains("compound"));assertFalse(lexicon.contains("example/SM"))
        assertFalse(lexicon.contains("examples")) // No guessed inflection in the root-only guard.
    }
}
