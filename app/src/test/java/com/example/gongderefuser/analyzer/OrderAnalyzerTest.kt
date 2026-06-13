package com.example.gongderefuser.analyzer

import com.example.gongderefuser.model.OrderData
import com.example.gongderefuser.matching.KeywordMatchResult
import com.example.gongderefuser.matching.LocationAnalysisResult
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderAnalyzerTest {

    @Test
    fun goodOrderGetsAcceptRecommendation() {
        val analysis = OrderAnalyzer.analyzeResult(
            OrderData(
                price = 120,
                minutes = 25,
                distance = 4.0,
                isTargetOffer = true
            )
        )

        assertEquals("建议接单", analysis.recommendation)
        assertEquals(true, analysis.score >= 75)
    }

    @Test
    fun lowHourlyOrderGetsCautionRecommendation() {
        val analysis = OrderAnalyzer.analyzeResult(
            OrderData(
                price = 45,
                minutes = 40,
                distance = 8.0,
                isTargetOffer = true
            ),
            targetHourly = 300
        )

        assertEquals("慎重考虑", analysis.recommendation)
        assertEquals(true, analysis.score in 55..74)
    }

    @Test
    fun veryWeakOrderGetsRejectRecommendation() {
        val analysis = OrderAnalyzer.analyzeResult(
            order = OrderData(
                price = 45,
                minutes = 80,
                distance = 18.0,
                isTargetOffer = true
            ),
            rules = RuleSettings.RuleConfig(
                minPrice = 120,
                maxDistance = 8.0,
                maxMinutes = 35,
                targetHourly = 300
            ),
            whitelistEntry = null,
            blacklistEntry = null
        )

        assertEquals("不建议接单", analysis.recommendation)
        assertEquals(true, analysis.score < 55)
    }

    @Test
    fun hiddenLocationRiskDoesNotChangeScore() {
        val analysis = OrderAnalyzer.analyzeResult(
            order = OrderData(
                price = 120,
                minutes = 25,
                distance = 4.0,
                isTargetOffer = true,
                address = "龜山區萬壽路一段"
            ),
            rules = RuleSettings.RuleConfig(
                minPrice = 0,
                maxDistance = 999.0,
                maxMinutes = 999,
                targetHourly = 0
            ),
            whitelistEntry = null,
            blacklistEntry = null,
            locationAnalysis = LocationAnalysisResult(
                normalizedText = "龜山區萬壽路一段",
                addressMatches = listOf(
                    KeywordMatchResult(
                        canonicalName = "龜山區萬壽路一段",
                        matchedAlias = "萬壽路一段",
                        category = "ADDRESS_RULE",
                        district = "龜山區",
                        level = "BLACK_STRONG",
                        confidence = 1.0,
                        scoreImpact = -45
                    )
                ),
                merchantMatches = emptyList(),
                totalScoreImpact = -45,
                strongestLevel = "BLACK_STRONG"
            )
        )

        assertEquals(0, analysis.locationScoreImpact)
        assertEquals("BLACK_STRONG", analysis.strongestLocationLevel)
        assertEquals("建议接单", analysis.recommendation)
    }

    @Test
    fun manualListMatchingDoesNotUseApproximateTypos() {
        assertEquals(
            false,
            OrderAnalyzer.matchesManualListKeyword(
                text = "台灣桃園市龜山區長庚醫護新村170號",
                keyword = "長庚醫院"
            )
        )
    }

    @Test
    fun manualListMatchingKeepsTraditionalSimplifiedExactContainment() {
        assertEquals(
            true,
            OrderAnalyzer.matchesManualListKeyword(
                text = "麥味登 龜山丘比特",
                keyword = "龟山丘比特"
            )
        )
    }
}
