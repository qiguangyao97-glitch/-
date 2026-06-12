package com.example.gongderefuser.matching

object OcrTextNormalizer {
    fun normalize(raw: String, replacements: Map<String, String> = emptyMap()): String {
        var text = raw
            .map(::normalizeChar)
            .joinToString("")
            .replace(Regex("[\\t\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        replacements.forEach { (from, to) ->
            if (from.isNotEmpty()) {
                text = text.replace(from, to, ignoreCase = true)
            }
        }

        return text
            .replace(Regex("[^\\p{IsHan}a-zA-Z0-9$./\\-()（）\\s公里分鐘分钟]"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun compact(raw: String, replacements: Map<String, String> = emptyMap()): String {
        return normalize(raw, replacements)
            .lowercase()
            .replace(Regex("[^\\p{IsHan}a-z0-9]"), "")
    }

    private fun normalizeChar(char: Char): Char {
        return when (char) {
            in 'Ａ'..'Ｚ' -> 'A' + (char - 'Ａ')
            in 'ａ'..'ｚ' -> 'a' + (char - 'ａ')
            in '０'..'９' -> '0' + (char - '０')
            '＄' -> '$'
            '（' -> '('
            '）' -> ')'
            '－', '—', '–' -> '-'
            else -> char
        }
    }
}
