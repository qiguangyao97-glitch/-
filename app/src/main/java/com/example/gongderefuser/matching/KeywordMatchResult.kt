package com.example.gongderefuser.matching

data class KeywordMatchResult(
    val canonicalName: String,
    val matchedAlias: String,
    val category: String,
    val district: String?,
    val level: String,
    val confidence: Double,
    val scoreImpact: Int
)
