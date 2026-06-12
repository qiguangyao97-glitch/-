package com.example.gongderefuser.analyzer

import com.example.gongderefuser.model.OrderData
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
}
