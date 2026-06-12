package com.example.gongderefuser.matching

import android.util.Log

class OrderLocationAnalyzer(
    private val repository: LocationKeywordRepository
) {
    fun analyze(rawOcrText: String): LocationAnalysisResult {
        val normalizedText = OcrTextNormalizer.normalize(rawOcrText, repository.replacements)
        val addressMatches = FuzzyMatcher.match(
            normalizedText = normalizedText,
            rules = repository.addressRules,
            replacements = repository.replacements
        )
            .filter(::isEffectiveAddressMatch)
            .takeTopMatches()
        val merchantMatches = FuzzyMatcher.match(
            normalizedText = normalizedText,
            rules = repository.merchantRules,
            replacements = repository.replacements
        ).takeTopMatches()

        val totalScoreImpact = addressMatches
            .filter { it.level != "NORMAL" }
            .sumOf { it.scoreImpact }
            .coerceIn(-80, 30)
        val strongestLevel = strongestLevel(addressMatches)

        Log.d("LOCATION_NORMALIZED", normalizedText)
        addressMatches.forEach {
            Log.d("LOCATION_MATCH", "${it.level} ${it.canonicalName} ${"%.2f".format(it.confidence)} score=${it.scoreImpact}")
        }
        merchantMatches.forEach {
            Log.d("MERCHANT_MATCH", "${it.canonicalName} ${"%.2f".format(it.confidence)}")
        }
        Log.d("LOCATION_SCORE", totalScoreImpact.toString())

        return LocationAnalysisResult(
            normalizedText = normalizedText,
            addressMatches = addressMatches,
            merchantMatches = merchantMatches,
            totalScoreImpact = totalScoreImpact,
            strongestLevel = strongestLevel
        )
    }

    private fun isEffectiveAddressMatch(match: KeywordMatchResult): Boolean {
        return match.level == "NORMAL" || match.confidence >= matchConfidenceFloor(match.level)
    }

    private fun matchConfidenceFloor(level: String): Double {
        return when (level) {
            "BLACK_STRONG" -> 0.85
            "BLACK_MEDIUM" -> 0.78
            "RISK_NOTICE", "WHITE_HINT" -> 0.70
            else -> 0.75
        }
    }

    private fun List<KeywordMatchResult>.takeTopMatches(): List<KeywordMatchResult> {
        return groupBy { it.canonicalName }
            .values
            .mapNotNull { matches -> matches.maxByOrNull { it.confidence } }
            .sortedWith(compareByDescending<KeywordMatchResult> { levelPriority(it.level) }.thenByDescending { it.confidence })
            .take(5)
    }

    private fun strongestLevel(matches: List<KeywordMatchResult>): String? {
        return matches
            .filter { it.level != "NORMAL" }
            .maxByOrNull { levelPriority(it.level) }
            ?.level
    }

    private fun levelPriority(level: String): Int {
        return when (level) {
            "BLACK_STRONG" -> 4
            "BLACK_MEDIUM" -> 3
            "RISK_NOTICE" -> 2
            "WHITE_HINT" -> 1
            else -> 0
        }
    }
}
