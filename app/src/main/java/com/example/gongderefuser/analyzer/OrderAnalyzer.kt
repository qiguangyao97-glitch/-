package com.example.gongderefuser.analyzer

import android.content.Context
import android.util.Log
import com.example.gongderefuser.matching.KeywordMatchResult
import com.example.gongderefuser.matching.LocationAnalysisResult
import com.example.gongderefuser.matching.LocationKeywordRepository
import com.example.gongderefuser.matching.OrderLocationAnalyzer
import com.example.gongderefuser.model.OrderData
import kotlin.math.roundToInt

/**
 * 訂單分析器
 */
object OrderAnalyzer {
    private const val SCORE_LOG_TAG = "ORDER_SCORE"

    data class AnalysisResult(
        val orderType: String,
        val price: Int,
        val minutes: Int,
        val distance: Double,
        val cost: Double,
        val netIncome: Double,
        val effectiveHourly: Double,
        val yuanPerKm: Double,
        val averagePrice: Double,
        val hourlyRatio: Double,
        val hourlyScore: Double,
        val yuanPerKmScore: Double,
        val averagePriceScore: Double,
        val baseScore: Double,
        val scoreMoneySource: String = "原始訂單金額",
        val distancePenalty: Int = 0,
        val distancePenaltyReason: String = "",
        val specialScore: Int,
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
        val isSameLocationStack: Boolean = false,
        val deliveryCount: Int = 1,
        val isMerchantWhitelisted: Boolean = false,
        val isAddressWhitelisted: Boolean = false,
        val isMerchantBlacklisted: Boolean = false,
        val isAddressBlacklisted: Boolean = false,
        val matchedWhitelistMerchantKeyword: String = "",
        val matchedWhitelistAddressKeyword: String = "",
        val matchedBlacklistMerchantKeyword: String = "",
        val matchedBlacklistAddressKeyword: String = "",
        val originalPrice: Int = price,
        val effectiveDeliveryCount: Int = deliveryCount.coerceAtLeast(1),
        val subsidyPerOrder: Int = 0,
        val subsidyTotal: Int = 0,
        val effectiveMoney: Int = price,
        val acceptMode: RuleSettings.AcceptMode = RuleSettings.AcceptMode.NORMAL,
        val rewardPerTrip: Int = 0,
        val rewardModeBonus: Int = 0,
        val rewardModeEligible: Boolean = false,
        val rewardModeReason: String = "",
        val multiOrderFactor: Double = 1.0,
        val multiOrderAveragePerOrder: Double = averagePrice
    )

