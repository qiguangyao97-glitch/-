package com.example.gongderefuser.analyzer

import android.content.Context

object RuleSettings {
    private const val PREFS_NAME = "gongde_refuser_rule_settings"

    private const val KEY_NORMAL_MIN_PRICE = "normal_min_price"
    private const val KEY_NORMAL_MAX_DISTANCE = "normal_max_distance"
    private const val KEY_NORMAL_MAX_MINUTES = "normal_max_minutes"
    private const val KEY_NORMAL_TARGET_HOURLY = "normal_target_hourly"
    private const val KEY_NORMAL_COST_PER_KM = "normal_cost_per_km"
    private const val KEY_BLACK_MIN_PRICE = "black_min_price"
    private const val KEY_BLACK_MAX_DISTANCE = "black_max_distance"
    private const val KEY_BLACK_MAX_MINUTES = "black_max_minutes"
    private const val KEY_BLACK_TARGET_HOURLY = "black_target_hourly"
    private const val KEY_BLACK_COST_PER_KM = "black_cost_per_km"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_BLACKLIST = "blacklist"

    data class RuleConfig(
        val minPrice: Int,
        val maxDistance: Double,
        val maxMinutes: Int,
        val targetHourly: Int,
        val costPerKm: Double = RuleManager.COST_PER_KM
    )

    data class Settings(
        val normal: RuleConfig,
        val blacklist: RuleConfig,
        val whitelistEntries: List<ListEntry>,
        val blacklistEntries: List<ListEntry>
    )

    data class ListEntry(
        val keyword: String,
        val note: String = ""
    )

    fun load(context: Context): Settings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Settings(
            normal = RuleConfig(
                minPrice = prefs.getInt(KEY_NORMAL_MIN_PRICE, 0),
                maxDistance = prefs.getFloat(KEY_NORMAL_MAX_DISTANCE, 999f).toDouble(),
                maxMinutes = prefs.getInt(KEY_NORMAL_MAX_MINUTES, 999),
                targetHourly = prefs.getInt(KEY_NORMAL_TARGET_HOURLY, RuleManager.DEFAULT_TARGET_HOURLY),
                costPerKm = prefs.getFloat(KEY_NORMAL_COST_PER_KM, RuleManager.COST_PER_KM.toFloat()).toDouble()
            ),
            blacklist = RuleConfig(
                minPrice = prefs.getInt(KEY_BLACK_MIN_PRICE, 150),
                maxDistance = prefs.getFloat(KEY_BLACK_MAX_DISTANCE, 999f).toDouble(),
                maxMinutes = prefs.getInt(KEY_BLACK_MAX_MINUTES, 999),
                targetHourly = prefs.getInt(KEY_BLACK_TARGET_HOURLY, 300),
                costPerKm = prefs.getFloat(KEY_BLACK_COST_PER_KM, RuleManager.COST_PER_KM.toFloat()).toDouble()
            ),
            whitelistEntries = parseEntries(prefs.getString(KEY_WHITELIST, "").orEmpty()),
            blacklistEntries = parseEntries(prefs.getString(KEY_BLACKLIST, "").orEmpty())
        )
    }

    fun save(
        context: Context,
        normal: RuleConfig,
        blacklist: RuleConfig,
        whitelistText: String,
        blacklistText: String
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NORMAL_MIN_PRICE, normal.minPrice)
            .putFloat(KEY_NORMAL_MAX_DISTANCE, normal.maxDistance.toFloat())
            .putInt(KEY_NORMAL_MAX_MINUTES, normal.maxMinutes)
            .putInt(KEY_NORMAL_TARGET_HOURLY, normal.targetHourly)
            .putFloat(KEY_NORMAL_COST_PER_KM, normal.costPerKm.toFloat())
            .putInt(KEY_BLACK_MIN_PRICE, blacklist.minPrice)
            .putFloat(KEY_BLACK_MAX_DISTANCE, blacklist.maxDistance.toFloat())
            .putInt(KEY_BLACK_MAX_MINUTES, blacklist.maxMinutes)
            .putInt(KEY_BLACK_TARGET_HOURLY, blacklist.targetHourly)
            .putFloat(KEY_BLACK_COST_PER_KM, blacklist.costPerKm.toFloat())
            .putString(KEY_WHITELIST, whitelistText.trim())
            .putString(KEY_BLACKLIST, blacklistText.trim())
            .apply()
    }

    fun loadWhitelistText(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WHITELIST, "")
            .orEmpty()
    }

    fun loadBlacklistText(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BLACKLIST, "")
            .orEmpty()
    }

    fun serializeEntries(entries: List<ListEntry>): String {
        return entries.joinToString("\n") { entry ->
            if (entry.note.isBlank()) entry.keyword else "${entry.keyword}|${entry.note}"
        }
    }

    fun parseEntries(text: String): List<ListEntry> {
        return text
            .lines()
            .flatMap { line ->
                line.split(',', '，', ';', '；')
            }
            .map { raw ->
                val parts = raw.trim().split('|', limit = 2)
                ListEntry(
                    keyword = parts.getOrNull(0).orEmpty().trim(),
                    note = parts.getOrNull(1).orEmpty().trim()
                )
            }
            .filter { it.keyword.length >= 2 }
            .distinctBy { it.keyword }
    }

    fun parseKeywords(text: String): List<String> {
        return parseEntries(text).map { it.keyword }
    }
}
