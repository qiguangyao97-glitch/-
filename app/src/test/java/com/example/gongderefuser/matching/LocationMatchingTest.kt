package com.example.gongderefuser.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocationMatchingTest {

    private val repository by lazy {
        LocationKeywordRepository.parse(
            listOf(
                File("src/main/assets/linkou_guishan_address_library_v1.txt"),
                File("app/src/main/assets/linkou_guishan_address_library_v1.txt")
            ).first { it.exists() }.readText()
        )
    }

    @Test
    fun parseLibraryLoadsRiskRulesAndReplacements() {
        assertTrue(repository.rules.any { it.level == "BLACK_STRONG" })
        assertTrue(repository.rules.any { it.category == "MERCHANT" })
        assertEquals("龜", repository.replacements["龟"])
        assertEquals("路", repository.replacements["珞"])
    }

    @Test
    fun fuzzyMatcherCorrectsGuishanWanshouRoad() {
        val normalized = OcrTextNormalizer.normalize(
            "龜出區萬壽珞一叚",
            repository.replacements
        )
        val match = FuzzyMatcher.match(
            normalizedText = normalized,
            rules = repository.addressRules,
            replacements = repository.replacements
        ).firstOrNull { it.level == "BLACK_STRONG" }

        assertNotNull(match)
        assertEquals("龜山區萬壽路一段", match!!.canonicalName)
        assertTrue(match.confidence >= 0.85)
    }

    @Test
    fun merchantAliasMatchesCommonChain() {
        val normalized = OcrTextNormalizer.normalize(
            "麥噹勞 林囗 A9",
            repository.replacements
        )
        val match = FuzzyMatcher.match(
            normalizedText = normalized,
            rules = repository.merchantRules,
            replacements = repository.replacements
        ).firstOrNull()

        assertNotNull(match)
        assertEquals("麥當勞", match!!.canonicalName)
    }
}
