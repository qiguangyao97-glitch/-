package com.example.gongderefuser

import android.content.Context
import com.example.gongderefuser.analyzer.OrderAnalyzer
import com.example.gongderefuser.analyzer.RuleSettings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OrderHistory {
    private const val PREF_NAME = "order_history"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 50

    data class Record(
        val timestamp: Long,
        val source: String,
        val storeName: String,
        val storeAddress: String,
        val orderType: String,
        val price: Int,
        val minutes: Int,
        val distance: Double,
        val effectiveHourly: Double,
        val shouldAccept: Boolean,
        val score: Int,
        val recommendation: String,
        val isBlacklisted: Boolean,
        val isWhitelisted: Boolean,
        val isSameLocationStack: Boolean,
        val acceptMode: String = "",
        val deliveryCount: Int = 1,
        val rewardPerTrip: Int = 0,
        val screenshotPath: String = ""
    ) {
        fun timeLabel(): String {
            return SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }

    fun add(
        context: Context,
        analysis: OrderAnalyzer.AnalysisResult,
        source: String,
        screenshotPath: String = ""
    ) {
        val records = load(context).toMutableList()
        records.add(
            0,
            Record(
                timestamp = System.currentTimeMillis(),
                source = source,
                storeName = analysis.storeName.ifBlank { "未识别商家" },
                storeAddress = analysis.storeAddress,
                orderType = analysis.orderType,
                price = analysis.price,
                minutes = analysis.minutes,
                distance = analysis.distance,
                effectiveHourly = analysis.effectiveHourly,
                shouldAccept = analysis.shouldAccept,
                score = analysis.score,
                recommendation = analysis.recommendation,
                isBlacklisted = analysis.isBlacklisted,
                isWhitelisted = analysis.isWhitelisted,
                isSameLocationStack = analysis.isSameLocationStack,
                acceptMode = acceptModeLabel(analysis.acceptMode),
                deliveryCount = analysis.deliveryCount,
                rewardPerTrip = analysis.rewardPerTrip,
                screenshotPath = screenshotPath
            )
        )
        save(context, records.take(MAX_RECORDS))
    }

    fun load(context: Context): List<Record> {
        val raw = prefs(context).getString(KEY_RECORDS, null) ?: return emptyList()
        val rule = RuleSettings.load(context).normal
        val scoreBase = rule.scoreBase
        val fatOrderMinAmount = rule.fatOrderMinAmount
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val hasScore = item.has("score")
                    val storedShouldAccept = item.optBoolean("shouldAccept")
                    val score = if (hasScore) {
                        item.optInt("score")
                    } else if (storedShouldAccept) {
                        OrderAnalyzer.acceptThreshold(scoreBase)
                    } else {
                        0
                    }
                    val shouldAccept = score >= OrderAnalyzer.acceptThreshold(scoreBase)
                    val price = item.optInt("price")
                    val recommendation = if (hasScore) {
                        OrderAnalyzer.recommendationForScore(score, scoreBase, price, fatOrderMinAmount)
                    } else if (storedShouldAccept) {
                        "站著掙"
                    } else {
                        "狗都不接"
                    }
                    add(
                        Record(
                            timestamp = item.optLong("timestamp"),
                            source = item.optString("source"),
                            storeName = item.optString("storeName", "未识别商家"),
                            storeAddress = item.optString("storeAddress"),
                            orderType = item.optString("orderType"),
                            price = price,
                            minutes = item.optInt("minutes"),
                            distance = item.optDouble("distance"),
                            effectiveHourly = item.optDouble("effectiveHourly"),
                            shouldAccept = shouldAccept,
                            score = score,
                            recommendation = recommendation,
                            isBlacklisted = item.optBoolean("isBlacklisted"),
                            isWhitelisted = item.optBoolean("isWhitelisted"),
                            isSameLocationStack = item.optBoolean("isSameLocationStack"),
                            acceptMode = item.optString("acceptMode"),
                            deliveryCount = item.optInt("deliveryCount", deliveryCountFromOrderType(item.optString("orderType"))),
                            rewardPerTrip = item.optInt("rewardPerTrip"),
                            screenshotPath = item.optString("screenshotPath")
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RECORDS).apply()
    }

    fun clearSource(context: Context, source: String) {
        save(context, load(context).filterNot { it.source == source })
    }

    fun delete(context: Context, timestamp: Long) {
        save(context, load(context).filterNot { it.timestamp == timestamp })
    }

    private fun save(context: Context, records: List<Record>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("timestamp", record.timestamp)
                    .put("source", record.source)
                    .put("storeName", record.storeName)
                    .put("storeAddress", record.storeAddress)
                    .put("orderType", record.orderType)
                    .put("price", record.price)
                    .put("minutes", record.minutes)
                    .put("distance", record.distance)
                    .put("effectiveHourly", record.effectiveHourly)
                    .put("shouldAccept", record.shouldAccept)
                    .put("score", record.score)
                    .put("recommendation", record.recommendation)
                    .put("isBlacklisted", record.isBlacklisted)
                    .put("isWhitelisted", record.isWhitelisted)
                    .put("isSameLocationStack", record.isSameLocationStack)
                    .put("acceptMode", record.acceptMode)
                    .put("deliveryCount", record.deliveryCount)
                    .put("rewardPerTrip", record.rewardPerTrip)
                    .put("screenshotPath", record.screenshotPath)
            )
        }
        prefs(context).edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun acceptModeLabel(mode: RuleSettings.AcceptMode): String {
        return when (mode) {
            RuleSettings.AcceptMode.REWARD -> "趟奖模式"
            RuleSettings.AcceptMode.NORMAL -> "正常模式"
        }
    }

    private fun deliveryCountFromOrderType(orderType: String): Int {
        return Regex("""\d+""").find(orderType)?.value?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
