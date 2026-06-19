package com.example.gongderefuser.analyzer

import android.content.Context

object RuleSettings {
    private const val PREFS_NAME = "gongde_refuser_rule_settings"

    private const val KEY_NORMAL_MIN_PRICE = "normal_min_price"
    private const val KEY_NORMAL_MAX_DISTANCE = "normal_max_distance"
    private const val KEY_NORMAL_MAX_MINUTES = "normal_max_minutes"
    private const val KEY_NORMAL_TARGET_HOURLY = "normal_target_hourly"
    private const val KEY_NORMAL_TARGET_YUAN_PER_KM = "normal_target_yuan_per_km"
    private const val KEY_NORMAL_TARGET_AVERAGE_PRICE = "normal_target_average_price"
    private const val KEY_NORMAL_MIN_SCORE = "normal_min_score"
    private const val KEY_NORMAL_SCORE_BASE = "normal_score_base"
    private const val KEY_NORMAL_SUBSIDY_PER_ORDER = "normal_subsidy_per_order"
    private const val KEY_NORMAL_FAT_ORDER_MIN_AMOUNT = "normal_fat_order_min_amount"
    private const val KEY_NORMAL_COST_PER_KM = "normal_cost_per_km"
    private const val KEY_BLACK_MIN_PRICE = "black_min_price"
    private const val KEY_BLACK_MAX_DISTANCE = "black_max_distance"
    private const val KEY_BLACK_MAX_MINUTES = "black_max_minutes"
    private const val KEY_BLACK_TARGET_HOURLY = "black_target_hourly"
    private const val KEY_BLACK_TARGET_YUAN_PER_KM = "black_target_yuan_per_km"
    private const val KEY_BLACK_TARGET_AVERAGE_PRICE = "black_target_average_price"
    private const val KEY_BLACK_MIN_SCORE = "black_min_score"
    private const val KEY_BLACK_SCORE_BASE = "black_score_base"
    private const val KEY_BLACK_SUBSIDY_PER_ORDER = "black_subsidy_per_order"
    private const val KEY_BLACK_FAT_ORDER_MIN_AMOUNT = "black_fat_order_min_amount"
    private const val KEY_BLACK_COST_PER_KM = "black_cost_per_km"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_BLACKLIST = "blacklist"
    private const val KEY_IMPORTED_LOCATION_LIST_DEFAULTS = "imported_location_list_defaults"
    private const val KEY_ACCEPT_MODE = "accept_mode"
    private const val KEY_REWARD_PER_TRIP = "reward_per_trip"

    enum class AcceptMode {
        NORMAL,
        REWARD
    }

    data class RuleConfig(
        val minPrice: Int,
        val maxDistance: Double,
        val maxMinutes: Int,
        val targetHourly: Int,
        val targetYuanPerKm: Double = RuleManager.DEFAULT_TARGET_YUAN_PER_KM,
        val targetAveragePrice: Double = RuleManager.DEFAULT_TARGET_AVERAGE_PRICE,
        val minScore: Int = 0,
        val scoreBase: Int = RuleManager.DEFAULT_SCORE_BASE,
        val subsidyPerOrder: Int = RuleManager.DEFAULT_SUBSIDY_PER_ORDER,
        val fatOrderMinAmount: Int = RuleManager.DEFAULT_FAT_ORDER_MIN_AMOUNT,
        val costPerKm: Double = RuleManager.COST_PER_KM
    )

    data class Settings(
        val normal: RuleConfig,
        val blacklist: RuleConfig,
        val whitelistEntries: List<ListEntry>,
        val blacklistEntries: List<ListEntry>,
        val acceptMode: AcceptMode = AcceptMode.NORMAL,
        val rewardPerTrip: Int = 0
    )

    data class ListEntry(
        val keyword: String,
        val note: String = ""
    )

