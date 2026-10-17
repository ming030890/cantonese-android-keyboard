package com.madeinhk.cantonesevoicekeyboard.english

import android.content.Context
import java.io.Reader
import java.util.Locale

/** Standard spellings protect input. Compiled forms follow the pinned affix flags;
 * the original root-only source remains a fallback for older asset bundles. */
internal class EnglishLiteralLexicon private constructor(private val words:Set<String>,private val acronyms:Set<String>) {
    fun contains(word:String)=word.lowercase(Locale.ROOT) in words || word in acronyms
    companion object {
        private var cached:EnglishLiteralLexicon?=null
        fun assetPath(context:Context)=if(context.assets.list("english/validity")?.contains("valid_words.txt")==true)
            "english/validity/valid_words.txt" else "english/validity/en_US.dic"
        @Synchronized fun load(context:Context):EnglishLiteralLexicon = cached ?: run {
            val path=assetPath(context.applicationContext)
            context.applicationContext.assets.open(path).bufferedReader().use {
                if(path.endsWith("valid_words.txt")) fromWordsReader(it) else fromReader(it)
            }
        }.also {cached=it}

        fun fromWordsReader(reader:Reader):EnglishLiteralLexicon {
            val words=reader.buffered().readLines()
            require(words.isNotEmpty() && words.all {it.matches(Regex("[A-Za-z]+(?:'[A-Za-z]+)*"))})
            return spellings(words)
        }
        private fun spellings(values:List<String>):EnglishLiteralLexicon {
            val (acronyms,ordinary)=values.partition {it.length>1 && it==it.uppercase(Locale.ROOT)}
            return EnglishLiteralLexicon(ordinary.map {it.lowercase(Locale.ROOT)}.toSet(),acronyms.toSet())
        }

        fun fromReader(reader:Reader):EnglishLiteralLexicon {
            val lines=reader.buffered().readLines()
            require(lines.firstOrNull()?.toIntOrNull()?.let { it>0 }==true)
            val ascii=Regex("[A-Za-z]+(?:'[A-Za-z]+)*")
            val words=lines.drop(1).mapNotNull { line ->
                val entry=line.substringBefore('\t').substringBefore(' ')
                val flags=entry.substringAfter('/',"")
                // The pinned affix file marks c as ONLYINCOMPOUND.
                if('c' in flags) null else entry.substringBefore('/').takeIf {ascii.matches(it)}
            }
            require(words.isNotEmpty())
            return spellings(words)
        }
    }
}
