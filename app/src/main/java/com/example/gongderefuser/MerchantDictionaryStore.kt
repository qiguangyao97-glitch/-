package com.example.gongderefuser

import android.content.Context
import com.example.gongderefuser.matching.OcrTextNormalizer

object MerchantDictionaryStore {
    data class Entry(
        val canonicalName: String,
        val aliases: List<String>
    )

    @Volatile
    private var entries: List<Entry> = emptyList()

    fun load(context: Context) {
        runCatching {
            context.applicationContext.assets.open("merchant_dictionary.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> loadFromText(reader.readText()) }
        }
    }

    fun loadFromText(text: String) {
        entries = text
            .lineSequence()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotBlank() }
            .mapNotNull(::parseEntry)
            .toList()
    }

    fun correct(candidate: String): String {
        val cleaned = candidate.trim()
        if (cleaned.length < 3) return cleaned

        val compactCandidate = compact(cleaned)
        if (compactCandidate.length < 3) return cleaned

        bestExactMatch(compactCandidate)?.let { return it }
        bestFuzzyMatch(compactCandidate)?.let { return it }
        return cleaned
    }

    private fun bestExactMatch(compactCandidate: String): String? {
        return entries
            .asSequence()
            .flatMap { entry -> entry.allNames().asSequence().map { name -> entry to compact(name) } }
            .filter { (_, compactName) ->
                compactName.length >= 4 &&
                    compactCandidate.contains(compactName) &&
                    compactName.length >= (compactCandidate.length * EXACT_MIN_COVERAGE).toInt()
            }
            .maxWithOrNull(compareBy<Pair<Entry, String>> { it.second.length })
            ?.first
            ?.canonicalName
    }

    private fun bestFuzzyMatch(compactCandidate: String): String? {
        return entries
            .asSequence()
            .mapNotNull { entry ->
                val confidence = entry.allNames()
                    .asSequence()
                    .map(::compact)
                    .filter { it.length >= 4 }
                    .map { name -> bestSimilarity(compactCandidate, name) }
                    .maxOrNull() ?: 0.0
                val threshold = minimumConfidence(compactCandidate.length, compact(entry.canonicalName).length)
                if (confidence >= threshold) entry to confidence else null
            }
            .maxWithOrNull(
                compareBy<Pair<Entry, Double>> { it.second }
                    .thenBy { compact(it.first.canonicalName).length }
            )
            ?.first
            ?.canonicalName
    }

    private fun parseEntry(line: String): Entry? {
        val names = line.split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val canonical = names.firstOrNull() ?: return null
        return Entry(canonicalName = canonical, aliases = names.drop(1))
    }

    private fun Entry.allNames(): List<String> = listOf(canonicalName) + aliases

    private fun compact(text: String): String {
        return OcrTextNormalizer.compact(OcrCorrectionStore.applyMerchant(text))
    }

    private fun minimumConfidence(candidateLength: Int, canonicalLength: Int): Double {
        val length = minOf(candidateLength, canonicalLength)
        return when {
            length <= 4 -> 0.92
            length <= 8 -> 0.84
            else -> 0.78
        }
    }

    private fun bestSimilarity(text: String, target: String): Double {
        if (text.length < 3 || target.length < 3) return 0.0
        val minWindow = (target.length - 2).coerceAtLeast(3)
        val maxWindow = (target.length + 2).coerceAtMost(text.length)
        if (minWindow > maxWindow) return similarity(text, target)

        var best = 0.0
        for (windowSize in minWindow..maxWindow) {
            for (start in 0..(text.length - windowSize)) {
                best = maxOf(best, similarity(text.substring(start, start + windowSize), target))
            }
        }
        return best
    }

    private fun similarity(a: String, b: String): Double {
        val distance = levenshteinDistance(a, b, 5)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
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

    private const val EXACT_MIN_COVERAGE = 0.70
}
