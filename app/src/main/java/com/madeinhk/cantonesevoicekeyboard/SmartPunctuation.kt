package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode

internal object SmartPunctuation {

    fun isChineseChar(c: Char): Boolean {
        val code = c.code
        if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF) {
            return true
        }
        if (Character.isIdeographic(code)) {
            return true
        }
        if (code in 0x3001..0x303F) {
            return true
        }
        if (code in 0xFF01..0xFF0F || code in 0xFF1A..0xFF20 ||
            code in 0xFF3B..0xFF40 || code in 0xFF5B..0xFF65) {
            return true
        }
        return c in "“”‘’—…、。，！？：；（）【】「」『』《》～"
    }

    fun isPunctuation(c: Char): Boolean =
        c in ",.!?;:()[]{}<>~、。，！？：；（）【】「」『』《》～" ||
        c in "“”‘’"

    fun isChineseContext(
        textBeforeCursor: String?,
        currentMode: KeyboardInputMode,
        isComposingQuick: Boolean = false,
        isComposingEnglish: Boolean = false,
    ): Boolean {
        if (isComposingEnglish) return false
        if (isComposingQuick) return true
        if (textBeforeCursor.isNullOrEmpty()) {
            return currentMode == KeyboardInputMode.Quick || currentMode == KeyboardInputMode.Handwriting
        }
        val trimmed = textBeforeCursor.trimEnd(' ', '\t')
        if (trimmed.isEmpty() || trimmed.last() == '\n' || trimmed.last() == '\r') {
            return currentMode == KeyboardInputMode.Quick || currentMode == KeyboardInputMode.Handwriting
        }
        val lastChar = trimmed.last()
        return isChineseChar(lastChar)
    }

    fun toFullWidth(text: String, textBeforeCursor: String? = null): String {
        if (text.isEmpty()) return text
        var preceding = textBeforeCursor.orEmpty().takeLastWhile { it != '\n' && it != '\r' }
        return buildString(text.length) {
            for (ch in text) {
                val converted = when (ch) {
                    ',' -> '，'
                    '.' -> '。'
                    '?' -> '？'
                    '!' -> '！'
                    ':' -> '：'
                    ';' -> '；'
                    '(' -> '（'
                    ')' -> '）'
                    '[' -> '【'
                    ']' -> '】'
                    '{' -> '「'
                    '}' -> '」'
                    '<' -> '《'
                    '>' -> '》'
                    '~' -> '～'
                    '"' -> {
                        val openCount = preceding.count { it == '“' }
                        val closeCount = preceding.count { it == '”' }
                        if (openCount > closeCount) '”' else '“'
                    }
                    '\'' -> {
                        val openCount = preceding.count { it == '‘' }
                        val closeCount = preceding.count { it == '’' }
                        if (openCount > closeCount) '’' else '‘'
                    }
                    '-' -> '－'
                    '_' -> '＿'
                    '/' -> '／'
                    '\\' -> '＼'
                    else -> ch
                }
                append(converted)
                preceding += converted
            }
        }
    }

    fun toAscii(text: String): String {
        if (text.isEmpty()) return text
        return buildString(text.length) {
            for (ch in text) {
                append(
                    when (ch) {
                        '，' -> ','
                        '。' -> '.'
                        '？' -> '?'
                        '！' -> '!'
                        '：' -> ':'
                        '；' -> ';'
                        '（' -> '('
                        '）' -> ')'
                        '【', '「', '『' -> '['
                        '】', '」', '』' -> ']'
                        '《' -> '<'
                        '》' -> '>'
                        '～' -> '~'
                        '“', '”' -> '"'
                        '‘', '’' -> '\''
                        '－', '—' -> '-'
                        '＿' -> '_'
                        '／' -> '/'
                        '＼' -> '\\'
                        '、' -> ','
                        else -> ch
                    }
                )
            }
        }
    }

    fun toSmartPunctuation(
        text: String,
        isChineseContext: Boolean,
        textBeforeCursor: String? = null,
    ): String {
        return if (isChineseContext) {
            toFullWidth(text, textBeforeCursor)
        } else {
            toAscii(text)
        }
    }
}
