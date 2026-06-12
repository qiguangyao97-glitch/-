package com.example.gongderefuser.matching

object FuzzyMatcher {
    fun match(
        normalizedText: String,
        rules: List<KeywordRule>,
        replacements: Map<String, String>
    ): List<KeywordMatchResult> {
        val compactText = OcrTextNormalizer.compact(normalizedText, replacements).take(MAX_TEXT_LENGTH)
        if (compactText.isBlank()) return emptyList()

        return rules.mapNotNull { rule ->
            matchRule(compactText, rule, replacements)
        }
            .groupBy { it.canonicalName }
            .mapNotNull { (_, matches) -> matches.maxByOrNull { it.confidence } }
            .sortedWith(compareByDescending<KeywordMatchResult> { it.confidence }.thenBy { it.scoreImpact })
    }

    private fun matchRule(
        compactText: String,
        rule: KeywordRule,
        replacements: Map<String, String>
    ): KeywordMatchResult? {
        val aliases = (listOf(rule.canonicalName) + rule.aliases)
            .map { OcrTextNormalizer.compact(it, replacements) }
            .filter { it.length >= 2 }
            .distinct()

        aliases.firstOrNull { compactText.contains(it, ignoreCase = true) }?.let { alias ->
            return rule.toResult(alias, 1.0)
        }

        return aliases
            .filter { it.length >= 4 }
            .mapNotNull { alias ->
                val confidence = bestSimilarity(compactText, alias)
                if (confidence >= maxOf(rule.minConfidence, minimumConfidence(alias.length))) {
                    rule.toResult(alias, confidence)
                } else {
                    null
                }
            }
            .maxByOrNull { it.confidence }
    }

    private fun KeywordRule.toResult(alias: String, confidence: Double): KeywordMatchResult {
        return KeywordMatchResult(
            canonicalName = canonicalName,
            matchedAlias = alias,
            category = category,
            district = district,
            level = level,
            confidence = confidence,
            scoreImpact = scoreImpact
        )
    }

    private fun minimumConfidence(length: Int): Double {
        return when {
            length <= 3 -> 0.95
            length <= 6 -> 0.80
            else -> 0.75
        }
    }

    private fun bestSimilarity(text: String, alias: String): Double {
        if (text.length < 2 || alias.length < 2) return 0.0
        val minWindow = (alias.length - 1).coerceAtLeast(2)
        val maxWindow = (alias.length + 1).coerceAtMost(text.length)
        var best = 0.0

        for (windowSize in minWindow..maxWindow) {
            for (start in 0..(text.length - windowSize)) {
                val candidate = text.substring(start, start + windowSize)
                val distance = levenshteinDistance(candidate, alias, 4)
                val similarity = 1.0 - distance.toDouble() / maxOf(candidate.length, alias.length)
                if (similarity > best) {
                    best = similarity
                }
            }
        }
        return best
    }

    private fun levenshteinDistance(a: String, b: String, limit: Int): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost
                )
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > limit) return limit + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private const val MAX_TEXT_LENGTH = 1000
}
