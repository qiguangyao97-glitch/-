package com.example.gongderefuser.analyzer

import android.content.Context
import com.example.gongderefuser.matching.KeywordMatchResult
import com.example.gongderefuser.matching.LocationAnalysisResult
import com.example.gongderefuser.matching.LocationKeywordRepository
import com.example.gongderefuser.matching.OrderLocationAnalyzer
import com.example.gongderefuser.model.OrderData

/**
 * 訂單分析器
 */
object OrderAnalyzer {

    data class AnalysisResult(
        val orderType: String,
        val price: Int,
        val minutes: Int,
        val distance: Double,
        val cost: Double,
        val netIncome: Double,
        val effectiveHourly: Double,
        val shouldAccept: Boolean,
        val score: Int,
        val recommendation: String,
        val storeName: String = "",
        val storeAddress: String = "",
        val isWhitelisted: Boolean = false,
        val matchedWhitelistKeyword: String = "",
        val whitelistNote: String = "",
        val isBlacklisted: Boolean = false,
        val matchedBlacklistKeyword: String = "",
        val blacklistNote: String = "",
        val locationScoreImpact: Int = 0,
        val strongestLocationLevel: String? = null,
        val matchedLocationKeyword: String = "",
        val matchedMerchantKeyword: String = "",
        val isSameLocationStack: Boolean = false
    )

    fun analyzeResult(context: Context, order: OrderData): AnalysisResult {
        val settings = RuleSettings.load(context)
        val whitelistEntry = findListEntry(order, settings.whitelistEntries)
        val blacklistEntry = if (whitelistEntry == null) {
            findListEntry(order, settings.blacklistEntries)
        } else {
            null
        }
        val isBlacklisted = blacklistEntry != null
        val rules = if (isBlacklisted) settings.blacklist else settings.normal
        val locationAnalysis = OrderLocationAnalyzer(LocationKeywordRepository.load(context))
            .analyze("${order.storeName}\n${order.address}")
        return analyzeResult(
            order = order,
            rules = rules,
            whitelistEntry = whitelistEntry,
            blacklistEntry = blacklistEntry,
            locationAnalysis = locationAnalysis
        )
    }

    fun analyzeResult(
        order: OrderData,
        targetHourly: Int = RuleManager.DEFAULT_TARGET_HOURLY
    ): AnalysisResult {
        return analyzeResult(
            order = order,
            rules = RuleSettings.RuleConfig(
                minPrice = 0,
                maxDistance = 999.0,
                maxMinutes = 999,
                targetHourly = targetHourly
            ),
            whitelistEntry = null,
            blacklistEntry = null,
            locationAnalysis = null
        )
    }

    internal fun analyzeResult(
        order: OrderData,
        rules: RuleSettings.RuleConfig,
        whitelistEntry: RuleSettings.ListEntry?,
        blacklistEntry: RuleSettings.ListEntry?,
        locationAnalysis: LocationAnalysisResult? = null
    ): AnalysisResult {
        val cost = order.distance * rules.costPerKm
        val netIncome = order.price - cost
        val effectiveHourly = (netIncome / order.minutes) * 60
        val isBlacklisted = blacklistEntry != null
        val passesRuleLimits = order.price >= rules.minPrice &&
                order.distance <= rules.maxDistance &&
                order.minutes <= rules.maxMinutes &&
                effectiveHourly >= rules.targetHourly
        val score = calculateScore(
            order = order,
            rules = rules,
            effectiveHourly = effectiveHourly,
            isWhitelisted = whitelistEntry != null,
            isBlacklisted = isBlacklisted,
            passesRuleLimits = passesRuleLimits
        )
        val finalScore = (score + (locationAnalysis?.totalScoreImpact ?: 0)).coerceIn(0, 100)

        return AnalysisResult(
            orderType = buildOrderType(order),
            price = order.price,
            minutes = order.minutes,
            distance = order.distance,
            cost = cost,
            netIncome = netIncome,
            effectiveHourly = effectiveHourly,
            shouldAccept = finalScore >= SCORE_ACCEPT,
            score = finalScore,
            recommendation = buildRecommendation(finalScore),
            storeName = order.storeName,
            storeAddress = order.address,
            isWhitelisted = whitelistEntry != null,
            matchedWhitelistKeyword = whitelistEntry?.keyword.orEmpty(),
            whitelistNote = whitelistEntry?.note.orEmpty(),
            isBlacklisted = isBlacklisted,
            matchedBlacklistKeyword = blacklistEntry?.keyword.orEmpty(),
            blacklistNote = blacklistEntry?.note.orEmpty(),
            locationScoreImpact = locationAnalysis?.totalScoreImpact ?: 0,
            strongestLocationLevel = locationAnalysis?.strongestLevel,
            matchedLocationKeyword = locationAnalysis?.addressMatches?.bestKeyword().orEmpty(),
            matchedMerchantKeyword = locationAnalysis?.merchantMatches?.bestKeyword().orEmpty(),
            isSameLocationStack = order.isSameLocationStack
        )
    }

    fun analyze(
        order: OrderData,
        targetHourly: Int = RuleManager.DEFAULT_TARGET_HOURLY
    ): String {
        val analysis = analyzeResult(order, targetHourly)

        val result = StringBuilder()

        result.appendLine("订单分析结果")
        result.appendLine("订单类型：${analysis.orderType}")
        result.appendLine("金额：${analysis.price} 元")
        result.appendLine("时间：${analysis.minutes} 分钟")
        result.appendLine("距离：${formatDistance(analysis.distance)} 公里")
        result.appendLine("成本：${formatMoney(analysis.cost)} 元")
        result.appendLine("本单净收益：${formatMoney(analysis.netIncome)} 元")
        result.appendLine("预计时薪：${formatMoney(analysis.effectiveHourly)} 元 / 小时")
        if (analysis.locationScoreImpact != 0) {
            result.appendLine("位置分：${analysis.locationScoreImpact}")
        }
        result.appendLine("评分：${analysis.score} 分")
        result.appendLine("建议：${analysis.recommendation}")

        return result.toString()
    }

