package com.madeinhk.cantonesevoicekeyboard.english

import android.content.Context
import java.io.Reader
import java.util.Locale

/** Standard spellings protect input; they never add or rank correction candidates.
 * Root-only SCOWL extraction. Affixed forms are not guessed from suffixes.
 */
internal class EnglishLiteralLexicon private constructor(private val words:Set<String>) {
    fun contains(word:String)=word.lowercase(Locale.ROOT) in words
    companion object {
        private var cached:EnglishLiteralLexicon?=null
        @Synchronized fun load(context:Context):EnglishLiteralLexicon = cached ?: context.applicationContext.assets
            .open("english/validity/en_US.dic").bufferedReader().use { fromReader(it) }.also {cached=it}

        fun fromReader(reader:Reader):EnglishLiteralLexicon {
            val lines=reader.buffered().readLines()
            require(lines.firstOrNull()?.toIntOrNull()?.let { it>0 }==true)
            val ascii=Regex("[a-z]+(?:'[a-z]+)*")
            val words=lines.drop(1).mapNotNull { line ->
                val entry=line.substringBefore('\t').substringBefore(' ')
                val flags=entry.substringAfter('/',"")
                // The pinned affix file marks c as ONLYINCOMPOUND.
                if('c' in flags) null else entry.substringBefore('/').lowercase(Locale.ROOT).takeIf {ascii.matches(it)}
            }.toSet()
            require(words.isNotEmpty())
            return EnglishLiteralLexicon(words)
        }
    }
}
