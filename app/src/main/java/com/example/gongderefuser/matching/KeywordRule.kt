package com.example.gongderefuser.matching

data class KeywordRule(
    val canonicalName: String,
    val aliases: List<String>,
    val category: String,
    val district: String? = null,
    val level: String = "NORMAL",
    val scoreImpact: Int = 0,
    val minConfidence: Double = 0.75
)
