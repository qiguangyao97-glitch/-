package com.example.gongderefuser

import android.content.Context
import com.example.gongderefuser.matching.OcrTextNormalizer

object MerchantDictionaryStore {
    data class Entry(
        val canonicalName: String,
        val aliases: List<String>
    )

    private data class IndexedEntry(
        val canonicalName: String,
        val compactNames: List<String>,
        val grams: Set<String>
    )

    @Volatile
    private var entries: List<IndexedEntry> = emptyList()

    fun load(context: Context) {
        runCatching {
            val assets = context.applicationContext.assets
            val base = assets.open("merchant_dictionary.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> parseEntries(reader.readText()) }
            val brands = assets.open("merchant_brand_roots.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> parseNames(reader.readText()) }
            val branches = assets.open("merchant_branch_suffixes.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> parseNames(reader.readText()) }
            entries = buildIndex((base + buildBranchEntries(brands, branches)).distinctBy {
                compact(it.canonicalName)
            })
        }
    }

    fun loadFromText(text: String) {
        entries = buildIndex(parseEntries(text))
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
            .flatMap { entry -> entry.compactNames.asSequence().map { name -> entry to name } }
            .filter { (_, compactName) ->
                compactName.length >= 4 &&
                    compactCandidate.contains(compactName) &&
                    compactName.length >= (compactCandidate.length * EXACT_MIN_COVERAGE).toInt()
            }
            .maxWithOrNull(compareBy<Pair<IndexedEntry, String>> { it.second.length })
            ?.first
            ?.canonicalName
    }

    private fun bestFuzzyMatch(compactCandidate: String): String? {
        val candidateGrams = grams(compactCandidate)
        if (candidateGrams.isEmpty()) return null

        return entries
            .asSequence()
            .filter { entry -> entry.grams.any(candidateGrams::contains) }
            .mapNotNull { entry ->
                val confidence = entry.compactNames
                    .asSequence()
                    .filter { it.length >= 4 }
                    .map { name -> bestSimilarity(compactCandidate, name) }
                    .maxOrNull() ?: 0.0
                val threshold = minimumConfidence(compactCandidate.length, entry.compactNames.firstOrNull()?.length ?: 0)
                if (confidence >= threshold) entry to confidence else null
            }
            .maxWithOrNull(
                compareBy<Pair<IndexedEntry, Double>> { it.second }
                    .thenBy { it.first.compactNames.firstOrNull()?.length ?: 0 }
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

    private fun parseEntries(text: String): List<Entry> {
        return text
            .lineSequence()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotBlank() }
            .mapNotNull(::parseEntry)
            .toList()
    }

    private fun parseNames(text: String): List<String> {
        return text
            .lineSequence()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun buildBranchEntries(brands: List<String>, branches: List<String>): List<Entry> {
        return brands.flatMap { brand ->
            branches.map { branch ->
                val canonical = "$brand $branch"
                Entry(
                    canonicalName = canonical,
                    aliases = listOf(
                        "$brand$branch",
                        canonical.replace("林口", "株口"),
                        canonical.replace("林口", "林囗"),
                        canonical.replace("龜山", "龟山")
                    ).distinct()
                )
            }
        }
    }

    private fun Entry.allNames(): List<String> = listOf(canonicalName) + aliases

    private fun buildIndex(rawEntries: List<Entry>): List<IndexedEntry> {
        return rawEntries.mapNotNull { entry ->
            val compactNames = entry.allNames()
                .map(::compact)
                .filter { it.length >= 3 }
                .distinct()
            if (compactNames.isEmpty()) {
                null
            } else {
                IndexedEntry(
                    canonicalName = entry.canonicalName,
                    compactNames = compactNames,
                    grams = compactNames.flatMap(::grams).toSet()
                )
            }
        }
    }

    private fun compact(text: String): String {
        return OcrTextNormalizer.compact(OcrCorrectionStore.applyMerchant(text))
    }

    private fun grams(text: String): Set<String> {
        if (text.length < 2) return emptySet()
        return (0 until text.length - 1)
            .map { index -> text.substring(index, index + 2) }
            .filterNot { gram -> noisyGrams.contains(gram) }
            .toSet()
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
    private val noisyGrams = setOf("林口", "龜山", "龟山", "總店", "总店")
}
