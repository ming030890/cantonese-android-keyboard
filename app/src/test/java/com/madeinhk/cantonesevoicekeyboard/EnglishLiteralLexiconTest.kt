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
    @Test fun compiledFormsAreExplicitAndMalformedSourcesFailClosed() {
        val lexicon=EnglishLiteralLexicon.fromWordsReader("fix\nfixed\nfixing\niterate\n".reader())
        assertTrue(lexicon.contains("Fixed"));assertTrue(lexicon.contains("iterate"));assertFalse(lexicon.contains("fized"))
        for(source in listOf("","fix/DS\n","fixed\nmade-up\n")) {
            try {EnglishLiteralLexicon.fromWordsReader(source.reader());fail("Malformed forms accepted")}
            catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun uppercaseAcronymsDoNotProtectUnrelatedLowercaseMistaps() {
        for(lexicon in listOf(EnglishLiteralLexicon.fromReader("3\nKS\nis\nName\n".reader()),
            EnglishLiteralLexicon.fromWordsReader("KS\nis\nname\n".reader()))) {
            assertTrue(lexicon.contains("KS"));assertFalse(lexicon.contains("ks"))
            assertTrue(lexicon.contains("Is"));assertTrue(lexicon.contains("Name"))
        }
    }
    @Test fun compiledSpellingValidationPreservesApostrophesAndRejectsMalformedEntries() {
        val lexicon=EnglishLiteralLexicon.fromWordsReader("don't\nO'Brien\nrock'n'roll\nNASA\n".reader())
        for(word in listOf("DON'T","o'brien","Rock'n'roll","NASA")) assertTrue(word,lexicon.contains(word))
        assertFalse(lexicon.contains("nasa"))
        for(word in listOf("'word","word'","wo''rd","123","café","two words","a-b","a’b","")) {
            try {EnglishLiteralLexicon.fromWordsReader("valid\n$word\n".reader());fail("Accepted: $word")}
            catch(_:IllegalArgumentException) {}
        }
    }
}