    fun load(context: Context): Settings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manualLists = removeImportedLocationListDefaultsIfNeeded(context)
        return Settings(
            normal = RuleConfig(
                minPrice = prefs.getInt(KEY_NORMAL_MIN_PRICE, 35),
                maxDistance = prefs.getFloat(KEY_NORMAL_MAX_DISTANCE, 10f).toDouble(),
                maxMinutes = prefs.getInt(KEY_NORMAL_MAX_MINUTES, 35),
                targetHourly = prefs.getInt(KEY_NORMAL_TARGET_HOURLY, RuleManager.DEFAULT_TARGET_HOURLY),
                targetYuanPerKm = prefs.getFloat(KEY_NORMAL_TARGET_YUAN_PER_KM, RuleManager.DEFAULT_TARGET_YUAN_PER_KM.toFloat()).toDouble(),
                targetAveragePrice = prefs.getFloat(KEY_NORMAL_TARGET_AVERAGE_PRICE, RuleManager.DEFAULT_TARGET_AVERAGE_PRICE.toFloat()).toDouble(),
                minScore = prefs.getInt(KEY_NORMAL_MIN_SCORE, 0),
                scoreBase = prefs.getInt(KEY_NORMAL_SCORE_BASE, RuleManager.DEFAULT_SCORE_BASE).coerceIn(50, 100),
                subsidyPerOrder = prefs.getInt(KEY_NORMAL_SUBSIDY_PER_ORDER, RuleManager.DEFAULT_SUBSIDY_PER_ORDER).coerceAtLeast(0),
                fatOrderMinAmount = prefs.getInt(KEY_NORMAL_FAT_ORDER_MIN_AMOUNT, RuleManager.DEFAULT_FAT_ORDER_MIN_AMOUNT).coerceAtLeast(0),
                costPerKm = prefs.getFloat(KEY_NORMAL_COST_PER_KM, RuleManager.COST_PER_KM.toFloat()).toDouble()
            ),
            blacklist = RuleConfig(
                minPrice = prefs.getInt(KEY_BLACK_MIN_PRICE, 150),
                maxDistance = prefs.getFloat(KEY_BLACK_MAX_DISTANCE, 999f).toDouble(),
                maxMinutes = prefs.getInt(KEY_BLACK_MAX_MINUTES, 999),
                targetHourly = prefs.getInt(KEY_BLACK_TARGET_HOURLY, RuleManager.DEFAULT_TARGET_HOURLY),
                targetYuanPerKm = prefs.getFloat(KEY_BLACK_TARGET_YUAN_PER_KM, RuleManager.DEFAULT_TARGET_YUAN_PER_KM.toFloat()).toDouble(),
                targetAveragePrice = prefs.getFloat(KEY_BLACK_TARGET_AVERAGE_PRICE, RuleManager.DEFAULT_TARGET_AVERAGE_PRICE.toFloat()).toDouble(),
                minScore = prefs.getInt(KEY_BLACK_MIN_SCORE, 0),
                scoreBase = prefs.getInt(KEY_BLACK_SCORE_BASE, RuleManager.DEFAULT_SCORE_BASE).coerceIn(50, 100),
                subsidyPerOrder = prefs.getInt(KEY_BLACK_SUBSIDY_PER_ORDER, RuleManager.DEFAULT_SUBSIDY_PER_ORDER).coerceAtLeast(0),
                fatOrderMinAmount = prefs.getInt(KEY_BLACK_FAT_ORDER_MIN_AMOUNT, RuleManager.DEFAULT_FAT_ORDER_MIN_AMOUNT).coerceAtLeast(0),
                costPerKm = prefs.getFloat(KEY_BLACK_COST_PER_KM, RuleManager.COST_PER_KM.toFloat()).toDouble()
            ),
            whitelistEntries = parseEntries(manualLists.first),
            blacklistEntries = parseEntries(manualLists.second),
            acceptMode = loadAcceptMode(prefs.getString(KEY_ACCEPT_MODE, AcceptMode.NORMAL.name)),
            rewardPerTrip = prefs.getInt(KEY_REWARD_PER_TRIP, 0).coerceAtLeast(0)
        )
    }

    private fun loadAcceptMode(value: String?): AcceptMode {
        return runCatching { AcceptMode.valueOf(value.orEmpty()) }.getOrDefault(AcceptMode.NORMAL)
    }

    private fun removeImportedLocationListDefaultsIfNeeded(context: Context): Pair<String, String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentWhitelistText = prefs.getString(KEY_WHITELIST, "").orEmpty()
        val currentBlacklistText = prefs.getString(KEY_BLACKLIST, "").orEmpty()
        if (!prefs.getBoolean(KEY_IMPORTED_LOCATION_LIST_DEFAULTS, false)) {
            return currentWhitelistText to currentBlacklistText
        }

        val currentWhitelist = parseEntries(currentWhitelistText)
            .filterNot(::isImportedLocationEntry)
        val currentBlacklist = parseEntries(currentBlacklistText)
            .filterNot(::isImportedLocationEntry)

        val manualWhitelistText = serializeEntries(currentWhitelist)
        val manualBlacklistText = serializeEntries(currentBlacklist)
        prefs.edit()
            .putString(KEY_WHITELIST, manualWhitelistText)
            .putString(KEY_BLACKLIST, manualBlacklistText)
            .putBoolean(KEY_IMPORTED_LOCATION_LIST_DEFAULTS, false)
            .apply()
        return manualWhitelistText to manualBlacklistText
    }

    private fun isImportedLocationEntry(entry: ListEntry): Boolean {
        return entry.note.startsWith("内置词库") || entry.note.startsWith("內置詞庫")
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
            .putFloat(KEY_NORMAL_TARGET_YUAN_PER_KM, normal.targetYuanPerKm.toFloat())
            .putFloat(KEY_NORMAL_TARGET_AVERAGE_PRICE, normal.targetAveragePrice.toFloat())
            .putInt(KEY_NORMAL_MIN_SCORE, normal.minScore)
            .putInt(KEY_NORMAL_SCORE_BASE, normal.scoreBase.coerceIn(50, 100))
            .putInt(KEY_NORMAL_SUBSIDY_PER_ORDER, normal.subsidyPerOrder.coerceAtLeast(0))
            .putInt(KEY_NORMAL_FAT_ORDER_MIN_AMOUNT, normal.fatOrderMinAmount.coerceAtLeast(0))
            .putFloat(KEY_NORMAL_COST_PER_KM, normal.costPerKm.toFloat())
            .putInt(KEY_BLACK_MIN_PRICE, blacklist.minPrice)
            .putFloat(KEY_BLACK_MAX_DISTANCE, blacklist.maxDistance.toFloat())
            .putInt(KEY_BLACK_MAX_MINUTES, blacklist.maxMinutes)
            .putInt(KEY_BLACK_TARGET_HOURLY, blacklist.targetHourly)
            .putFloat(KEY_BLACK_TARGET_YUAN_PER_KM, blacklist.targetYuanPerKm.toFloat())
            .putFloat(KEY_BLACK_TARGET_AVERAGE_PRICE, blacklist.targetAveragePrice.toFloat())
            .putInt(KEY_BLACK_MIN_SCORE, blacklist.minScore)
            .putInt(KEY_BLACK_SCORE_BASE, blacklist.scoreBase.coerceIn(50, 100))
            .putInt(KEY_BLACK_SUBSIDY_PER_ORDER, blacklist.subsidyPerOrder.coerceAtLeast(0))
            .putInt(KEY_BLACK_FAT_ORDER_MIN_AMOUNT, blacklist.fatOrderMinAmount.coerceAtLeast(0))
            .putFloat(KEY_BLACK_COST_PER_KM, blacklist.costPerKm.toFloat())
            .putString(KEY_WHITELIST, whitelistText.trim())
            .putString(KEY_BLACKLIST, blacklistText.trim())
            .apply()
    }

    fun saveAcceptMode(
        context: Context,
        acceptMode: AcceptMode,
        rewardPerTrip: Int
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCEPT_MODE, acceptMode.name)
            .putInt(KEY_REWARD_PER_TRIP, rewardPerTrip.coerceAtLeast(0))
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
            val keyword = encodeEntryPart(entry.keyword)
            val note = encodeEntryPart(entry.note)
            if (note.isBlank()) keyword else "$keyword|$note"
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
                    keyword = decodeEntryPart(parts.getOrNull(0).orEmpty()).trim(),
                    note = decodeEntryPart(parts.getOrNull(1).orEmpty()).trim()
                )
            }
            .filter { it.keyword.length >= 2 }
            .distinctBy { it.keyword }
    }

    private fun encodeEntryPart(value: String): String {
        return value
            .trim()
            .replace("\\", "\\\\")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "\\n")
    }

    private fun decodeEntryPart(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> {
                        result.append('\n')
                        index += 2
                        continue
                    }
                    '\\' -> {
                        result.append('\\')
                        index += 2
                        continue
                    }
                }
            }
            result.append(char)
            index += 1
        }
        return result.toString()
    }

    fun parseKeywords(text: String): List<String> {
        return parseEntries(text).map { it.keyword }
    }

    fun containsMatchingEntry(entries: List<ListEntry>, keyword: String): Boolean {
        val candidates = keywordCandidates(keyword)
        if (candidates.isEmpty()) return false
        return entries.any { entry ->
            val existingCandidates = keywordCandidates(entry.keyword)
            existingCandidates.any { existing ->
                candidates.any { candidate ->
                    existing == candidate ||
                            existing.contains(candidate) ||
                            candidate.contains(existing)
                }
            }
        }
    }

    private fun keywordCandidates(keyword: String): Set<String> {
        val parts = keyword
            .lines()
            .map(::normalizeListKeyword)
            .filter { it.length >= 2 }
        val full = normalizeListKeyword(keyword)
        return (parts + full).filter { it.length >= 2 }.toSet()
    }

    private fun normalizeListKeyword(value: String): String {
        return value
            .lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[,，;；|]"), "")
    }
}
