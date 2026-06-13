package com.example.gongderefuser

import android.content.Context
import com.example.gongderefuser.analyzer.OrderAnalyzer
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
        val isSameLocationStack: Boolean
    ) {
        fun timeLabel(): String {
            return SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }

    fun add(context: Context, analysis: OrderAnalyzer.AnalysisResult, source: String) {
        val records = load(context).toMutableList()
        records.add(
            0,
            Record(
                timestamp = System.currentTimeMillis(),
                source = source,
                storeName = analysis.storeName.ifBlank { "未识别商家" },
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
                isSameLocationStack = analysis.isSameLocationStack
            )
        )
        save(context, records.take(MAX_RECORDS))
    }

    fun load(context: Context): List<Record> {
        val raw = prefs(context).getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        Record(
                            timestamp = item.optLong("timestamp"),
                            source = item.optString("source"),
                            storeName = item.optString("storeName", "未识别商家"),
                            orderType = item.optString("orderType"),
                            price = item.optInt("price"),
                            minutes = item.optInt("minutes"),
                            distance = item.optDouble("distance"),
                            effectiveHourly = item.optDouble("effectiveHourly"),
                            shouldAccept = item.optBoolean("shouldAccept"),
                            score = item.optInt("score", if (item.optBoolean("shouldAccept")) 85 else 40),
                            recommendation = item.optString(
                                "recommendation",
                                if (item.optBoolean("shouldAccept")) "建议接单" else "不建议接单"
                            ),
                            isBlacklisted = item.optBoolean("isBlacklisted"),
                            isWhitelisted = item.optBoolean("isWhitelisted"),
                            isSameLocationStack = item.optBoolean("isSameLocationStack")
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RECORDS).apply()
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
            )
        }
        prefs(context).edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