    private fun calculateScore(
        order: OrderData,
        rules: RuleSettings.RuleConfig,
        effectiveHourly: Double,
        isWhitelisted: Boolean,
        isBlacklisted: Boolean,
        passesRuleLimits: Boolean
    ): Int {
        var score = BASE_SCORE

        if (passesRuleLimits) {
            score += PASS_RULE_BONUS
        }
        if (isWhitelisted) {
            score += WHITELIST_BONUS
        }
        if (isBlacklisted) {
            score -= BLACKLIST_PENALTY
        }
        if (order.isSameLocationStack) {
            score += SAME_LOCATION_STACK_BONUS
        }
        if (order.price < rules.minPrice) {
            score -= PRICE_PENALTY
        }
        if (order.distance > rules.maxDistance) {
            score -= DISTANCE_PENALTY
        }
        if (order.minutes > rules.maxMinutes) {
            score -= MINUTES_PENALTY
        }
        if (effectiveHourly < rules.targetHourly) {
            score -= HOURLY_PENALTY
        }

        return score.coerceIn(0, 100)
    }

    private fun buildRecommendation(score: Int): String {
        return when {
            score >= SCORE_ACCEPT -> "建议接单"
            score >= SCORE_CAUTION -> "慎重考虑"
            else -> "不建议接单"
        }
    }

    fun formatMoney(value: Double): String {
        return "%.0f".format(value)
    }

    fun formatDistance(value: Double): String {
        return if (value % 1.0 == 0.0) {
            "%.0f".format(value)
        } else {
            "%.1f".format(value)
        }
    }

    private fun buildOrderType(order: OrderData): String {
        return when {
            order.isSameLocationStack -> "夹单（爽单）"
            order.isStackOrder -> "叠单（${order.deliveryCount}单）"
            order.isExclusive -> "独享"
            else -> "独享"
        }
    }

    private fun findListEntry(
        order: OrderData,
        entries: List<RuleSettings.ListEntry>
    ): RuleSettings.ListEntry? {
        if (entries.isEmpty()) return null

        val searchableText = "${order.storeName}\n${order.address}"
        return entries.firstOrNull { entry ->
            matchesListKeyword(searchableText, entry.keyword)
        }
    }

    private fun matchesListKeyword(text: String, keyword: String): Boolean {
        val rawKeyword = keyword.trim()
        if (rawKeyword.length < 2) return false
        if (text.contains(rawKeyword, ignoreCase = true)) return true

        val normalizedText = normalizeForMatching(text)
        val normalizedKeyword = normalizeForMatching(rawKeyword)
        if (normalizedKeyword.length < 2) return false
        if (normalizedText.contains(normalizedKeyword, ignoreCase = true)) return true

        val maxDistance = when {
            normalizedKeyword.length < 4 -> return false
            normalizedKeyword.length < 7 -> 1
            else -> 2
        }
        return containsApproximate(normalizedText, normalizedKeyword, maxDistance)
    }

    private fun normalizeForMatching(value: String): String {
        val simplified = value.map { char ->
            traditionalToSimplified[char] ?: char
        }.joinToString("")
        return simplified
            .lowercase()
            .replace(Regex("[^\\p{IsHan}a-z0-9]"), "")
    }

    private fun containsApproximate(text: String, keyword: String, maxDistance: Int): Boolean {
        if (text.length < keyword.length) return false
        val minWindow = (keyword.length - maxDistance).coerceAtLeast(2)
        val maxWindow = (keyword.length + maxDistance).coerceAtMost(text.length)
        for (windowSize in minWindow..maxWindow) {
            for (start in 0..(text.length - windowSize)) {
                val candidate = text.substring(start, start + windowSize)
                if (levenshteinDistance(candidate, keyword, maxDistance) <= maxDistance) {
                    return true
                }
            }
        }
        return false
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

    private fun List<KeywordMatchResult>.bestKeyword(): String {
        return maxByOrNull { it.confidence }?.canonicalName.orEmpty()
    }

    private val traditionalToSimplified = mapOf(
        '長' to '长',
        '庚' to '庚',
        '醫' to '医',
        '院' to '院',
        '龜' to '龟',
        '區' to '区',
        '臺' to '台',
        '灣' to '湾',
        '園' to '园',
        '縣' to '县',
        '鄉' to '乡',
        '鎮' to '镇',
        '號' to '号',
        '樓' to '楼',
        '樂' to '乐',
        '善' to '善',
        '麥' to '麦',
        '當' to '当',
        '勞' to '劳',
        '復' to '复',
        '興' to '兴',
        '勝' to '胜',
        '客' to '客',
        '華' to '华',
        '廣' to '广',
        '國' to '国',
        '門' to '门'
    )

    private const val BASE_SCORE = 70
    private const val PASS_RULE_BONUS = 15
    private const val WHITELIST_BONUS = 15
    private const val BLACKLIST_PENALTY = 25
    private const val SAME_LOCATION_STACK_BONUS = 10
    private const val PRICE_PENALTY = 12
    private const val DISTANCE_PENALTY = 10
    private const val MINUTES_PENALTY = 8
    private const val HOURLY_PENALTY = 15
    private const val SCORE_ACCEPT = 75
    private const val SCORE_CAUTION = 55
}
