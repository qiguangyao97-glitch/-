package com.example.gongderefuser

import android.content.Context

object OcrCorrectionStore {
    enum class Scope {
        ALL,
        MERCHANT,
        ADDRESS
    }

    data class Rule(
        val scope: Scope,
        val wrong: String,
        val right: String
    )

    @Volatile
    private var rules: List<Rule> = emptyList()

    fun load(context: Context) {
        runCatching {
            context.applicationContext.assets.open("ocr_corrections.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    loadFromText(reader.readText())
                }
        }
    }

    fun loadFromText(text: String) {
        rules = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull(::parseRule)
            .toList()
    }

    fun applyAll(text: String): String {
        return apply(text, Scope.ALL)
    }

    fun applyMerchant(text: String): String {
        return apply(text, Scope.MERCHANT)
    }

    fun applyAddress(text: String): String {
        return apply(text, Scope.ADDRESS)
    }

    private fun apply(text: String, scope: Scope): String {
        var output = text
        rules.forEach { rule ->
            if (rule.scope == Scope.ALL || rule.scope == scope) {
                output = output.replace(rule.wrong, rule.right)
            }
        }
        return output
    }

    private fun parseRule(line: String): Rule? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val scope = when (parts[0].trim().lowercase()) {
            "all" -> Scope.ALL
            "merchant" -> Scope.MERCHANT
            "address" -> Scope.ADDRESS
            else -> return null
        }
        val wrong = parts[1].trim()
        val right = parts.drop(2).joinToString("\t").trim()
        if (wrong.isBlank() || right.isBlank()) return null
        return Rule(scope, wrong, right)
    }
}