    fun analyzeResult(context: Context, order: OrderData): AnalysisResult {
        val settings = RuleSettings.load(context)
        val whitelistMerchantEntry = findListEntry(order.storeName, settings.whitelistEntries)
        val whitelistAddressEntry = findListEntry(order.address, settings.whitelistEntries)
        val blacklistMerchantEntry = findListEntry(order.storeName, settings.blacklistEntries)
        val blacklistAddressEntry = findListEntry(order.address, settings.blacklistEntries)
        val whitelistEntry = whitelistMerchantEntry ?: whitelistAddressEntry
        val blacklistEntry = blacklistMerchantEntry ?: blacklistAddressEntry
        val locationAnalysis = OrderLocationAnalyzer(LocationKeywordRepository.load(context))
            .analyze("${order.storeName}\n${order.address}")
        val result = analyzeResult(
            order = order,
            rules = settings.normal,
            whitelistEntry = whitelistEntry,
            blacklistEntry = blacklistEntry,
            whitelistMerchantEntry = whitelistMerchantEntry,
            whitelistAddressEntry = whitelistAddressEntry,
                blacklistMerchantEntry = blacklistMerchantEntry,
                blacklistAddressEntry = blacklistAddressEntry,
                locationAnalysis = locationAnalysis,
                acceptMode = settings.acceptMode,
                rewardPerTrip = settings.rewardPerTrip
        )
        Log.i(
            SCORE_LOG_TAG,
            buildScoreDebugLog(
                rules = settings.normal,
                order = order,
                analysis = result,
                blacklistEntry = blacklistEntry
            )
        )
        return result
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
                targetHourly = targetHourly,
                targetYuanPerKm = RuleManager.DEFAULT_TARGET_YUAN_PER_KM,
                targetAveragePrice = RuleManager.DEFAULT_TARGET_AVERAGE_PRICE
            ),
            whitelistEntry = null,
            blacklistEntry = null,
            locationAnalysis = null,
            acceptMode = RuleSettings.AcceptMode.NORMAL,
            rewardPerTrip = 0
        )
    }

    internal fun analyzeResult(
        order: OrderData,
        rules: RuleSettings.RuleConfig,
        whitelistEntry: RuleSettings.ListEntry?,
        blacklistEntry: RuleSettings.ListEntry?,
        whitelistMerchantEntry: RuleSettings.ListEntry? = null,
        whitelistAddressEntry: RuleSettings.ListEntry? = null,
        blacklistMerchantEntry: RuleSettings.ListEntry? = null,
        blacklistAddressEntry: RuleSettings.ListEntry? = null,
        locationAnalysis: LocationAnalysisResult? = null,
        acceptMode: RuleSettings.AcceptMode = RuleSettings.AcceptMode.NORMAL,
        rewardPerTrip: Int = 0
    ): AnalysisResult {
        val originalMoney = order.price
        val effectiveDeliveryCount = order.deliveryCount.coerceAtLeast(1)
        val subsidyPerOrder = 0
        val subsidyTotal = 0
        val effectiveMoney = originalMoney
        val cost = order.distance * rules.costPerKm
        val netIncome = effectiveMoney - cost
        val effectiveHourly = if (order.minutes > 0) {
            (effectiveMoney.toDouble() / order.minutes) * 60
        } else {
            0.0
        }
        val yuanPerKm = if (order.distance > 0.0) effectiveMoney / order.distance else 0.0
        val averagePrice = effectiveMoney.toDouble() / effectiveDeliveryCount
        val hourlyRatio = if (rules.targetHourly > 0) effectiveHourly / rules.targetHourly else 0.0
        val hourlyScore = scoreFromRatio(hourlyRatio, rules.scoreBase)
        val yuanPerKmRatio = if (rules.targetYuanPerKm > 0.0) yuanPerKm / rules.targetYuanPerKm else 0.0
        val yuanPerKmScore = scoreFromRatio(yuanPerKmRatio, rules.scoreBase)
        val averagePriceRatio = if (rules.targetAveragePrice > 0.0) averagePrice / rules.targetAveragePrice else 0.0
        val averagePriceScore = scoreFromRatio(averagePriceRatio, rules.scoreBase)
        val rawBaseScore = hourlyScore * 0.45 + yuanPerKmScore * 0.35 + averagePriceScore * 0.20
        val multiOrderFactor = if (effectiveDeliveryCount >= 2) {
            (0.65 + averagePriceRatio * 0.35).coerceIn(0.75, 1.10)
        } else {
            1.0
        }
        val baseScore = rawBaseScore * multiOrderFactor
        val isBlacklisted = blacklistEntry != null
        val distancePenalty = distancePenaltyFor(order.distance)
        val rewardMode = calculateRewardModeBonus(
            order = order,
            rules = rules,
            acceptMode = acceptMode,
            rewardPerTrip = rewardPerTrip,
            isBlacklisted = isBlacklisted,
            hourlyRatio = hourlyRatio,
            yuanPerKmRatio = yuanPerKmRatio,
            averagePrice = averagePrice
        )
        val bonusScore = (if (order.isSameLocationStack) SAME_DROPOFF_BONUS else 0) + rewardMode.bonus
        val penaltyScore = (if (isBlacklisted) BLACKLIST_PENALTY else 0) +
                distancePenalty.points
        val specialScore = bonusScore - penaltyScore
        val score = (baseScore + bonusScore - penaltyScore).roundToInt().coerceIn(0, 100)
        val acceptThreshold = acceptThreshold(rules.scoreBase)
        val recommendation = recommendationForScore(score, rules.scoreBase, originalMoney, rules.fatOrderMinAmount)
        val shouldAccept = score >= acceptThreshold
        return AnalysisResult(
            orderType = buildOrderType(order),
            price = effectiveMoney,
            minutes = order.minutes,
            distance = order.distance,
            cost = cost,
            netIncome = netIncome,
            effectiveHourly = effectiveHourly,
            yuanPerKm = yuanPerKm,
            averagePrice = averagePrice,
            hourlyRatio = hourlyRatio,
            hourlyScore = hourlyScore,
            yuanPerKmScore = yuanPerKmScore,
            averagePriceScore = averagePriceScore,
            baseScore = baseScore,
            distancePenalty = distancePenalty.points,
            distancePenaltyReason = distancePenalty.reason,
            specialScore = specialScore,
            shouldAccept = shouldAccept,
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
            isSameLocationStack = order.isSameLocationStack,
            deliveryCount = order.deliveryCount,
            isMerchantWhitelisted = whitelistMerchantEntry != null,
            isAddressWhitelisted = whitelistAddressEntry != null,
            isMerchantBlacklisted = blacklistMerchantEntry != null,
            isAddressBlacklisted = blacklistAddressEntry != null,
            matchedWhitelistMerchantKeyword = whitelistMerchantEntry?.keyword.orEmpty(),
            matchedWhitelistAddressKeyword = whitelistAddressEntry?.keyword.orEmpty(),
            matchedBlacklistMerchantKeyword = blacklistMerchantEntry?.keyword.orEmpty(),
            matchedBlacklistAddressKeyword = blacklistAddressEntry?.keyword.orEmpty(),
            originalPrice = originalMoney,
            effectiveDeliveryCount = effectiveDeliveryCount,
            subsidyPerOrder = subsidyPerOrder,
            subsidyTotal = subsidyTotal,
            effectiveMoney = effectiveMoney,
            acceptMode = acceptMode,
            rewardPerTrip = rewardPerTrip,
            rewardModeBonus = rewardMode.bonus,
            rewardModeEligible = rewardMode.eligible,
            rewardModeReason = rewardMode.reason,
            multiOrderFactor = multiOrderFactor,
            multiOrderAveragePerOrder = averagePrice
        )
    }

    private data class RewardModeBonus(
        val bonus: Int,
        val eligible: Boolean,
        val reason: String
    )

    private data class DistancePenalty(
        val points: Int,
        val reason: String
    )

    private fun distancePenaltyFor(distanceKm: Double): DistancePenalty {
        return when {
            distanceKm >= 12.0 -> DistancePenalty(30, "distance_12km_plus")
            distanceKm >= 10.0 -> DistancePenalty(22, "distance_10_12km")
            distanceKm >= 8.0 -> DistancePenalty(12, "distance_8_10km")
            distanceKm >= 5.0 -> DistancePenalty(5, "distance_5_8km")
            else -> DistancePenalty(0, "none")
        }
    }

    private fun calculateRewardModeBonus(
        order: OrderData,
        rules: RuleSettings.RuleConfig,
        acceptMode: RuleSettings.AcceptMode,
        rewardPerTrip: Int,
        isBlacklisted: Boolean,
        hourlyRatio: Double,
        yuanPerKmRatio: Double,
        averagePrice: Double
    ): RewardModeBonus {
        if (acceptMode != RuleSettings.AcceptMode.REWARD) {
            return RewardModeBonus(0, false, "mode_normal")
        }
        if (rewardPerTrip <= 0) {
            return RewardModeBonus(0, false, "reward_zero")
        }
        val count = order.deliveryCount.coerceAtLeast(1)
        if (count <= 1) {
            return RewardModeBonus(0, false, "single_order")
        }
        if (isBlacklisted) {
            return RewardModeBonus(0, false, "blacklist_matched")
        }
        val averagePriceRatio = if (rules.targetAveragePrice > 0.0) {
            averagePrice / rules.targetAveragePrice
        } else {
            0.0
        }
        if (hourlyRatio < REWARD_MIN_HOURLY_RATIO) {
            return RewardModeBonus(0, false, "low_hourly")
        }
        if (yuanPerKmRatio < REWARD_MIN_PER_KM_RATIO) {
            return RewardModeBonus(0, false, "low_per_km")
        }
        if (averagePriceRatio < REWARD_MIN_AVERAGE_PRICE_RATIO) {
            return RewardModeBonus(0, false, "low_average_price")
        }
        val rewardValueForExtraTrips = rewardPerTrip * (count - 1)
        val bonus = ((rewardValueForExtraTrips.toDouble() / order.price.coerceAtLeast(1)) * 25.0)
            .roundToInt()
            .coerceIn(1, REWARD_MODE_MAX_BONUS)
        return RewardModeBonus(bonus, true, "multi_order_reward")
    }

    private fun buildScoreDebugLog(
        rules: RuleSettings.RuleConfig,
        order: OrderData,
        analysis: AnalysisResult,
        blacklistEntry: RuleSettings.ListEntry?
    ): String {
        val baseScore = analysis.baseScore
        val doubleOrderMatched = order.deliveryCount > 1
        val doubleOrderBonus = analysis.rewardModeBonus
        val sameDropoffMatched = order.isSameLocationStack
        val sameDropoffBonus = if (sameDropoffMatched) SAME_DROPOFF_BONUS else 0
        val bonusScore = doubleOrderBonus + sameDropoffBonus
        val blacklistMatched = blacklistEntry != null
        val blacklistPenalty = if (blacklistMatched) BLACKLIST_PENALTY else 0
        val lowAmountMatched = order.price in 1 until rules.minPrice
        val lowAmountPenalty = 0
        val otherPenaltyReason = analysis.distancePenaltyReason
        val otherPenalty = analysis.distancePenalty
        val penaltyScore = blacklistPenalty + lowAmountPenalty + otherPenalty
        val finalScoreBeforeClamp = baseScore + bonusScore - penaltyScore
        return buildString {
            appendLine("===== RULE SETTINGS =====")
            appendLine("targetHourly=${rules.targetHourly}")
            appendLine("targetPerKm=${formatMoney(rules.targetYuanPerKm)}")
            appendLine("minOrderAmount=${rules.minPrice}")
            appendLine("minScore=${rules.minScore}")
            appendLine("scoreBase=${rules.scoreBase}")
            appendLine("fatOrderMinAmount=${rules.fatOrderMinAmount}")
            appendLine("acceptMode=${analysis.acceptMode}")
            appendLine("rewardPerTrip=${analysis.rewardPerTrip}")
            appendLine()
            appendLine("===== ORDER RAW DATA =====")
            appendLine("money=${order.price}")
            appendLine("minutes=${order.minutes}")
            appendLine("distanceKm=${formatDistance(order.distance)}")
            appendLine("deliveryCount=${order.deliveryCount}")
            appendLine("isPairOffer=${order.deliveryCount > 1}")
            appendLine("sameDropoff=${order.isSameLocationStack}")
            appendLine("merchant=${order.storeName}")
            appendLine("address=${order.address}")
            appendLine("priceStatus=${order.priceStatus}")
            appendLine("tripStatus=${order.tripStatus}")
            appendLine("merchantStatus=${order.merchantStatus}")
            appendLine("addressStatus=${order.addressStatus}")
            appendLine("typeStatus=${order.typeStatus}")
            appendLine()
            appendLine("===== REWARD MODE DEBUG =====")
            appendLine("originalMoney=${analysis.originalPrice}")
            appendLine("scoreMoneySource=${analysis.scoreMoneySource}")
            appendLine("deliveryCount=${order.deliveryCount}")
            appendLine("effectiveDeliveryCount=${analysis.effectiveDeliveryCount}")
            appendLine("acceptMode=${analysis.acceptMode}")
            appendLine("rewardPerTrip=${analysis.rewardPerTrip}")
            appendLine("rewardModeEligible=${analysis.rewardModeEligible}")
            appendLine("rewardModeBonus=${analysis.rewardModeBonus}")
            appendLine("rewardModeReason=${analysis.rewardModeReason}")
            appendLine("scoringMoney=${analysis.effectiveMoney}")
            appendLine()
            appendLine("===== CALCULATED METRICS =====")
            appendLine("hourly=${formatMoney(analysis.effectiveHourly)}")
            appendLine("perKm=${formatMoney(analysis.yuanPerKm)}")
            appendLine("averagePerOrder=${formatMoney(analysis.multiOrderAveragePerOrder)}")
            appendLine("multiOrderFactor=${formatRatio(analysis.multiOrderFactor)}")
            appendLine("hourlyRatio=${formatRatio(analysis.hourlyRatio)}")
            appendLine("perKmRatio=${formatRatio(if (rules.targetYuanPerKm > 0.0) analysis.yuanPerKm / rules.targetYuanPerKm else 0.0)}")
            appendLine()
            appendLine("===== SCORE COMPONENTS =====")
            appendLine("hourlyScore=${analysis.hourlyScore.roundToInt()}")
            appendLine("perKmScore=${analysis.yuanPerKmScore.roundToInt()}")
            appendLine("averagePriceScore=${analysis.averagePriceScore.roundToInt()}")
            appendLine("baseScoreFormula=hourly*0.45+perKm*0.35+average*0.20")
            appendLine("multiOrderFactor=${formatRatio(analysis.multiOrderFactor)}")
            appendLine("baseScore=${baseScore.roundToInt()}")
            appendLine()
            appendLine("===== SCORE DEBUG =====")
            appendLine("模式=${acceptModeLabel(analysis.acceptMode)}")
            appendLine("金額來源=${analysis.scoreMoneySource}")
            appendLine("時薪評分=${analysis.hourlyScore.roundToInt()}")
            appendLine("元公里評分=${analysis.yuanPerKmScore.roundToInt()}")
            appendLine("平均單價評分=${analysis.averagePriceScore.roundToInt()}")
            appendLine("基礎評分=${analysis.baseScore.roundToInt()}")
            appendLine("趟獎加權=${analysis.rewardModeBonus}")
            appendLine("趟獎原因=${analysis.rewardModeReason}")
            appendLine("多單動態系數=${formatRatio(analysis.multiOrderFactor)}")
            appendLine("最終評分=${analysis.score}")
            appendLine()
            appendLine("===== BONUS DEBUG =====")
            appendLine("doubleOrderMatched=$doubleOrderMatched")
            appendLine("doubleOrderBonus=$doubleOrderBonus")
            appendLine("rewardModeEligible=${analysis.rewardModeEligible}")
            appendLine("rewardModeReason=${analysis.rewardModeReason}")
            appendLine("sameDropoffMatched=$sameDropoffMatched")
            appendLine("sameDropoffBonus=$sameDropoffBonus")
            appendLine("bonusScore=$bonusScore")
            appendLine()
            appendLine("===== PENALTY DEBUG =====")
            appendLine("blacklistMatched=$blacklistMatched")
            appendLine("blacklistKeyword=${blacklistEntry?.keyword.orEmpty()}")
            appendLine("blacklistPenalty=$blacklistPenalty")
            appendLine("lowAmountMatched=$lowAmountMatched")
            appendLine("lowAmountPenalty=$lowAmountPenalty")
            appendLine("DISTANCE_PENALTY")
            appendLine("distanceKm=${formatDistance(order.distance)}")
            appendLine("distancePenalty=${analysis.distancePenalty}")
            appendLine("distancePenaltyReason=${analysis.distancePenaltyReason}")
            appendLine("otherPenaltyReason=$otherPenaltyReason")
            appendLine("otherPenalty=$otherPenalty")
            appendLine("penaltyScore=$penaltyScore")
            appendLine()
            appendLine("===== FINAL SCORE DEBUG =====")
            appendLine("baseScore=${baseScore.roundToInt()}")
            appendLine("bonusScore=$bonusScore")
            appendLine("penaltyScore=$penaltyScore")
            appendLine("finalScoreBeforeClamp=${formatMoney(finalScoreBeforeClamp)}")
            appendLine("finalScore=${analysis.score}")
            appendLine("minScore=${rules.minScore}")
            appendLine("passedMinScore=${analysis.score >= rules.minScore}")
            appendLine("resultText=${analysis.recommendation}")
        }.trim()
    }

    private fun scoreFromRatio(ratio: Double, scoreBase: Int): Double {
        val base = scoreBase.coerceIn(50, 100).toDouble()
        val score = if (ratio >= 1.0) {
            base + ((ratio - 1.0) * (100.0 - base))
        } else {
            base * ratio
        }
        return score.coerceIn(0.0, 100.0)
    }

    private fun formatRatio(value: Double): String {
        return String.format(java.util.Locale.US, "%.3f", value)
    }

    fun analyze(
        context: Context,
        order: OrderData
    ): String {
        return buildAnalysisText(analyzeResult(context, order))
    }

    fun analyze(
        order: OrderData,
        targetHourly: Int = RuleManager.DEFAULT_TARGET_HOURLY
    ): String {
        return buildAnalysisText(analyzeResult(order, targetHourly))
    }

    private fun buildAnalysisText(analysis: AnalysisResult): String {
        val result = StringBuilder()

        result.appendLine("訂單分析結果")
        result.appendLine("訂單類型：${analysis.orderType}")
        result.appendLine("金額：${analysis.price} 元")
        result.appendLine("時間：${analysis.minutes} 分鐘")
        result.appendLine("距離：${formatDistance(analysis.distance)} 公里")
        result.appendLine("成本：${formatMoney(analysis.cost)} 元")
        result.appendLine("本單淨收益：${formatMoney(analysis.netIncome)} 元")
        result.appendLine("預計時薪：${formatMoney(analysis.effectiveHourly)} 元 / 小時")
        result.appendLine("元/公里：${formatMoney(analysis.yuanPerKm)} 元 / 公里")
        result.appendLine("平均單價：${formatMoney(analysis.averagePrice)} 元")
        result.appendLine("金額來源：${analysis.scoreMoneySource}")
        result.appendLine("時薪評分：${analysis.hourlyScore.roundToInt()} 分")
        result.appendLine("元/km評分：${analysis.yuanPerKmScore.roundToInt()} 分")
        result.appendLine("平均單價評分：${analysis.averagePriceScore.roundToInt()} 分")
        result.appendLine("基礎評分：${analysis.baseScore.roundToInt()} 分")
        if (analysis.acceptMode == RuleSettings.AcceptMode.REWARD) {
            result.appendLine("趟獎加權：+${analysis.rewardModeBonus} 分")
        }
        result.appendLine("最終評分：${analysis.score} 分")
        result.appendLine("配送數量：${analysis.deliveryCount} 單")
        if (analysis.locationScoreImpact != 0) {
            result.appendLine("位置分：${analysis.locationScoreImpact}")
        }
        result.appendLine("評分：${analysis.score} 分")
        result.appendLine("建議：${analysis.recommendation}")

        return result.toString()
    }

    private fun calculateSpecialScore(
        order: OrderData,
        isBlacklisted: Boolean
    ): Int {
        var score = 0
        if (order.isSameLocationStack) {
            score += SAME_DROPOFF_BONUS
        }
        if (isBlacklisted) {
            score -= BLACKLIST_PENALTY
        }

        return score.coerceIn(-10, 5)
    }

    fun acceptThreshold(scoreBase: Int): Int {
        return maxOf(scoreBase.coerceIn(0, 100), SCORE_MIN_ACCEPT_THRESHOLD)
    }

    fun recommendationForScore(
        score: Int,
        scoreBase: Int,
        money: Int = 0,
        fatOrderMinAmount: Int = RuleManager.DEFAULT_FAT_ORDER_MIN_AMOUNT
    ): String {
        return buildRecommendation(
            score = score.coerceIn(0, 100),
            acceptThreshold = acceptThreshold(scoreBase),
            money = money,
            fatOrderMinAmount = fatOrderMinAmount.coerceAtLeast(0)
        )
    }

    private fun acceptModeLabel(mode: RuleSettings.AcceptMode): String {
        return when (mode) {
            RuleSettings.AcceptMode.REWARD -> "趟獎模式"
            RuleSettings.AcceptMode.NORMAL -> "正常模式"
        }
    }

    private fun buildRecommendation(
        score: Int,
        acceptThreshold: Int,
        money: Int,
        fatOrderMinAmount: Int
    ): String {
        return when {
            score >= SCORE_SURPRISE && money >= fatOrderMinAmount -> "掙他娘的"
            score >= acceptThreshold -> "站著掙"
            score >= SCORE_REVIEW -> "跪著送"
            else -> "狗都不接"
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
        if (order.isAddOnOrder) return "新增外送訂單"
        val count = order.deliveryCount.coerceAtLeast(1)
        return if (count == 1) {
            "一單"
        } else {
            "${count}單"
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

    private fun findListEntry(
        text: String,
        entries: List<RuleSettings.ListEntry>
    ): RuleSettings.ListEntry? {
        if (entries.isEmpty() || text.isBlank()) return null
        return entries.firstOrNull { entry ->
            matchesManualListKeyword(text, entry.keyword)
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

    private const val SAME_DROPOFF_BONUS = 5
    private const val BLACKLIST_PENALTY = 10
    private const val REWARD_MODE_MAX_BONUS = 15
    private const val REWARD_MIN_HOURLY_RATIO = 0.70
    private const val REWARD_MIN_PER_KM_RATIO = 0.70
    private const val REWARD_MIN_AVERAGE_PRICE_RATIO = 0.70
    private const val SCORE_REVIEW = 60
    private const val SCORE_MIN_ACCEPT_THRESHOLD = 60
    private const val SCORE_SURPRISE = 90
}
