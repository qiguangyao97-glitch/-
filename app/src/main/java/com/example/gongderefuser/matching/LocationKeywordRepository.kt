package com.example.gongderefuser.matching

import android.content.Context

class LocationKeywordRepository private constructor(
    val rules: List<KeywordRule>,
    val replacements: Map<String, String>
) {
    val addressRules: List<KeywordRule> = rules.filter { it.category != CATEGORY_MERCHANT }
    val merchantRules: List<KeywordRule> = rules.filter { it.category == CATEGORY_MERCHANT }

    companion object {
        private const val ASSET_NAME = "linkou_guishan_address_library_v1.txt"
        private const val CATEGORY_MERCHANT = "MERCHANT"

        @Volatile
        private var cached: LocationKeywordRepository? = null

        fun load(context: Context): LocationKeywordRepository {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: parse(
                    context.applicationContext.assets.open(ASSET_NAME)
                        .bufferedReader(Charsets.UTF_8)
                        .readText()
                ).also { cached = it }
            }
        }

        fun parse(raw: String): LocationKeywordRepository {
            val rules = mutableListOf<KeywordRule>()
            val replacements = linkedMapOf<String, String>()
            var section = ""

            raw.lineSequence().forEach { rawLine ->
                val line = rawLine.substringBefore("#").trim()
                if (line.isBlank()) return@forEach
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.removePrefix("[").removeSuffix("]")
                    return@forEach
                }

                if (section == "OCR_NORMALIZE_REPLACE" && line.contains("=>")) {
                    val from = line.substringBefore("=>").trim()
                    val to = line.substringAfter("=>").trim()
                    if (from.isNotEmpty()) replacements[from] = to
                    return@forEach
                }

                if (section == "VERSION" || section.isBlank()) return@forEach

                parseRule(section, line)?.let(rules::add)
            }

            return LocationKeywordRepository(rules, replacements)
        }

        private fun parseRule(section: String, line: String): KeywordRule? {
            val keywordPart = line.substringBefore(";").trim()
            if (keywordPart.isBlank() || keywordPart.contains("=>")) return null

            val words = keywordPart.split("|")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val canonical = words.firstOrNull() ?: return null
            val metadata = parseMetadata(line.substringAfter(";", ""))
            val category = categoryFor(section)
            val level = levelFor(section)

            return KeywordRule(
                canonicalName = canonical,
                aliases = words.drop(1),
                category = category,
                district = metadata["district"],
                level = level,
                scoreImpact = metadata["score"]?.toIntOrNull() ?: 0,
                minConfidence = metadata["minConfidence"]?.toDoubleOrNull() ?: defaultConfidenceFor(level)
            )
        }

        private fun parseMetadata(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return raw.split(";")
                .mapNotNull { part ->
                    val key = part.substringBefore("=", "").trim()
                    val value = part.substringAfter("=", "").trim()
                    if (key.isBlank() || value.isBlank()) null else key to value
                }
                .toMap()
        }

        private fun categoryFor(section: String): String {
            return when {
                section == "MERCHANT_CHAIN" || section == "USER_APPEND_MERCHANT" -> CATEGORY_MERCHANT
                section.startsWith("BLACK") || section == "RISK_NOTICE" || section == "WHITE_HINT" ||
                        section.startsWith("USER_APPEND_BLACK") -> "ADDRESS_RULE"
                section.contains("DISTRICT") -> "DISTRICT"
                section.contains("VILLAGE") -> "VILLAGE"
                section.contains("ROAD") -> "ROAD"
                section.contains("LANDMARK") -> "LANDMARK"
                else -> section
            }
        }

        private fun levelFor(section: String): String {
            return when {
                section == "MERCHANT_CHAIN" || section == "USER_APPEND_MERCHANT" -> "MERCHANT"
                section.startsWith("BLACK") || section == "RISK_NOTICE" || section == "WHITE_HINT" ||
                        section.startsWith("USER_APPEND_BLACK") -> section
                else -> "NORMAL"
            }
        }

        private fun defaultConfidenceFor(level: String): Double {
            return when (level) {
                "BLACK_STRONG" -> 0.85
                "BLACK_MEDIUM" -> 0.78
                "RISK_NOTICE", "WHITE_HINT" -> 0.75
                else -> 0.75
            }
        }
    }
}
