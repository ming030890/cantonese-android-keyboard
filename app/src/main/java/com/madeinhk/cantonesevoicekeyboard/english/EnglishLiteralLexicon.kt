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
            val words=HashSet<String>()
            val acronyms=HashSet<String>()
            reader.buffered().forEachLine { word ->
                require(validSpelling(word))
                if(word.length>1 && word==word.uppercase(Locale.ROOT)) acronyms.add(word)
                else words.add(word.lowercase(Locale.ROOT))
            }
            require(words.isNotEmpty() || acronyms.isNotEmpty())
            return EnglishLiteralLexicon(words,acronyms)
        }
        // Same grammar as [A-Za-z]+(?:'[A-Za-z]+)*, without compiling a regex per entry.
        private fun validSpelling(word:String):Boolean {
            var previousLetter=false
            for(char in word) {
                if(char in 'a'..'z' || char in 'A'..'Z') previousLetter=true
                else if(char=='\'' && previousLetter) previousLetter=false
                else return false
            }
            return previousLetter
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
