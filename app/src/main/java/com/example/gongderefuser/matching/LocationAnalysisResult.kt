package com.example.gongderefuser.matching

data class LocationAnalysisResult(
    val normalizedText: String,
    val addressMatches: List<KeywordMatchResult>,
    val merchantMatches: List<KeywordMatchResult>,
    val totalScoreImpact: Int,
    val strongestLevel: String?
)
