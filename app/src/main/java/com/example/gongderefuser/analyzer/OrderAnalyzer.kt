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
        val blacklistEntry = findListEntry(order, settings.blacklistEntries)
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
                minPrice = 35,
                maxDistance = 10.0,
                maxMinutes = 35,
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
        val belowTargetHourly = rules.targetHourly > 0 && effectiveHourly < rules.targetHourly
        val score = calculateScore(
            order = order,
            rules = rules,
            isBlacklisted = isBlacklisted
        )
        val recommendation = buildRecommendation(score, belowTargetHourly)
        return AnalysisResult(
            orderType = buildOrderType(order),
            price = order.price,
            minutes = order.minutes,
            distance = order.distance,
            cost = cost,
            netIncome = netIncome,
            effectiveHourly = effectiveHourly,
            shouldAccept = recommendation == "建议接单",
            score = score,
            recommendation = recommendation,
            storeName = order.storeName,
            storeAddress = order.address,
            isWhitelisted = whitelistEntry != null,
            matchedWhitelistKeyword = whitelistEntry?.keyword.orEmpty(),
            whitelistNote = whitelistEntry?.note.orEmpty(),
            isBlacklisted = isBlacklisted,
            matchedBlacklistKeyword = blacklistEntry?.keyword.orEmpty(),
            blacklistNote = blacklistEntry?.note.orEmpty(),
            locationScoreImpact = 0,
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
        isBlacklisted: Boolean
    ): Int {
        var score = BASE_SCORE

        score += proportionalScore(order.price.toDouble(), rules.minPrice.toDouble(), PRICE_WEIGHT, higherIsBetter = true)
        score += proportionalScore(order.distance, rules.maxDistance, DISTANCE_WEIGHT, higherIsBetter = false)
        score += proportionalScore(order.minutes.toDouble(), rules.maxMinutes.toDouble(), MINUTES_WEIGHT, higherIsBetter = false)
        if (isBlacklisted) {
            score -= BLACKLIST_PENALTY
        }
        if (order.isSameLocationStack) {
            score += SAME_LOCATION_STACK_BONUS
        }

        return score.coerceIn(0, 100)
    }

    private fun proportionalScore(
        actual: Double,
        target: Double,
        weight: Int,
        higherIsBetter: Boolean
    ): Int {
        if (target <= 0.0 || target >= 900.0 || actual <= 0.0) return 0
        val ratio = if (higherIsBetter) {
            (actual - target) / target
        } else {
            (target - actual) / target
        }
        return (ratio * weight).toInt().coerceIn(-weight, weight)
    }

    private fun buildRecommendation(score: Int, belowTargetHourly: Boolean): String {
        return when {
            score >= SCORE_ACCEPT && !belowTargetHourly -> "建议接单"
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
        if (order.isAddOnOrder) return "新增外送订单"
        val count = order.deliveryCount.coerceAtLeast(1)
        return if (count == 1) {
            "一单"
        } else {
            "${count}单"
        }
    }

    private fun findListEntry(
        order: OrderData,
        entries: List<RuleSettings.ListEntry>
    ): RuleSettings.ListEntry? {
        if (entries.isEmpty()) return null

        val searchableText = "${order.storeName}\n${order.address}"
        return entries.firstOrNull { entry ->
            matchesManualListKeyword(searchableText, entry.keyword)
        }
    }

    internal fun matchesManualListKeyword(text: String, keyword: String): Boolean {
        val rawKeyword = keyword.trim()
        if (rawKeyword.length < 2) return false
        if (text.contains(rawKeyword, ignoreCase = true)) return true

        val normalizedText = normalizeForMatching(text)
        val normalizedKeyword = normalizeForMatching(rawKeyword)
        if (normalizedKeyword.length < 2) return false
        return normalizedText.contains(normalizedKeyword, ignoreCase = true)
    }

    private fun normalizeForMatching(value: String): String {
        val simplified = value.map { char ->
            traditionalToSimplified[char] ?: char
        }.joinToString("")
        return simplified
            .lowercase()
            .replace(Regex("[^\\p{IsHan}a-z0-9]"), "")
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

    private const val BASE_SCORE = 60
    private const val PRICE_WEIGHT = 20
    private const val DISTANCE_WEIGHT = 15
    private const val MINUTES_WEIGHT = 15
    private const val BLACKLIST_PENALTY = 25
    private const val SAME_LOCATION_STACK_BONUS = 10
    private const val SCORE_ACCEPT = 75
    private const val SCORE_CAUTION = 55
}
